package de.kiliantaubmann.bella.core.mcp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.tools.SchemaCheck;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Minimal MCP server ("Streamable HTTP", JSON responses) that offers Bella's
 * tools to the Claude Code CLI. It listens on the loopback interface only and
 * requires a random bearer token, so only the CLI started by Bella can call
 * it. Every call goes through the {@link ToolExecutor}: policy, open-editor
 * router and confirmation apply exactly as in Bella's own chat.
 */
public final class McpServer implements AutoCloseable {

	public static final String PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2025-06-18";
	private static final int MAX_BODY = 32 * 1024 * 1024;

	private final ToolExecutor executor;
	private final String version;
	private final String token;
	private ServerSocket socket;
	private ExecutorService pool;
	private volatile ToolExecutor.Observer observer = ToolExecutor.Observer.NONE;
	private volatile CancelToken cancel = CancelToken.NONE;

	public McpServer(ToolExecutor executor, String version) {
		this.executor = executor;
		this.version = version;
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		this.token = HexFormat.of().formatHex(random);
	}

	public synchronized void start() throws IOException {
		if (socket != null) {
			return;
		}
		socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		pool = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "bella-mcp-server");
			t.setDaemon(true);
			return t;
		});
		ServerSocket s = socket;
		Thread acceptor = new Thread(() -> acceptLoop(s), "bella-mcp-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	/**
	 * Routes tool progress of the running turn to its listener; tool calls
	 * made after the turn was cancelled see a cancelled token.
	 */
	public void setTurn(ToolExecutor.Observer observer, CancelToken cancel) {
		this.observer = observer == null ? ToolExecutor.Observer.NONE : observer;
		this.cancel = cancel == null ? CancelToken.NONE : cancel;
	}

	public synchronized boolean isRunning() {
		return socket != null && !socket.isClosed();
	}

	public String url() {
		return "http://127.0.0.1:" + socket.getLocalPort() + PATH;
	}

	public String token() {
		return token;
	}

	/** {@code mcpServers} entry for Claude Code's {@code --mcp-config}. */
	public JsonObject claudeCodeConfig(String serverName) {
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token);
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", url());
		server.add("headers", headers);
		JsonObject servers = new JsonObject();
		servers.add(serverName, server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root;
	}

	private void acceptLoop(ServerSocket s) {
		while (!s.isClosed()) {
			try {
				Socket client = s.accept();
				pool.execute(() -> handle(client));
			} catch (SocketException e) {
				return; // closed
			} catch (IOException e) {
				// keep accepting
			}
		}
	}

	// ---- HTTP -----------------------------------------------------------------------

	private record Request(String method, String path, Map<String, String> headers, byte[] body) {
	}

	private void handle(Socket client) {
		try (client; InputStream in = new BufferedInputStream(client.getInputStream());
				OutputStream out = client.getOutputStream()) {
			client.setSoTimeout(30_000);
			Request req = read(in);
			if (req == null) {
				return;
			}
			client.setSoTimeout(0); // tool calls may wait for the developer's confirmation
			respond(req, out);
		} catch (IOException e) {
			// client went away
		}
	}

	private static Request read(InputStream in) throws IOException {
		String requestLine = line(in);
		if (requestLine == null || requestLine.isEmpty()) {
			return null;
		}
		String[] parts = requestLine.split(" ");
		if (parts.length < 2) {
			return null;
		}
		Map<String, String> headers = new LinkedHashMap<>();
		String h;
		while ((h = line(in)) != null && !h.isEmpty()) {
			int colon = h.indexOf(':');
			if (colon > 0) {
				headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
			}
		}
		int length = 0;
		if (headers.containsKey("content-length")) {
			length = Integer.parseInt(headers.get("content-length"));
		}
		if (length < 0 || length > MAX_BODY) {
			throw new IOException("request too large");
		}
		byte[] body = in.readNBytes(length);
		String path = parts[1];
		int q = path.indexOf('?');
		return new Request(parts[0].toUpperCase(Locale.ROOT), q < 0 ? path : path.substring(0, q), headers, body);
	}

	private static String line(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				buf.write(b);
			}
			if (buf.size() > 16 * 1024) {
				throw new IOException("header line too long");
			}
		}
		if (b == -1 && buf.size() == 0) {
			return null;
		}
		return buf.toString(StandardCharsets.ISO_8859_1);
	}

	private void respond(Request req, OutputStream out) throws IOException {
		if (!PATH.equals(req.path())) {
			write(out, 404, "Not Found", null, null);
			return;
		}
		if (!authorized(req.headers().get("authorization"))) {
			write(out, 401, "Unauthorized", null, null);
			return;
		}
		switch (req.method()) {
		case "POST" -> handlePost(req, out);
		case "DELETE" -> write(out, 200, "OK", null, null);
		default -> write(out, 405, "Method Not Allowed", Map.of("Allow", "POST, DELETE"), null);
		}
	}

	private boolean authorized(String header) {
		String expected = "Bearer " + token;
		return header != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				header.getBytes(StandardCharsets.UTF_8));
	}

	private void handlePost(Request req, OutputStream out) throws IOException {
		JsonElement payload;
		try {
			payload = Json.parseStrict(new String(req.body(), StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			write(out, 400, "Bad Request", null, error(null, -32700, "Parse error"));
			return;
		}
		if (payload.isJsonArray()) {
			JsonArray responses = new JsonArray();
			for (JsonElement e : payload.getAsJsonArray()) {
				JsonObject r = e.isJsonObject() ? dispatch(e.getAsJsonObject()) : error(null, -32600, "Invalid Request");
				if (r != null) {
					responses.add(r);
				}
			}
			if (responses.isEmpty()) {
				write(out, 202, "Accepted", null, null);
			} else {
				write(out, 200, "OK", null, responses);
			}
			return;
		}
		if (!payload.isJsonObject()) {
			write(out, 400, "Bad Request", null, error(null, -32600, "Invalid Request"));
			return;
		}
		JsonObject response = dispatch(payload.getAsJsonObject());
		if (response == null) {
			write(out, 202, "Accepted", null, null);
		} else {
			write(out, 200, "OK", null, response);
		}
	}

	private static void write(OutputStream out, int status, String reason, Map<String, String> extra, JsonElement body)
			throws IOException {
		byte[] bytes = body == null ? new byte[0] : Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
		if (body != null) {
			head.append("Content-Type: application/json\r\n");
		}
		head.append("Content-Length: ").append(bytes.length).append("\r\n");
		head.append("Connection: close\r\n");
		if (extra != null) {
			extra.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
		}
		head.append("\r\n");
		out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.write(bytes);
		out.flush();
	}

	// ---- JSON-RPC ---------------------------------------------------------------------

	/** @return the response, or {@code null} for notifications */
	JsonObject dispatch(JsonObject msg) {
		JsonElement id = msg.get("id");
		String method = Json.str(msg, "method");
		if (id == null || id.isJsonNull()) {
			return null; // notification, e.g. notifications/initialized
		}
		if (method == null) {
			return error(id, -32600, "Invalid Request");
		}
		JsonObject params = Json.obj(msg, "params");
		return switch (method) {
		case "initialize" -> result(id, initialize(params));
		case "ping" -> result(id, new JsonObject());
		case "tools/list" -> result(id, listTools());
		case "tools/call" -> result(id, callTool(params));
		default -> error(id, -32601, "Method not found: " + method);
		};
	}

	private JsonObject initialize(JsonObject params) {
		JsonObject r = new JsonObject();
		String requested = Json.str(params, "protocolVersion");
		r.addProperty("protocolVersion", requested != null ? requested : PROTOCOL_VERSION);
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		JsonObject caps = new JsonObject();
		caps.add("tools", tools);
		r.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "bella");
		info.addProperty("version", version);
		r.add("serverInfo", info);
		r.addProperty("instructions",
				"Tools of the Bella Eclipse plug-in. adt_* tools act on the SAP system through the developer's ADT logon.");
		return r;
	}

	private JsonObject listTools() {
		JsonArray list = new JsonArray();
		for (ToolSpec t : executor.registry().tools()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			JsonObject annotations = new JsonObject();
			annotations.addProperty("readOnlyHint", t.kind() == ToolSpec.Kind.READ);
			o.add("annotations", annotations);
			list.add(o);
		}
		JsonObject r = new JsonObject();
		r.add("tools", list);
		return r;
	}

	private JsonObject callTool(JsonObject params) {
		String name = Json.str(params, "name");
		JsonObject args = Json.obj(params, "arguments");
		if (args == null) {
			args = new JsonObject();
		}
		String inputError = null;
		ToolSpec spec = name == null ? null : executor.registry().find(name).orElse(null);
		if (spec != null) {
			inputError = SchemaCheck.validate(spec.inputSchema(), args);
		}
		ToolCall call = new ToolCall("mcp-" + System.nanoTime(), String.valueOf(name), args, Json.GSON.toJson(args),
				inputError);
		ToolResult r = executor.run(call, observer, cancel);
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", r.content() == null || r.content().isEmpty() ? "(empty)" : r.content());
		JsonArray content = new JsonArray();
		content.add(text);
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", r.isError());
		return result;
	}

	private static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("result", result);
		return r;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		if (id != null) {
			r.add("id", id);
		} else {
			r.add("id", com.google.gson.JsonNull.INSTANCE);
		}
		r.add("error", err);
		return r;
	}

	@Override
	public synchronized void close() {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException e) {
				// ignore
			}
			socket = null;
		}
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}
}

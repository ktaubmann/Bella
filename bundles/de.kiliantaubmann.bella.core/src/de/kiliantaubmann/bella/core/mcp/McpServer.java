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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

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
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Minimal MCP server ("Streamable HTTP", JSON responses) that offers Bella's
 * tools to the Claude Code or Copilot CLI started by Bella, or, with a fixed
 * port and token, to other programs on the developer's machine. It listens
 * on the loopback interface only, requires a bearer token and refuses
 * requests a web page could send (foreign {@code Origin} or {@code Host}).
 * Every call goes through the {@link ToolExecutor}: policy, open-editor
 * router and confirmation apply exactly as in Bella's own chat.
 */
public final class McpServer implements AutoCloseable {

	public static final String PATH = "/mcp";
	private static final String PROTOCOL_VERSION = "2025-06-18";
	private static final int MAX_BODY = 32 * 1024 * 1024;
	/**
	 * How long the CLI waits for one tool call, confirmation dialog included.
	 * Also set per server: the CLI otherwise aborts an HTTP tool call after
	 * five minutes without an answer or progress (idle timeout), whatever
	 * {@code MCP_TOOL_TIMEOUT} says.
	 */
	public static final int TOOL_TIMEOUT_MILLIS = 1_800_000;
	/** Characters of a late result reported to the model. */
	static final int LATE_RESULT_CHARS = 1_500;
	/** Header that carries the session the server handed out in {@code initialize}. */
	static final String SESSION_HEADER = "Mcp-Session-Id";
	/** Sessions kept at most; the oldest is dropped beyond, for clients that never end theirs. */
	static final int MAX_SESSIONS = 64;
	/** The client of the tool call running on this thread, for the confirmation dialog. */
	private static final ThreadLocal<String> CALLER = new ThreadLocal<>();

	private final ToolExecutor executor;
	private final String version;
	private final String token;
	private final int port;
	private final Predicate<ToolSpec> offered;
	private final String instructions;
	private volatile Runnable refresh = () -> {
	};
	private ServerSocket socket;
	private ExecutorService pool;
	private volatile ToolExecutor.Observer observer = ToolExecutor.Observer.NONE;
	private volatile CancelToken cancel = CancelToken.NONE;
	/** Running tool calls by JSON-RPC id, so that {@code notifications/cancelled} can stop them. */
	private final Map<String, CancelToken> running = new ConcurrentHashMap<>();
	/**
	 * One client's connection: its name and the results of its calls that went
	 * on after it had given up, reported with its next call and to no one else.
	 */
	private static final class Session {
		final String id;
		final String clientName;
		final List<String> late = new ArrayList<>();

		Session(String id, String clientName) {
			this.id = id;
			this.clientName = clientName;
		}

		/** Key of a running call: JSON-RPC ids are only unique within one client. */
		String key(JsonElement requestId) {
			return id + " " + requestId;
		}
	}

	/** Sessions by id, oldest first. */
	private final Map<String, Session> sessions = new LinkedHashMap<>();
	/** Clients that send no session id; they share one session as before sessions existed. */
	private final Session anonymous = new Session("", null);

	/** A private server for a CLI Bella starts: any free port, a random token, all tools. */
	public McpServer(ToolExecutor executor, String version) {
		this(executor, version, newToken(), 0, t -> true,
				"Tools of the Bella Eclipse plug-in. adt_* tools act on the SAP system through the developer's ADT logon.");
	}

	/**
	 * @param token        the bearer token callers must send
	 * @param port         loopback port, 0 for any free one
	 * @param offered      the tools listed and callable; others count as unknown
	 * @param instructions the server's instructions for the client's model
	 */
	public McpServer(ToolExecutor executor, String version, String token, int port, Predicate<ToolSpec> offered,
			String instructions) {
		this.executor = executor;
		this.version = version;
		this.token = token;
		this.port = port;
		this.offered = offered;
		this.instructions = instructions;
	}

	/** A random bearer token (64 hex digits). */
	public static String newToken() {
		byte[] random = new byte[32];
		new SecureRandom().nextBytes(random);
		return HexFormat.of().formatHex(random);
	}

	public synchronized void start() throws IOException {
		if (socket != null) {
			return;
		}
		socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
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

	/**
	 * Reads the tool list again before it is listed, and before a call of a
	 * tool not known yet. The CLIs Bella starts need none, since the chat has
	 * just refreshed the registry; another program may come before any chat.
	 */
	public void refreshToolsWith(Runnable refresh) {
		this.refresh = refresh == null ? () -> {
		} : refresh;
	}

	/**
	 * The name the client gave in {@code initialize} whose tool call runs on
	 * the current thread; {@code null} outside a call or for a client that
	 * sends no session id.
	 */
	public static String callerName() {
		return CALLER.get();
	}

	/** {@code mcpServers} entry for Claude Code's {@code --mcp-config}. */
	public JsonObject claudeCodeConfig(String serverName) {
		return McpSetupCommands.servers(serverName, McpSetupCommands.claudeCodeEntry(url(), token));
	}

	/**
	 * {@code mcpServers} entry for the Copilot CLI's
	 * {@code --additional-mcp-config}. Unlike a server named in ACP's
	 * {@code session/new}, it can carry a timeout; without one the CLI gives
	 * up on a tool call after three minutes (one in older versions), e.g.
	 * while the confirmation dialog is open.
	 */
	public JsonObject copilotConfig(String serverName) {
		return McpSetupCommands.servers(serverName, McpSetupCommands.copilotEntry(url(), token));
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
			Request req;
			try {
				req = read(in, this::authorized);
			} catch (Forbidden e) {
				Log.warn("mcp", "Bella's MCP server refused a request from a web page or a foreign host name");
				write(out, 403, "Forbidden", null, null);
				return;
			} catch (Unauthorized e) {
				Log.warn("mcp", "Bella's MCP server refused a request without the right token");
				write(out, 401, "Unauthorized", null, null);
				// Read a small body away, so closing the socket does not reset the connection before the
				// client has read the 401; a large one is not read at all.
				if (e.length > 0 && e.length <= 64 * 1024) {
					in.skipNBytes(e.length);
				}
				return;
			}
			if (req == null) {
				return;
			}
			client.setSoTimeout(0); // tool calls may wait for the developer's confirmation
			respond(req, out);
		} catch (IOException e) {
			// client went away
		}
	}

	/** Thrown before the body is read, so an unknown caller cannot make Bella buffer a large body. */
	private static final class Unauthorized extends IOException {
		private static final long serialVersionUID = 1L;
		final int length;

		Unauthorized(int length) {
			this.length = length;
		}
	}

	/** Thrown for a request a web page could have sent (DNS rebinding); its body is not read. */
	private static final class Forbidden extends IOException {
		private static final long serialVersionUID = 1L;
	}

	/**
	 * Whether {@code Host} and {@code Origin} name the loopback interface:
	 * the CLIs send no {@code Origin}, a browser always does.
	 */
	static boolean local(Map<String, String> headers) {
		String host = headers.get("host");
		if (host != null && !loopbackName(host)) {
			return false;
		}
		String origin = headers.get("origin");
		if (origin == null) {
			return true;
		}
		int scheme = origin.indexOf("://");
		return scheme > 0 && (origin.startsWith("http://") || origin.startsWith("https://"))
				&& loopbackName(origin.substring(scheme + 3));
	}

	/** {@code 127.0.0.1}, {@code localhost} or {@code [::1]}, with an optional port. */
	private static boolean loopbackName(String hostAndPort) {
		String h = hostAndPort.trim().toLowerCase(Locale.ROOT);
		if (h.startsWith("[::1]")) {
			h = h.substring(5);
		} else {
			int colon = h.indexOf(':');
			String name = colon < 0 ? h : h.substring(0, colon);
			if (!name.equals("127.0.0.1") && !name.equals("localhost")) {
				return false;
			}
			h = colon < 0 ? "" : h.substring(colon);
		}
		return h.isEmpty() || h.matches(":\\d{1,5}/?");
	}

	private static Request read(InputStream in, Predicate<String> authorized) throws IOException {
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
			if (headers.size() > 100) {
				throw new IOException("too many headers");
			}
			if (colon > 0) {
				headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
			}
		}
		int length = 0;
		if (headers.containsKey("content-length")) {
			try {
				length = Integer.parseInt(headers.get("content-length").trim());
			} catch (NumberFormatException e) {
				throw new IOException("bad content-length");
			}
		}
		if (!local(headers)) {
			throw new Forbidden();
		}
		if (!authorized.test(headers.get("authorization"))) {
			throw new Unauthorized(length);
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
		String sessionId = req.headers().get(SESSION_HEADER.toLowerCase(Locale.ROOT));
		Session session = sessionId == null ? anonymous : session(sessionId);
		if (session == null) {
			// unknown or dropped: the client starts a new session with initialize
			write(out, 404, "Not Found", null, error(null, -32001, "Session not found"));
			return;
		}
		switch (req.method()) {
		case "POST" -> handlePost(req, session, out);
		case "DELETE" -> {
			if (sessionId != null) {
				synchronized (sessions) {
					sessions.remove(sessionId);
				}
			}
			write(out, 200, "OK", null, null);
		}
		default -> write(out, 405, "Method Not Allowed", Map.of("Allow", "POST, DELETE"), null);
		}
	}

	private Session session(String id) {
		synchronized (sessions) {
			return sessions.get(id);
		}
	}

	/** A new session for a client that sent {@code initialize}; returns its id. */
	private String openSession(String clientName) {
		String id = newToken();
		synchronized (sessions) {
			while (sessions.size() >= MAX_SESSIONS) {
				var oldest = sessions.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
			sessions.put(id, new Session(id, clientName));
		}
		return id;
	}

	private boolean authorized(String header) {
		String expected = "Bearer " + token;
		return header != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				header.getBytes(StandardCharsets.UTF_8));
	}

	private void handlePost(Request req, Session session, OutputStream out) throws IOException {
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
				JsonObject r = e.isJsonObject() ? dispatch(e.getAsJsonObject(), session)
						: error(null, -32600, "Invalid Request");
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
		JsonObject msg = payload.getAsJsonObject();
		if ("initialize".equals(Json.str(msg, "method"))) {
			String id = openSession(clientName(Json.obj(msg, "params")));
			JsonObject response = dispatch(msg, session);
			write(out, 200, "OK", Map.of(SESSION_HEADER, id), response);
			return;
		}
		JsonObject response = dispatch(msg, session);
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
		return dispatch(msg, anonymous);
	}

	private JsonObject dispatch(JsonObject msg, Session session) {
		JsonElement id = msg.get("id");
		String method = Json.str(msg, "method");
		JsonObject params = Json.obj(msg, "params");
		if (id == null || id.isJsonNull()) {
			if ("notifications/cancelled".equals(method)) {
				cancelled(params, session);
			}
			return null; // other notifications, e.g. notifications/initialized
		}
		if (method == null) {
			return error(id, -32600, "Invalid Request");
		}
		Log.debug("mcp", () -> "server <- " + method + (params == null ? "" : " " + Log.clip(Json.GSON.toJson(params), 2_000)));
		return switch (method) {
		case "initialize" -> result(id, initialize(params));
		case "ping" -> result(id, new JsonObject());
		case "tools/list" -> result(id, listTools());
		case "tools/call" -> result(id, callTool(id, params, session));
		default -> error(id, -32601, "Method not found: " + method);
		};
	}

	private JsonObject initialize(JsonObject params) {
		JsonObject client = Json.obj(params, "clientInfo");
		Log.info("mcp", "Bella's MCP server: client " + Json.str(client, "name") + " " + Json.str(client, "version")
				+ " connected");
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
		r.addProperty("instructions", instructions);
		return r;
	}

	private static String clientName(JsonObject initializeParams) {
		String name = Json.str(Json.obj(initializeParams, "clientInfo"), "name");
		return name == null || name.isBlank() ? null : name.trim();
	}

	private JsonObject listTools() {
		refresh.run();
		JsonArray list = new JsonArray();
		for (ToolSpec t : executor.registry().tools()) {
			if (!offered.test(t)) {
				continue;
			}
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

	/**
	 * The caller stopped waiting for a request (timeout, interrupted turn): a
	 * confirmation still open is closed and the call does not run.
	 */
	private void cancelled(JsonObject params, Session session) {
		JsonElement requestId = params == null ? null : params.get("requestId");
		CancelToken token = requestId == null ? null : running.get(session.key(requestId));
		Log.warn("mcp", "Bella's MCP server: the client cancelled request " + requestId
				+ (Json.str(params, "reason") == null ? "" : " (" + Json.str(params, "reason") + ")")
				+ (token == null ? ", which is no longer running" : ""));
		if (token != null) {
			token.cancel();
		}
	}

	private JsonObject callTool(JsonElement id, JsonObject params, Session session) {
		String name = Json.str(params, "name");
		JsonObject args = Json.obj(params, "arguments");
		if (args == null) {
			args = new JsonObject();
		}
		String inputError = null;
		ToolSpec spec = name == null ? null : executor.registry().find(name).orElse(null);
		if (spec == null && name != null) {
			refresh.run();
			spec = executor.registry().find(name).orElse(null);
		}
		if (spec != null && !offered.test(spec)) {
			return toolError("Unknown tool: " + name);
		}
		if (spec != null) {
			inputError = SchemaCheck.validate(spec.inputSchema(), args);
		}
		ToolCall call = new ToolCall("mcp-" + System.nanoTime(), String.valueOf(name), args, Json.GSON.toJson(args),
				inputError);
		String key = session.key(id);
		CancelToken token = cancel.child();
		running.put(key, token);
		ToolResult r;
		CALLER.set(session.clientName);
		try {
			r = executor.run(call, observer, token);
		} finally {
			CALLER.remove();
			running.remove(key);
		}
		// taken before this call's own late result is added: that goes to the next call, not to the lost answer
		String notes = lateNotes(session);
		if (token.isCancelled() && spec != null && spec.kind() != ToolSpec.Kind.READ
				&& !ToolExecutor.NOT_RUN.equals(r.content())) {
			String out = r.content() == null ? "" : r.content();
			synchronized (session.late) {
				session.late.add("- " + name + " " + Log.clip(Json.GSON.toJson(args), 300) + (r.isError() ? " failed: " : " ran: ")
						+ (out.length() > LATE_RESULT_CHARS ? out.substring(0, LATE_RESULT_CHARS) + " …" : out));
			}
		}
		JsonArray content = new JsonArray();
		if (notes != null) {
			JsonObject note = new JsonObject();
			note.addProperty("type", "text");
			note.addProperty("text", notes);
			content.add(note);
		}
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", r.content() == null || r.content().isEmpty() ? "(empty)" : r.content());
		content.add(text);
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", r.isError());
		return result;
	}

	private static JsonObject toolError(String message) {
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", message);
		content.add(text);
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", true);
		return result;
	}

	/** Late results not yet reported, once; {@code null} if there are none. */
	private static String lateNotes(Session session) {
		synchronized (session.late) {
			if (session.late.isEmpty()) {
				return null;
			}
			String notes = "Bella: earlier tool calls you stopped waiting for (e.g. after a timeout) still ran to the "
					+ "end. Check their outcome before repeating them:\n" + String.join("\n", session.late);
			session.late.clear();
			return notes;
		}
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

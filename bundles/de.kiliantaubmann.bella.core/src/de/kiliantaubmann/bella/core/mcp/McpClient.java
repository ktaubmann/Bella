package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Minimal MCP client: {@code initialize}, {@code tools/list} (paginated) and
 * {@code tools/call}. Enough to use ARC-1 or any other MCP server as a tool
 * source.
 */
public final class McpClient implements AutoCloseable {

	public static final String PROTOCOL_VERSION = "2025-06-18";

	private final McpTransport transport;
	private final AtomicLong ids = new AtomicLong();
	private volatile boolean initialized;
	private String serverName = "";
	private String serverVersion = "";

	public McpClient(McpTransport transport) {
		this.transport = transport;
	}

	public synchronized void initialize(String clientVersion) throws IOException {
		if (initialized) {
			return;
		}
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", PROTOCOL_VERSION);
		params.add("capabilities", new JsonObject());
		JsonObject info = new JsonObject();
		info.addProperty("name", "bella");
		info.addProperty("version", clientVersion);
		params.add("clientInfo", info);
		JsonObject result = call("initialize", params, CancelToken.NONE);
		String version = Json.str(result, "protocolVersion");
		transport.setProtocolVersion(version == null ? PROTOCOL_VERSION : version);
		JsonObject server = Json.obj(result, "serverInfo");
		serverName = String.valueOf(Json.str(server, "name"));
		serverVersion = String.valueOf(Json.str(server, "version"));
		JsonObject note = new JsonObject();
		note.addProperty("jsonrpc", "2.0");
		note.addProperty("method", "notifications/initialized");
		transport.notify(note);
		initialized = true;
		Log.info("mcp", "connected to " + serverName + " " + serverVersion + " (protocol "
				+ (version == null ? PROTOCOL_VERSION : version) + ")");
	}

	public String serverName() {
		return serverName;
	}

	public String serverVersion() {
		return serverVersion;
	}

	/** A tool as announced by the server. */
	public record Tool(String name, String description, JsonObject inputSchema, JsonObject annotations) {
	}

	/** Result of {@code tools/call}. */
	public record CallResult(String text, boolean isError) {
	}

	public List<Tool> listTools() throws IOException {
		List<Tool> tools = new ArrayList<>();
		String cursor = null;
		do {
			JsonObject params = new JsonObject();
			if (cursor != null) {
				params.addProperty("cursor", cursor);
			}
			JsonObject result = call("tools/list", params, CancelToken.NONE);
			JsonArray list = Json.arr(result, "tools");
			if (list != null) {
				for (JsonElement e : list) {
					JsonObject t = e.getAsJsonObject();
					JsonObject schema = Json.obj(t, "inputSchema");
					if (schema == null) {
						schema = new JsonObject();
						schema.addProperty("type", "object");
					}
					tools.add(new Tool(Json.str(t, "name"), Json.str(t, "description"), schema,
							Json.obj(t, "annotations")));
				}
			}
			cursor = Json.str(result, "nextCursor");
		} while (cursor != null && !cursor.isEmpty());
		return tools;
	}

	public CallResult callTool(String name, JsonObject arguments, CancelToken cancel) throws IOException {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments == null ? new JsonObject() : arguments);
		JsonObject result = call("tools/call", params, cancel);
		StringBuilder text = new StringBuilder();
		JsonArray content = Json.arr(result, "content");
		if (content != null) {
			for (JsonElement e : content) {
				JsonObject c = e.getAsJsonObject();
				String type = Json.str(c, "type");
				if ("text".equals(type)) {
					if (!text.isEmpty()) {
						text.append('\n');
					}
					text.append(Json.str(c, "text"));
				} else if ("resource".equals(type)) {
					JsonObject res = Json.obj(c, "resource");
					if (Json.str(res, "text") != null) {
						text.append('\n').append(Json.str(res, "text"));
					}
				} else if (type != null) {
					text.append("\n[").append(type).append(" content omitted]");
				}
			}
		}
		JsonObject structured = Json.obj(result, "structuredContent");
		if (text.isEmpty() && structured != null) {
			text.append(Json.GSON.toJson(structured));
		}
		JsonElement isError = result.get("isError");
		return new CallResult(text.toString(), isError != null && isError.isJsonPrimitive() && isError.getAsBoolean());
	}

	private JsonObject call(String method, JsonObject params, CancelToken cancel) throws IOException {
		JsonObject req = new JsonObject();
		req.addProperty("jsonrpc", "2.0");
		req.addProperty("id", ids.incrementAndGet());
		req.addProperty("method", method);
		req.add("params", params);
		JsonObject response = transport.request(req, cancel);
		JsonObject error = Json.obj(response, "error");
		if (error != null) {
			Log.warn("mcp", method + " failed: " + Json.integer(error, "code", -1) + " " + Json.str(error, "message"));
			throw new McpException(Json.integer(error, "code", -1), String.valueOf(Json.str(error, "message")));
		}
		JsonObject result = Json.obj(response, "result");
		return result == null ? new JsonObject() : result;
	}

	@Override
	public void close() {
		transport.close();
	}
}

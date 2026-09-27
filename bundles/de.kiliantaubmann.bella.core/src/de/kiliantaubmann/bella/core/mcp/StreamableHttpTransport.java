package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.SseParser;

/**
 * MCP "Streamable HTTP" transport: each JSON-RPC message is POSTed; the server
 * answers with plain JSON or an SSE stream that eventually carries the
 * response. The {@code Mcp-Session-Id} header is kept across requests.
 */
public final class StreamableHttpTransport implements McpTransport {

	private final URI endpoint;
	private final Map<String, String> extraHeaders;
	private final HttpTransport http;
	private volatile String sessionId;
	private volatile String protocolVersion;

	/** @param bearerToken optional, sent as {@code Authorization: Bearer …} */
	public StreamableHttpTransport(URI endpoint, String bearerToken, HttpTransport http) {
		this.endpoint = endpoint;
		this.http = http;
		this.extraHeaders = new LinkedHashMap<>();
		if (bearerToken != null && !bearerToken.isBlank()) {
			extraHeaders.put("authorization", "Bearer " + bearerToken);
		}
	}

	@Override
	public void setProtocolVersion(String version) {
		this.protocolVersion = version;
	}

	private Map<String, String> headers() {
		Map<String, String> h = new LinkedHashMap<>(extraHeaders);
		h.put("content-type", "application/json");
		h.put("accept", "application/json, text/event-stream");
		if (sessionId != null) {
			h.put("mcp-session-id", sessionId);
		}
		if (protocolVersion != null) {
			h.put("mcp-protocol-version", protocolVersion);
		}
		return h;
	}

	@Override
	public JsonObject request(JsonObject request, CancelToken cancel) throws IOException {
		JsonElement id = request.get("id");
		try (HttpTransport.Response response = http.post(endpoint, headers(), Json.GSON.toJson(request), cancel)) {
			response.header("mcp-session-id").ifPresent(s -> sessionId = s);
			if (response.status() == 404 && sessionId != null) {
				sessionId = null;
				throw new IOException("MCP session expired, please retry");
			}
			if (response.status() / 100 != 2) {
				String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				throw new IOException("MCP server returned HTTP " + response.status() + ": " + abbreviate(body));
			}
			String contentType = response.header("content-type").orElse("application/json");
			if (contentType.startsWith("text/event-stream")) {
				AtomicReference<JsonObject> result = new AtomicReference<>();
				SseParser.parse(response.body(), (event, data) -> {
					try {
						JsonObject msg = Json.parseObject(data);
						if (id != null && id.equals(msg.get("id")) && (msg.has("result") || msg.has("error"))) {
							result.set(msg);
							return false;
						}
					} catch (RuntimeException e) {
						// ignore non-JSON keep-alives
					}
					return true;
				}, cancel);
				if (result.get() == null) {
					throw new IOException("MCP stream ended without a response");
				}
				return result.get();
			}
			String body = new String(response.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			JsonElement parsed = com.google.gson.JsonParser.parseString(body);
			if (parsed.isJsonArray()) {
				for (JsonElement e : parsed.getAsJsonArray()) {
					if (e.isJsonObject() && id != null && id.equals(e.getAsJsonObject().get("id"))) {
						return e.getAsJsonObject();
					}
				}
				throw new IOException("MCP batch response without matching id");
			}
			return parsed.getAsJsonObject();
		}
	}

	@Override
	public void notify(JsonObject notification) throws IOException {
		try (HttpTransport.Response response = http.post(endpoint, headers(), Json.GSON.toJson(notification),
				CancelToken.NONE)) {
			response.body().readAllBytes();
		}
	}

	@Override
	public void close() {
		if (sessionId != null) {
			try {
				http.delete(endpoint, headers());
			} catch (IOException e) {
				// server may not support explicit termination
			}
		}
	}

	private static String abbreviate(String s) {
		return s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}

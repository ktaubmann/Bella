package de.kiliantaubmann.bella.core.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.tools.SchemaCheck;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.SseParser;

/**
 * Claude via the Anthropic Messages API ({@code POST /v1/messages}), always
 * streamed. Plain HTTP is used instead of the Java SDK to keep the OSGi bundle
 * free of OkHttp/Kotlin/Jackson.
 */
public final class AnthropicProvider implements LlmProvider {

	public static final String ID = "anthropic";
	public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
	public static final String DEFAULT_CHAT_MODEL = "claude-opus-5";
	public static final String DEFAULT_COMPLETION_MODEL = "claude-haiku-4-5";

	static final String API_VERSION = "2023-06-01";
	static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

	private final Supplier<String> apiKey;
	private final String baseUrl;
	private final boolean refusalFallback;
	private final HttpTransport http;
	private final int maxRetries;

	public AnthropicProvider(Supplier<String> apiKey, String baseUrl, boolean refusalFallback, HttpTransport http) {
		this(apiKey, baseUrl, refusalFallback, http, 2);
	}

	AnthropicProvider(Supplier<String> apiKey, String baseUrl, boolean refusalFallback, HttpTransport http,
			int maxRetries) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : stripSlash(baseUrl);
		this.refusalFallback = refusalFallback;
		this.http = http;
		this.maxRetries = maxRetries;
	}

	@Override
	public String id() {
		return ID;
	}

	JsonObject buildBody(ChatRequest r) {
		JsonObject body = new JsonObject();
		body.addProperty("model", r.model());
		body.addProperty("max_tokens", r.maxTokens());
		body.addProperty("stream", true);
		if (r.system() != null && !r.system().isBlank()) {
			JsonArray system = new JsonArray();
			system.add(Json.textBlock(r.system()));
			body.add("system", system);
		}
		JsonArray messages = new JsonArray();
		r.messages().forEach(messages::add);
		body.add("messages", messages);
		boolean chat = r.purpose() == ChatRequest.Purpose.CHAT;
		if (chat) {
			// Automatic caching: the breakpoint moves to the last cacheable block,
			// so each turn reads the previous turns from the cache.
			JsonObject cache = new JsonObject();
			cache.addProperty("type", "ephemeral");
			body.add("cache_control", cache);
		}
		if (!r.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec t : r.tools()) {
				JsonObject tool = new JsonObject();
				tool.addProperty("name", t.name());
				tool.addProperty("description", t.description());
				tool.add("input_schema", t.inputSchema());
				// Stream large inputs (source code) as generated; validated in finish().
				tool.addProperty("eager_input_streaming", true);
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		if (chat && ModelCaps.adaptiveThinking(r.model())) {
			JsonObject thinking = new JsonObject();
			thinking.addProperty("type", "adaptive");
			thinking.addProperty("display", "summarized");
			body.add("thinking", thinking);
		}
		if (r.effort() != null && !r.effort().isBlank() && ModelCaps.effort(r.model())) {
			JsonObject outputConfig = new JsonObject();
			outputConfig.addProperty("effort", r.effort());
			body.add("output_config", outputConfig);
		}
		if (chat && useFallback(r)) {
			body.addProperty("fallbacks", "default");
		}
		return body;
	}

	private boolean useFallback(ChatRequest r) {
		return refusalFallback && ModelCaps.refusalFallback(r.model());
	}

	Map<String, String> headers(ChatRequest r) {
		Map<String, String> h = new LinkedHashMap<>();
		h.put("x-api-key", apiKey.get());
		h.put("anthropic-version", API_VERSION);
		h.put("content-type", "application/json");
		h.put("accept", "text/event-stream");
		if (r.purpose() == ChatRequest.Purpose.CHAT && useFallback(r)) {
			h.put("anthropic-beta", FALLBACK_BETA);
		}
		return h;
	}

	@Override
	public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		String key = apiKey.get();
		if (key == null || key.isBlank()) {
			throw new LlmException(401, "No Anthropic API key configured (Preferences → Bella).");
		}
		String body = Json.GSON.toJson(buildBody(request));
		URI uri = URI.create(baseUrl + "/v1/messages");
		int attempt = 0;
		while (true) {
			cancel.throwIfCancelled();
			try (HttpTransport.Response response = http.post(uri, headers(request), body, cancel)) {
				int status = response.status();
				if (status == 200) {
					return readStream(response.body(), request, listener, cancel);
				}
				String error = errorMessage(readAll(response.body()));
				LlmException ex = new LlmException(status, "HTTP " + status + ": " + error);
				if (ex.retryable() && attempt < maxRetries) {
					sleep(retryDelay(response, attempt), cancel);
					attempt++;
					continue;
				}
				throw ex;
			} catch (IOException e) {
				cancel.throwIfCancelled();
				if (attempt < maxRetries) {
					sleep(1000L << attempt, cancel);
					attempt++;
					continue;
				}
				throw new LlmException("Network error: " + e.getMessage(), e);
			}
		}
	}

	private static long retryDelay(HttpTransport.Response response, int attempt) {
		return response.header("retry-after").map(v -> {
			try {
				return Math.min(30_000L, (long) (Double.parseDouble(v) * 1000));
			} catch (NumberFormatException e) {
				return 1000L << attempt;
			}
		}).orElse(1000L << attempt);
	}

	private static void sleep(long millis, CancelToken cancel) throws CancelledException {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			cancel.throwIfCancelled();
			try {
				Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CancelledException();
			}
		}
	}

	ChatResult readStream(InputStream in, ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		Accumulator acc = new Accumulator(request, listener);
		try {
			SseParser.parse(in, (event, data) -> acc.onEvent(event, data), cancel);
		} catch (StreamError e) {
			throw new LlmException(e.status, e.getMessage());
		} catch (IOException e) {
			cancel.throwIfCancelled();
			throw new LlmException("Stream interrupted: " + e.getMessage(), e);
		}
		cancel.throwIfCancelled();
		return acc.finish();
	}

	/** Assembles content blocks from stream events. */
	static final class Accumulator {
		private final ChatRequest request;
		private final StreamListener listener;
		private final TreeMap<Integer, JsonObject> blocks = new TreeMap<>();
		private final Map<Integer, StringBuilder> partialJson = new TreeMap<>();
		private String model;
		private String stopReason;
		private String stopDetail;
		private int inputTokens;
		private int outputTokens;
		private int cacheRead;
		private int cacheWrite;

		Accumulator(ChatRequest request, StreamListener listener) {
			this.request = request;
			this.listener = listener;
			this.model = request.model();
		}

		boolean onEvent(String event, String data) throws StreamError {
			JsonObject e;
			try {
				e = Json.parseObject(data);
			} catch (RuntimeException ex) {
				return true;
			}
			String type = Json.str(e, "type");
			if (type == null) {
				type = event;
			}
			switch (type) {
			case "message_start" -> {
				JsonObject msg = Json.obj(e, "message");
				if (Json.str(msg, "model") != null) {
					model = Json.str(msg, "model");
				}
				usage(Json.obj(msg, "usage"));
			}
			case "content_block_start" -> {
				int index = Json.integer(e, "index", blocks.size());
				JsonObject block = Json.obj(e, "content_block");
				if (block == null) {
					return true;
				}
				block = block.deepCopy();
				String blockType = Json.str(block, "type");
				if ("tool_use".equals(blockType)) {
					partialJson.put(index, new StringBuilder());
					listener.onToolUseStart(Json.str(block, "id"), Json.str(block, "name"));
				} else if ("fallback".equals(blockType)) {
					listener.onFallback(Json.str(Json.obj(block, "from"), "model"),
							Json.str(Json.obj(block, "to"), "model"));
				}
				blocks.put(index, block);
			}
			case "content_block_delta" -> {
				int index = Json.integer(e, "index", -1);
				JsonObject block = blocks.get(index);
				JsonObject delta = Json.obj(e, "delta");
				if (block == null || delta == null) {
					return true;
				}
				switch (String.valueOf(Json.str(delta, "type"))) {
				case "text_delta" -> {
					String t = Json.str(delta, "text");
					append(block, "text", t);
					listener.onText(t);
				}
				case "thinking_delta" -> {
					String t = Json.str(delta, "thinking");
					append(block, "thinking", t);
					listener.onThinking(t);
				}
				case "signature_delta" -> append(block, "signature", Json.str(delta, "signature"));
				case "input_json_delta" -> {
					StringBuilder sb = partialJson.computeIfAbsent(index, i -> new StringBuilder());
					String part = Json.str(delta, "partial_json");
					if (part != null) {
						sb.append(part);
					}
				}
				default -> {
					// citations and future delta types are not needed here
				}
				}
			}
			case "message_delta" -> {
				JsonObject delta = Json.obj(e, "delta");
				if (Json.str(delta, "stop_reason") != null) {
					stopReason = Json.str(delta, "stop_reason");
				}
				JsonObject details = Json.obj(delta, "stop_details");
				if (details == null) {
					details = Json.obj(e, "stop_details");
				}
				if (details != null) {
					stopDetail = Json.str(details, "explanation");
					if (stopDetail == null) {
						stopDetail = Json.str(details, "category");
					}
				}
				usage(Json.obj(e, "usage"));
			}
			case "error" -> {
				JsonObject err = Json.obj(e, "error");
				String errType = Json.str(err, "type");
				int status = "overloaded_error".equals(errType) ? 529 : 500;
				throw new StreamError(status, (errType == null ? "error" : errType) + ": " + Json.str(err, "message"));
			}
			case "message_stop" -> {
				return false;
			}
			default -> {
				// ping, content_block_stop
			}
			}
			return true;
		}

		private void usage(JsonObject u) {
			if (u == null) {
				return;
			}
			inputTokens = Math.max(inputTokens, Json.integer(u, "input_tokens", 0));
			outputTokens = Math.max(outputTokens, Json.integer(u, "output_tokens", 0));
			cacheRead = Math.max(cacheRead, Json.integer(u, "cache_read_input_tokens", 0));
			cacheWrite = Math.max(cacheWrite, Json.integer(u, "cache_creation_input_tokens", 0));
		}

		private static void append(JsonObject block, String field, String delta) {
			if (delta == null) {
				return;
			}
			String prev = Json.str(block, field);
			block.addProperty(field, prev == null ? delta : prev + delta);
		}

		ChatResult finish() {
			List<JsonObject> content = new ArrayList<>();
			List<ToolCall> calls = new ArrayList<>();
			Map<String, ToolSpec> specs = new LinkedHashMap<>();
			for (ToolSpec t : request.tools()) {
				specs.put(t.name(), t);
			}
			for (Map.Entry<Integer, JsonObject> entry : blocks.entrySet()) {
				JsonObject block = entry.getValue();
				if ("tool_use".equals(Json.str(block, "type"))) {
					StringBuilder raw = partialJson.get(entry.getKey());
					String rawInput = raw == null || raw.isEmpty() ? "{}" : raw.toString();
					String error = null;
					JsonObject input = new JsonObject();
					try {
						JsonElement parsed = Json.parseStrict(rawInput);
						ToolSpec spec = specs.get(Json.str(block, "name"));
						error = spec == null ? null : SchemaCheck.validate(spec.inputSchema(), parsed);
						if (parsed.isJsonObject()) {
							input = parsed.getAsJsonObject();
						} else if (error == null) {
							error = "input must be a JSON object";
						}
					} catch (JsonParseException ex) {
						error = "invalid JSON: " + ex.getMessage();
					}
					block.add("input", input);
					calls.add(new ToolCall(Json.str(block, "id"), Json.str(block, "name"), input, rawInput, error));
				}
				content.add(block);
			}
			content = dropBlocksBeforeFallback(content);
			// Tool calls cut off by a fallback boundary are gone from the content, so drop them here too.
			List<String> keptIds = new ArrayList<>();
			for (JsonObject b : content) {
				if ("tool_use".equals(Json.str(b, "type"))) {
					keptIds.add(Json.str(b, "id"));
				}
			}
			calls.removeIf(c -> !keptIds.contains(c.id()));

			StringBuilder text = new StringBuilder();
			JsonArray array = new JsonArray();
			for (JsonObject b : content) {
				array.add(b);
				if ("text".equals(Json.str(b, "type")) && Json.str(b, "text") != null) {
					text.append(Json.str(b, "text"));
				}
			}
			return new ChatResult(Json.message("assistant", array), text.toString(), List.copyOf(calls),
					StopReason.fromAnthropic(stopReason), stopDetail, model,
					new Usage(inputTokens, outputTokens, cacheRead, cacheWrite));
		}

		/**
		 * After a mid-output fallback, thinking and tool_use blocks produced
		 * before the last {@code fallback} marker must not be echoed back; the
		 * marker itself is only an audit record.
		 */
		static List<JsonObject> dropBlocksBeforeFallback(List<JsonObject> content) {
			int last = -1;
			for (int i = 0; i < content.size(); i++) {
				if ("fallback".equals(Json.str(content.get(i), "type"))) {
					last = i;
				}
			}
			if (last < 0) {
				return content;
			}
			List<JsonObject> out = new ArrayList<>();
			for (int i = 0; i < content.size(); i++) {
				String t = Json.str(content.get(i), "type");
				if ("fallback".equals(t)) {
					continue;
				}
				if (i < last && ("thinking".equals(t) || "redacted_thinking".equals(t) || "tool_use".equals(t)
						|| "server_tool_use".equals(t))) {
					continue;
				}
				out.add(content.get(i));
			}
			return out;
		}
	}

	private static final class StreamError extends IOException {
		private static final long serialVersionUID = 1L;
		final int status;

		StreamError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	static String errorMessage(String body) {
		try {
			JsonObject o = Json.parseObject(body);
			JsonObject err = Json.obj(o, "error");
			if (err != null && Json.str(err, "message") != null) {
				return Json.str(err, "message");
			}
		} catch (RuntimeException e) {
			// not JSON
		}
		return body.length() > 500 ? body.substring(0, 500) : body;
	}

	static String readAll(InputStream in) {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private static String stripSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}

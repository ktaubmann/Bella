package de.kiliantaubmann.bella.core.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
 * Any endpoint that speaks the OpenAI Chat Completions protocol: OpenAI,
 * Azure OpenAI, Ollama, LM Studio, vLLM. The conversation is kept in
 * Anthropic format and converted per request; replies are converted back.
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

	public static final String ID = "openai";

	private final Supplier<String> apiKey;
	private final String baseUrl;
	private final HttpTransport http;

	/**
	 * @param baseUrl e.g. {@code https://api.openai.com/v1} or
	 *                {@code http://localhost:11434/v1}; {@code /chat/completions} is appended
	 */
	public OpenAiCompatibleProvider(Supplier<String> apiKey, String baseUrl, HttpTransport http) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.http = http;
	}

	@Override
	public String id() {
		return ID;
	}

	JsonObject buildBody(ChatRequest r) {
		JsonObject body = new JsonObject();
		body.addProperty("model", r.model());
		body.addProperty("stream", true);
		body.addProperty("max_tokens", r.maxTokens());
		JsonArray messages = new JsonArray();
		if (r.system() != null && !r.system().isBlank()) {
			JsonObject sys = new JsonObject();
			sys.addProperty("role", "system");
			sys.addProperty("content", r.system());
			messages.add(sys);
		}
		for (JsonObject m : r.messages()) {
			convertMessage(m, messages);
		}
		body.add("messages", messages);
		if (!r.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (ToolSpec t : r.tools()) {
				JsonObject fn = new JsonObject();
				fn.addProperty("name", t.name());
				fn.addProperty("description", t.description());
				fn.add("parameters", t.inputSchema());
				JsonObject tool = new JsonObject();
				tool.addProperty("type", "function");
				tool.add("function", fn);
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		return body;
	}

	/** Anthropic message → one or more Chat Completions messages. */
	static void convertMessage(JsonObject m, JsonArray out) {
		String role = Json.str(m, "role");
		JsonElement content = m.get("content");
		if (content != null && content.isJsonPrimitive()) {
			JsonObject msg = new JsonObject();
			msg.addProperty("role", role);
			msg.addProperty("content", content.getAsString());
			out.add(msg);
			return;
		}
		JsonArray blocks = content == null ? new JsonArray() : content.getAsJsonArray();
		StringBuilder text = new StringBuilder();
		JsonArray toolCalls = new JsonArray();
		List<JsonObject> toolResults = new ArrayList<>();
		for (JsonElement el : blocks) {
			JsonObject b = el.getAsJsonObject();
			switch (String.valueOf(Json.str(b, "type"))) {
			case "text" -> text.append(Json.str(b, "text"));
			case "tool_use" -> {
				JsonObject fn = new JsonObject();
				fn.addProperty("name", Json.str(b, "name"));
				JsonElement input = b.get("input");
				fn.addProperty("arguments", input == null ? "{}" : Json.GSON.toJson(input));
				JsonObject call = new JsonObject();
				call.addProperty("id", Json.str(b, "id"));
				call.addProperty("type", "function");
				call.add("function", fn);
				toolCalls.add(call);
			}
			case "tool_result" -> {
				JsonObject msg = new JsonObject();
				msg.addProperty("role", "tool");
				msg.addProperty("tool_call_id", Json.str(b, "tool_use_id"));
				msg.addProperty("content", toolResultText(b));
				toolResults.add(msg);
			}
			default -> {
				// thinking and other Anthropic-only blocks have no equivalent
			}
			}
		}
		toolResults.forEach(out::add);
		if (!text.isEmpty() || !toolCalls.isEmpty()) {
			JsonObject msg = new JsonObject();
			msg.addProperty("role", role);
			if (!text.isEmpty()) {
				msg.addProperty("content", text.toString());
			} else {
				msg.add("content", com.google.gson.JsonNull.INSTANCE);
			}
			if (!toolCalls.isEmpty()) {
				msg.add("tool_calls", toolCalls);
			}
			out.add(msg);
		}
	}

	private static String toolResultText(JsonObject b) {
		JsonElement c = b.get("content");
		if (c == null) {
			return "";
		}
		if (c.isJsonPrimitive()) {
			return c.getAsString();
		}
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : c.getAsJsonArray()) {
			if (e.isJsonObject() && "text".equals(Json.str(e.getAsJsonObject(), "type"))) {
				sb.append(Json.str(e.getAsJsonObject(), "text"));
			}
		}
		return sb.toString();
	}

	@Override
	public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("content-type", "application/json");
		headers.put("accept", "text/event-stream");
		String key = apiKey.get();
		if (key != null && !key.isBlank()) {
			headers.put("authorization", "Bearer " + key);
		}
		URI uri = URI.create(baseUrl + "/chat/completions");
		try (HttpTransport.Response response = http.post(uri, headers, Json.GSON.toJson(buildBody(request)), cancel)) {
			if (response.status() != 200) {
				String body = AnthropicProvider.readAll(response.body());
				throw new LlmException(response.status(),
						"HTTP " + response.status() + ": " + AnthropicProvider.errorMessage(body));
			}
			return readStream(response.body(), request, listener, cancel);
		} catch (IOException e) {
			cancel.throwIfCancelled();
			throw new LlmException("Network error: " + e.getMessage(), e);
		}
	}

	ChatResult readStream(InputStream in, ChatRequest request, StreamListener listener, CancelToken cancel)
			throws IOException, CancelledException {
		StringBuilder text = new StringBuilder();
		TreeMap<Integer, String[]> calls = new TreeMap<>(); // index -> {id, name}
		TreeMap<Integer, StringBuilder> args = new TreeMap<>();
		String[] finish = new String[1];
		String[] model = { request.model() };
		int[] usage = new int[2];
		SseParser.parse(in, (event, data) -> {
			if ("[DONE]".equals(data.trim())) {
				return false;
			}
			JsonObject chunk;
			try {
				chunk = Json.parseObject(data);
			} catch (RuntimeException e) {
				return true;
			}
			if (Json.str(chunk, "model") != null) {
				model[0] = Json.str(chunk, "model");
			}
			JsonObject u = Json.obj(chunk, "usage");
			if (u != null) {
				usage[0] = Json.integer(u, "prompt_tokens", usage[0]);
				usage[1] = Json.integer(u, "completion_tokens", usage[1]);
			}
			JsonArray choices = Json.arr(chunk, "choices");
			if (choices == null || choices.isEmpty()) {
				return true;
			}
			JsonObject choice = choices.get(0).getAsJsonObject();
			if (Json.str(choice, "finish_reason") != null) {
				finish[0] = Json.str(choice, "finish_reason");
			}
			JsonObject delta = Json.obj(choice, "delta");
			if (delta == null) {
				return true;
			}
			String t = Json.str(delta, "content");
			if (t != null && !t.isEmpty()) {
				text.append(t);
				listener.onText(t);
			}
			String reasoning = Json.str(delta, "reasoning_content");
			if (reasoning != null && !reasoning.isEmpty()) {
				listener.onThinking(reasoning);
			}
			JsonArray tc = Json.arr(delta, "tool_calls");
			if (tc != null) {
				for (JsonElement el : tc) {
					JsonObject c = el.getAsJsonObject();
					int index = Json.integer(c, "index", calls.size());
					String[] meta = calls.computeIfAbsent(index, i -> new String[2]);
					if (Json.str(c, "id") != null) {
						meta[0] = Json.str(c, "id");
					}
					JsonObject fn = Json.obj(c, "function");
					if (Json.str(fn, "name") != null) {
						if (meta[1] == null) {
							listener.onToolUseStart(meta[0], Json.str(fn, "name"));
						}
						meta[1] = Json.str(fn, "name");
					}
					if (Json.str(fn, "arguments") != null) {
						args.computeIfAbsent(index, i -> new StringBuilder()).append(Json.str(fn, "arguments"));
					}
				}
			}
			return true;
		}, cancel);
		cancel.throwIfCancelled();

		Map<String, ToolSpec> specs = new LinkedHashMap<>();
		request.tools().forEach(t -> specs.put(t.name(), t));
		JsonArray content = new JsonArray();
		if (!text.isEmpty()) {
			content.add(Json.textBlock(text.toString()));
		}
		List<ToolCall> toolCalls = new ArrayList<>();
		int n = 0;
		for (Map.Entry<Integer, String[]> e : calls.entrySet()) {
			String id = e.getValue()[0] != null ? e.getValue()[0] : "call_" + (n++);
			String name = e.getValue()[1];
			String raw = args.containsKey(e.getKey()) ? args.get(e.getKey()).toString() : "{}";
			if (raw.isBlank()) {
				raw = "{}";
			}
			JsonObject input = new JsonObject();
			String error = null;
			try {
				JsonElement parsed = Json.parseStrict(raw);
				ToolSpec spec = specs.get(name);
				error = spec == null ? null : SchemaCheck.validate(spec.inputSchema(), parsed);
				if (parsed.isJsonObject()) {
					input = parsed.getAsJsonObject();
				}
			} catch (JsonParseException ex) {
				error = "invalid JSON: " + ex.getMessage();
			}
			JsonObject block = new JsonObject();
			block.addProperty("type", "tool_use");
			block.addProperty("id", id);
			block.addProperty("name", name);
			block.add("input", input);
			content.add(block);
			toolCalls.add(new ToolCall(id, name, input, raw, error));
		}
		StopReason stop = StopReason.fromOpenAi(finish[0]);
		if (!toolCalls.isEmpty() && stop == StopReason.OTHER) {
			stop = StopReason.TOOL_USE;
		}
		return new ChatResult(Json.message("assistant", content), text.toString(), List.copyOf(toolCalls), stop, null,
				model[0], new Usage(usage[0], usage[1], 0, 0));
	}
}

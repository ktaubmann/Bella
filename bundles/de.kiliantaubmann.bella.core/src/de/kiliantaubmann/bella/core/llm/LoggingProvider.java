package de.kiliantaubmann.bella.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Writes each request of the wrapped provider to Bella's log: model, purpose,
 * sizes, stop reason, tokens and duration; with the detail level also the
 * system prompt, the last message and the answer.
 */
public final class LoggingProvider implements LlmProvider {

	private static final String AREA = "llm";

	private final LlmProvider delegate;

	private LoggingProvider(LlmProvider delegate) {
		this.delegate = delegate;
	}

	/** Wraps {@code provider} once; logging costs nothing while the log is off. */
	public static LlmProvider wrap(LlmProvider provider) {
		return provider instanceof LoggingProvider ? provider : new LoggingProvider(provider);
	}

	@Override
	public LlmProvider unwrap() {
		return delegate.unwrap();
	}

	@Override
	public String id() {
		return delegate.id();
	}

	@Override
	public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		long start = System.nanoTime();
		Log.info(AREA, id() + " request: model=" + request.model() + ", purpose=" + request.purpose() + ", messages="
				+ request.messages().size() + ", tools=" + request.tools().size()
				+ (request.effort() == null ? "" : ", effort=" + request.effort()));
		Log.debug(AREA, () -> "system prompt:\n" + Log.clip(request.system()) + "\nlast message:\n"
				+ Log.clip(lastMessage(request)));
		try {
			ChatResult r = delegate.chat(request, listener, cancel);
			Usage u = r.usage() == null ? Usage.NONE : r.usage();
			Log.info(AREA, id() + " answer: stop=" + r.stopReason() + ", model=" + r.model() + ", tokens in="
					+ u.inputTokens() + " out=" + u.outputTokens() + " cacheRead=" + u.cacheReadTokens() + " cacheWrite="
					+ u.cacheWriteTokens() + ", toolCalls=" + r.toolCalls().size() + ", text=" + r.text().length()
					+ " chars (" + Log.millisSince(start) + " ms)"
					+ (r.stopDetail() == null ? "" : ", detail: " + r.stopDetail()));
			Log.debug(AREA, () -> "answer text:\n" + Log.clip(r.text()));
			return r;
		} catch (LlmException e) {
			Log.warn(AREA, id() + " failed after " + Log.millisSince(start) + " ms: "
					+ (e.status() > 0 ? "HTTP " + e.status() + ": " : "") + e.getMessage());
			throw e;
		} catch (CancelledException e) {
			Log.info(AREA, id() + " cancelled after " + Log.millisSince(start) + " ms");
			throw e;
		} catch (RuntimeException e) {
			Log.error(AREA, id() + " failed after " + Log.millisSince(start) + " ms", e);
			throw e;
		}
	}

	private static String lastMessage(ChatRequest request) {
		if (request.messages().isEmpty()) {
			return "";
		}
		JsonObject m = request.messages().get(request.messages().size() - 1);
		JsonElement content = m.get("content");
		if (content == null) {
			return "";
		}
		if (content.isJsonPrimitive()) {
			return content.getAsString();
		}
		StringBuilder sb = new StringBuilder();
		if (content.isJsonArray()) {
			for (JsonElement b : content.getAsJsonArray()) {
				if (!b.isJsonObject()) {
					continue;
				}
				JsonObject block = b.getAsJsonObject();
				String type = String.valueOf(Json.str(block, "type"));
				switch (type) {
				case "text" -> sb.append(Json.str(block, "text"));
				case "tool_result" -> sb.append("[tool_result ").append(Json.str(block, "tool_use_id")).append("] ")
						.append(Json.GSON.toJson(block.get("content")));
				default -> sb.append('[').append(type).append(']');
				}
				sb.append('\n');
			}
		}
		return sb.toString().stripTrailing();
	}
}

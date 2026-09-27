package de.kiliantaubmann.bella.core.llm;

import java.util.List;

import com.google.gson.JsonObject;

/**
 * Outcome of one request.
 *
 * @param assistantMessage the reply in Anthropic Messages format, ready to be
 *                         appended to the conversation (thinking blocks and
 *                         signatures preserved)
 * @param text             concatenated visible text
 * @param toolCalls        tool calls in the reply
 * @param stopReason       why the model stopped
 * @param stopDetail       refusal explanation or similar, may be {@code null}
 * @param model            model that produced the reply (differs from the
 *                         requested one after a server-side fallback)
 * @param usage            token usage
 */
public record ChatResult(JsonObject assistantMessage, String text, List<ToolCall> toolCalls, StopReason stopReason,
		String stopDetail, String model, Usage usage) {
}

package de.kiliantaubmann.bella.core.llm;

import java.util.List;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.ToolSpec;

/**
 * One request to a model.
 *
 * @param model     model id
 * @param system    system prompt (stable, so it can be cached)
 * @param messages  conversation in Anthropic Messages format; providers for
 *                  other APIs convert from it
 * @param tools     tools the model may call, may be empty
 * @param maxTokens output cap
 * @param purpose   chat turns think and may call tools; completions are short
 *                  and latency sensitive
 * @param effort    optional effort level ({@code low … max}), {@code null} for
 *                  the model default
 */
public record ChatRequest(String model, String system, List<JsonObject> messages, List<ToolSpec> tools, int maxTokens,
		Purpose purpose, String effort) {

	public enum Purpose {
		CHAT, COMPLETION
	}

	public ChatRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
	}
}

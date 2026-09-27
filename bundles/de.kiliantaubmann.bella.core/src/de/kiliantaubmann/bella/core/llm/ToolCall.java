package de.kiliantaubmann.bella.core.llm;

import com.google.gson.JsonObject;

/**
 * A tool call requested by the model.
 *
 * @param id       id to answer with the {@code tool_result}
 * @param name     tool name as exposed to the model
 * @param input    parsed input; empty object when {@link #inputError()} is set
 * @param rawInput input exactly as streamed
 * @param inputError why the input could not be used, or {@code null}
 */
public record ToolCall(String id, String name, JsonObject input, String rawInput, String inputError) {

	public boolean inputValid() {
		return inputError == null;
	}
}

package de.kiliantaubmann.bella.core.tools;

/** Result of a tool call, returned to the model as a {@code tool_result}. */
public record ToolResult(String content, boolean isError) {

	public static ToolResult ok(String content) {
		return new ToolResult(content, false);
	}

	public static ToolResult error(String content) {
		return new ToolResult(content, true);
	}
}

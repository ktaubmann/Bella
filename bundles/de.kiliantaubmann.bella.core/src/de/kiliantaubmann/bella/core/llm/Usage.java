package de.kiliantaubmann.bella.core.llm;

/** Token usage of one response. */
public record Usage(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {

	public static final Usage NONE = new Usage(0, 0, 0, 0);
}

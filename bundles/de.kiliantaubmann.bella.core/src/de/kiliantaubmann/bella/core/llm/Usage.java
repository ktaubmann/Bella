package de.kiliantaubmann.bella.core.llm;

/** Token usage of one response. */
public record Usage(int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {

	public static final Usage NONE = new Usage(0, 0, 0, 0);

	/** The sum of both, e.g. over the model requests of one turn. */
	public Usage plus(Usage other) {
		if (other == null) {
			return this;
		}
		return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
				cacheReadTokens + other.cacheReadTokens, cacheWriteTokens + other.cacheWriteTokens);
	}

	/** All input of the request, cached or not: how much of the context window it filled. */
	public int promptTokens() {
		return inputTokens + cacheReadTokens + cacheWriteTokens;
	}

	public boolean isEmpty() {
		return promptTokens() == 0 && outputTokens == 0;
	}
}

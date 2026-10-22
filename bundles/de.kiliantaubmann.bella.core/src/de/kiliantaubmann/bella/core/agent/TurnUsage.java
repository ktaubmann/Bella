package de.kiliantaubmann.bella.core.agent;

import de.kiliantaubmann.bella.core.llm.Usage;

/**
 * Tokens of one turn of a conversation.
 *
 * @param total         sum over all model requests of the turn (a turn with
 *                      tool calls makes several)
 * @param contextTokens how much of the context window the conversation fills
 *                      after the turn: input and output of its last request;
 *                      {@code 0} if unknown
 * @param contextWindow size of the model's context window, {@code 0} if unknown
 */
public record TurnUsage(Usage total, int contextTokens, int contextWindow) {

	public TurnUsage {
		total = total == null ? Usage.NONE : total;
	}

	/** Share of the context window in percent, {@code -1} if the window or the fill is unknown. */
	public int contextPercent() {
		if (contextWindow <= 0 || contextTokens <= 0) {
			return -1;
		}
		return (int) Math.min(100, Math.round(100.0 * contextTokens / contextWindow));
	}

	public boolean isEmpty() {
		return total.isEmpty() && contextTokens == 0;
	}
}

package de.kiliantaubmann.bella.core.llm;

/** Why the model stopped, normalised across providers. */
public enum StopReason {
	END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE_TURN, STOP_SEQUENCE, OTHER;

	public static StopReason fromAnthropic(String s) {
		if (s == null) {
			return OTHER;
		}
		return switch (s) {
		case "end_turn" -> END_TURN;
		case "tool_use" -> TOOL_USE;
		case "max_tokens" -> MAX_TOKENS;
		case "refusal" -> REFUSAL;
		case "pause_turn" -> PAUSE_TURN;
		case "stop_sequence" -> STOP_SEQUENCE;
		default -> OTHER;
		};
	}

	public static StopReason fromOpenAi(String s) {
		if (s == null) {
			return OTHER;
		}
		return switch (s) {
		case "stop" -> END_TURN;
		case "tool_calls", "function_call" -> TOOL_USE;
		case "length" -> MAX_TOKENS;
		case "content_filter" -> REFUSAL;
		default -> OTHER;
		};
	}
}

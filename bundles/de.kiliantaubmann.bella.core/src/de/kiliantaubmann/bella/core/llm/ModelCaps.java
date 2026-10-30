package de.kiliantaubmann.bella.core.llm;

import java.util.List;

/** Request features that depend on the Claude model family. */
public final class ModelCaps {

	/** Families that take {@code thinking: {type: "adaptive"}} and {@code output_config.effort}. */
	private static final List<String> ADAPTIVE = List.of("claude-opus-5", "claude-opus-4-8", "claude-opus-4-7",
			"claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6", "claude-fable-5", "claude-mythos-5");

	/** Families that accept server-side refusal fallbacks ({@code fallbacks: "default"}). */
	private static final List<String> FALLBACK = List.of("claude-opus-5", "claude-fable-5", "claude-mythos-5");

	/** Families with the tool search tool; Claude Haiku 4.5 has none, Claude Haiku 5.5 has it. */
	private static final List<String> TOOL_SEARCH = List.of("claude-fable-5", "claude-mythos-5", "claude-opus-5",
			"claude-sonnet-5", "claude-haiku-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
			"claude-opus-4-5", "claude-sonnet-4-6", "claude-sonnet-4-5");

	/** Families with context editing ({@code clear_tool_uses}); Claude 4.5 and later. */
	private static final List<String> CONTEXT_EDITING = List.of("claude-fable-5", "claude-mythos-5", "claude-opus-5",
			"claude-sonnet-5", "claude-haiku-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
			"claude-opus-4-5", "claude-sonnet-4-6", "claude-sonnet-4-5", "claude-haiku-4-5");

	/**
	 * Families with a context window of 200,000 tokens; newer Claude models have 1,000,000.
	 * {@code claude-sonnet-4-2} and {@code claude-opus-4-2} match the dated IDs of Claude 4.0.
	 */
	private static final List<String> WINDOW_200K = List.of("claude-haiku-4", "claude-sonnet-4-5", "claude-sonnet-4-0",
			"claude-sonnet-4-2", "claude-opus-4-5", "claude-opus-4-1", "claude-opus-4-0", "claude-opus-4-2",
			"claude-3");

	private ModelCaps() {
	}

	/**
	 * Context window of a Claude model in tokens, {@code 0} for models Bella
	 * does not know. A prefix such as {@code us.anthropic.} is ignored.
	 */
	public static int contextWindow(String model) {
		int at = model == null ? -1 : model.indexOf("claude-");
		if (at < 0) {
			return 0;
		}
		return startsWithAny(model.substring(at), WINDOW_200K) ? 200_000 : 1_000_000;
	}

	public static boolean adaptiveThinking(String model) {
		return startsWithAny(model, ADAPTIVE);
	}

	public static boolean effort(String model) {
		return startsWithAny(model, ADAPTIVE);
	}

	public static boolean refusalFallback(String model) {
		return startsWithAny(model, FALLBACK);
	}

	/** Whether tools can be deferred to the tool search tool ({@code defer_loading}). */
	public static boolean toolSearch(String model) {
		return startsWithAny(model, TOOL_SEARCH);
	}

	/** Whether old tool results can be cleared server-side ({@code context_management}). */
	public static boolean contextEditing(String model) {
		return startsWithAny(model, CONTEXT_EDITING);
	}

	private static boolean startsWithAny(String model, List<String> prefixes) {
		if (model == null) {
			return false;
		}
		for (String p : prefixes) {
			if (model.startsWith(p)) {
				return true;
			}
		}
		return false;
	}
}

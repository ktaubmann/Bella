package de.kiliantaubmann.bella.core.llm;

import java.util.List;

/** Request features that depend on the Claude model family. */
public final class ModelCaps {

	/** Families that take {@code thinking: {type: "adaptive"}} and {@code output_config.effort}. */
	private static final List<String> ADAPTIVE = List.of("claude-opus-5", "claude-opus-4-8", "claude-opus-4-7",
			"claude-opus-4-6", "claude-sonnet-5", "claude-sonnet-4-6", "claude-fable-5", "claude-mythos-5");

	/** Families that accept server-side refusal fallbacks ({@code fallbacks: "default"}). */
	private static final List<String> FALLBACK = List.of("claude-opus-5", "claude-fable-5", "claude-mythos-5");

	private ModelCaps() {
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

package de.kiliantaubmann.bella.core.llm;

/** Receives streaming progress. All methods are called on the request thread. */
public interface StreamListener {

	StreamListener NONE = new StreamListener() {
	};

	default void onText(String delta) {
	}

	default void onThinking(String delta) {
	}

	default void onToolUseStart(String id, String name) {
	}

	/** A safety decline was answered by another model server-side. */
	default void onFallback(String fromModel, String toModel) {
	}
}

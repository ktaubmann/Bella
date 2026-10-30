package de.kiliantaubmann.bella.core.agent;

import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;

/**
 * A chat with tools. Implemented by {@link ChatSession} (Bella runs the tool
 * loop against an API) and by the Claude Code session (the CLI runs the loop
 * and calls Bella's tools through the local MCP server).
 */
public interface Conversation extends AutoCloseable {

	/** Sends a user message and runs until the model's turn is complete. */
	void ask(String userText, ConversationListener listener, CancelToken cancel)
			throws LlmException, CancelledException;

	/** Starts over with a new system prompt. */
	void reset(String system);

	/**
	 * Whether the next {@link #ask} continues the conversation the model already has. A CLI that has to
	 * start again (process ended, model or effort changed) begins without the earlier messages.
	 */
	default boolean continuesConversation() {
		return true;
	}

	/** Short label for the status line, e.g. the model and how it is reached. */
	default String describe() {
		return "";
	}

	/** The conversation itself, or the one a decorator such as {@link LoggingConversation} wraps. */
	default Conversation unwrap() {
		return this;
	}

	@Override
	default void close() {
	}
}

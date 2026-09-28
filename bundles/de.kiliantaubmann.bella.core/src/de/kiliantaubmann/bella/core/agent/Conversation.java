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

	/** Short label for the status line, e.g. the model and how it is reached. */
	default String describe() {
		return "";
	}

	@Override
	default void close() {
	}
}

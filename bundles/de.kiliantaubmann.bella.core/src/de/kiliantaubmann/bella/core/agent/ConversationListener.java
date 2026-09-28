package de.kiliantaubmann.bella.core.agent;

import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;

/** Progress of a conversation turn, called on the thread that runs it. */
public interface ConversationListener extends StreamListener, ToolExecutor.Observer {

	default void onTurnStart() {
	}

	/** Something the developer should know that is not model text (refusal, truncation, policy). */
	default void onNotice(String message) {
	}

	/** @param last the last model result, or {@code null} when the backend does not produce one */
	default void onTurnEnd(ChatResult last) {
	}
}

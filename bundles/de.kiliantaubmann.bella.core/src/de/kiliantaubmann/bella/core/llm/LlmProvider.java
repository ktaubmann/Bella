package de.kiliantaubmann.bella.core.llm;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;

/** A model API: Anthropic's Messages API or an OpenAI-compatible endpoint. */
public interface LlmProvider {

	String id();

	/** The provider itself, or the one a decorator such as {@link LoggingProvider} wraps. */
	default LlmProvider unwrap() {
		return this;
	}

	ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException;
}

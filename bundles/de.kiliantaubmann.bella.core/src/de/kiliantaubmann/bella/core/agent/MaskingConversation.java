package de.kiliantaubmann.bella.core.agent;

import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.mask.Masker;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;

/**
 * Masks what the developer sends and unmasks what the model streams back, for
 * any backend (API, Claude Code or Copilot CLI). Tool inputs and results are
 * handled by the {@code ToolExecutor} with the same {@link Masker}, so the
 * model works only with placeholders and the developer sees only real names.
 */
public final class MaskingConversation implements Conversation {

	private final Conversation delegate;
	private final Masker masker;

	private MaskingConversation(Conversation delegate, Masker masker) {
		this.delegate = delegate;
		this.masker = masker;
	}

	public static Conversation wrap(Conversation c, Masker masker) {
		return masker == null || masker == Masker.NONE ? c : new MaskingConversation(c, masker);
	}

	@Override
	public Conversation unwrap() {
		return delegate.unwrap();
	}

	@Override
	public void ask(String userText, ConversationListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		Masker.UnmaskStream text = masker.stream(listener::onText);
		Masker.UnmaskStream thinking = masker.stream(listener::onThinking);
		Runnable flush = () -> {
			thinking.flush();
			text.flush();
		};
		ConversationListener unmasking = new ConversationListener() {
			@Override
			public void onText(String delta) {
				thinking.flush();
				text.accept(delta);
			}

			@Override
			public void onThinking(String delta) {
				text.flush();
				thinking.accept(delta);
			}

			@Override
			public void onToolUseStart(String id, String name) {
				flush.run();
				listener.onToolUseStart(id, name);
			}

			@Override
			public void onToolCall(ToolSpec tool, ToolCall call) {
				flush.run();
				listener.onToolCall(tool, call);
			}

			@Override
			public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
				listener.onToolResult(tool, call, result);
			}

			@Override
			public void onFallback(String fromModel, String toModel) {
				listener.onFallback(fromModel, toModel);
			}

			@Override
			public void onTurnStart() {
				listener.onTurnStart();
			}

			@Override
			public void onNotice(String message) {
				flush.run();
				listener.onNotice(masker.unmask(message));
			}

			@Override
			public void onTurnEnd(ChatResult last) {
				flush.run();
				listener.onTurnEnd(last);
			}
		};
		try {
			delegate.ask(masker.mask(userText), unmasking, cancel);
		} finally {
			flush.run();
		}
	}

	@Override
	public void reset(String system) {
		delegate.reset(masker.mask(system));
	}

	@Override
	public String describe() {
		return delegate.describe();
	}

	@Override
	public void close() {
		delegate.close();
	}
}

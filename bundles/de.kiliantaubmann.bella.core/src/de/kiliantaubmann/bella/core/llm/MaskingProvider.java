package de.kiliantaubmann.bella.core.llm;

import java.util.List;

import de.kiliantaubmann.bella.core.mask.Masker;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;

/**
 * Masks a single request (editor actions, completion, deriving conventions)
 * and unmasks the answer, so generated code reaches the editor with the real
 * names. The chat does not use it: there the history stays masked and
 * {@code MaskingConversation} unmasks only what the developer sees.
 */
public final class MaskingProvider implements LlmProvider {

	private final LlmProvider delegate;
	private final Masker masker;

	private MaskingProvider(LlmProvider delegate, Masker masker) {
		this.delegate = delegate;
		this.masker = masker;
	}

	public static LlmProvider wrap(LlmProvider provider, Masker masker) {
		return masker == null || masker == Masker.NONE ? provider : new MaskingProvider(provider, masker);
	}

	@Override
	public LlmProvider unwrap() {
		return delegate.unwrap();
	}

	@Override
	public String id() {
		return delegate.id();
	}

	@Override
	public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		if (!masker.active()) {
			return delegate.chat(request, listener, cancel);
		}
		ChatRequest masked = new ChatRequest(request.model(),
				request.system() == null ? null : masker.mask(request.system()) + Masker.PROMPT_NOTE,
				request.messages().stream().map(masker::maskMessage).toList(), request.tools(), request.maxTokens(),
				request.purpose(), request.effort());
		Masker.UnmaskStream text = masker.stream(listener::onText);
		Masker.UnmaskStream thinking = masker.stream(listener::onThinking);
		StreamListener unmasking = new StreamListener() {
			@Override
			public void onText(String delta) {
				text.accept(delta);
			}

			@Override
			public void onThinking(String delta) {
				thinking.accept(delta);
			}

			@Override
			public void onToolUseStart(String id, String name) {
				listener.onToolUseStart(id, name);
			}

			@Override
			public void onFallback(String fromModel, String toModel) {
				listener.onFallback(fromModel, toModel);
			}
		};
		ChatResult r;
		try {
			r = delegate.chat(masked, unmasking, cancel);
		} finally {
			thinking.flush();
			text.flush();
		}
		List<ToolCall> calls = r.toolCalls().stream().map(c -> new ToolCall(c.id(), c.name(),
				masker.unmask(c.input()), masker.unmask(c.rawInput()), c.inputError())).toList();
		return new ChatResult(masker.unmaskMessage(r.assistantMessage()), masker.unmask(r.text()), calls,
				r.stopReason(), masker.unmask(r.stopDetail()), r.model(), r.usage());
	}
}

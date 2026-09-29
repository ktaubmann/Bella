package de.kiliantaubmann.bella.core.agent;

import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Writes the turns of a chat to Bella's log, whichever backend runs it (API,
 * Claude Code or Copilot CLI): question size, notices, tool use, stop reason,
 * tokens, duration and errors; with the detail level also question and answer.
 */
public final class LoggingConversation implements Conversation {

	private static final String AREA = "chat";

	private final Conversation delegate;

	private LoggingConversation(Conversation delegate) {
		this.delegate = delegate;
	}

	public static Conversation wrap(Conversation c) {
		return c instanceof LoggingConversation ? c : new LoggingConversation(c);
	}

	@Override
	public Conversation unwrap() {
		return delegate.unwrap();
	}

	@Override
	public void ask(String userText, ConversationListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		String kind = delegate.unwrap().getClass().getSimpleName();
		long start = System.nanoTime();
		Log.info(AREA, kind + " question: " + (userText == null ? 0 : userText.length()) + " chars ["
				+ delegate.describe() + "]");
		Log.debug(AREA, () -> "question:\n" + Log.clip(userText));
		StringBuilder answer = new StringBuilder();
		ConversationListener logging = new ConversationListener() {
			@Override
			public void onText(String delta) {
				if (Log.detail()) {
					synchronized (answer) {
						if (answer.length() < Log.MAX_CONTENT) {
							answer.append(delta);
						}
					}
				}
				listener.onText(delta);
			}

			@Override
			public void onThinking(String delta) {
				listener.onThinking(delta);
			}

			@Override
			public void onToolUseStart(String id, String name) {
				listener.onToolUseStart(id, name);
			}

			@Override
			public void onToolCall(ToolSpec tool, ToolCall call) {
				listener.onToolCall(tool, call);
			}

			@Override
			public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
				listener.onToolResult(tool, call, result);
			}

			@Override
			public void onFallback(String fromModel, String toModel) {
				Log.info(AREA, "model fallback: " + fromModel + " -> " + toModel);
				listener.onFallback(fromModel, toModel);
			}

			@Override
			public void onTurnStart() {
				listener.onTurnStart();
			}

			@Override
			public void onNotice(String message) {
				Log.info(AREA, "notice: " + message);
				listener.onNotice(message);
			}

			@Override
			public void onTurnEnd(ChatResult last) {
				if (last != null) {
					Usage u = last.usage() == null ? Usage.NONE : last.usage();
					Log.info(AREA, "turn end: stop=" + last.stopReason() + ", model=" + last.model() + ", tokens in="
							+ u.inputTokens() + " out=" + u.outputTokens());
					if (Log.detail() && last.text() != null) {
						synchronized (answer) {
							if (answer.isEmpty()) {
								answer.append(last.text()); // backends that do not stream text
							}
						}
					}
				}
				listener.onTurnEnd(last);
			}
		};
		try {
			delegate.ask(userText, logging, cancel);
			Log.info(AREA, kind + " answered (" + Log.millisSince(start) + " ms)");
		} catch (LlmException e) {
			Log.warn(AREA, kind + " failed after " + Log.millisSince(start) + " ms: "
					+ (e.status() > 0 ? "HTTP " + e.status() + ": " : "") + e.getMessage());
			throw e;
		} catch (CancelledException e) {
			Log.info(AREA, kind + " stopped by the developer after " + Log.millisSince(start) + " ms");
			throw e;
		} catch (RuntimeException e) {
			Log.error(AREA, kind + " failed after " + Log.millisSince(start) + " ms", e);
			throw e;
		} finally {
			Log.debug(AREA, () -> {
				synchronized (answer) {
					return "answer:\n" + Log.clip(answer.toString());
				}
			});
		}
	}

	@Override
	public void reset(String system) {
		Log.info(AREA, "new conversation");
		delegate.reset(system);
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

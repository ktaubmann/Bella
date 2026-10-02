package de.kiliantaubmann.bella.core.copilot;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Single requests without tools through the GitHub Copilot CLI: explain,
 * generate, rewrite, implement method and completion. Each request starts
 * {@code copilot --acp}, opens a session without MCP servers, sends one prompt
 * (over stdin, so there is no command-line length limit) and ends the process.
 * Chats with tools use {@link CopilotSession}.
 */
public final class CopilotProvider implements LlmProvider {

	public static final String ID = "copilot";

	private final CopilotCli cli;

	public CopilotProvider(CopilotCli cli) {
		this.cli = cli;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		if (!request.tools().isEmpty()) {
			throw new LlmException(0, "Requests with tools go through the Copilot chat session.");
		}
		cancel.throwIfCancelled();
		AtomicReference<Turn> turn = new AtomicReference<>();
		AtomicReference<String> sessionId = new AtomicReference<>();
		String effort = request.purpose() == ChatRequest.Purpose.COMPLETION ? null : request.effort();
		Acp acp = cli.startAcp(request.model(), effort, (method, params) -> {
			Turn t = turn.get();
			if (t != null && "session/update".equals(method)
					&& java.util.Objects.equals(Json.str(params, "sessionId"), sessionId.get())) {
				t.onUpdate(params);
			}
		}, (method, params) -> {
			if ("session/request_permission".equals(method)) {
				// No tools in single requests: reject whatever the CLI wants to do.
				return Turn.decidePermission(params, null, title -> {
				});
			}
			throw new Acp.AcpException(-32601, "Not supported by Bella: " + method);
		});
		cancel.onCancel(acp::close);
		try {
			JsonObject created = acp.request("session/new", cli.newSessionParams(null, null), 60_000);
			sessionId.set(Json.str(created, "sessionId"));
			Turn t = new Turn(adapt(listener));
			turn.set(t);
			JsonObject result = acp.request("session/prompt",
					CopilotSession.promptParams(sessionId.get(), promptText(request)), 0);
			t.finish();
			cancel.throwIfCancelled();
			return t.result(result, request.model());
		} catch (Acp.AcpException e) {
			throw new LlmException(0, e.isAuthRequired() ? CopilotCli.loginMessage() : e.getMessage());
		} catch (IOException e) {
			cancel.throwIfCancelled();
			throw new LlmException(0, e.getMessage());
		} finally {
			acp.close();
		}
	}

	/** System prompt and messages as one text; earlier turns are quoted so nothing is lost. */
	static String promptText(ChatRequest request) {
		StringBuilder sb = new StringBuilder();
		if (request.system() != null && !request.system().isBlank()) {
			sb.append("<instructions>\n").append(request.system()).append("\n</instructions>\n\n");
		}
		int n = request.messages().size();
		if (n > 1) {
			sb.append("<earlier_messages>\n");
			for (int i = 0; i < n - 1; i++) {
				JsonObject m = request.messages().get(i);
				sb.append('[').append(Json.str(m, "role")).append("]\n").append(plainText(m)).append("\n\n");
			}
			sb.append("</earlier_messages>\n\n");
		}
		if (n > 0) {
			sb.append(plainText(request.messages().get(n - 1)));
		}
		return sb.toString();
	}

	private static String plainText(JsonObject message) {
		JsonElement c = message.get("content");
		if (c == null) {
			return "";
		}
		if (c.isJsonPrimitive()) {
			return c.getAsString();
		}
		StringBuilder sb = new StringBuilder();
		if (c.isJsonArray()) {
			for (JsonElement b : c.getAsJsonArray()) {
				if (b.isJsonObject() && "text".equals(Json.str(b.getAsJsonObject(), "type"))) {
					if (sb.length() > 0) {
						sb.append("\n\n");
					}
					sb.append(Json.str(b.getAsJsonObject(), "text"));
				}
			}
		}
		return sb.toString();
	}

	private static ConversationListener adapt(StreamListener l) {
		return new ConversationListener() {
			@Override
			public void onText(String delta) {
				l.onText(delta);
			}

			@Override
			public void onThinking(String delta) {
				l.onThinking(delta);
			}
		};
	}
}

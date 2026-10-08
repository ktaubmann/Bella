package de.kiliantaubmann.bella.core.copilot;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.mcp.McpServer;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * A chat through the GitHub Copilot CLI and therefore the developer's Copilot
 * subscription. One {@code copilot --acp} process lives as long as the chat
 * and keeps the history. The CLI calls Bella's tools through a private
 * {@link McpServer}, where the usual policy, open-editor router and
 * confirmation apply; everything else it asks permission for is rejected.
 */
public final class CopilotSession implements Conversation {

	static final long INTERRUPT_GRACE_MS = 3_000;

	private final CopilotCli cli;
	private final Supplier<ChatSession.Settings> settings;
	private final ToolExecutor executor;
	private final String version;

	private String system;
	private McpServer server;
	private Acp acp;
	private String sessionId;
	private String processModel;
	private String processEffort;
	/** The system prompt still has to be sent with the next message of this session. */
	private boolean sendSystem = true;
	/** The CLI lost the conversation and the developer was not told yet. */
	private boolean contextLost;
	private volatile Turn turn;
	private volatile ConversationListener turnListener;

	public CopilotSession(CopilotCli cli, Supplier<ChatSession.Settings> settings, String system,
			ToolExecutor executor, String version) {
		this.cli = cli;
		this.settings = settings;
		this.system = system;
		this.executor = executor;
		this.version = version;
	}

	@Override
	public String describe() {
		String model = settings.get().model();
		return (model == null || model.isBlank() ? "default" : model) + " (Copilot)";
	}

	@Override
	public synchronized void reset(String newSystem) {
		stopProcess();
		system = newSystem;
		contextLost = false;
	}

	@Override
	public void ask(String userText, ConversationListener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		cancel.throwIfCancelled();
		listener.onTurnStart();
		Acp a;
		String sid;
		String prompt;
		Turn t = new Turn(listener);
		synchronized (this) {
			try {
				ensureSession();
			} catch (Acp.AcpException e) {
				stopProcess();
				listener.onNotice(Turn.notice(e));
				listener.onTurnEnd(null);
				return;
			}
			if (contextLost) {
				listener.onNotice("cp_restarted");
				contextLost = false;
			}
			a = acp;
			sid = sessionId;
			prompt = sendSystem && system != null && !system.isBlank()
					? "<instructions>\n" + system + "\n</instructions>\n\n" + userText
					: userText;
			sendSystem = false;
			turn = t;
			turnListener = listener;
		}
		server.setTurn(listener, cancel);
		cancel.onCancel(() -> interrupt(a, sid, t));
		JsonObject result;
		try {
			result = a.request("session/prompt", promptParams(sid, prompt), 0);
		} catch (Acp.AcpException e) {
			finish(t);
			if (cancel.isCancelled()) {
				throw new CancelledException();
			}
			listener.onNotice(Turn.notice(e));
			listener.onTurnEnd(t.result((String) null, processModel));
			return;
		} catch (IOException e) {
			finish(t);
			synchronized (this) {
				if (acp == a) {
					stopProcess();
				}
				contextLost = true;
			}
			if (cancel.isCancelled()) {
				throw new CancelledException();
			}
			throw new LlmException(0, e.getMessage());
		}
		finish(t);
		String stop = Json.str(result, "stopReason");
		if (cancel.isCancelled() || "cancelled".equals(stop)) {
			listener.onTurnEnd(t.result(result, processModel));
			throw new CancelledException();
		}
		switch (String.valueOf(stop)) {
		case "max_tokens" -> listener.onNotice("max_tokens");
		case "max_turn_requests" -> listener.onNotice("max_rounds");
		case "refusal" -> listener.onNotice("refusal:");
		default -> {
		}
		}
		listener.onTurnEnd(t.result(result, processModel));
	}

	private void finish(Turn t) {
		t.finish();
		server.setTurn(null, null);
		turn = null;
		turnListener = null;
	}

	/**
	 * The ACP prompt. Leading {@code <instructions>} go into a second block:
	 * Copilot names the session after the first one, and the session list
	 * should show the question, not Bella's system prompt.
	 */
	static JsonObject promptParams(String sessionId, String text) {
		JsonArray prompt = new JsonArray();
		String end = "</instructions>";
		int close = text.startsWith("<instructions>") ? text.indexOf(end) : -1;
		String rest = close < 0 ? "" : text.substring(close + end.length()).strip();
		if (close >= 0 && !rest.isEmpty()) {
			prompt.add(textBlock(rest));
			prompt.add(textBlock(text.substring(0, close + end.length())));
		} else {
			prompt.add(textBlock(text));
		}
		JsonObject p = new JsonObject();
		p.addProperty("sessionId", sessionId);
		p.add("prompt", prompt);
		return p;
	}

	private static JsonObject textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		return block;
	}

	/** Asks the CLI to stop the turn; ends the process if it does not stop in time. */
	private void interrupt(Acp a, String sid, Turn t) {
		if (t.finished()) {
			return;
		}
		JsonObject p = new JsonObject();
		p.addProperty("sessionId", sid);
		try {
			a.notify("session/cancel", p);
		} catch (IOException e) {
			a.close();
			return;
		}
		Thread killer = new Thread(() -> {
			long deadline = System.currentTimeMillis() + INTERRUPT_GRACE_MS;
			while (!t.finished() && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (!t.finished()) {
				a.close();
			}
		}, "bella-copilot-interrupt");
		killer.setDaemon(true);
		killer.start();
	}

	private void onNotification(String method, JsonObject params) {
		Turn t = turn;
		if (t != null && "session/update".equals(method) && Objects.equals(Json.str(params, "sessionId"), sessionId)) {
			t.onUpdate(params);
		}
	}

	private JsonElement onRequest(String method, JsonObject params) throws Acp.AcpException {
		if ("session/request_permission".equals(method)) {
			return Turn.decidePermission(params, name -> executor.registry().find(name).isPresent(), title -> {
				ConversationListener l = turnListener;
				if (l != null) {
					l.onNotice("cp_denied:" + title);
				}
			});
		}
		throw new Acp.AcpException(-32601, "Not supported by Bella: " + method);
	}

	private void ensureSession() throws LlmException, Acp.AcpException {
		ChatSession.Settings s = settings.get();
		if (acp != null && (!acp.isAlive() || !Objects.equals(processModel, s.model())
				|| !Objects.equals(processEffort, s.effort()))) {
			stopProcess();
			contextLost = true;
		}
		if (acp != null) {
			return;
		}
		try {
			if (server == null) {
				server = new McpServer(executor, version);
			}
			server.start();
		} catch (IOException e) {
			throw new LlmException("Could not start Bella's tool server: " + e.getMessage(), e);
		}
		Acp a = cli.startAcp(s.model(), s.effort(), this::onNotification, this::onRequest);
		try {
			JsonObject created = a.request("session/new", cli.newSessionParams(server.url(), server.token()), 60_000);
			sessionId = Json.str(created, "sessionId");
		} catch (IOException e) {
			a.close();
			throw new LlmException("The Copilot CLI did not open a session: " + e.getMessage(), e);
		} catch (Acp.AcpException e) {
			a.close();
			throw e;
		}
		acp = a;
		processModel = s.model();
		processEffort = s.effort();
		sendSystem = true;
	}

	private void stopProcess() {
		if (acp != null) {
			acp.close();
			acp = null;
		}
		sessionId = null;
	}

	@Override
	public synchronized void close() {
		stopProcess();
		if (server != null) {
			server.close();
			server = null;
		}
	}
}

package de.kiliantaubmann.bella.core.claudecode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
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
 * A chat that runs through the Claude Code CLI and therefore the developer's
 * Claude subscription. One CLI process lives as long as the chat; it keeps the
 * history itself. The CLI runs the tool loop and calls Bella's tools through a
 * private {@link McpServer}, where the usual policy, open-editor router and
 * confirmation apply.
 */
public final class ClaudeCodeSession implements Conversation {

	static final long INTERRUPT_GRACE_MS = 3_000;

	private final ClaudeCli cli;
	private final Supplier<ChatSession.Settings> settings;
	private final ToolExecutor executor;
	private final String version;
	private final AtomicInteger requestIds = new AtomicInteger();

	private String system;
	private McpServer server;
	private ProcessLauncher.CliProcess process;
	private BufferedReader stdout;
	private String processModel;
	private String processEffort;
	private Path systemFile;
	private Path mcpConfigFile;
	/** The CLI lost the conversation (crash, stop or model change) and the developer was not told yet. */
	private boolean contextLost;
	private boolean mcpWarned;

	public ClaudeCodeSession(ClaudeCli cli, Supplier<ChatSession.Settings> settings, String system,
			ToolExecutor executor, String version) {
		this.cli = cli;
		this.settings = settings;
		this.system = system;
		this.executor = executor;
		this.version = version;
	}

	@Override
	public String describe() {
		return settings.get().model() + " (Claude Code)";
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
		ProcessLauncher.CliProcess p;
		BufferedReader in;
		synchronized (this) {
			ensureProcess();
			if (contextLost) {
				listener.onNotice("cc_restarted");
				contextLost = false;
			}
			p = process;
			in = stdout;
		}
		server.setTurn(listener, cancel);
		StreamJson turn = new StreamJson(listener);
		Turn state = new Turn();
		cancel.onCancel(() -> interrupt(p, state));
		try {
			writeLine(p, userMessage(userText));
			String line;
			while (!turn.done() && (line = in.readLine()) != null) {
				turn.accept(line);
				JsonObject request = turn.takeControlRequest();
				if (request != null) {
					answerUnsupported(p, request);
				}
				if (!mcpWarned && turn.mcpStatus() != null && !"connected".equals(turn.mcpStatus())) {
					mcpWarned = true;
					listener.onNotice("cc_mcp:" + turn.mcpStatus());
				}
			}
		} catch (IOException e) {
			// stream closed: the process died or was stopped; handled below
		} finally {
			state.finished = true;
			server.setTurn(null, null);
		}
		if (!turn.done()) {
			String err = p.stderrTail().trim();
			synchronized (this) {
				if (process == p) {
					stopProcess();
				}
				contextLost = true;
			}
			if (cancel.isCancelled()) {
				throw new CancelledException();
			}
			throw new LlmException(0, "Claude Code ended unexpectedly" + (err.isEmpty() ? "." : ": " + tail(err)));
		}
		if (cancel.isCancelled()) {
			listener.onTurnEnd(turn.toResult(processModel));
			throw new CancelledException();
		}
		String notice = turn.errorNotice();
		if (notice != null) {
			listener.onNotice(notice);
		}
		listener.onTurnEnd(turn.toResult(processModel));
	}

	private static final class Turn {
		volatile boolean finished;
	}

	/** Asks the CLI to stop the turn; kills it if it does not end the turn in time. */
	private void interrupt(ProcessLauncher.CliProcess p, Turn state) {
		if (state.finished) {
			return;
		}
		JsonObject req = new JsonObject();
		req.addProperty("type", "control_request");
		req.addProperty("request_id", "bella-" + requestIds.incrementAndGet());
		JsonObject body = new JsonObject();
		body.addProperty("subtype", "interrupt");
		req.add("request", body);
		try {
			writeLine(p, req);
		} catch (IOException e) {
			p.destroy();
			return;
		}
		Thread killer = new Thread(() -> {
			long deadline = System.currentTimeMillis() + INTERRUPT_GRACE_MS;
			while (!state.finished && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (!state.finished) {
				p.destroy();
			}
		}, "bella-claude-interrupt");
		killer.setDaemon(true);
		killer.start();
	}

	private void answerUnsupported(ProcessLauncher.CliProcess p, JsonObject request) throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("subtype", "error");
		response.addProperty("request_id", Json.str(request, "request_id"));
		response.addProperty("error", "Not supported by Bella");
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "control_response");
		msg.add("response", response);
		writeLine(p, msg);
	}

	private static JsonObject userMessage(String text) {
		JsonArray content = new JsonArray();
		content.add(Json.textBlock(text));
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", content);
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "user");
		msg.add("message", message);
		return msg;
	}

	private static void writeLine(ProcessLauncher.CliProcess p, JsonObject msg) throws IOException {
		OutputStream out = p.stdin();
		synchronized (out) {
			out.write((Json.GSON.toJson(msg) + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
	}

	private void ensureProcess() throws LlmException {
		ChatSession.Settings s = settings.get();
		if (process != null && (!process.isAlive() || !Objects.equals(processModel, s.model())
				|| !Objects.equals(processEffort, s.effort()))) {
			stopProcess();
			contextLost = true;
		}
		if (process != null) {
			return;
		}
		try {
			if (server == null) {
				server = new McpServer(executor, version);
			}
			server.start();
			Files.createDirectories(cli.workDir());
			systemFile = Files.createTempFile(cli.workDir(), "system-", ".md");
			Files.writeString(systemFile, system == null ? "" : system, StandardCharsets.UTF_8);
			// createTempFile is owner-only on POSIX; the file holds the MCP bearer token.
			mcpConfigFile = Files.createTempFile(cli.workDir(), "mcp-", ".json");
			Files.writeString(mcpConfigFile,
					Json.GSON.toJson(server.claudeCodeConfig(ClaudeCli.MCP_SERVER_NAME)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			deleteFiles();
			throw new LlmException("Could not prepare the Claude Code session: " + e.getMessage(), e);
		}
		try {
			process = cli.start(ClaudeCli.sessionArgs(s.model(), s.effort(), systemFile, mcpConfigFile));
		} catch (LlmException e) {
			deleteFiles();
			throw e;
		}
		processModel = s.model();
		processEffort = s.effort();
		stdout = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8));
	}

	private void stopProcess() {
		if (process != null) {
			try {
				process.stdin().close();
			} catch (IOException e) {
				// ignore
			}
			process.destroy();
			process = null;
			stdout = null;
		}
		deleteFiles();
	}

	private void deleteFiles() {
		for (Path f : new Path[] { systemFile, mcpConfigFile }) {
			if (f != null) {
				try {
					Files.deleteIfExists(f);
				} catch (IOException e) {
					// ignore
				}
			}
		}
		systemFile = null;
		mcpConfigFile = null;
	}

	private static String tail(String s) {
		return s.length() > 600 ? "…" + s.substring(s.length() - 600) : s;
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

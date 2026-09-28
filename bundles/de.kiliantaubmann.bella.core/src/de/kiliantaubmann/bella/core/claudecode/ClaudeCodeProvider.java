package de.kiliantaubmann.bella.core.claudecode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
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
 * Single requests without tools through the Claude Code CLI: explain,
 * generate, rewrite, implement method and completion. Each request starts the
 * CLI once, sends the prompt on stdin (no length limit on Windows) and reads
 * the streamed answer. Chats with tools use {@link ClaudeCodeSession}.
 */
public final class ClaudeCodeProvider implements LlmProvider {

	public static final String ID = "claude-code";

	private final ClaudeCli cli;

	public ClaudeCodeProvider(ClaudeCli cli) {
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
			throw new LlmException(0, "Requests with tools go through the Claude Code chat session.");
		}
		cancel.throwIfCancelled();
		Path systemFile = null;
		ProcessLauncher.CliProcess p = null;
		try {
			try {
				Files.createDirectories(cli.workDir());
				systemFile = Files.createTempFile(cli.workDir(), "system-", ".md");
				Files.writeString(systemFile, request.system() == null ? "" : request.system(),
						StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new LlmException("Could not prepare the Claude Code request: " + e.getMessage(), e);
			}
			p = cli.start(ClaudeCli.oneShotArgs(request.model(), effort(request), systemFile));
			ProcessLauncher.CliProcess proc = p;
			cancel.onCancel(proc::destroy);
			StreamJson turn = new StreamJson(adapt(listener));
			try {
				OutputStream out = p.stdin();
				out.write((Json.GSON.toJson(userMessage(request)) + "\n").getBytes(StandardCharsets.UTF_8));
				out.close();
				BufferedReader in = new BufferedReader(new InputStreamReader(p.stdout(), StandardCharsets.UTF_8));
				String line;
				while (!turn.done() && (line = in.readLine()) != null) {
					turn.accept(line);
				}
			} catch (IOException e) {
				// handled below
			}
			cancel.throwIfCancelled();
			if (!turn.done()) {
				String err = p.stderrTail().trim();
				throw new LlmException(0, "Claude Code ended unexpectedly" + (err.isEmpty() ? "." : ": " + err));
			}
			String notice = turn.errorNotice();
			if (notice != null) {
				throw new LlmException(0, describe(notice));
			}
			return turn.toResult(request.model());
		} finally {
			if (p != null) {
				p.destroy();
			}
			if (systemFile != null) {
				try {
					Files.deleteIfExists(systemFile);
				} catch (IOException e) {
					// ignore
				}
			}
		}
	}

	/** Completions use the model default; small models may not accept an effort level. */
	private static String effort(ChatRequest request) {
		return request.purpose() == ChatRequest.Purpose.COMPLETION ? null : request.effort();
	}

	private static String describe(String notice) {
		if (notice.equals("cc_login")) {
			return "Claude Code is not logged in. Run 'claude auth login' in a terminal (or set a token from "
					+ "'claude setup-token' in Bella's preferences).";
		}
		int colon = notice.indexOf(':');
		return colon < 0 ? notice : notice.substring(colon + 1);
	}

	/**
	 * Flattens the request's messages into one user message. Single-turn
	 * requests (the normal case) pass through unchanged; earlier turns are
	 * quoted so nothing is lost.
	 */
	static JsonObject userMessage(ChatRequest request) {
		JsonArray content = new JsonArray();
		int n = request.messages().size();
		if (n > 1) {
			StringBuilder earlier = new StringBuilder("<earlier_messages>\n");
			for (int i = 0; i < n - 1; i++) {
				JsonObject m = request.messages().get(i);
				earlier.append('[').append(Json.str(m, "role")).append("]\n").append(plainText(m)).append("\n\n");
			}
			earlier.append("</earlier_messages>");
			content.add(Json.textBlock(earlier.toString()));
		}
		if (n > 0) {
			JsonObject last = request.messages().get(n - 1);
			JsonElement c = last.get("content");
			if (c != null && c.isJsonArray()) {
				for (JsonElement b : c.getAsJsonArray()) {
					if (b.isJsonObject() && "text".equals(Json.str(b.getAsJsonObject(), "type"))) {
						content.add(Json.textBlock(Json.str(b.getAsJsonObject(), "text")));
					}
				}
			} else if (c != null && c.isJsonPrimitive()) {
				content.add(Json.textBlock(c.getAsString()));
			}
		}
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", content);
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "user");
		msg.add("message", message);
		return msg;
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

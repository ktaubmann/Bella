package de.kiliantaubmann.bella.core.copilot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

/**
 * Stands in for {@code copilot --acp}: answers initialize and session/new like
 * the real CLI (checked against v1.0.89) and hands prompts to the test.
 */
class FakeAcp implements ProcessLauncher {

	/** What the simulated agent does with a prompt. */
	interface PromptScript {
		void onPrompt(Proc p, JsonElement id, String sessionId, String text);
	}

	volatile boolean loggedIn = true;
	volatile boolean ignoreCancel;
	volatile PromptScript script = (p, id, sid, text) -> {
		p.update(sid, "agent_message_chunk", "Antwort auf: ");
		p.update(sid, "agent_message_chunk", lastLine(text));
		p.reply(id, stopReason("end_turn"));
	};
	final List<Proc> started = new ArrayList<>();

	static String lastLine(String text) {
		int nl = text.lastIndexOf('\n');
		return nl < 0 ? text : text.substring(nl + 1);
	}

	static JsonObject stopReason(String reason) {
		JsonObject r = new JsonObject();
		r.addProperty("stopReason", reason);
		return r;
	}

	@Override
	public synchronized CliProcess start(List<String> command, Map<String, String> env, Path workDir) {
		Proc p = new Proc(command, env, workDir);
		started.add(p);
		return p;
	}

	/** One simulated CLI process. */
	final class Proc implements ProcessLauncher.CliProcess {
		final List<String> command;
		final Map<String, String> env;
		final Path workDir;
		/** Everything Bella sent (requests, notifications, responses). */
		final List<JsonObject> received = new CopyOnWriteArrayList<>();
		final List<String> prompts = new CopyOnWriteArrayList<>();
		final List<String> firstBlocks = new CopyOnWriteArrayList<>();
		volatile JsonObject newSessionParams;
		volatile JsonElement pendingPrompt;
		private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
		private volatile boolean alive = true;
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
		private int sessions;

		Proc(List<String> command, Map<String, String> env, Path workDir) {
			this.command = command;
			this.env = env;
			this.workDir = workDir;
			if (!command.contains("--acp")) {
				// e.g. "copilot version": print and exit
				out.add("GitHub Copilot CLI 1.0.89\n".getBytes(StandardCharsets.UTF_8));
				alive = false;
			}
		}

		void send(JsonObject msg) {
			msg.addProperty("jsonrpc", "2.0");
			out.add((Json.GSON.toJson(msg) + "\n").getBytes(StandardCharsets.UTF_8));
		}

		void reply(JsonElement id, JsonObject result) {
			JsonObject m = new JsonObject();
			m.add("id", id);
			m.add("result", result);
			send(m);
		}

		void error(JsonElement id, int code, String message) {
			JsonObject e = new JsonObject();
			e.addProperty("code", code);
			e.addProperty("message", message);
			JsonObject m = new JsonObject();
			m.add("id", id);
			m.add("error", e);
			send(m);
		}

		void update(String sessionId, String kind, String text) {
			JsonObject content = new JsonObject();
			content.addProperty("type", "text");
			content.addProperty("text", text);
			JsonObject u = new JsonObject();
			u.addProperty("sessionUpdate", kind);
			u.add("content", content);
			JsonObject params = new JsonObject();
			params.addProperty("sessionId", sessionId);
			params.add("update", u);
			JsonObject m = new JsonObject();
			m.addProperty("method", "session/update");
			m.add("params", params);
			send(m);
		}

		/** The agent asks Bella for permission; the answer shows up in {@link #received}. */
		void requestPermission(int id, String sessionId, String title, String kind) {
			JsonObject toolCall = new JsonObject();
			toolCall.addProperty("toolCallId", "tc" + id);
			toolCall.addProperty("title", title);
			if (kind != null) {
				toolCall.addProperty("kind", kind);
			}
			JsonArray options = new JsonArray();
			for (String[] o : new String[][] { { "allow", "allow_once" }, { "always", "allow_always" },
					{ "reject", "reject_once" } }) {
				JsonObject opt = new JsonObject();
				opt.addProperty("optionId", o[0]);
				opt.addProperty("name", o[0]);
				opt.addProperty("kind", o[1]);
				options.add(opt);
			}
			JsonObject params = new JsonObject();
			params.addProperty("sessionId", sessionId);
			params.add("toolCall", toolCall);
			params.add("options", options);
			JsonObject m = new JsonObject();
			m.addProperty("id", id);
			m.addProperty("method", "session/request_permission");
			m.add("params", params);
			send(m);
		}

		String arg(String flag) {
			int i = command.indexOf(flag);
			return i < 0 || i + 1 >= command.size() ? null : command.get(i + 1);
		}

		JsonObject responseTo(int id) {
			for (JsonObject m : received) {
				JsonElement mid = m.get("id");
				if (m.get("method") == null && mid != null && mid.getAsInt() == id) {
					return m;
				}
			}
			return null;
		}

		private void onMessage(JsonObject msg) {
			received.add(msg);
			String method = Json.str(msg, "method");
			JsonElement id = msg.get("id");
			JsonObject params = Json.obj(msg, "params");
			if (method == null) {
				return; // a response to one of our requests
			}
			switch (method) {
			case "initialize" -> {
				JsonObject http = new JsonObject();
				http.addProperty("http", true);
				JsonObject caps = new JsonObject();
				caps.add("mcpCapabilities", http);
				JsonObject r = new JsonObject();
				r.addProperty("protocolVersion", 1);
				r.add("agentCapabilities", caps);
				reply(id, r);
			}
			case "session/new" -> {
				if (!loggedIn) {
					error(id, -32000, "Authentication required");
					return;
				}
				newSessionParams = params;
				JsonObject r = new JsonObject();
				r.addProperty("sessionId", "s" + (++sessions));
				JsonArray available = new JsonArray();
				for (String m : List.of("claude-sonnet-4.5", "gpt-5")) {
					JsonObject o = new JsonObject();
					o.addProperty("modelId", m);
					available.add(o);
				}
				JsonObject models = new JsonObject();
				models.add("availableModels", available);
				r.add("models", models);
				reply(id, r);
			}
			case "session/prompt" -> {
				JsonArray blocks = params.getAsJsonArray("prompt");
				String text = blocks.get(0).getAsJsonObject().get("text").getAsString();
				firstBlocks.add(text);
				if (blocks.size() > 1) {
					// instructions come second; tests read the prompt in its logical order
					text = blocks.get(1).getAsJsonObject().get("text").getAsString() + "\n\n" + text;
				}
				prompts.add(text);
				pendingPrompt = id;
				script.onPrompt(this, id, Json.str(params, "sessionId"), text);
			}
			case "session/cancel" -> {
				if (!ignoreCancel && pendingPrompt != null) {
					reply(pendingPrompt, stopReason("cancelled"));
				}
			}
			default -> {
				if (id != null) {
					error(id, -32601, "Method not found");
				}
			}
			}
		}

		private final OutputStream stdin = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				if (!alive) {
					throw new IOException("process ended");
				}
				if (b == '\n') {
					String line = pending.toString(StandardCharsets.UTF_8);
					pending.reset();
					onMessage(Json.parseObject(line));
				} else {
					pending.write(b);
				}
			}
		};

		private final InputStream stdout = new InputStream() {
			private byte[] current = new byte[0];
			private int pos;

			@Override
			public int read() throws IOException {
				while (pos >= current.length) {
					if (!alive && out.isEmpty()) {
						return -1;
					}
					try {
						byte[] next = out.poll(20, TimeUnit.MILLISECONDS);
						if (next != null) {
							current = next;
							pos = 0;
						}
					} catch (InterruptedException e) {
						throw new IOException(e);
					}
				}
				return current[pos++] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0) {
					return 0;
				}
				int first = read();
				if (first < 0) {
					return -1;
				}
				b[off] = (byte) first;
				int n = 1;
				while (n < len && pos < current.length) {
					b[off + n++] = current[pos++];
				}
				return n;
			}
		};

		@Override
		public OutputStream stdin() {
			return stdin;
		}

		@Override
		public InputStream stdout() {
			return stdout;
		}

		@Override
		public String stderrTail() {
			return "fake stderr";
		}

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public int waitFor(long millis) {
			return alive ? -1 : 0;
		}

		@Override
		public void destroy() {
			alive = false;
		}
	}
}

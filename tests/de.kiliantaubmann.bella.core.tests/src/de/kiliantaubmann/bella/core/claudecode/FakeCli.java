package de.kiliantaubmann.bella.core.claudecode;

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
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

/**
 * Stands in for the {@code claude} process: records how it was started and
 * answers every stdin line through a script.
 */
final class FakeCli implements ProcessLauncher {

	/** One started process. */
	static final class Proc implements ProcessLauncher.CliProcess {
		final List<String> command;
		final Map<String, String> env;
		final Path workDir;
		final List<JsonObject> received = new CopyOnWriteArrayList<>();
		private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
		private volatile boolean alive = true;
		private final BiConsumer<Proc, JsonObject> script;
		private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

		Proc(List<String> command, Map<String, String> env, Path workDir, BiConsumer<Proc, JsonObject> script) {
			this.command = command;
			this.env = env;
			this.workDir = workDir;
			this.script = script;
		}

		/** Writes one line to the process's stdout. */
		void emit(String json) {
			out.add((json + "\n").getBytes(StandardCharsets.UTF_8));
		}

		String arg(String flag) {
			int i = command.indexOf(flag);
			return i < 0 || i + 1 >= command.size() ? null : command.get(i + 1);
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
					JsonObject msg = Json.parseObject(line);
					received.add(msg);
					script.accept(Proc.this, msg);
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
						byte[] next = out.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS);
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

	final List<Proc> started = new ArrayList<>();
	private final BiConsumer<Proc, JsonObject> script;

	FakeCli(BiConsumer<Proc, JsonObject> script) {
		this.script = script;
	}

	@Override
	public synchronized CliProcess start(List<String> command, Map<String, String> env, Path workDir) {
		Proc p = new Proc(command, env, workDir, script);
		started.add(p);
		return p;
	}

	static String init() {
		return "{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"claude-opus-5\",\"mcp_servers\":[{\"name\":\"bella\",\"status\":\"connected\"}]}";
	}

	static String delta(String text) {
		JsonObject d = new JsonObject();
		d.addProperty("type", "text_delta");
		d.addProperty("text", text);
		JsonObject ev = new JsonObject();
		ev.addProperty("type", "content_block_delta");
		ev.addProperty("index", 0);
		ev.add("delta", d);
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "stream_event");
		msg.add("event", ev);
		return Json.GSON.toJson(msg);
	}

	static String assistantText(String text) {
		return "{\"type\":\"assistant\",\"message\":{\"model\":\"claude-opus-5\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":"
				+ Json.GSON.toJson(text) + "}]},\"parent_tool_use_id\":null}";
	}

	static String success(String text) {
		return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":" + Json.GSON.toJson(text)
				+ ",\"usage\":{\"input_tokens\":12,\"output_tokens\":5,\"cache_read_input_tokens\":3}}";
	}
}

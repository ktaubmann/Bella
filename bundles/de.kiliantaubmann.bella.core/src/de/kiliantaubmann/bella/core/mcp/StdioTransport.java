package de.kiliantaubmann.bella.core.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Executables;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * MCP over stdio: starts the server as a child process (e.g.
 * {@code npx -y arc-1@latest}) and exchanges newline-delimited JSON-RPC.
 */
public final class StdioTransport implements McpTransport {

	private final Process process;
	private final BufferedWriter writer;
	private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final StringBuilder stderrTail = new StringBuilder();
	private final long timeoutSeconds;

	public StdioTransport(List<String> command, Map<String, String> env, long timeoutSeconds) throws IOException {
		this.timeoutSeconds = timeoutSeconds;
		ProcessBuilder pb;
		try {
			pb = new ProcessBuilder(resolve(command, System.getenv(), System.getProperty("os.name", ""),
					Files::isRegularFile));
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
		pb.environment().putAll(env);
		try {
			this.process = pb.start();
		} catch (IOException e) {
			Log.warn("mcp", "cannot start MCP server " + String.join(" ", command) + ": " + e.getMessage());
			throw e;
		}
		Log.info("mcp", "started MCP server (pid " + process.pid() + "): " + String.join(" ", command)
				+ (env.isEmpty() ? "" : " | environment: set " + new java.util.TreeSet<>(env.keySet())));
		process.onExit().thenAccept(p -> {
			String line = "MCP server (pid " + p.pid() + ") ended with exit code " + p.exitValue();
			String err = stderrTail().strip();
			Log.info("mcp", err.isEmpty() ? line : line + "\nstderr:\n" + Log.clip(err, 4_000));
		});
		this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread out = new Thread(this::readStdout, "bella-mcp-stdout");
		out.setDaemon(true);
		out.start();
		Thread err = new Thread(this::readStderr, "bella-mcp-stderr");
		err.setDaemon(true);
		err.start();
	}

	/**
	 * On Windows a bare program name such as {@code npx} is looked up on the
	 * PATH with {@code .exe}, {@code .cmd} and {@code .bat}: Java only adds
	 * {@code .exe} itself, and npm installs {@code .cmd} shims, which have to
	 * run through {@code cmd.exe}. Elsewhere the command is used as it is.
	 */
	static List<String> resolve(List<String> command, Map<String, String> env, String osName,
			Predicate<Path> exists) {
		if (command.isEmpty() || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
			return command;
		}
		String program = command.get(0);
		List<String> args = command.subList(1, command.size());
		Path exe;
		if (program.contains("\\") || program.contains("/") || program.contains(".")) {
			exe = Path.of(program);
		} else {
			exe = Executables.find(null, env, osName, exists,
					List.of(program + ".exe", program + ".cmd", program + ".bat"), List.of()).orElse(null);
			if (exe == null) {
				return command;
			}
		}
		return Executables.command(exe, args);
	}

	/** Splits a command line honouring double quotes. */
	public static List<String> splitCommand(String commandLine) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (char c : commandLine.trim().toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			} else if (Character.isWhitespace(c) && !quoted) {
				if (!cur.isEmpty()) {
					parts.add(cur.toString());
					cur.setLength(0);
				}
			} else {
				cur.append(c);
			}
		}
		if (!cur.isEmpty()) {
			parts.add(cur.toString());
		}
		return parts;
	}

	private void readStdout() {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String l = line;
				Log.debug("mcp", () -> "stdio -> " + Log.clip(l));
				try {
					JsonObject msg = Json.parseObject(line);
					if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
						CompletableFuture<JsonObject> f = pending.remove(msg.get("id").toString());
						if (f != null) {
							f.complete(msg);
						}
					}
				} catch (RuntimeException e) {
					// servers may log non-JSON to stdout; ignore
				}
			}
		} catch (IOException e) {
			// process ended
		}
		IOException ended = new IOException("MCP server process ended. " + stderrTail());
		pending.values().forEach(f -> f.completeExceptionally(ended));
		pending.clear();
	}

	private void readStderr() {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String l = line;
				Log.debug("mcp", () -> "stdio stderr: " + Log.clip(l));
				synchronized (stderrTail) {
					stderrTail.append(line).append('\n');
					if (stderrTail.length() > 4000) {
						stderrTail.delete(0, stderrTail.length() - 4000);
					}
				}
			}
		} catch (IOException e) {
			// process ended
		}
	}

	private String stderrTail() {
		synchronized (stderrTail) {
			String s = stderrTail.toString().trim();
			return s.length() > 500 ? s.substring(s.length() - 500) : s;
		}
	}

	@Override
	public JsonObject request(JsonObject request, CancelToken cancel) throws IOException {
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(request.get("id").toString(), future);
		send(request);
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
		while (true) {
			if (cancel.isCancelled()) {
				pending.remove(request.get("id").toString());
				throw new IOException("cancelled");
			}
			try {
				return future.get(200, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				if (System.currentTimeMillis() > deadline) {
					pending.remove(request.get("id").toString());
					throw new IOException("MCP server did not answer within " + timeoutSeconds + " s");
				}
			} catch (ExecutionException e) {
				throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted", e);
			}
		}
	}

	@Override
	public void notify(JsonObject notification) throws IOException {
		send(notification);
	}

	private synchronized void send(JsonObject message) throws IOException {
		if (!process.isAlive()) {
			throw new IOException("MCP server process is not running. " + stderrTail());
		}
		String json = Json.GSON.toJson(message);
		Log.debug("mcp", () -> "stdio <- " + Log.clip(json));
		writer.write(json);
		writer.write('\n');
		writer.flush();
	}

	@Override
	public void close() {
		try {
			writer.close();
		} catch (IOException e) {
			// ignore
		}
		// npx and cmd.exe start the server as a child; it holds the SAP connection and must end too.
		List<ProcessHandle> children = process.descendants().toList();
		process.destroy();
		children.forEach(ProcessHandle::destroy);
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
			children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}
}

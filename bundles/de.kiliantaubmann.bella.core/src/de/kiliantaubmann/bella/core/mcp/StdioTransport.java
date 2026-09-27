package de.kiliantaubmann.bella.core.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

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
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.environment().putAll(env);
		this.process = pb.start();
		this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread out = new Thread(this::readStdout, "bella-mcp-stdout");
		out.setDaemon(true);
		out.start();
		Thread err = new Thread(this::readStderr, "bella-mcp-stderr");
		err.setDaemon(true);
		err.start();
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
		writer.write(Json.GSON.toJson(message));
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
		process.destroy();
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}
}

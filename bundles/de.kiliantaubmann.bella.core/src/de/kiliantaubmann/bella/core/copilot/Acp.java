package de.kiliantaubmann.bella.core.copilot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

/**
 * Minimal client for the Agent Client Protocol (JSON-RPC 2.0, one message per
 * line on stdin/stdout) as spoken by {@code copilot --acp}. A reader thread
 * completes pending requests, forwards notifications and answers requests the
 * agent sends to the client (e.g. permission requests).
 */
final class Acp implements AutoCloseable {

	/** A JSON-RPC error returned by the agent. */
	static final class AcpException extends Exception {
		private static final long serialVersionUID = 1L;
		private final int code;

		AcpException(int code, String message) {
			super(message);
			this.code = code;
		}

		int code() {
			return code;
		}

		boolean isAuthRequired() {
			String m = String.valueOf(getMessage()).toLowerCase(java.util.Locale.ROOT);
			return m.contains("authenticat") || m.contains("not logged in") || m.contains("login");
		}
	}

	interface NotificationHandler {
		void onNotification(String method, JsonObject params);
	}

	/** Answers a request from the agent; throw to reply with an error. */
	interface RequestHandler {
		JsonElement onRequest(String method, JsonObject params) throws AcpException;
	}

	private final ProcessLauncher.CliProcess process;
	private final NotificationHandler notifications;
	private final RequestHandler requests;
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private volatile boolean closed;

	Acp(ProcessLauncher.CliProcess process, NotificationHandler notifications, RequestHandler requests) {
		this.process = process;
		this.notifications = notifications;
		this.requests = requests;
		Thread reader = new Thread(this::readLoop, "bella-acp-reader");
		reader.setDaemon(true);
		reader.start();
	}

	ProcessLauncher.CliProcess process() {
		return process;
	}

	boolean isAlive() {
		return !closed && process.isAlive();
	}

	/**
	 * Sends a request and waits for its result.
	 *
	 * @param timeoutMillis {@code 0} waits until the answer arrives or the process ends
	 */
	JsonObject request(String method, JsonObject params, long timeoutMillis) throws AcpException, IOException {
		long id = ids.incrementAndGet();
		CompletableFuture<JsonObject> f = new CompletableFuture<>();
		pending.put(id, f);
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("id", id);
		msg.addProperty("method", method);
		msg.add("params", params == null ? new JsonObject() : params);
		try {
			write(msg);
			JsonObject response = timeoutMillis > 0 ? f.get(timeoutMillis, TimeUnit.MILLISECONDS) : f.get();
			JsonObject error = Json.obj(response, "error");
			if (error != null) {
				throw new AcpException(Json.integer(error, "code", 0), String.valueOf(Json.str(error, "message")));
			}
			JsonElement result = response.get("result");
			return result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
		} catch (TimeoutException e) {
			throw new IOException("No answer from the Copilot CLI to " + method, e);
		} finally {
			pending.remove(id);
		}
	}

	void notify(String method, JsonObject params) throws IOException {
		JsonObject msg = new JsonObject();
		msg.addProperty("jsonrpc", "2.0");
		msg.addProperty("method", method);
		msg.add("params", params == null ? new JsonObject() : params);
		write(msg);
	}

	private void write(JsonObject msg) throws IOException {
		OutputStream out = process.stdin();
		synchronized (out) {
			out.write((Json.GSON.toJson(msg) + "\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
		}
	}

	private void readLoop() {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(process.stdout(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				handle(line);
			}
		} catch (IOException e) {
			// process ended
		} finally {
			closed = true;
			IOException gone = new IOException("The Copilot CLI ended" + stderr());
			pending.values().forEach(f -> f.completeExceptionally(gone));
		}
	}

	private String stderr() {
		String err = process.stderrTail().trim();
		if (err.isEmpty()) {
			return ".";
		}
		return ": " + (err.length() > 600 ? "…" + err.substring(err.length() - 600) : err);
	}

	private void handle(String line) {
		JsonObject msg;
		try {
			JsonElement e = Json.parseStrict(line.trim());
			if (!e.isJsonObject()) {
				return;
			}
			msg = e.getAsJsonObject();
		} catch (JsonParseException e) {
			return; // log output or other noise
		}
		String method = Json.str(msg, "method");
		JsonElement id = msg.get("id");
		if (method == null) {
			if (id != null && id.isJsonPrimitive()) {
				CompletableFuture<JsonObject> f = pending.get(id.getAsLong());
				if (f != null) {
					f.complete(msg);
				}
			}
			return;
		}
		JsonObject params = Json.obj(msg, "params");
		if (params == null) {
			params = new JsonObject();
		}
		if (id == null || id.isJsonNull()) {
			try {
				notifications.onNotification(method, params);
			} catch (RuntimeException ex) {
				// a failing listener must not stop the protocol
			}
			return;
		}
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		try {
			JsonElement result = requests.onRequest(method, params);
			response.add("result", result == null ? new JsonObject() : result);
		} catch (AcpException ex) {
			response.add("error", error(ex.code(), ex.getMessage()));
		} catch (RuntimeException ex) {
			response.add("error", error(-32603, String.valueOf(ex.getMessage())));
		}
		try {
			write(response);
		} catch (IOException ex) {
			// process gone
		}
	}

	private static JsonObject error(int code, String message) {
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		return e;
	}

	@Override
	public void close() {
		closed = true;
		try {
			process.stdin().close();
		} catch (IOException e) {
			// ignore
		}
		process.destroy();
	}
}

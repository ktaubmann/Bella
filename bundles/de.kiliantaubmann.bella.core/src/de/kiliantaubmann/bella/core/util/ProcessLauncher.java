package de.kiliantaubmann.bella.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Starts a CLI process (Claude Code, Copilot); replaced by a fake in tests. */
public interface ProcessLauncher {

	/** A running CLI process. */
	interface CliProcess {
		OutputStream stdin();

		InputStream stdout();

		/** The last few KB the process wrote to stderr, for error messages. */
		String stderrTail();

		boolean isAlive();

		/** @return the exit code, or {@code -1} if still running after the timeout */
		int waitFor(long millis) throws InterruptedException;

		void destroy();
	}

	/**
	 * @param env variables to set; a {@code null} value removes the variable
	 *            from the inherited environment
	 */
	CliProcess start(List<String> command, Map<String, String> env, Path workDir) throws IOException;

	/** File name of the program, also behind {@code cmd.exe /c}. */
	static String displayName(List<String> command) {
		if (command.isEmpty()) {
			return "?";
		}
		String exe = command.get(0);
		if (command.size() > 2 && exe.toLowerCase(java.util.Locale.ROOT).endsWith("cmd.exe")
				&& command.get(1).equalsIgnoreCase("/c")) {
			exe = command.get(2);
		}
		int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
		return exe.substring(slash + 1);
	}

	/** Names of the variables set and removed; values may be tokens and are never shown. */
	static String envSummary(Map<String, String> env) {
		List<String> set = new java.util.ArrayList<>();
		List<String> removed = new java.util.ArrayList<>();
		env.forEach((k, v) -> (v == null ? removed : set).add(k));
		java.util.Collections.sort(set);
		java.util.Collections.sort(removed);
		return (set.isEmpty() ? "" : "set " + set) + (set.isEmpty() || removed.isEmpty() ? "" : ", ")
				+ (removed.isEmpty() ? "" : "removed " + removed);
	}

	/** Launcher based on {@link ProcessBuilder}; logs start, exit and (with the detail level) every line. */
	ProcessLauncher SYSTEM = (command, env, workDir) -> {
		ProcessBuilder pb = new ProcessBuilder(command);
		if (workDir != null) {
			pb.directory(workDir.toFile());
		}
		Map<String, String> target = pb.environment();
		env.forEach((k, v) -> {
			if (v == null) {
				target.remove(k);
			} else {
				target.put(k, v);
			}
		});
		String name = displayName(command);
		Process p;
		try {
			p = pb.start();
		} catch (IOException e) {
			Log.warn("cli", "cannot start " + String.join(" ", command) + ": " + e.getMessage());
			throw e;
		}
		long started = System.nanoTime();
		Log.info("cli", "started " + name + " (pid " + p.pid() + "): " + String.join(" ", command)
				+ (env.isEmpty() ? "" : " | environment: " + envSummary(env))
				+ (workDir == null ? "" : " | directory: " + workDir));
		java.util.concurrent.atomic.AtomicBoolean stoppedByBella = new java.util.concurrent.atomic.AtomicBoolean();
		StringBuilder tail = new StringBuilder();
		Thread drain = new Thread(() -> {
			byte[] buf = new byte[4096];
			try (InputStream err = p.getErrorStream()) {
				int n;
				while ((n = err.read(buf)) > 0) {
					String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
					Log.debug("cli", () -> name + " stderr: " + Log.clip(chunk.stripTrailing()));
					synchronized (tail) {
						tail.append(chunk);
						if (tail.length() > 8192) {
							tail.delete(0, tail.length() - 8192);
						}
					}
				}
			} catch (IOException e) {
				// process ended
			}
		}, "bella-claude-stderr");
		drain.setDaemon(true);
		drain.start();
		ProtocolLog protocol = new ProtocolLog(name);
		p.onExit().thenAccept(ended -> {
			protocol.finish();
			String err;
			synchronized (tail) {
				err = tail.toString().strip();
			}
			int code = ended.exitValue();
			String line = name + " (pid " + ended.pid() + ") ended with exit code " + code + " after "
					+ Log.millisSince(started) + " ms" + (stoppedByBella.get() ? " (stopped by Bella)" : "")
					+ (err.isEmpty() ? "" : "\nstderr:\n" + Log.clip(err, 4_000));
			if (code == 0 || stoppedByBella.get()) {
				Log.info("cli", line);
			} else {
				Log.warn("cli", line);
			}
		});
		OutputStream stdin = LineTap.out(p.getOutputStream(), l -> protocol.line("<-", l));
		InputStream stdout = LineTap.in(p.getInputStream(), l -> protocol.line("->", l));
		return new CliProcess() {
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
				synchronized (tail) {
					return tail.toString();
				}
			}

			@Override
			public boolean isAlive() {
				return p.isAlive();
			}

			@Override
			public int waitFor(long millis) throws InterruptedException {
				return p.waitFor(millis, TimeUnit.MILLISECONDS) ? p.exitValue() : -1;
			}

			@Override
			public void destroy() {
				stoppedByBella.set(true);
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
			}
		};
	};
}

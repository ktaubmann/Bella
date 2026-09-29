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

	/** Launcher based on {@link ProcessBuilder}. */
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
		Process p = pb.start();
		StringBuilder tail = new StringBuilder();
		Thread drain = new Thread(() -> {
			byte[] buf = new byte[4096];
			try (InputStream err = p.getErrorStream()) {
				int n;
				while ((n = err.read(buf)) > 0) {
					synchronized (tail) {
						tail.append(new String(buf, 0, n, StandardCharsets.UTF_8));
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
		return new CliProcess() {
			@Override
			public OutputStream stdin() {
				return p.getOutputStream();
			}

			@Override
			public InputStream stdout() {
				return p.getInputStream();
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
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
			}
		};
	};
}

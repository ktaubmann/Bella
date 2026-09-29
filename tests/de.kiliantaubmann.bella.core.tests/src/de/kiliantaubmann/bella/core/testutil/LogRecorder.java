package de.kiliantaubmann.bella.core.testutil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.kiliantaubmann.bella.core.util.Log;

/** Collects log entries; {@link #close()} switches the log off again. */
public final class LogRecorder implements Log.Sink, AutoCloseable {

	public final List<String> lines = new CopyOnWriteArrayList<>();

	public static LogRecorder start(Log.Level level) {
		LogRecorder r = new LogRecorder();
		Log.configure(r, level);
		return r;
	}

	@Override
	public void write(Log.Level level, String area, String message) {
		lines.add(level + " [" + area + "] " + message);
	}

	public String all() {
		return String.join("\n", lines);
	}

	@Override
	public void close() {
		Log.configure(null, Log.Level.INFO);
	}
}

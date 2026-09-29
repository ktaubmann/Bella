package de.kiliantaubmann.bella.ui.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import de.kiliantaubmann.bella.core.util.Log;

/**
 * Bella's log file ({@code bella.log} in the plugin's state location). One
 * line per entry, further lines of an entry indented; at {@link #MAX_BYTES} the
 * file moves to {@code bella.log.1} and a new one starts.
 */
public final class LogFile implements Log.Sink {

	static final long MAX_BYTES = 5L * 1024 * 1024;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private final Path file;
	private final long maxBytes;
	private OutputStream out;
	private long size;

	public LogFile(Path file) {
		this(file, MAX_BYTES);
	}

	LogFile(Path file, long maxBytes) {
		this.file = file;
		this.maxBytes = maxBytes;
	}

	public Path path() {
		return file;
	}

	/** The previous file after a rotation. */
	public Path previous() {
		return file.resolveSibling(file.getFileName() + ".1");
	}

	@Override
	public synchronized void write(Log.Level level, String area, String message) {
		String text = message == null ? "" : message.replace("\r\n", "\n").replace("\n", "\n    ");
		String line = LocalDateTime.now().format(TIME) + " " + String.format("%-5s", level) + " [" + area + "] " + text
				+ System.lineSeparator();
		byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
		try {
			if (out == null) {
				open();
			}
			if (size > 0 && size + bytes.length > maxBytes) {
				rotate();
			}
			out.write(bytes);
			out.flush();
			size += bytes.length;
		} catch (IOException e) {
			// the log must never disturb the developer's work; try again with the next entry
			closeQuietly();
		}
	}

	private void open() throws IOException {
		Files.createDirectories(file.getParent());
		out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		size = Files.size(file);
	}

	private void rotate() throws IOException {
		closeQuietly();
		Files.move(file, previous(), StandardCopyOption.REPLACE_EXISTING);
		open();
	}

	/** Empties the log, including the previous file. */
	public synchronized void clear() {
		closeQuietly();
		try {
			Files.deleteIfExists(previous());
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// a file that is open elsewhere (Windows) stays; the next write appends
		}
	}

	public synchronized void close() {
		closeQuietly();
	}

	private void closeQuietly() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
				// nothing to do
			}
			out = null;
		}
	}
}

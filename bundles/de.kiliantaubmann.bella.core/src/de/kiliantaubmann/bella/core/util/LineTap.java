package de.kiliantaubmann.bella.core.util;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Passes a stream through unchanged and hands each complete line to a
 * consumer, e.g. to log the protocol lines of a CLI. Very long lines are cut.
 */
final class LineTap {

	/** Bytes kept of one line; the rest of the line is dropped from the copy. */
	static final int MAX_LINE = 64 * 1024;

	private final ByteArrayOutputStream line = new ByteArrayOutputStream();
	private final Consumer<String> consumer;
	private boolean cut;

	private LineTap(Consumer<String> consumer) {
		this.consumer = consumer;
	}

	private synchronized void accept(byte[] b, int off, int len) {
		for (int i = off; i < off + len; i++) {
			if (b[i] == '\n') {
				emit();
			} else if (line.size() < MAX_LINE) {
				line.write(b[i]);
			} else {
				cut = true;
			}
		}
	}

	private void emit() {
		String s = line.toString(StandardCharsets.UTF_8);
		if (s.endsWith("\r")) {
			s = s.substring(0, s.length() - 1);
		}
		line.reset();
		boolean wasCut = cut;
		cut = false;
		try {
			consumer.accept(wasCut ? s + " … [line cut]" : s);
		} catch (RuntimeException e) {
			// a failing consumer must not break the stream
		}
	}

	static InputStream in(InputStream in, Consumer<String> consumer) {
		LineTap tap = new LineTap(consumer);
		return new FilterInputStream(in) {
			@Override
			public int read() throws IOException {
				int c = super.read();
				if (c >= 0) {
					tap.accept(new byte[] { (byte) c }, 0, 1);
				}
				return c;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				int n = super.read(b, off, len);
				if (n > 0) {
					tap.accept(b, off, n);
				}
				return n;
			}
		};
	}

	static OutputStream out(OutputStream out, Consumer<String> consumer) {
		LineTap tap = new LineTap(consumer);
		return new FilterOutputStream(out) {
			@Override
			public void write(int b) throws IOException {
				out.write(b);
				tap.accept(new byte[] { (byte) b }, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
				tap.accept(b, off, len);
			}
		};
	}
}

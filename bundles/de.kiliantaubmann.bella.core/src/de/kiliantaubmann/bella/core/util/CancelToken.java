package de.kiliantaubmann.bella.core.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation for long-running requests. Closeables registered
 * here (typically an open HTTP response stream) are closed on cancel, which
 * unblocks a reader thread waiting on the network.
 */
public final class CancelToken {

	public static final CancelToken NONE = new CancelToken();

	private volatile boolean cancelled;
	private final CopyOnWriteArrayList<Closeable> closeables = new CopyOnWriteArrayList<>();

	public boolean isCancelled() {
		return cancelled;
	}

	public void cancel() {
		if (this == NONE) {
			return;
		}
		cancelled = true;
		for (Closeable c : closeables) {
			try {
				c.close();
			} catch (IOException e) {
				// best effort
			}
		}
		closeables.clear();
	}

	public void onCancel(Closeable closeable) {
		if (this == NONE) {
			return;
		}
		if (cancelled) {
			try {
				closeable.close();
			} catch (IOException e) {
				// best effort
			}
			return;
		}
		closeables.add(closeable);
	}

	public void throwIfCancelled() throws CancelledException {
		if (cancelled) {
			throw new CancelledException();
		}
	}

	public static final class CancelledException extends Exception {
		private static final long serialVersionUID = 1L;

		public CancelledException() {
			super("cancelled");
		}
	}
}

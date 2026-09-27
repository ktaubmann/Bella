package de.kiliantaubmann.bella.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Server-Sent Events parser (WHATWG event-stream format): {@code event:} and
 * {@code data:} fields, multi-line data joined with newlines, comment lines
 * starting with a colon ignored, an empty line dispatching the event.
 */
public final class SseParser {

	public interface Handler {
		/** @return {@code false} to stop reading */
		boolean onEvent(String event, String data) throws IOException;
	}

	private SseParser() {
	}

	public static void parse(InputStream in, Handler handler, CancelToken cancel) throws IOException {
		parse(new InputStreamReader(in, StandardCharsets.UTF_8), handler, cancel);
	}

	public static void parse(Reader in, Handler handler, CancelToken cancel) throws IOException {
		BufferedReader reader = in instanceof BufferedReader b ? b : new BufferedReader(in);
		String event = null;
		StringBuilder data = null;
		String line;
		while ((line = reader.readLine()) != null) {
			if (cancel.isCancelled()) {
				return;
			}
			if (line.isEmpty()) {
				if (data != null) {
					if (!handler.onEvent(event == null ? "message" : event, data.toString())) {
						return;
					}
				}
				event = null;
				data = null;
				continue;
			}
			if (line.startsWith(":")) {
				continue;
			}
			int colon = line.indexOf(':');
			String field = colon < 0 ? line : line.substring(0, colon);
			String value = colon < 0 ? "" : line.substring(colon + 1);
			if (value.startsWith(" ")) {
				value = value.substring(1);
			}
			switch (field) {
			case "event" -> event = value;
			case "data" -> {
				if (data == null) {
					data = new StringBuilder(value);
				} else {
					data.append('\n').append(value);
				}
			}
			default -> {
				// id, retry: not needed
			}
			}
		}
		if (data != null && !cancel.isCancelled()) {
			handler.onEvent(event == null ? "message" : event, data.toString());
		}
	}
}

package de.kiliantaubmann.bella.core.util;

import java.util.regex.Pattern;

/**
 * Writes the protocol lines of a CLI process to the detail log. Claude Code
 * streams every text fragment as its own {@code stream_event} line and repeats
 * the content in the final {@code assistant} message; Copilot sends fragments
 * as {@code session/update} chunks and the chat log records the whole answer.
 * Such fragments are only counted; long thinking signatures are shortened.
 */
final class ProtocolLog {

	private static final Pattern SIGNATURE = Pattern.compile("\"signature\":\"[A-Za-z0-9+/=_-]{16,}\"");

	private final String name;
	private int skipped;

	ProtocolLog(String name) {
		this.name = name;
	}

	/** A line the process wrote ({@code ->}) or received ({@code <-}). */
	void line(String direction, String line) {
		if (!Log.detail()) {
			return;
		}
		if (isNoise(line)) {
			synchronized (this) {
				skipped++;
			}
			return;
		}
		Log.debug("cli", () -> name + " " + direction + " " + Log.clip(shorten(line)));
	}

	/** Called when the process ends. */
	void finish() {
		int n;
		synchronized (this) {
			n = skipped;
			skipped = 0;
		}
		if (n > 0) {
			Log.debug("cli", () -> name + ": " + n + " streaming events not logged (their content is in the full messages)");
		}
	}

	static boolean isNoise(String line) {
		if (line.startsWith("{\"type\":\"stream_event\"")
				|| line.startsWith("{\"type\":\"system\",\"subtype\":\"thinking_tokens\"")) {
			return true; // Claude Code: fragments of the assistant message that follows
		}
		// Copilot (ACP): text fragments; the chat log records the whole answer
		return line.contains("\"session/update\"") && (line.contains("\"sessionUpdate\":\"agent_message_chunk\"")
				|| line.contains("\"sessionUpdate\":\"agent_thought_chunk\""));
	}

	static String shorten(String line) {
		return SIGNATURE.matcher(line).replaceAll("\"signature\":\"[…]\"");
	}
}

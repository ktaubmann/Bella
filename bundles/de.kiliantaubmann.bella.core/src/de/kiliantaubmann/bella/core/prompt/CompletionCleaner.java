package de.kiliantaubmann.bella.core.prompt;

/** Post-processing of raw completion output before it is shown as ghost text. */
public final class CompletionCleaner {

	private static final int MAX_LINES = 12;

	private CompletionCleaner() {
	}

	public static String clean(String raw, String prefix, String suffix) {
		if (raw == null) {
			return "";
		}
		String s = raw.replace("\r\n", "\n");
		// strip a markdown fence the model added despite instructions
		String trimmed = s.strip();
		if (trimmed.startsWith("```")) {
			int nl = trimmed.indexOf('\n');
			s = nl < 0 ? "" : trimmed.substring(nl + 1);
			int end = s.lastIndexOf("```");
			if (end >= 0) {
				s = s.substring(0, end);
			}
			s = stripTrailingNewline(s);
		}
		// the model sometimes repeats the current line up to the cursor
		String currentLine = prefix.substring(prefix.lastIndexOf('\n') + 1);
		if (!currentLine.isBlank() && s.startsWith(currentLine)) {
			s = s.substring(currentLine.length());
		} else if (!currentLine.isBlank() && s.startsWith(currentLine.stripLeading())) {
			s = s.substring(currentLine.stripLeading().length());
		}
		// drop a tail that repeats the code after the cursor
		String after = suffix.stripLeading();
		if (!after.isEmpty()) {
			for (int len = Math.min(after.length(), s.length()); len >= 3; len--) {
				if (s.endsWith(after.substring(0, len))) {
					s = s.substring(0, s.length() - len);
					break;
				}
			}
		}
		String[] lines = s.split("\n", -1);
		if (lines.length > MAX_LINES) {
			s = String.join("\n", java.util.Arrays.copyOf(lines, MAX_LINES));
		}
		return s.isBlank() ? "" : stripTrailingNewline(s);
	}

	private static String stripTrailingNewline(String s) {
		while (s.endsWith("\n")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}
}

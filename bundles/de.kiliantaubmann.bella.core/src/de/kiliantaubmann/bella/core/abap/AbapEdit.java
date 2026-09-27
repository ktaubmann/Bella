package de.kiliantaubmann.bella.core.abap;

/** Text edits that keep ABAP formatting intact. */
public final class AbapEdit {

	/** Replace {@code length} characters at {@code offset} with {@code text}. */
	public record Replacement(int offset, int length, String text) {

		public String applyTo(String source) {
			return source.substring(0, offset) + text + source.substring(offset + length);
		}
	}

	private AbapEdit() {
	}

	/**
	 * Replacement for the body of a routine: everything between the opening
	 * statement and the line of the closing statement. The new body is indented
	 * two spaces deeper than the {@code METHOD} line, like the ADT formatter.
	 */
	public static Replacement replaceBody(String src, AbapStructureScanner.Block block, String newBody) {
		String indent = indentationOfLine(src, block.start());
		int from = block.headerEnd();
		int footerLine = lineStart(src, block.footerStart());
		boolean footerOnOwnLine = src.substring(footerLine, block.footerStart()).isBlank() && footerLine > from;
		int to = footerOnOwnLine ? footerLine : block.footerStart();
		String body = indent(stripBlankEdges(newBody), indent + "  ");
		String text = "\n" + (body.isEmpty() ? "" : body + "\n") + (footerOnOwnLine ? "" : indent);
		return new Replacement(from, to - from, text);
	}

	/** Re-indents a snippet so that its least indented line starts with {@code indent}. */
	public static String indent(String code, String indent) {
		String[] lines = code.replace("\r\n", "\n").split("\n", -1);
		int min = Integer.MAX_VALUE;
		for (String l : lines) {
			if (l.isBlank()) {
				continue;
			}
			int ws = 0;
			while (ws < l.length() && l.charAt(ws) == ' ') {
				ws++;
			}
			min = Math.min(min, ws);
		}
		if (min == Integer.MAX_VALUE) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			String l = lines[i];
			if (i > 0) {
				sb.append('\n');
			}
			if (l.isBlank()) {
				continue;
			}
			// full-line comments must keep the asterisk in column one
			if (l.startsWith("*")) {
				sb.append(l);
			} else {
				sb.append(indent).append(l.substring(Math.min(min, l.length())));
			}
		}
		return sb.toString();
	}

	/** Indentation (spaces and tabs) of the line containing {@code offset}. */
	public static String indentationOfLine(String src, int offset) {
		int ls = lineStart(src, offset);
		int i = ls;
		while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) {
			i++;
		}
		return src.substring(ls, i);
	}

	public static int lineStart(String src, int offset) {
		int nl = src.lastIndexOf('\n', Math.max(0, offset - 1));
		return nl < 0 ? 0 : nl + 1;
	}

	private static String stripBlankEdges(String s) {
		String[] lines = s.replace("\r\n", "\n").split("\n", -1);
		int a = 0;
		int b = lines.length;
		while (a < b && lines[a].isBlank()) {
			a++;
		}
		while (b > a && lines[b - 1].isBlank()) {
			b--;
		}
		return String.join("\n", java.util.Arrays.asList(lines).subList(a, b));
	}
}

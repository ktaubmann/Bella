package de.kiliantaubmann.bella.core.abap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lightweight ABAP statement scanner. It knows comments ({@code *} in column
 * one, {@code "} to end of line) and literals ({@code '…'}, {@code `…`},
 * {@code |…|} templates), splits the source into statements at the period and
 * pairs block statements (METHOD/ENDMETHOD, FORM/ENDFORM, …). That is enough
 * to find the method around the cursor without a full parser.
 */
public final class AbapStructureScanner {

	/** One ABAP statement. {@code start} is its first code character, {@code end} is after the period. */
	public record Statement(int start, int end, String text) {

		/** The first {@code n} words, upper-cased. */
		public List<String> words(int n) {
			List<String> words = new ArrayList<>();
			for (String w : text.trim().split("\\s+")) {
				if (words.size() == n) {
					break;
				}
				if (!w.isEmpty()) {
					words.add(w.endsWith(".") ? w.substring(0, w.length() - 1) : w);
				}
			}
			return words.stream().map(w -> w.toUpperCase(Locale.ROOT)).toList();
		}
	}

	public enum Kind {
		METHOD, FORM, FUNCTION, MODULE, CLASS_DEFINITION, CLASS_IMPLEMENTATION, INTERFACE;

		public boolean isRoutine() {
			return this == METHOD || this == FORM || this == FUNCTION || this == MODULE;
		}
	}

	/**
	 * A block such as {@code METHOD x. … ENDMETHOD.}
	 *
	 * @param start       start of the opening statement
	 * @param headerEnd   after the period of the opening statement (body starts here)
	 * @param footerStart start of the closing statement (body ends here)
	 * @param end         after the period of the closing statement
	 */
	public record Block(Kind kind, String name, int start, int headerEnd, int footerStart, int end) {

		public boolean contains(int offset) {
			return offset >= start && offset <= end;
		}

		public String body(String source) {
			return source.substring(headerEnd, footerStart);
		}
	}

	private AbapStructureScanner() {
	}

	public static List<Statement> statements(String src) {
		List<Statement> result = new ArrayList<>();
		int n = src.length();
		int i = 0;
		int stmtStart = -1;
		StringBuilder text = new StringBuilder();
		boolean lineStart = true;
		while (i < n) {
			char c = src.charAt(i);
			if (lineStart && c == '*') {
				i = skipToEol(src, i);
				continue;
			}
			lineStart = false;
			if (c == '\n') {
				lineStart = true;
				if (stmtStart >= 0) {
					space(text);
				}
				i++;
				continue;
			}
			if (c == '"') {
				i = skipToEol(src, i);
				continue;
			}
			if (c == '\'' || c == '`') {
				int end = skipQuoted(src, i, c);
				if (stmtStart < 0) {
					stmtStart = i;
				}
				text.append(src, i, end);
				i = end;
				continue;
			}
			if (c == '|') {
				int end = skipTemplate(src, i);
				if (stmtStart < 0) {
					stmtStart = i;
				}
				text.append(src, i, end);
				i = end;
				continue;
			}
			if (c == '.') {
				if (stmtStart < 0) {
					stmtStart = i;
				}
				text.append('.');
				result.add(new Statement(stmtStart, i + 1, text.toString()));
				text.setLength(0);
				stmtStart = -1;
				i++;
				continue;
			}
			if (!Character.isWhitespace(c) && stmtStart < 0) {
				stmtStart = i;
			}
			if (stmtStart >= 0) {
				if (Character.isWhitespace(c)) {
					space(text);
				} else {
					text.append(c);
				}
			}
			i++;
		}
		return result;
	}

	/** Appends a single blank, collapsing runs of whitespace and line breaks. */
	private static void space(StringBuilder text) {
		if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
			text.append(' ');
		}
	}

	private static int skipToEol(String s, int i) {
		int nl = s.indexOf('\n', i);
		return nl < 0 ? s.length() : nl;
	}

	private static int skipQuoted(String s, int i, char quote) {
		int j = i + 1;
		while (j < s.length()) {
			char c = s.charAt(j);
			if (c == quote) {
				if (j + 1 < s.length() && s.charAt(j + 1) == quote) {
					j += 2;
					continue;
				}
				return j + 1;
			}
			if (c == '\n') {
				return j;
			}
			j++;
		}
		return j;
	}

	/** Skips a string template, including embedded expressions that may contain literals or templates. */
	private static int skipTemplate(String s, int i) {
		int j = i + 1;
		int depth = 0;
		while (j < s.length()) {
			char c = s.charAt(j);
			if (depth == 0) {
				if (c == '\\') {
					j += 2;
					continue;
				}
				if (c == '|') {
					return j + 1;
				}
				if (c == '{') {
					depth = 1;
				}
				j++;
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
			} else if (c == '\'' || c == '`') {
				j = skipQuoted(s, j, c);
				continue;
			} else if (c == '|') {
				j = skipTemplate(s, j);
				continue;
			}
			j++;
		}
		return j;
	}

	/** All blocks in source order of their opening statement. */
	public static List<Block> blocks(String src) {
		List<Block> blocks = new ArrayList<>();
		Deque<Open> open = new ArrayDeque<>();
		for (Statement st : statements(src)) {
			List<String> w = st.words(3);
			if (w.isEmpty()) {
				continue;
			}
			String first = w.get(0);
			String second = w.size() > 1 ? w.get(1) : "";
			String third = w.size() > 2 ? w.get(2) : "";
			switch (first) {
			case "METHOD" -> open.push(new Open(Kind.METHOD, second, st));
			case "FORM" -> open.push(new Open(Kind.FORM, second, st));
			case "FUNCTION" -> open.push(new Open(Kind.FUNCTION, second, st));
			case "MODULE" -> {
				// "MODULE x OUTPUT." opens a block, a bare "MODULE x." in flow logic does not
				if (third.equals("OUTPUT") || third.equals("INPUT") || third.isEmpty()) {
					open.push(new Open(Kind.MODULE, second, st));
				}
			}
			case "CLASS" -> {
				if (third.equals("DEFINITION") && !isDeferredOrLoad(st)) {
					open.push(new Open(Kind.CLASS_DEFINITION, second, st));
				} else if (third.equals("IMPLEMENTATION")) {
					open.push(new Open(Kind.CLASS_IMPLEMENTATION, second, st));
				}
			}
			case "INTERFACE" -> {
				if (!isDeferredOrLoad(st)) {
					open.push(new Open(Kind.INTERFACE, second, st));
				}
			}
			case "ENDMETHOD" -> close(open, Kind.METHOD, st, blocks);
			case "ENDFORM" -> close(open, Kind.FORM, st, blocks);
			case "ENDFUNCTION" -> close(open, Kind.FUNCTION, st, blocks);
			case "ENDMODULE" -> close(open, Kind.MODULE, st, blocks);
			case "ENDCLASS" -> {
				if (!open.isEmpty() && (open.peek().kind == Kind.CLASS_DEFINITION
						|| open.peek().kind == Kind.CLASS_IMPLEMENTATION)) {
					close(open, open.peek().kind, st, blocks);
				}
			}
			case "ENDINTERFACE" -> close(open, Kind.INTERFACE, st, blocks);
			default -> {
				// ordinary statement
			}
			}
		}
		blocks.sort((a, b) -> Integer.compare(a.start(), b.start()));
		return blocks;
	}

	private static boolean isDeferredOrLoad(Statement st) {
		String upper = st.text().toUpperCase(Locale.ROOT);
		return upper.contains(" DEFERRED") || upper.matches(".*\\sLOAD\\s*\\.$");
	}

	private record Open(Kind kind, String name, Statement statement) {
	}

	private static void close(Deque<Open> open, Kind kind, Statement end, List<Block> out) {
		// Unbalanced code while typing: unwind to the nearest block of the right kind.
		while (!open.isEmpty()) {
			Open o = open.pop();
			if (o.kind == kind) {
				out.add(new Block(kind, o.name.toUpperCase(Locale.ROOT), o.statement.start(), o.statement.end(),
						end.start(), end.end()));
				return;
			}
		}
	}

	/** The innermost METHOD/FORM/FUNCTION/MODULE containing {@code offset}. */
	public static Optional<Block> routineAt(String src, int offset) {
		Block best = null;
		for (Block b : blocks(src)) {
			if (b.kind().isRoutine() && b.contains(offset) && (best == null || b.start() > best.start())) {
				best = b;
			}
		}
		return Optional.ofNullable(best);
	}

	/** The class implementation or definition containing {@code offset}. */
	public static Optional<Block> classAt(String src, int offset) {
		Block best = null;
		for (Block b : blocks(src)) {
			if ((b.kind() == Kind.CLASS_IMPLEMENTATION || b.kind() == Kind.CLASS_DEFINITION) && b.contains(offset)
					&& (best == null || b.start() > best.start())) {
				best = b;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Source of {@code CLASS name DEFINITION … ENDCLASS.}, if present in this source. */
	public static Optional<String> classDefinition(String src, String className) {
		for (Block b : blocks(src)) {
			if (b.kind() == Kind.CLASS_DEFINITION && b.name().equalsIgnoreCase(className)) {
				return Optional.of(src.substring(b.start(), b.end()));
			}
		}
		return Optional.empty();
	}

	/**
	 * The {@code METHODS} (or {@code CLASS-METHODS}) statement declaring a
	 * method, searched in the class definition. Interface methods
	 * ({@code intf~meth}) are not declared in the class and yield empty.
	 */
	public static Optional<String> methodDeclaration(String src, String className, String methodName) {
		Optional<String> def = classDefinition(src, className);
		if (def.isEmpty()) {
			return Optional.empty();
		}
		for (Statement st : statements(def.get())) {
			List<String> w = st.words(2);
			if (w.size() == 2 && (w.get(0).equals("METHODS") || w.get(0).equals("CLASS-METHODS"))
					&& w.get(1).replace(":", "").equalsIgnoreCase(methodName)) {
				return Optional.of(st.text().trim());
			}
		}
		return Optional.empty();
	}
}

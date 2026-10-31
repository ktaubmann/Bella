package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;
import de.kiliantaubmann.bella.core.lint.AbaplintRules.BoolAssignment;
import de.kiliantaubmann.bella.core.lint.AbaplintRules.ElseAfterExit;

/**
 * Fixes for style findings whose correction is fixed: obsolete MOVE, COMPUTE,
 * ADD/SUBTRACT/MULTIPLY/DIVIDE, REFRESH, CALL METHOD, CREATE OBJECT,
 * DESCRIBE TABLE … LINES, NOT … IS, RECEIVING, an omittable EXPORTING,
 * IF … abap_true/abap_false … ENDIF, ELSE after RETURN, several statements on
 * one line, blanks before the period, empty lines in a statement and trailing
 * spaces. A statement is only changed when it stands alone (no chain, no
 * comment inside) and has the plain form; anything else stays a finding for
 * the model. Literals are masked, so keywords inside them never match.
 */
public final class AbapLintFix {

	/** The fixed source and one entry per change ({@code "Line 3 obsolete_move"}), lines of the original. */
	public record Result(String source, List<String> applied) {
	}

	private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
	private static final Pattern MOVE = Pattern.compile("MOVE\\s+(?!EXACT\\b)(\\S+)\\s+(\\??TO)\\s+(\\S+)\\.", FLAGS);
	private static final Pattern COMPUTE = Pattern.compile("COMPUTE\\s+(?!EXACT\\b)(.+)", FLAGS);
	private static final Pattern ADD = Pattern.compile("ADD\\s+(\\S+)\\s+TO\\s+(\\S+)\\.", FLAGS);
	private static final Pattern SUBTRACT = Pattern.compile("SUBTRACT\\s+(\\S+)\\s+FROM\\s+(\\S+)\\.", FLAGS);
	private static final Pattern MULTIPLY_DIVIDE = Pattern.compile("(MULTIPLY|DIVIDE)\\s+(\\S+)\\s+BY\\s+(\\S+)\\.",
			FLAGS);
	private static final Pattern REFRESH = Pattern.compile("REFRESH\\s+([^\\s.]+)\\.", FLAGS);
	private static final Pattern DESCRIBE = Pattern.compile("DESCRIBE\\s+TABLE\\s+(\\S+)\\s+LINES\\s+([^\\s.]+)\\.",
			FLAGS);
	private static final Pattern CALL_METHOD = Pattern.compile("CALL\\s+METHOD\\s+([^\\s.]+)(\\s.*)?\\.", FLAGS);
	private static final Pattern CALL_METHOD_RECEIVING = Pattern.compile(
			"CALL\\s+METHOD\\s+([^\\s.(]+)(?:\\s+EXPORTING(\\s.*?))?\\s+RECEIVING\\s+\\S+\\s*=\\s*([^\\s.]+)\\s*\\.", FLAGS);
	private static final Pattern FUNCTIONAL_RECEIVING = Pattern.compile(
			"([^\\s(]+)\\(\\s*(?:EXPORTING(\\s.*?))?\\s*RECEIVING\\s+\\S+\\s*=\\s*([^\\s)]+)\\s*\\)\\s*\\.", FLAGS);
	private static final Pattern CREATE_OBJECT = Pattern.compile(
			"CREATE\\s+OBJECT\\s+([^\\s.]+)(?:\\s+TYPE\\s+([^\\s.(]+))?(?:\\s+EXPORTING(\\s.*))?\\.", FLAGS);
	private static final Pattern OTHER_PARAMETERS = Pattern.compile("(?s).*\\b(?:IMPORTING|CHANGING|EXCEPTIONS)\\b.*",
			Pattern.CASE_INSENSITIVE);
	/** Tables with a header line, where REFRESH, CLEAR and lines( ) mean different things. */
	private static final Pattern HEADER_LINE = Pattern.compile("(?i)\\bOCCURS\\b|\\bWITH\\s+HEADER\\s+LINE\\b|^\\s*TABLES\\b",
			Pattern.MULTILINE);

	private AbapLintFix() {
	}

	/** Applies all fixes; CDS sources are returned unchanged. */
	public static Result fix(String source, Target target) {
		if (source == null || source.isBlank() || CdsLint.isCds(source)) {
			return new Result(source, List.of());
		}
		Target t = target == null ? Target.UNKNOWN : target;
		List<String> applied = new ArrayList<>();
		List<int[]> moved = new ArrayList<>();
		String structured = blockFixes(source, t, applied, moved);
		String fixed = statementFixes(structured, t, applied, moved);
		fixed = trimLineEnds(fixed, applied, moved);
		applied.sort((a, b) -> Integer.compare(lineNumber(a), lineNumber(b)));
		return new Result(fixed, List.copyOf(applied));
	}

	private record Fix(String rule, String text) {
	}

	private record Edit(int start, int end, String text, String rule, int line) {
	}

	// ---- fixes over several statements

	/**
	 * xsdbool( ) for IF blocks that only set a flag, and ELSE after branches that all leave the block. Overlapping
	 * blocks are left for the next run. {@code moved} records each change as {newStartLine, newLines, oldLines}.
	 */
	private static String blockFixes(String source, Target t, List<String> applied, List<int[]> moved) {
		List<Statement> statements = AbapStructureScanner.statements(source);
		List<String> codes = statements.stream().map(AbapLint::normalized).toList();
		int[] lineStarts = AbapLint.lineStarts(source);
		String mask = mask(source);
		List<Edit> edits = new ArrayList<>();
		if (!t.below(740)) {
			for (BoolAssignment b : AbaplintRules.boolAssignments(codes)) {
				Statement first = statements.get(b.ifIndex());
				Statement last = statements.get(b.endIndex());
				if (hasComment(mask, first.start(), last.end())) {
					continue;
				}
				String condition = source.substring(first.start(), first.end() - 1).replaceFirst("(?i)^IF\\s+", "")
						.strip();
				String target = source.substring(statements.get(b.ifIndex() + 1).start(),
						statements.get(b.ifIndex() + 1).end()).split("\\s*=", 2)[0].strip();
				edits.add(new Edit(first.start(), last.end(), target + " = xsdbool( "
						+ (b.negated() ? "NOT ( " + condition + " )" : condition) + " ).", "use_bool_expression",
						AbapLint.lineOf(lineStarts, first.start())));
			}
		}
		for (ElseAfterExit e : AbaplintRules.elseAfterExits(codes)) {
			if (e.elseIf()) {
				continue;
			}
			Statement ifSt = statements.get(e.ifIndex());
			Statement elseSt = statements.get(e.elseIndex());
			Statement endSt = statements.get(e.endIndex());
			String body = source.substring(elseSt.end(), endSt.start());
			int delta = indentOf(firstCodeLine(body)).length() - indentOf(lineOf(source, elseSt.start())).length();
			edits.add(new Edit(ifSt.start(), endSt.end(), source.substring(ifSt.start(), elseSt.start()) + "ENDIF."
					+ dedent(body, delta).stripTrailing(), "else_after_all_returns",
					AbapLint.lineOf(lineStarts, elseSt.start())));
		}
		edits.sort((a, b) -> Integer.compare(a.start(), b.start()));
		StringBuilder out = new StringBuilder();
		int pos = 0;
		int shift = 0;
		for (Edit e : edits) {
			if (e.start() < pos) {
				continue;
			}
			out.append(source, pos, e.start()).append(e.text());
			int oldLines = lines(source.substring(e.start(), e.end()));
			int newLines = lines(e.text());
			int startLine = AbapLint.lineOf(lineStarts, e.start());
			moved.add(new int[] { startLine + shift, newLines, oldLines });
			shift += newLines - oldLines;
			applied.add("Line " + e.line() + " " + e.rule());
			pos = e.end();
		}
		out.append(source, pos, source.length());
		return out.toString();
	}

	// ---- fixes of single statements

	private static String statementFixes(String source, Target t, List<String> applied, List<int[]> moved) {
		boolean headerLines = HEADER_LINE.matcher(source).find();
		int[] lineStarts = AbapLint.lineStarts(source);
		String mask = mask(source);
		StringBuilder out = new StringBuilder(source);
		List<Statement> statements = AbapStructureScanner.statements(source);
		// from the end, so the offsets of earlier statements stay valid
		for (int i = statements.size() - 1; i >= 0; i--) {
			Statement st = statements.get(i);
			int line = original(AbapLint.lineOf(lineStarts, st.start()), moved);
			if (i + 1 < statements.size() && AbaplintRules.sameLine(source, st, statements.get(i + 1))
					&& !hasComment(mask, st.start(), statements.get(i + 1).end())) {
				out.replace(st.end(), statements.get(i + 1).start(), "\n" + indentOf(lineOf(source, st.start())));
				applied.add("Line " + line + " max_one_statement");
			}
			String slice = source.substring(st.start(), st.end());
			String sliceMask = mask.substring(st.start(), st.end());
			if (!standalone(sliceMask)) {
				continue;
			}
			String text = slice;
			Fix f = fixStatement(text, sliceMask, t, headerLines);
			if (f != null) {
				text = f.text();
				applied.add("Line " + line + " " + f.rule());
			}
			for (String rule : List.of("prefer_is_not", "exporting", "space_before_dot", "empty_line_in_statement")) {
				String changed = inStatement(rule, text, mask(text));
				if (!changed.equals(text)) {
					text = changed;
					applied.add("Line " + line + " " + rule);
				}
			}
			if (!text.equals(slice)) {
				out.replace(st.start(), st.end(), text);
			}
		}
		return out.toString();
	}

	/** No chain, no comment and no pragma (whose place a rewrite could move) inside the statement. */
	private static boolean standalone(String sliceMask) {
		return sliceMask.endsWith(".") && sliceMask.indexOf(':') < 0 && !sliceMask.contains("##")
				&& !hasComment(sliceMask, 0, sliceMask.length());
	}

	private static boolean hasComment(String mask, int from, int to) {
		String part = mask.substring(from, to);
		return part.indexOf('"') >= 0 || part.matches("(?s)(?:.*\\n)?\\*.*");
	}

	/** One statement in its modern form, or null. Patterns run on the mask, the result is built from the text. */
	private static Fix fixStatement(String s, String m, Target t, boolean headerLines) {
		String first = m.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
		boolean compoundAssignment = t.cloud() || t.release() >= 754;
		return switch (first) {
		case "MOVE" -> match(MOVE, s, m, g -> new Fix("obsolete_move",
				g.get(3) + (g.get(2).startsWith("?") ? " ?= " : " = ") + g.get(1) + "."));
		case "COMPUTE" -> match(COMPUTE, s, m, g -> new Fix("obsolete_compute", g.get(1)));
		case "ADD" -> match(ADD, s, m, g -> arithmetic(g.get(2), "+", g.get(1), compoundAssignment));
		case "SUBTRACT" -> match(SUBTRACT, s, m, g -> arithmetic(g.get(2), "-", g.get(1), compoundAssignment));
		case "MULTIPLY", "DIVIDE" -> match(MULTIPLY_DIVIDE, s, m, g -> arithmetic(g.get(2),
				g.get(1).equalsIgnoreCase("MULTIPLY") ? "*" : "/", g.get(3), compoundAssignment));
		case "REFRESH" -> headerLines ? null
				: match(REFRESH, s, m, g -> new Fix("obsolete_refresh", "CLEAR " + g.get(1) + "."));
		case "DESCRIBE" -> headerLines ? null
				: match(DESCRIBE, s, m, g -> new Fix("describe_lines", g.get(2) + " = lines( " + g.get(1) + " )."));
		case "CALL" -> {
			Fix receiving = match(CALL_METHOD_RECEIVING, s, m, AbapLintFix::receiving);
			yield receiving != null ? receiving : match(CALL_METHOD, s, m, AbapLintFix::callMethod);
		}
		case "CREATE" -> t.below(740) ? null : match(CREATE_OBJECT, s, m, AbapLintFix::createObject);
		default -> match(FUNCTIONAL_RECEIVING, s, m, AbapLintFix::receiving);
		};
	}

	/** The groups of a match on the mask, read from the text. */
	private record Groups(Matcher matcher, String text) {

		String get(int group) {
			return matcher.start(group) < 0 ? null : text.substring(matcher.start(group), matcher.end(group));
		}
	}

	private interface GroupFix {
		Fix apply(Groups g);
	}

	private static Fix match(Pattern p, String text, String mask, GroupFix fix) {
		Matcher m = p.matcher(mask);
		return m.matches() ? fix.apply(new Groups(m, text)) : null;
	}

	/** {@code b += a} from 7.54 on, {@code b = b + a} before or when the release is unknown. */
	private static Fix arithmetic(String target, String operator, String operand, boolean compound) {
		return new Fix("obsolete_arithmetic", compound ? target + " " + operator + "= " + operand + "."
				: target + " = " + target + " " + operator + " " + operand + ".");
	}

	private static Fix callMethod(Groups g) {
		String method = g.get(1);
		String rest = g.get(2) == null ? "" : g.get(2);
		// dynamic calls, OLE (CALL METHOD OF) and RECEIVING with other parameters keep their form
		if (method.contains("(") || method.equalsIgnoreCase("OF")
				|| mask(rest).toUpperCase(Locale.ROOT).matches("(?s).*\\bRECEIVING\\b.*")) {
			return null;
		}
		return new Fix("call_method", method + "(" + (rest.isBlank() ? " " : rest + " ") + ").");
	}

	/** {@code v = obj->method( … ).} for a call with only EXPORTING and RECEIVING. */
	private static Fix receiving(Groups g) {
		String parameters = g.get(2) == null ? "" : g.get(2).strip();
		if (g.get(1).contains("(") || g.get(1).equalsIgnoreCase("OF") || OTHER_PARAMETERS.matcher(mask(parameters))
				.matches()) {
			return null;
		}
		return new Fix(g.matcher().pattern() == CALL_METHOD_RECEIVING ? "call_method" : "omit_receiving", g.get(3) + " = " + g.get(1) + "(" + (parameters.isEmpty() ? " " : " "
				+ parameters + " ") + ").");
	}

	private static Fix createObject(Groups g) {
		String parameters = g.get(3) == null ? "" : g.get(3);
		// EXCEPTIONS and shared-objects AREA HANDLE have no NEW form
		if (mask(parameters).toUpperCase(Locale.ROOT).matches("(?s).*\\b(?:EXCEPTIONS|AREA)\\b.*")) {
			return null;
		}
		String type = g.get(2) == null ? "#" : g.get(2);
		return new Fix("create_object", g.get(1) + " = NEW " + type + "(" + (parameters.isBlank() ? " " : parameters + " ")
				+ ").");
	}

	/** Changes inside a statement that keep its form. */
	private static String inStatement(String rule, String text, String mask) {
		switch (rule) {
		case "prefer_is_not" -> {
			Matcher m = AbaplintRules.NOT_IS.matcher(mask);
			StringBuilder sb = new StringBuilder();
			int pos = 0;
			while (m.find()) {
				String operand = text.substring(m.start(1), m.end(1));
				String keyword = text.substring(m.end(1), m.end()).strip();
				sb.append(text, pos, m.start()).append(operand)
						.append(keyword.equalsIgnoreCase("IN") ? " NOT IN " : " " + keyword + " NOT ");
				pos = m.end();
			}
			return sb.append(text, pos, text.length()).toString();
		}
		case "exporting" -> {
			String result = text;
			for (int at = AbaplintRules.omittableExporting(mask); at >= 0; at = AbaplintRules
					.omittableExporting(mask(result))) {
				int end = at + "EXPORTING".length();
				while (end < result.length() && Character.isWhitespace(result.charAt(end))) {
					end++;
				}
				result = result.substring(0, at) + result.substring(end);
			}
			return result;
		}
		case "space_before_dot" -> {
			return text.replaceFirst("(?s)(\\S)[ \\t]+\\.$", "$1.");
		}
		case "empty_line_in_statement" -> {
			return text.replaceAll("\\n[ \\t]*(?=\\r?\\n)", "");
		}
		default -> {
			return text;
		}
		}
	}

	// ---- helpers

	/** The text with the content of literals and string templates replaced by '_' (same length, same lines). */
	static String mask(String s) {
		StringBuilder sb = new StringBuilder(s);
		boolean lineStart = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\n') {
				lineStart = true;
				continue;
			}
			if (lineStart && c == '*' || c == '"') {
				while (i + 1 < s.length() && s.charAt(i + 1) != '\n') {
					i++;
				}
				continue;
			}
			lineStart = false;
			if (c == '\'' || c == '`' || c == '|') {
				int j = i + 1;
				while (j < s.length() && s.charAt(j) != '\n') {
					char d = s.charAt(j);
					if (c == '|' && d == '\\') {
						j += 2;
						continue;
					}
					if (d == c) {
						if (c != '|' && j + 1 < s.length() && s.charAt(j + 1) == c) {
							j += 2;
							continue;
						}
						break;
					}
					j++;
				}
				for (int k = i + 1; k < Math.min(j, s.length()); k++) {
					sb.setCharAt(k, '_');
				}
				i = j;
			}
		}
		return sb.toString();
	}

	/** The original line of a line in the code after the block fixes. */
	private static int original(int line, List<int[]> moved) {
		int offset = 0;
		for (int[] m : moved) {
			if (line > m[0] + m[1]) {
				offset += m[2] - m[1];
			} else if (line >= m[0]) {
				return m[0] + offset;
			}
		}
		return line + offset;
	}

	private static int lineNumber(String entry) {
		Matcher m = Pattern.compile("^Line (\\d+)").matcher(entry);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static int lines(String s) {
		return (int) s.chars().filter(c -> c == '\n').count();
	}

	private static String lineOf(String source, int offset) {
		int start = source.lastIndexOf('\n', offset - 1) + 1;
		int end = source.indexOf('\n', offset);
		return source.substring(start, end < 0 ? source.length() : end);
	}

	private static String indentOf(String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
			i++;
		}
		return line.substring(0, i);
	}

	private static String firstCodeLine(String body) {
		for (String l : body.split("\n")) {
			if (!l.isBlank()) {
				return l;
			}
		}
		return "";
	}

	/** Removes up to {@code delta} leading blanks from every line after the first. */
	private static String dedent(String body, int delta) {
		if (delta <= 0) {
			return body;
		}
		String[] lines = body.split("\n", -1);
		for (int i = 1; i < lines.length; i++) {
			int n = 0;
			while (n < delta && n < lines[i].length() && lines[i].charAt(n) == ' ') {
				n++;
			}
			lines[i] = lines[i].substring(n);
		}
		return String.join("\n", lines);
	}

	/** Removes trailing spaces, except on lines inside a string template that goes on. */
	private static String trimLineEnds(String source, List<String> applied, List<int[]> moved) {
		String[] lines = source.split("\n", -1);
		int trimmed = 0;
		int firstTrimmed = 0;
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			boolean cr = line.endsWith("\r");
			String body = cr ? line.substring(0, line.length() - 1) : line;
			String stripped = body.stripTrailing();
			if (!stripped.equals(body) && stripped.chars().filter(c -> c == '|').count() % 2 == 0) {
				lines[i] = stripped + (cr ? "\r" : "");
				if (trimmed++ == 0) {
					firstTrimmed = i + 1;
				}
			}
		}
		if (trimmed > 0) {
			applied.add("Line " + original(firstTrimmed, moved) + " whitespace_end"
					+ (trimmed > 1 ? " (" + trimmed + " lines)" : ""));
		}
		return String.join("\n", lines);
	}
}

package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;

/**
 * Bella's style check: a small set of rules for obsolete statements,
 * database access and error handling, in the spirit of abaplint and the
 * Clean ABAP guide. Works on the text alone, without an SAP system; it is not
 * a replacement for ATC or abaplint.
 */
public final class AbapLint {

	public enum Severity {
		ERROR, WARNING, INFO;

		public String label() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** One finding; {@code line} is 1-based and relative to the checked text. */
	public record Finding(int line, String rule, Severity severity, String message) {

		public String format() {
			return "Line " + line + " [" + severity.label() + "] " + rule + ": " + message;
		}
	}

	private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'|`(?:[^`]|``)*`|\\|[^|]*\\|");
	private static final Pattern SELECT_STAR = Pattern.compile("\\bSELECT\\s+(?:SINGLE\\s+)?(?:DISTINCT\\s+)?\\*|\\bFIELDS\\s+\\*");
	private static final Pattern INTO_TARGET = Pattern.compile(
			"\\bINTO\\s+(?:CORRESPONDING\\s+FIELDS\\s+OF\\s+)?@?(?:DATA\\(|FINAL\\()?\\s*([A-Z0-9_<>~-]+)");
	private static final Pattern MESSAGE_ABORT = Pattern.compile("^MESSAGE\\b.*\\bTYPE\\s+'[AX]'|^MESSAGE\\s+[AX]\\d{3}\\b");

	private AbapLint() {
	}

	public static List<Finding> check(String source) {
		List<Finding> out = new ArrayList<>();
		if (source == null || source.isBlank()) {
			return out;
		}
		int[] lineStarts = lineStarts(source);
		List<Statement> statements = AbapStructureScanner.statements(source);
		int loopDepth = 0;
		int openSelect = -1;
		for (int i = 0; i < statements.size(); i++) {
			Statement st = statements.get(i);
			String raw = st.text().trim().toUpperCase(Locale.ROOT);
			String code = LITERAL.matcher(st.text()).replaceAll("''").trim().toUpperCase(Locale.ROOT);
			if (code.endsWith(".")) {
				code = code.substring(0, code.length() - 1).trim();
			}
			List<String> w = List.of(code.split("\\s+"));
			String first = w.get(0).replace(":", "");
			String second = w.size() > 1 ? w.get(1) : "";
			int line = lineOf(lineStarts, st.start());

			switch (first) {
			case "METHOD", "FORM", "FUNCTION", "MODULE" -> loopDepth = 0;
			case "LOOP", "DO", "WHILE", "PROVIDE" -> loopDepth++;
			case "ENDLOOP", "ENDDO", "ENDWHILE", "ENDPROVIDE" -> loopDepth = Math.max(0, loopDepth - 1);
			default -> {
				// no block change
			}
			}

			// ---- obsolete statements
			if (first.equals("MOVE") && code.contains(" TO ")) {
				out.add(new Finding(line, "obsolete_move", Severity.WARNING, "MOVE is obsolete; use the assignment a = b."));
			}
			if (first.equals("COMPUTE")) {
				out.add(new Finding(line, "obsolete_compute", Severity.WARNING, "COMPUTE is obsolete; write the expression directly."));
			}
			if ((first.equals("ADD") && code.contains(" TO ")) || (first.equals("SUBTRACT") && code.contains(" FROM "))
					|| ((first.equals("MULTIPLY") || first.equals("DIVIDE")) && code.contains(" BY "))) {
				out.add(new Finding(line, "obsolete_arithmetic", Severity.WARNING,
						first + " is obsolete; use +=, -=, *= or /=."));
			}
			if (first.equals("CALL") && second.equals("METHOD") && !code.matches("CALL METHOD\\s*\\(.*")
					&& !code.matches("CALL METHOD \\S*(->|=>)\\(.*")) {
				out.add(new Finding(line, "call_method", Severity.INFO,
						"Use the functional call obj->method( ) instead of CALL METHOD."));
			}
			if (first.equals("CREATE") && second.equals("OBJECT") && !code.matches(".*\\bTYPE\\s*\\(.*")) {
				out.add(new Finding(line, "create_object", Severity.INFO, "Use NEW #( ) instead of CREATE OBJECT."));
			}
			if (first.equals("REFRESH")) {
				out.add(new Finding(line, "obsolete_refresh", Severity.WARNING, "REFRESH is obsolete; use CLEAR."));
			}
			if (first.equals("DESCRIBE") && second.equals("TABLE") && code.contains(" LINES ")) {
				out.add(new Finding(line, "describe_lines", Severity.INFO, "Use lines( itab ) instead of DESCRIBE TABLE … LINES."));
			}
			if (first.equals("RANGES")) {
				out.add(new Finding(line, "obsolete_ranges", Severity.WARNING, "RANGES is obsolete; use TYPE RANGE OF."));
			}
			if ((first.equals("DATA") || first.equals("TYPES")) && code.matches(".*\\bOCCURS\\s+\\d+.*")) {
				out.add(new Finding(line, "obsolete_occurs", Severity.WARNING,
						"OCCURS is obsolete; declare a table type with TYPE STANDARD TABLE OF."));
			}
			if (code.contains("WITH HEADER LINE")) {
				out.add(new Finding(line, "header_line", Severity.WARNING,
						"Tables with header lines are obsolete; use a separate work area or field symbol."));
			}
			if (first.equals("TABLES")) {
				out.add(new Finding(line, "obsolete_tables", Severity.WARNING,
						"TABLES work areas are obsolete; declare explicit variables."));
			}
			if (first.equals("FORM") || first.equals("PERFORM")) {
				out.add(new Finding(line, "form_routine", Severity.INFO,
						"FORM routines are obsolete; use methods of a (local) class."));
			}

			// ---- database access
			if (first.equals("SELECT") || (first.equals("WITH") && code.contains(" SELECT "))) {
				boolean single = second.equals("SINGLE");
				if (SELECT_STAR.matcher(code).find()) {
					out.add(new Finding(line, "select_star", Severity.WARNING,
							"SELECT * reads all columns; select only the fields you need."));
				}
				if (loopDepth > 0 || openSelect >= 0) {
					out.add(new Finding(line, "select_in_loop", Severity.WARNING,
							"SELECT inside a loop hits the database once per iteration; read all rows before the loop (JOIN, FOR ALL ENTRIES or a range)."));
				}
				if (!single && code.matches(".*\\bUP\\s+TO\\s+\\S+\\s+ROWS\\b.*") && !code.contains("ORDER BY")) {
					out.add(new Finding(line, "select_up_to_order", Severity.INFO,
							"UP TO n ROWS without ORDER BY returns arbitrary rows."));
				}
				if (single && !subrcChecked(statements, i, code)) {
					out.add(new Finding(line, "select_single_subrc", Severity.WARNING,
							"The result of SELECT SINGLE is not checked (sy-subrc or IS INITIAL)."));
				}
				if (!single && !code.matches(".*\\b(?:INTO|APPENDING)\\s+(?:CORRESPONDING\\s+FIELDS\\s+OF\\s+)?TABLE\\b.*")
						&& !code.contains("COUNT(") && isLoopSelect(statements, i)) {
					openSelect = line;
					out.add(new Finding(line, "select_endselect", Severity.WARNING,
							"SELECT … ENDSELECT fetches row by row; read INTO TABLE and loop over the table."));
				}
			}
			if (first.equals("ENDSELECT")) {
				openSelect = -1;
			}

			// ---- error handling and debugging
			if (first.equals("CATCH") && !second.equals("SYSTEM-EXCEPTIONS")) {
				if (code.matches(".*\\bCX_ROOT\\b.*")) {
					out.add(new Finding(line, "catch_cx_root", Severity.WARNING,
							"Catching CX_ROOT hides programming errors; catch the specific exception classes."));
				}
				String next = i + 1 < statements.size() ? firstWord(statements.get(i + 1)) : "";
				if (next.equals("ENDTRY") || next.equals("CATCH") || next.equals("CLEANUP")) {
					out.add(new Finding(line, "empty_catch", Severity.WARNING,
							"Empty CATCH block swallows the error; handle it, log it or re-raise it."));
				}
			}
			if (first.equals("BREAK-POINT") || (first.equals("BREAK") && w.size() == 2)) {
				out.add(new Finding(line, "break_point", Severity.ERROR, "Remove break-points before transport."));
			}
			if (MESSAGE_ABORT.matcher(raw).find()) {
				out.add(new Finding(line, "message_abort", Severity.WARNING,
						"MESSAGE TYPE 'A'/'X' terminates the program; raise an exception instead."));
			}
			if (first.equals("READ") && second.equals("TABLE") && code.contains(" WITH KEY ")
					&& !code.contains("BINARY SEARCH")) {
				out.add(new Finding(line, "read_table", Severity.INFO,
						"Consider a table expression itab[ key = … ] or line_exists( ) instead of READ TABLE … WITH KEY."));
			}
		}
		out.sort(Comparator.comparingInt(Finding::line));
		return out;
	}

	/** A SELECT that opens a loop: an ENDSELECT follows before the routine ends. */
	private static boolean isLoopSelect(List<Statement> statements, int index) {
		int depth = 0;
		for (int j = index + 1; j < statements.size(); j++) {
			String f = firstWord(statements.get(j));
			switch (f) {
			case "SELECT" -> depth++;
			case "ENDSELECT" -> {
				if (depth == 0) {
					return true;
				}
				depth--;
			}
			case "ENDMETHOD", "ENDFORM", "ENDFUNCTION", "ENDMODULE" -> {
				return false;
			}
			default -> {
				// continue
			}
			}
		}
		return false;
	}

	/** sy-subrc, IS INITIAL or the target variable is checked within the next three statements. */
	private static boolean subrcChecked(List<Statement> statements, int index, String selectCode) {
		Matcher m = INTO_TARGET.matcher(selectCode);
		String target = m.find() ? m.group(1) : null;
		for (int j = index + 1; j < Math.min(statements.size(), index + 4); j++) {
			String t = statements.get(j).text().toUpperCase(Locale.ROOT);
			if (t.contains("SY-SUBRC") || t.contains("IS INITIAL") || t.contains("IS NOT INITIAL")
					|| t.contains("LINE_EXISTS")) {
				return true;
			}
			String f = firstWord(statements.get(j));
			if (target != null && (f.equals("IF") || f.equals("CHECK") || f.equals("ASSERT") || f.equals("ELSEIF"))
					&& t.matches(".*\\b" + Pattern.quote(target) + "\\b.*")) {
				return true;
			}
		}
		return false;
	}

	private static String firstWord(Statement st) {
		List<String> w = st.words(1);
		return w.isEmpty() ? "" : w.get(0).replace(":", "");
	}

	private static int[] lineStarts(String s) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	private static int lineOf(int[] starts, int offset) {
		int lo = 0;
		int hi = starts.length - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (starts[mid] <= offset) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo + 1;
	}

	/** Findings as text for the model or the user; {@code "No findings."} when clean. */
	public static String format(List<Finding> findings) {
		if (findings.isEmpty()) {
			return "No findings.";
		}
		StringBuilder sb = new StringBuilder();
		for (Finding f : findings) {
			sb.append(f.format()).append('\n');
		}
		return sb.toString();
	}
}

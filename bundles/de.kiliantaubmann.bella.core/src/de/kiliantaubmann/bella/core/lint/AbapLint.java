package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.conventions.NamingRules;

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

	/**
	 * The system the code is for. Release rules apply only when the release is known.
	 *
	 * @param release SAP_BASIS release as a number, e.g. 702, 750, 816; 0 when unknown
	 * @param cloud   ABAP Cloud (BTP ABAP Environment or a cloud development package)
	 */
	public record Target(int release, boolean cloud) {

		public static final Target UNKNOWN = new Target(0, false);

		/** From the SAP_BASIS release ADT reports, e.g. "758" or "7.50"; anything else counts as unknown. */
		public static Target of(String basisRelease, boolean cloud) {
			String digits = basisRelease == null ? "" : basisRelease.replaceAll("[^0-9]", "");
			int release = digits.length() >= 3 ? Integer.parseInt(digits.substring(0, 3)) : 0;
			return new Target(release, cloud);
		}

		/** Whether the system is known to be older than {@code release}; ABAP Cloud has every feature. */
		boolean below(int minimum) {
			return !cloud && release > 0 && release < minimum;
		}

		/** e.g. "SAP_BASIS 7.02". */
		String label() {
			return cloud ? "ABAP Cloud" : "SAP_BASIS " + release / 100 + "." + String.format("%02d", release % 100);
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

	private static final Pattern INTERNAL_SCREEN_TEXT = Pattern.compile("%_\\S+_%_APP_%");
	/** Statements whose literals the user sees: WRITE, MESSAGE with a text, selection screen comments and titles. */
	private static final Pattern TEXT_OUTPUT = Pattern.compile(
			"^(?:WRITE\\b|MESSAGE\\s+''|SELECTION-SCREEN\\b.*\\b(?:COMMENT|TITLE)\\b)");
	/** A character literal with a letter that is not followed by a text symbol id {@code (nnn)}. */
	private static final Pattern BARE_TEXT_LITERAL = Pattern.compile(
			"(?:'(?:[^']|'')*\\p{L}(?:[^']|'')*'|`(?:[^`]|``)*\\p{L}(?:[^`]|``)*`)(?!\\s*\\(\\w{1,3}\\))");

	private static final Pattern MESSAGE_TYPE = Pattern.compile("(?i)\\b(?:TYPE|LIKE)\\s+'[A-Z]'");

	private AbapLint() {
	}

	/** Whether the statement shows a text literal (or a string template with text) without a text symbol. */
	static boolean hasTextLiteral(String statement) {
		// the message type of MESSAGE … TYPE 'E' DISPLAY LIKE 'W' is no text
		statement = MESSAGE_TYPE.matcher(statement).replaceAll("");
		if (BARE_TEXT_LITERAL.matcher(statement).find()) {
			return true;
		}
		String templates = statement.replaceAll("'(?:[^']|'')*'|`(?:[^`]|``)*`", "''");
		Matcher m = Pattern.compile("\\|((?:[^|\\\\]|\\\\.)*)\\|").matcher(templates);
		while (m.find()) {
			// the fixed text of a template, without its embedded expressions
			if (m.group(1).replaceAll("\\{[^}]*\\}", "").matches("(?s).*\\p{L}.*")) {
				return true;
			}
		}
		return false;
	}

	public static List<Finding> check(String source) {
		return check(source, NamingRules.NONE);
	}

	/** The style rules plus the project's naming rules (rule {@code naming}). */
	public static List<Finding> check(String source, NamingRules naming) {
		return check(source, naming, Target.UNKNOWN);
	}

	/**
	 * The style rules, the project's naming rules (rule {@code naming}) and the rules of the target system:
	 * syntax its release does not know, what ABAP Cloud forbids, and modern forms it offers. CDS data
	 * definitions get the CDS rules instead.
	 */
	public static List<Finding> check(String source, NamingRules naming, Target target) {
		List<Finding> out = new ArrayList<>();
		if (source == null || source.isBlank()) {
			return out;
		}
		if (target == null) {
			target = Target.UNKNOWN;
		}
		if (CdsLint.isCds(source)) {
			return CdsLint.check(source, target);
		}
		int[] lineStarts = lineStarts(source);
		List<Statement> statements = AbapStructureScanner.statements(source);
		List<String> codes = statements.stream().map(AbapLint::normalized).toList();
		int loopDepth = 0;
		int itabLoops = 0;
		int openSelect = -1;
		int routineStart = 0;
		for (int i = 0; i < statements.size(); i++) {
			Statement st = statements.get(i);
			String raw = st.text().trim().toUpperCase(Locale.ROOT);
			String code = normalized(st);
			List<String> w = List.of(code.split("\\s+"));
			String first = w.get(0).replace(":", "");
			String second = w.size() > 1 ? w.get(1) : "";
			int line = lineOf(lineStarts, st.start());

			switch (first) {
			case "METHOD", "FORM", "FUNCTION", "MODULE" -> {
				loopDepth = 0;
				itabLoops = 0;
				routineStart = i;
			}
			case "LOOP", "DO", "WHILE", "PROVIDE" -> loopDepth++;
			case "ENDLOOP", "ENDDO", "ENDWHILE", "ENDPROVIDE" -> loopDepth = Math.max(0, loopDepth - 1);
			default -> {
				// no block change
			}
			}

			boolean inLoop = loopDepth > 0 || openSelect >= 0;
			if (first.equals("LOOP")) {
				if (itabLoops > 0 && code.contains(" WHERE ") && !code.matches("LOOP\\s+AT\\s+GROUP\\b.*")) {
					out.add(new Finding(line, "nested_loop_where", Severity.INFO,
							"LOOP … WHERE inside a LOOP scans the inner table once per outer row unless it is a SORTED "
									+ "or HASHED table (or has a secondary key) on the WHERE fields."));
				}
				itabLoops++;
			} else if (first.equals("ENDLOOP")) {
				itabLoops = Math.max(0, itabLoops - 1);
			}
			if (first.equals("COMMIT") && inLoop) {
				out.add(new Finding(line, "commit_in_loop", Severity.WARNING,
						"COMMIT WORK inside a loop; commit once after the loop (one LUW) unless the loop is a deliberate "
								+ "package processing."));
			}
			if (first.equals("CALL") && second.equals("FUNCTION") && inLoop && code.contains(" DESTINATION ")
					&& !code.contains("STARTING NEW TASK")) {
				out.add(new Finding(line, "rfc_in_loop", Severity.WARNING,
						"Synchronous RFC inside a loop costs one round trip per iteration; collect the data and call once."));
			}

			// ---- Clean ABAP
			if (raw.matches("(?s).*(?:=|<>|\\bEQ|\\bNE)\\s*'X'(?:\\s|\\.|\\)|$).*")
					|| raw.matches("(?s).*(?:=|<>|\\bEQ|\\bNE)\\s*SPACE(?:\\s|\\.|\\)|$).*")
					&& raw.matches("(?s).*\\b(?:IF|ELSEIF|CHECK|WHILE)\\b.*")) {
				out.add(new Finding(line, "boolean_literal", Severity.INFO,
						"Use abap_true / abap_false (and xsdbool( )) instead of 'X' and space for booleans (Clean ABAP)."));
			}
			if (INTERNAL_SCREEN_TEXT.matcher(raw).find()) {
				out.add(new Finding(line, "internal_screen_text", Severity.ERROR,
						"Selection texts are set through SAP's internal screen fields %_…_%_APP_%; maintain them as "
								+ "selection texts in the text pool instead (adt_write_text_elements, part selections)."));
			}
			if (TEXT_OUTPUT.matcher(code).find() && hasTextLiteral(st.text())) {
				out.add(new Finding(line, "text_literal", Severity.WARNING,
						"Text literal shown to the user without a text symbol; use TEXT-nnn or 'text'(nnn) and maintain "
								+ "the text symbol (adt_write_text_elements), so it can be translated."));
			}
			if (first.equals("CONCATENATE")) {
				out.add(new Finding(line, "concatenate", Severity.INFO,
						"Use a string template |…{ x }…| or && instead of CONCATENATE (Clean ABAP)."));
			}
			if ((first.equals("METHODS") || first.equals("CLASS-METHODS")) && importingCount(code) > 3) {
				out.add(new Finding(line, "too_many_importing", Severity.INFO, "Method has " + importingCount(code)
						+ " IMPORTING parameters; aim for at most 3 (Clean ABAP), e.g. pass a structure or split the method."));
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
				if (code.contains("FOR ALL ENTRIES")) {
					Matcher fae = FAE_TABLE.matcher(code);
					if (fae.find() && !emptyChecked(statements, routineStart, i, fae.group(1))) {
						out.add(new Finding(line, "fae_empty_check", Severity.WARNING,
								"FOR ALL ENTRIES IN " + fae.group(1).toLowerCase(Locale.ROOT) + " without checking that the "
										+ "table is not empty; an empty table makes the WHERE condition void and reads "
										+ "the whole table."));
					}
				} else if (!code.contains(" WHERE ") && !code.matches(".*\\bUP\\s+TO\\b.*") && !aggregateOnly(code)) {
					out.add(new Finding(line, "select_no_where", Severity.INFO,
							"SELECT without WHERE reads the whole table; fine for small buffered customizing tables, "
									+ "otherwise restrict it or add UP TO n ROWS."));
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
				if (opensLoop(code) && isLoopSelect(statements, i)) {
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
		structureRules(statements, lineStarts, out);
		ReleaseRules.apply(statements, codes, lineStarts, target, out);
		lineRules(source, out);
		out.addAll(NamingCheck.check(source, naming, lineStarts));
		out.sort(Comparator.comparingInt(Finding::line));
		return out;
	}

	// ---- rules on blocks and routines (after abaplint's rule set, without a parser)

	private static final int MAX_NESTING = 6;
	private static final int MAX_METHOD_STATEMENTS = 110;
	private static final int MAX_COMPLEXITY = 25;
	private static final int MAX_LINE = 120;
	private static final Set<String> TERMINATORS = Set.of("RETURN", "CONTINUE", "EXIT");
	private static final Set<String> OPENERS = Set.of("IF", "CASE", "LOOP", "DO", "WHILE", "TRY",
			"PROVIDE");
	private static final Pattern INTO_HOST = Pattern.compile(
			"\\b(?:INTO|APPENDING)\\s+(?:CORRESPONDING\\s+FIELDS\\s+OF\\s+)?(?:TABLE\\s+)?([^\\s,]+)");

	private static void structureRules(List<Statement> statements, int[] lineStarts, List<Finding> out) {
		Deque<Set<String>> conditions = new ArrayDeque<>();
		Deque<String> beginNames = new ArrayDeque<>();
		Set<String> testClasses = new HashSet<>();
		String currentClass = "";
		int routineStart = -1;
		int depth = 0;
		boolean nestingReported = false;
		boolean inMacro = false;
		for (int i = 0; i < statements.size(); i++) {
			Statement st = statements.get(i);
			String code = normalized(st);
			String[] w = code.split("\\s+");
			String first = w[0].replace(":", "");
			String second = w.length > 1 ? w[1] : "";
			int line = lineOf(lineStarts, st.start());

			if (first.equals("DEFINE")) {
				inMacro = true;
			} else if (first.equals("END-OF-DEFINITION")) {
				inMacro = false;
				continue;
			}
			if (inMacro) {
				continue;
			}

			// obsolete statements beyond the main list
			if (List.of("DEMAND", "SUPPLY", "LOCAL").contains(first) || first.equals("ON") && second.equals("CHANGE")) {
				out.add(new Finding(line, "obsolete_statement", Severity.WARNING, (first.equals("ON") ? "ON CHANGE OF"
						: first) + " is obsolete."));
			}

			// classes for testing are left out of the method length rule
			if (first.equals("CLASS") && code.contains(" DEFINITION") && code.contains(" FOR TESTING")) {
				testClasses.add(second);
			}
			if (first.equals("CLASS") && code.contains(" IMPLEMENTATION")) {
				currentClass = second;
			}

			// a statement after RETURN, RAISE EXCEPTION … in the same block is never reached
			if (i + 1 < statements.size() && terminates(w)) {
				String next = firstWord(statements.get(i + 1));
				if (!closesBlock(next)) {
					out.add(new Finding(lineOf(lineStarts, statements.get(i + 1).start()), "unreachable_code",
							Severity.WARNING, "Statement after " + first + " is never reached."));
				}
			}

			// the same condition twice in one IF … ELSEIF chain
			if (first.equals("IF")) {
				Set<String> chain = new HashSet<>();
				chain.add(code.substring(2).trim());
				conditions.push(chain);
			} else if (first.equals("ELSEIF") && !conditions.isEmpty()
					&& !conditions.peek().add(code.substring(6).trim())) {
				out.add(new Finding(line, "identical_conditions", Severity.WARNING,
						"This ELSEIF repeats a condition of the same IF; the branch is never reached."));
			} else if (first.equals("ENDIF") && !conditions.isEmpty()) {
				conditions.pop();
			}

			// BEGIN OF x … END OF x
			for (String part : code.split(",")) {
				Matcher b = BEGIN_END.matcher(part);
				while (b.find()) {
					String name = blockName(b.group(2));
					if (b.group(1).equals("BEGIN")) {
						beginNames.push(name);
					} else if (!beginNames.isEmpty()) {
						String open = beginNames.pop();
						if (!open.equals(name) && !name.isEmpty() && !open.isEmpty()) {
							out.add(new Finding(line, "begin_end_names", Severity.ERROR,
									"END OF " + name + " closes BEGIN OF " + open + "."));
						}
					}
				}
			}

			// routines: nesting, length, complexity, unused variables
			if (List.of("METHOD", "FORM", "FUNCTION", "MODULE").contains(first)) {
				routineStart = i;
				depth = 0;
				nestingReported = false;
				continue;
			}
			if (List.of("ENDMETHOD", "ENDFORM", "ENDFUNCTION", "ENDMODULE").contains(first) && routineStart >= 0) {
				routine(statements, routineStart, i, lineStarts, testClasses.contains(currentClass), out);
				routineStart = -1;
				continue;
			}
			if (routineStart >= 0) {
				if (OPENERS.contains(first) || first.equals("SELECT") && opensLoop(code)
						&& isLoopSelect(statements, i)) {
					depth++;
					if (depth > MAX_NESTING && !nestingReported) {
						nestingReported = true;
						out.add(new Finding(line, "nesting", Severity.INFO, "Blocks are nested " + depth
								+ " deep; extract methods or return early (at most " + MAX_NESTING + ")."));
					}
				} else if (List.of("ENDIF", "ENDCASE", "ENDLOOP", "ENDDO", "ENDWHILE", "ENDTRY", "ENDPROVIDE",
						"ENDSELECT").contains(first)) {
					depth = Math.max(0, depth - 1);
				}
			}

			// strict Open SQL: once one host variable is escaped with @, all must be
			if ((first.equals("SELECT") || first.equals("WITH")) && st.text().contains("@")) {
				Matcher m = INTO_HOST.matcher(code);
				if (m.find() && !m.group(1).startsWith("@") && !m.group(1).startsWith("(")) {
					out.add(new Finding(line, "sql_escape_host_variables", Severity.WARNING,
							"The target " + m.group(1).toLowerCase(Locale.ROOT)
									+ " needs @ like the other host variables of this statement."));
				}
			}
		}
		keywordCase(statements, lineStarts, out);
	}

	private static final Pattern BEGIN_END = Pattern.compile("\\b(BEGIN|END)\\s+OF\\s+((?:\\S+\\s*){1,3})");

	/** The name after BEGIN OF / END OF, without BLOCK, ENUM, MESH, SCREEN, TABBED and COMMON PART. */
	private static String blockName(String rest) {
		List<String> words = new ArrayList<>(List.of(rest.trim().split("\\s+")));
		while (!words.isEmpty() && List.of("BLOCK", "ENUM", "MESH", "SCREEN", "TABBED", "COMMON", "PART")
				.contains(words.get(0))) {
			words.remove(0);
		}
		return words.isEmpty() ? "" : words.get(0).replaceAll("[.:]$", "");
	}

	private static boolean terminates(String[] w) {
		String first = w[0];
		if (TERMINATORS.contains(first) && w.length == 1) {
			return true;
		}
		if (first.equals("RAISE")) {
			String second = w.length > 1 ? w[1] : "";
			return !second.equals("EVENT") && !second.equals("RESUMABLE") && !second.equals("ENTITY");
		}
		return first.equals("LEAVE") && w.length > 1 && w[1].equals("PROGRAM");
	}

	private static boolean closesBlock(String first) {
		return first.startsWith("END") || List.of("ELSE", "ELSEIF", "WHEN", "CATCH", "CLEANUP", "METHOD", "FORM",
				"MODULE", "FUNCTION", "CLASS", "START-OF-SELECTION", "INITIALIZATION", "AT", "TOP-OF-PAGE",
				"LOAD-OF-PROGRAM", "GET", "DEFINE", "INCLUDE").contains(first);
	}

	private static void routine(List<Statement> statements, int start, int end, int[] lineStarts, boolean testClass,
			List<Finding> out) {
		Statement header = statements.get(start);
		int line = lineOf(lineStarts, header.start());
		String name = normalized(header).split("\\s+").length > 1 ? normalized(header).split("\\s+")[1] : "";
		int count = end - start - 1;
		if (!testClass && count > MAX_METHOD_STATEMENTS) {
			out.add(new Finding(line, "method_length", Severity.INFO, name.toLowerCase(Locale.ROOT) + " has " + count
					+ " statements; split it into smaller methods (at most " + MAX_METHOD_STATEMENTS + ")."));
		}
		int complexity = 1;
		for (int j = start + 1; j < end; j++) {
			String code = normalized(statements.get(j));
			String first = code.split("\\s+")[0];
			if (List.of("IF", "ELSEIF", "LOOP", "DO", "WHILE", "CATCH", "CHECK").contains(first)
					|| first.equals("WHEN") && !code.startsWith("WHEN OTHERS")) {
				complexity++;
			}
			if (List.of("IF", "ELSEIF", "WHILE", "CHECK").contains(first)) {
				Matcher m = Pattern.compile("\\b(?:AND|OR)\\b").matcher(code);
				while (m.find()) {
					complexity++;
				}
			}
		}
		if (complexity > MAX_COMPLEXITY) {
			out.add(new Finding(line, "cyclomatic_complexity", Severity.INFO, name.toLowerCase(Locale.ROOT)
					+ " has a cyclomatic complexity of " + complexity + "; split it (at most " + MAX_COMPLEXITY + ")."));
		}
		unusedVariables(statements, start, end, lineStarts, out);
	}

	private static final Pattern DECLARED = Pattern.compile("^([A-Z_][A-Z0-9_]*|<[A-Z0-9_]+>)\\b");
	private static final Pattern QUOTED = Pattern.compile("'(?:[^']|'')*'|`(?:[^`]|``)*`");

	/** Local DATA and FIELD-SYMBOLS of a routine that no other statement of it mentions. */
	private static void unusedVariables(List<Statement> statements, int start, int end, int[] lineStarts,
			List<Finding> out) {
		Map<String, Integer> declared = new LinkedHashMap<>();
		Map<String, Integer> declaredIn = new HashMap<>();
		// components between BEGIN OF and END OF are used as struct-comp, not on their own
		int structDepth = 0;
		for (int j = start + 1; j < end; j++) {
			String code = normalized(statements.get(j));
			String first = code.split("\\s+")[0].replace(":", "");
			if (!first.equals("DATA") && !first.equals("FIELD-SYMBOLS")) {
				continue;
			}
			String rest = code.substring(code.indexOf(first) + first.length()).replaceFirst("^\\s*:", "").trim();
			for (String part : topLevelParts(rest)) {
				String p = part.trim();
				if (p.startsWith("BEGIN OF")) {
					structDepth++;
					continue;
				}
				if (p.startsWith("END OF")) {
					structDepth = Math.max(0, structDepth - 1);
					continue;
				}
				if (structDepth > 0) {
					continue;
				}
				Matcher m = DECLARED.matcher(p);
				if (m.find()) {
					declared.put(m.group(1), lineOf(lineStarts, statements.get(j).start()));
					declaredIn.put(m.group(1), j);
				}
			}
		}
		if (declared.isEmpty()) {
			return;
		}
		// prepared once per routine, not once per variable
		List<String> texts = new ArrayList<>(end - start);
		for (int j = start + 1; j < end; j++) {
			texts.add(QUOTED.matcher(statements.get(j).text()).replaceAll("''").toUpperCase(Locale.ROOT));
		}
		for (Map.Entry<String, Integer> e : declared.entrySet()) {
			String name = e.getKey();
			Pattern use = Pattern.compile("(?<![\\w<>/~-])" + Pattern.quote(name) + "(?![\\w>])");
			boolean used = false;
			for (int j = start + 1; j < end && !used; j++) {
				String text = texts.get(j - start - 1);
				if (j == declaredIn.get(name) || !text.contains(name)) {
					continue;
				}
				used = use.matcher(text).find();
			}
			if (!used) {
				out.add(new Finding(e.getValue(), "unused_variables", Severity.WARNING,
						name.toLowerCase(Locale.ROOT) + " is declared but never used."));
			}
		}
	}

	/** Parts of a chained declaration, split at commas outside parentheses. */
	private static List<String> topLevelParts(String s) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int from = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth = Math.max(0, depth - 1);
			} else if (c == ',' && depth == 0) {
				parts.add(s.substring(from, i));
				from = i + 1;
			}
		}
		parts.add(s.substring(from));
		return parts;
	}

	/** Keywords in one case: reports statements whose first keyword breaks the case the rest of the code uses. */
	private static void keywordCase(List<Statement> statements, int[] lineStarts, List<Finding> out) {
		int upper = 0;
		int lower = 0;
		int firstLower = -1;
		int firstUpper = -1;
		for (Statement st : statements) {
			String word = st.text().trim().split("[\\s.:(]+")[0];
			if (!word.matches("[A-Za-z][A-Za-z-]*") || word.length() < 2) {
				continue;
			}
			if (word.equals(word.toUpperCase(Locale.ROOT))) {
				upper++;
				firstUpper = firstUpper < 0 ? lineOf(lineStarts, st.start()) : firstUpper;
			} else if (word.equals(word.toLowerCase(Locale.ROOT))) {
				lower++;
				firstLower = firstLower < 0 ? lineOf(lineStarts, st.start()) : firstLower;
			}
		}
		int total = upper + lower;
		if (total >= 5 && Math.min(upper, lower) > 0 && Math.max(upper, lower) * 5 >= total * 4) {
			boolean upperWins = upper > lower;
			out.add(new Finding(upperWins ? firstLower : firstUpper, "keyword_case", Severity.INFO,
					Math.min(upper, lower) + " statements start with " + (upperWins ? "lower" : "upper")
							+ "-case keywords while the rest uses " + (upperWins ? "upper" : "lower")
							+ " case; format the code with the pretty printer (adt_format)."));
		}
	}

	/** Long lines, trailing whitespace and runs of blank lines. */
	private static void lineRules(String source, List<Finding> out) {
		String[] lines = source.replace("\r\n", "\n").split("\n", -1);
		int trailing = 0;
		int firstTrailing = 0;
		int blank = 0;
		for (int i = 0; i < lines.length; i++) {
			String l = lines[i];
			if (l.length() > MAX_LINE) {
				out.add(new Finding(i + 1, "line_length", Severity.INFO,
						"Line has " + l.length() + " characters; keep lines up to " + MAX_LINE + "."));
			}
			if (!l.isEmpty() && Character.isWhitespace(l.charAt(l.length() - 1)) && !l.isBlank()) {
				trailing++;
				firstTrailing = firstTrailing == 0 ? i + 1 : firstTrailing;
			}
			blank = l.isBlank() ? blank + 1 : 0;
			if (blank == 4) {
				out.add(new Finding(i - 2, "sequential_blank", Severity.INFO,
						"Four or more blank lines in a row; one is enough."));
			}
		}
		if (trailing > 0) {
			out.add(new Finding(firstTrailing, "whitespace_end", Severity.INFO,
					trailing + (trailing == 1 ? " line ends" : " lines end") + " with spaces or tabs."));
		}
	}

	/** A SELECT that opens a loop: an ENDSELECT follows before the routine ends. */
	private static boolean isLoopSelect(List<Statement> statements, int index) {
		int depth = 0;
		for (int j = index + 1; j < statements.size(); j++) {
			String f = firstWord(statements.get(j));
			switch (f) {
			case "SELECT" -> {
				if (opensLoop(normalized(statements.get(j)))) {
					depth++;
				}
			}
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

	/** Statement text upper-cased, literals blanked, without the final period. */
	static String normalized(Statement st) {
		String code = LITERAL.matcher(st.text()).replaceAll("''").trim().toUpperCase(Locale.ROOT);
		return code.endsWith(".") ? code.substring(0, code.length() - 1).trim() : code;
	}

	/**
	 * Whether a SELECT needs an ENDSELECT: not SINGLE, not INTO/APPENDING TABLE,
	 * and not an aggregate-only select without GROUP BY (one result row).
	 */
	static boolean opensLoop(String selectCode) {
		String c = selectCode.trim();
		if (c.matches("SELECT\\s+SINGLE\\b.*")
				|| c.matches(".*\\b(?:INTO|APPENDING)\\s+(?:CORRESPONDING\\s+FIELDS\\s+OF\\s+)?TABLE\\b.*")) {
			return false;
		}
		return !aggregateOnly(c);
	}

	private static final Pattern AGGREGATE = Pattern.compile("\\b(?:COUNT|MAX|MIN|SUM|AVG)\\s*\\(");

	/** Only aggregate columns and no GROUP BY: exactly one result row. */
	static boolean aggregateOnly(String selectCode) {
		if (selectCode.contains("GROUP BY")) {
			return false;
		}
		Matcher m = Pattern.compile("^(?:WITH\\b.*?\\b)?SELECT\\s+(?:SINGLE\\s+)?(?:DISTINCT\\s+)?(.*?)\\s+(?:FROM|INTO)\\b")
				.matcher(selectCode);
		String fields = m.find() ? m.group(1) : "";
		if (selectCode.matches(".*\\bFIELDS\\b.*")) {
			Matcher f = Pattern.compile("\\bFIELDS\\s+(.*?)\\s+(?:WHERE|INTO|GROUP|ORDER|UP)\\b").matcher(selectCode);
			fields = f.find() ? f.group(1) : fields;
		}
		if (!AGGREGATE.matcher(fields).find()) {
			return false;
		}
		// every comma-separated column is an aggregate
		for (String col : fields.split(",(?![^(]*\\))")) {
			if (!AGGREGATE.matcher(col).find()) {
				return false;
			}
		}
		return true;
	}

	private static final Pattern FAE_TABLE = Pattern.compile("FOR\\s+ALL\\s+ENTRIES\\s+IN\\s+@?([A-Z0-9_<>~-]+)");

	/** Whether the driver table of FOR ALL ENTRIES is checked for emptiness earlier in the routine. */
	static boolean emptyChecked(List<Statement> statements, int from, int select, String table) {
		String t = Pattern.quote(table.replaceAll("\\[\\]$", ""));
		Pattern check = Pattern.compile("(?s).*(?:\\b" + t + "(?:\\[\\])?\\s+IS\\s+(?:NOT\\s+)?INITIAL"
				+ "|\\bLINES\\s*\\(\\s*" + t + "(?:\\[\\])?\\s*\\)).*");
		for (int j = Math.max(0, from); j < select; j++) {
			if (check.matcher(normalized(statements.get(j))).matches()) {
				return true;
			}
		}
		return false;
	}

	/** Number of IMPORTING parameters in a METHODS declaration. */
	static int importingCount(String methodsCode) {
		Matcher m = Pattern.compile("\\bIMPORTING\\b(.*?)(?:\\b(?:EXPORTING|CHANGING|RETURNING|RAISING|EXCEPTIONS)\\b|$)")
				.matcher(methodsCode);
		if (!m.find()) {
			return 0;
		}
		Matcher typed = Pattern.compile("\\b(?:TYPE|LIKE)\\b").matcher(m.group(1));
		int n = 0;
		while (typed.find()) {
			n++;
		}
		return n;
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

	static int[] lineStarts(String s) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	static int lineOf(int[] starts, int offset) {
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

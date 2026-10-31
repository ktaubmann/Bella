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

		private static final Pattern RELEASE = Pattern.compile("(?i)(?:SAP_BASIS\\s*)?([7-9])\\.?(\\d{2})(?!\\d)");

		/**
		 * From the SAP_BASIS release at the start of the text, e.g. "758", "7.50", "7.40 SP05" or "SAP_BASIS 758,
		 * on-premise"; what follows it is ignored. Anything else (a product release like "S/4HANA 2022" or
		 * "2022") counts as unknown, so it can never turn modern code into errors.
		 */
		public static Target of(String basisRelease, boolean cloud) {
			Matcher m = RELEASE.matcher(basisRelease == null ? "" : basisRelease.trim());
			int release = m.lookingAt() ? Integer.parseInt(m.group(1) + m.group(2)) : 0;
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
		boolean inRoutine = false;
		// tables of the enclosing LOOP AT statements, "" for DO and WHILE
		Deque<String> loopTables = new ArrayDeque<>();
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
				inRoutine = true;
				loopTables.clear();
			}
			case "ENDMETHOD", "ENDFORM", "ENDFUNCTION", "ENDMODULE" -> inRoutine = false;
			case "LOOP", "DO", "WHILE", "PROVIDE" -> {
				loopDepth++;
				Matcher at = LOOP_AT.matcher(code);
				loopTables.push(at.find() ? at.group(1) : "");
			}
			case "ENDLOOP", "ENDDO", "ENDWHILE", "ENDPROVIDE" -> {
				loopDepth = Math.max(0, loopDepth - 1);
				if (!loopTables.isEmpty()) {
					loopTables.pop();
				}
			}
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
			if (inLoop && changesDatabase(code, first, second)) {
				out.add(new Finding(line, "db_operation_in_loop", Severity.WARNING, first + " on a database table inside "
						+ "a loop runs once per iteration; collect the rows and change them with one statement "
						+ "(FROM TABLE) after the loop."));
			}
			if (first.equals("DELETE") && !loopTables.isEmpty()) {
				Matcher del = DELETE_INDEX_TABIX.matcher(code);
				if (del.matches() && loopTables.contains(del.group(1))) {
					out.add(new Finding(line, "delete_index_in_loop", Severity.WARNING, "DELETE "
							+ del.group(1).toLowerCase(Locale.ROOT) + " INDEX sy-tabix inside LOOP AT the same table; "
							+ "delete the rows with DELETE … WHERE instead of the loop."));
				}
			}
			if (inRoutine && loopDepth == 0 && openSelect < 0 && (first.equals("CHECK") && !second.equals("SELECT-OPTIONS")
					|| first.equals("EXIT") && w.size() == 1)) {
				out.add(new Finding(line, "exit_or_check", Severity.INFO, first + " outside a loop leaves the "
						+ "routine; use IF … RETURN instead (Clean ABAP)."));
			}
			if (SUBRC_STATEMENTS.contains(first + " " + second) || SUBRC_STATEMENTS.contains(first)
					|| first.equals("CALL") && second.equals("FUNCTION") && code.contains(" EXCEPTIONS ")) {
				if (needsSubrc(code, first) && !subrcNext(statements, i, code) && !code.matches(".*##(?:FM_)?SUBRC_OK\\b.*")
						&& !pseudoComment(source, st, "CI_SUBRC")) {
					out.add(new Finding(line, "check_subrc", Severity.WARNING, "sy-subrc is not checked after "
							+ (first.equals("CALL") ? "CALL FUNCTION with EXCEPTIONS" : first.equals("READ") ? "READ TABLE"
									: first.equals("OPEN") ? "OPEN DATASET" : first)
							+ "; check it in the next statement (or add \"#EC CI_SUBRC when the result does not matter)."));
				}
			}
			if (DB_STATEMENTS.contains(first) && (!first.equals("DELETE") || second.equals("FROM"))
					&& MANDT_FIELD.matcher(code).find() && !CLIENT_HANDLING.matcher(code).find()) {
				out.add(new Finding(line, "no_mandt_in_database_operations", Severity.WARNING,
						"ABAP SQL handles the client itself; remove MANDT from the statement (or use USING CLIENT)."));
			}
			if ((first.equals("METHODS") || first.equals("CLASS-METHODS"))) {
				for (String part : topLevelParts(code.substring(first.length()).replaceFirst("^\\s*:", ""))) {
					if (prefersReturning(part.trim())) {
						out.add(new Finding(line, "prefer_returning_to_exporting", Severity.INFO, "Method "
								+ part.trim().split("\\s+")[0].toLowerCase(Locale.ROOT) + " has one EXPORTING parameter "
								+ "and nothing else to return; use RETURNING (functional call) instead (Clean ABAP)."));
					}
				}
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
			int operators = List.of("IF", "ELSEIF", "WHILE", "CHECK").contains(first) ? logicalOperators(code) : 0;
			if (operators > MAX_CONDITION_OPERATORS) {
				out.add(new Finding(line, "complex_condition", Severity.INFO, "Condition joins " + (operators + 1)
						+ " parts with AND/OR; split it into well-named intermediate variables such as"
						+ " DATA(is_valid) = xsdbool( … ) (Clean ABAP)."));
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
				if (!target.below(750) && st.text().contains("@") && intoNotLast(code)) {
					out.add(new Finding(line, "strict_sql", Severity.WARNING, "In strict ABAP SQL (with @) the INTO or "
							+ "APPENDING clause goes last, after WHERE, GROUP BY, HAVING and ORDER BY."));
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
				boolean empty = next.equals("ENDTRY") || next.equals("CATCH") || next.equals("CLEANUP");
				// the pragma marks an intended empty handler; a cast is still better tested with IS INSTANCE OF
				if (empty && code.matches(".*\\bCX_SY_MOVE_CAST_ERROR\\b.*")) {
					out.add(new Finding(line, "try_cast_empty_catch", Severity.WARNING, "A down cast in TRY with an "
							+ "empty CATCH cx_sy_move_cast_error; test the type with IS INSTANCE OF before the cast instead."));
				} else if (empty && !code.contains("##NO_HANDLER")) {
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
		unusedMethods(statements, lineStarts, out);
		commentedCode(source, out);
		AbaplintRules.apply(source, statements, codes, lineStarts, target, out);
		ReleaseRules.apply(statements, codes, lineStarts, target, out);
		lineRules(source, out);
		out.addAll(NamingCheck.check(source, naming, lineStarts));
		out.sort(Comparator.comparingInt(Finding::line));
		return out;
	}

	// ---- rules on blocks and routines (after abaplint's rule set, without a parser)

	private static final int MAX_NESTING = 6;
	/** More AND/OR in one condition than a reader keeps in mind at once. */
	private static final int MAX_CONDITION_OPERATORS = 2;
	private static final Pattern LOGICAL_OPERATOR = Pattern.compile("\\b(?:AND|OR|EQUIV)\\b");
	private static final Pattern BETWEEN = Pattern.compile("\\bBETWEEN\\b");
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

	static boolean terminates(String[] w) {
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
		unusedTypes(statements, start, end, lineStarts, out);
	}

	/** Local TYPES of a routine that no other statement of it mentions; components of structures are not checked. */
	private static void unusedTypes(List<Statement> statements, int start, int end, int[] lineStarts,
			List<Finding> out) {
		Map<String, Integer> declared = new LinkedHashMap<>();
		Map<String, Integer> declaredIn = new HashMap<>();
		int structDepth = 0;
		for (int j = start + 1; j < end; j++) {
			String code = normalized(statements.get(j));
			String first = code.split("\\s+")[0].replace(":", "");
			if (!first.equals("TYPES")) {
				continue;
			}
			String rest = code.substring(code.indexOf(first) + first.length()).replaceFirst("^\\s*:", "").trim();
			for (String part : topLevelParts(rest)) {
				String p = part.trim();
				String name = null;
				if (p.startsWith("BEGIN OF")) {
					name = structDepth == 0 ? blockName(p.substring("BEGIN OF".length())) : null;
					structDepth++;
				} else if (p.startsWith("END OF")) {
					structDepth = Math.max(0, structDepth - 1);
				} else if (structDepth == 0) {
					Matcher m = DECLARED.matcher(p);
					name = m.find() ? m.group(1) : null;
				}
				if (name != null && !name.isEmpty()) {
					declared.put(name, lineOf(lineStarts, statements.get(j).start()));
					declaredIn.put(name, j);
				}
			}
		}
		for (Map.Entry<String, Integer> e : declared.entrySet()) {
			String name = e.getKey();
			Pattern use = Pattern.compile("(?<![\\w<>/~-])" + Pattern.quote(name) + "(?![\\w>])");
			boolean used = false;
			for (int j = start + 1; j < end && !used; j++) {
				String code = QUOTED.matcher(statements.get(j).text()).replaceAll("''").toUpperCase(Locale.ROOT);
				// END OF of a structure declared in its own statements names the type, too
				if (normalized(statements.get(j)).matches("TYPES:?\\s+END\\s+OF\\s+" + Pattern.quote(name))) {
					continue;
				}
				if (j == declaredIn.get(name)) {
					// a chain may use the type after declaring it: TYPES: ty_a TYPE i, ty_t TYPE TABLE OF ty_a.
					code = code.replaceAll("\\b(?:BEGIN|END)\\s+OF\\s+" + Pattern.quote(name) + "\\b", "")
							.replaceAll("(?:^TYPES:?|,)\\s*" + Pattern.quote(name) + "(?=\\s+(?:TYPE|LIKE)\\b)", ",");
				}
				used = code.contains(name) && use.matcher(code).find();
			}
			if (!used) {
				out.add(new Finding(e.getValue(), "unused_types", Severity.WARNING,
						"Type " + name.toLowerCase(Locale.ROOT) + " is declared but never used."));
			}
		}
	}

	private static final Pattern DECLARED = Pattern.compile("^([A-Z_][A-Z0-9_]*\\b|<[A-Z0-9_]+>)");
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
	static List<String> topLevelParts(String s) {
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

	private static final Set<String> ASSIGNMENT = Set.of("=", "?=", "+=", "-=", "*=", "/=", "&&=");

	/** Keywords in one case: reports statements whose first keyword breaks the case the rest of the code uses. */
	private static void keywordCase(List<Statement> statements, int[] lineStarts, List<Finding> out) {
		int upper = 0;
		int lower = 0;
		int firstLower = -1;
		int firstUpper = -1;
		for (Statement st : statements) {
			String[] tokens = st.text().trim().split("\\s+");
			// method calls (display( ), lo_x->run( )) and assignments (result-flag = …) start with a name, not a keyword
			if (tokens[0].endsWith("(") || tokens[0].contains("->") || tokens[0].contains("=>")
					|| tokens.length > 1 && ASSIGNMENT.contains(tokens[1])) {
				continue;
			}
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

	/** AND, OR and EQUIV in a normalized statement; the AND of BETWEEN … AND … does not count. */
	static int logicalOperators(String code) {
		return (int) (LOGICAL_OPERATOR.matcher(code).results().count() - BETWEEN.matcher(code).results().count());
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

	// ---- rules added after abaplint's rule set (rules.abaplint.org), without a parser

	private static final Pattern LOOP_AT = Pattern.compile("^LOOP\\s+AT\\s+(?!GROUP\\b|SCREEN\\b)@?([A-Z0-9_/<>~-]+)");
	private static final Pattern DELETE_INDEX_TABIX = Pattern.compile("^DELETE\\s+([A-Z0-9_/<>~-]+)\\s+INDEX\\s+SY-TABIX$");
	/** Statements whose outcome only sy-subrc tells; CALL FUNCTION counts when it has EXCEPTIONS. */
	private static final Set<String> SUBRC_STATEMENTS = Set.of("READ TABLE", "ASSIGN", "AUTHORITY-CHECK",
			"OPEN DATASET", "FIND");
	private static final Set<String> DB_STATEMENTS = Set.of("SELECT", "WITH", "UPDATE", "DELETE");
	private static final Pattern MANDT_FIELD = Pattern.compile("(?<![\\w/@-])(?:[A-Z0-9_/]+~)?MANDT(?![\\w-])");
	private static final Pattern CLIENT_HANDLING = Pattern.compile(
			"\\bCLIENT\\s+SPECIFIED\\b|\\bUSING\\s+(?:ALL\\s+)?CLIENTS?\\b");
	private static final Pattern FS_TARGET = Pattern.compile("\\b(?:ASSIGNING|TO)\\s+(<[A-Z0-9_]+>)");
	private static final Pattern REF_TARGET = Pattern.compile("\\bREFERENCE\\s+INTO\\s+(?:DATA\\()?([A-Z0-9_]+)");
	private static final Pattern EXPORTING_PART = Pattern.compile(
			"\\bEXPORTING\\b(.*?)(?:\\b(?:CHANGING|RETURNING|RAISING|EXCEPTIONS)\\b|$)");
	/** Types a RETURNING parameter cannot have. */
	private static final Pattern GENERIC_TYPE = Pattern.compile("(?:ANY|DATA|CLIKE|CSEQUENCE|XSEQUENCE|NUMERIC|SIMPLE"
			+ "|C|N|P|X|DECFLOAT|OBJECT|(?:ANY|INDEX|STANDARD|SORTED|HASHED)\\s+TABLE|TABLE)");

	/** Whether the statement's outcome has to be checked; a plain ASSIGN of a variable cannot fail. */
	private static boolean needsSubrc(String code, String first) {
		if (first.equals("ASSIGN")) {
			int to = code.indexOf(" TO ");
			String from = (to < 0 ? code : code.substring(0, to)).substring("ASSIGN".length()).trim();
			return from.startsWith("(") || from.startsWith("COMPONENT") || from.contains("[") || from.contains("( ")
					|| from.contains("->(") || from.contains("=>(");
		}
		if (first.equals("CALL")) {
			// EXCEPTIONS x = 0 ignores the exception on purpose
			Matcher values = Pattern.compile("=\\s*(\\d+)").matcher(code.substring(code.indexOf(" EXCEPTIONS ")));
			while (values.find()) {
				if (!values.group(1).equals("0")) {
					return true;
				}
			}
			return false;
		}
		return !(first.equals("FIND") && code.contains(" MATCH COUNT "));
	}

	/**
	 * Whether the next statement (after ENDIFs) checks the outcome: sy-subrc, or for a field symbol or data
	 * reference target whether it is assigned or bound (as abaplint's check_subrc).
	 */
	private static boolean subrcNext(List<Statement> statements, int index, String code) {
		Matcher fs = FS_TARGET.matcher(code);
		String fsName = fs.find() ? fs.group(1) : null;
		Matcher ref = REF_TARGET.matcher(code);
		String refName = ref.find() ? ref.group(1) : null;
		for (int j = index + 1; j < statements.size(); j++) {
			String next = normalized(statements.get(j));
			if (next.equals("ENDIF")) {
				continue;
			}
			return next.contains("SY-SUBRC") || next.contains("ASSERT_SUBRC")
					|| fsName != null && next.matches(".*" + Pattern.quote(fsName) + "\\s+IS\\s+(?:NOT\\s+)?ASSIGNED.*")
					|| refName != null && next.matches(".*\\b" + Pattern.quote(refName) + "\\s+IS\\s+(?:NOT\\s+)?BOUND.*");
		}
		return false;
	}

	/** Whether a pseudo comment {@code "#EC <name>} follows the statement on its last line. */
	private static boolean pseudoComment(String source, Statement st, String name) {
		int eol = source.indexOf('\n', Math.max(st.start(), st.end() - 1));
		String tail = source.substring(st.start(), eol < 0 ? source.length() : eol).toUpperCase(Locale.ROOT);
		return tail.matches("(?s).*\"\\s*#EC\\s+" + name + "\\b.*");
	}

	/**
	 * Whether a statement changes a database table. Without types, statements that may as well change an
	 * internal table (MODIFY itab FROM wa, DELETE itab …) count only with strict SQL (@) or FROM TABLE.
	 */
	static boolean changesDatabase(String code, String first, String second) {
		if (Set.of("ENTITIES", "ENTITY").contains(second)) {
			return false; // EML
		}
		boolean strict = code.contains("@") || code.contains(" FROM TABLE ");
		return switch (first) {
		case "UPDATE" -> true;
		case "DELETE" -> second.equals("FROM") && !code.matches("DELETE FROM (?:MEMORY|SHARED)\\b.*")
				|| strict && !second.equals("ADJACENT");
		case "INSERT" -> !Set.of("REPORT", "TEXTPOOL", "LINES", "INITIAL").contains(second)
				&& (!code.contains(" INTO ") || code.matches("INSERT INTO \\S+ VALUES\\b.*"));
		case "MODIFY" -> strict && !Set.of("SCREEN", "LINE", "TABLE", "CURRENT").contains(second);
		default -> false;
		};
	}

	/** A method declaration with exactly one EXPORTING parameter of a complete type and nothing else to return. */
	static boolean prefersReturning(String declaration) {
		String d = " " + declaration + " ";
		if (d.matches(".*\\b(?:RETURNING|CHANGING|REDEFINITION|FOR\\s+EVENT|FOR\\s+TESTING|ABSTRACT\\s+EVENTS)\\b.*")) {
			return false;
		}
		Matcher m = EXPORTING_PART.matcher(declaration);
		if (!m.find()) {
			return false;
		}
		String part = m.group(1).trim();
		Matcher typed = Pattern.compile("\\b(?:TYPE|LIKE)\\b").matcher(part);
		int n = 0;
		while (typed.find()) {
			n++;
		}
		if (n != 1) {
			return false;
		}
		Matcher type = Pattern.compile("\\bTYPE\\s+(.*)$").matcher(part);
		return !(type.find() && GENERIC_TYPE.matcher(type.group(1).trim()).matches());
	}

	/** Whether the INTO or APPENDING clause of a SELECT comes before another clause. */
	static boolean intoNotLast(String selectCode) {
		int depth = 0;
		for (int i = 0; i < selectCode.length(); i++) {
			char c = selectCode.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0 && (selectCode.startsWith(" INTO ", i) || selectCode.startsWith(" APPENDING ", i))) {
				return selectCode.substring(i).matches(
						".*\\b(?:WHERE|GROUP\\s+BY|HAVING|ORDER\\s+BY|FIELDS|UNION|INTERSECT|EXCEPT|FOR\\s+ALL\\s+ENTRIES)\\b.*");
			}
		}
		return false;
	}

	/**
	 * Private methods that no other statement of the source mentions. Needs the class definition and
	 * implementation in the same source; classes with friends and dynamic calls are left out, and test
	 * classes in another include are not seen, hence only info.
	 */
	private static void unusedMethods(List<Statement> statements, int[] lineStarts, List<Finding> out) {
		List<String> codes = statements.stream().map(AbapLint::normalized).toList();
		// calls inside string templates count: only quoted literals are blanked
		List<String> texts = statements.stream()
				.map(st -> QUOTED.matcher(st.text()).replaceAll("''").toUpperCase(Locale.ROOT)).toList();
		if (codes.stream().anyMatch(c -> c.matches(".*(?:CALL\\s+METHOD\\s*\\(|->\\(|=>\\().*"))) {
			return;
		}
		record Candidate(String className, String name, int declaration) {
		}
		List<Candidate> candidates = new ArrayList<>();
		Set<String> friends = new HashSet<>();
		Map<String, int[]> bodies = new HashMap<>();
		String defined = null;
		String implemented = null;
		boolean privateSection = false;
		int methodStart = -1;
		String methodName = null;
		for (int i = 0; i < codes.size(); i++) {
			String code = codes.get(i);
			String[] w = code.split("\\s+");
			String first = w[0].replace(":", "");
			String second = w.length > 1 ? w[1] : "";
			if (first.equals("CLASS") && code.contains(" DEFINITION")) {
				if (code.contains(" FRIENDS ")) {
					friends.add(second);
				}
				boolean declares = !code.matches(".*\\b(?:DEFERRED|LOAD|LOCAL\\s+FRIENDS)\\b.*");
				defined = declares && !code.contains(" FOR TESTING") ? second : null;
				privateSection = false;
			} else if (first.equals("CLASS") && code.contains(" IMPLEMENTATION")) {
				implemented = second;
			} else if (first.equals("ENDCLASS")) {
				defined = null;
				implemented = null;
			} else if (defined != null && code.matches("(?:PUBLIC|PROTECTED|PRIVATE)\\s+SECTION")) {
				privateSection = first.equals("PRIVATE");
			} else if (defined != null && privateSection && (first.equals("METHODS") || first.equals("CLASS-METHODS"))) {
				for (String part : topLevelParts(code.substring(first.length()).replaceFirst("^\\s*:", ""))) {
					String name = part.trim().split("\\s+")[0];
					if (!name.isEmpty() && !name.contains("~") && !part.matches(".*\\b(?:FOR\\s+TESTING|REDEFINITION)\\b.*")
							&& !Set.of("CONSTRUCTOR", "CLASS_CONSTRUCTOR").contains(name)) {
						candidates.add(new Candidate(defined, name, i));
					}
				}
			} else if (implemented != null && first.equals("METHOD")) {
				methodStart = i;
				methodName = second;
			} else if (implemented != null && first.equals("ENDMETHOD") && methodName != null) {
				bodies.put(implemented + "=>" + methodName, new int[] { methodStart, i });
				methodName = null;
			}
		}
		for (Candidate c : candidates) {
			int[] body = bodies.get(c.className() + "=>" + c.name());
			if (body == null || friends.contains(c.className())) {
				continue;
			}
			Pattern use = Pattern.compile("(?<![\\w/~])" + Pattern.quote(c.name()) + "(?![\\w])");
			boolean used = false;
			for (int i = 0; i < codes.size() && !used; i++) {
				if (i == c.declaration() || i >= body[0] && i <= body[1]) {
					continue;
				}
				used = texts.get(i).contains(c.name()) && use.matcher(texts.get(i)).find();
			}
			if (!used) {
				out.add(new Finding(lineOf(lineStarts, statements.get(c.declaration()).start()), "unused_methods",
						Severity.INFO, "Private method " + c.name().toLowerCase(Locale.ROOT) + " is not called in this "
								+ "source; remove it unless a test class in another include calls it."));
			}
		}
	}

	/** First words of statements that show a comment line is code, not prose. */
	private static final Pattern CODE_KEYWORD = Pattern.compile("^(?:DATA|TYPES|CONSTANTS|FIELD-SYMBOLS|IF|ELSEIF|"
			+ "LOOP|SELECT|WRITE|CALL|MOVE|CLEAR|APPEND|READ|MODIFY|DELETE|INSERT|UPDATE|PERFORM|RETURN|CHECK|CASE|"
			+ "WHEN|DO|WHILE|TRY|CATCH|RAISE|MESSAGE|ASSIGN|SORT|COLLECT|CONCATENATE|SPLIT|REPLACE|FIND|COMMIT|"
			+ "ROLLBACK|FREE|SUBMIT|LEAVE|CREATE|CONDENSE|TRANSLATE|ADD|SUBTRACT|EXIT|CONTINUE)\\b");
	private static final Pattern CODE_HINT = Pattern.compile("[_=@~'`<>]|\\(\\s|->|=>|\\s\\)");
	private static final Pattern BLOCK_END = Pattern.compile(
			"^(?:END(?:IF|LOOP|DO|WHILE|TRY|CASE|SELECT|METHOD|FORM|FUNCTION|CLASS)|ELSE)\\.$");

	/** Words that are ABAP keywords and so do not make a comment line prose. */
	private static final Set<String> SQL_AND_DECLARATION_WORDS = Set.of("AND", "OR", "NOT", "IS", "IN", "TO", "OF",
			"FROM", "INTO", "TABLE", "TYPE", "REF", "WHERE", "WITH", "KEY", "LINE", "LINES", "DATA", "VALUE", "BY",
			"SORTED", "STANDARD", "HASHED", "DEFAULT", "EMPTY", "UP", "ROWS", "INITIAL", "BOUND", "ASSIGNED", "AT",
			"INDEX", "ASSIGNING", "REFERENCE", "TRANSPORTING", "FIELDS", "SINGLE", "ORDER", "GROUP", "EXPORTING",
			"IMPORTING", "CHANGING", "RECEIVING", "EXCEPTIONS", "OTHERS", "RETURNING", "RAISING", "NEW", "LIKE",
			"CORRESPONDING", "APPENDING", "FOR", "ALL", "ENTRIES", "DISTINCT", "AS", "INNER", "LEFT", "OUTER", "JOIN",
			"ON", "SET", "TABLES", "THEN", "ELSE", "WHEN", "CASE", "COND", "SWITCH", "CONV", "REDUCE", "INIT", "NEXT");

	/**
	 * Plain words that are no ABAP keywords, three in a row or four in all: an English sentence, not a
	 * statement.
	 */
	private static boolean prose(String commentText) {
		int run = 0;
		int all = 0;
		for (String word : commentText.split("[\\s,.:;!?]+")) {
			boolean plain = word.matches("\\p{L}+") && !SQL_AND_DECLARATION_WORDS.contains(word.toUpperCase(Locale.ROOT))
					&& !CODE_KEYWORD.matcher(word.toUpperCase(Locale.ROOT)).matches();
			run = plain ? run + 1 : 0;
			all += plain ? 1 : 0;
			if (run >= 3 || all >= 4) {
				return true;
			}
		}
		return false;
	}

	/** Blocks of comment lines that hold ABAP statements, reported once at their first code line. */
	private static void commentedCode(String source, List<Finding> out) {
		String[] lines = source.replace("\r\n", "\n").split("\n", -1);
		boolean reported = false;
		for (int i = 0; i < lines.length; i++) {
			String l = lines[i];
			String text;
			if (l.startsWith("*") && !l.startsWith("*\"") && !l.startsWith("*&")) {
				text = l.substring(1).trim();
			} else if (l.trim().startsWith("\"") && !l.trim().startsWith("\"!") && !l.trim().startsWith("\"#")) {
				text = l.trim().substring(1).trim();
			} else {
				reported = false;
				continue;
			}
			String upper = text.toUpperCase(Locale.ROOT);
			// contractions such as it'll or isn't are prose, not literals
			String bare = QUOTED.matcher(text.replaceAll("(?<=\\p{L})'(?=\\p{L})", "")).replaceAll("''");
			boolean code = BLOCK_END.matcher(upper).matches() || (upper.endsWith(".") || upper.endsWith(","))
					&& (CODE_KEYWORD.matcher(upper).find() || upper.matches("^[A-Z_][A-Z0-9_-]*\\s*\\??=\\s.*")
							|| upper.matches("^[A-Z_][A-Z0-9_]*(?:->|=>)[A-Z_].*"))
					&& CODE_HINT.matcher(bare).find() && !prose(bare);
			if (code && !reported) {
				reported = true;
				out.add(new Finding(i + 1, "commented_code", Severity.INFO,
						"Commented-out code; remove it, the version history keeps the old state."));
			}
		}
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

	/**
	 * Findings as text for the model or the user, errors and warnings first, then the style hints;
	 * {@code "No findings."} when clean.
	 */
	public static String format(List<Finding> findings) {
		if (findings.isEmpty()) {
			return "No findings.";
		}
		List<Finding> problems = findings.stream().filter(f -> f.severity() != Severity.INFO).toList();
		List<Finding> hints = findings.stream().filter(f -> f.severity() == Severity.INFO).toList();
		StringBuilder sb = new StringBuilder();
		if (!problems.isEmpty()) {
			sb.append("Errors and warnings (fix them):\n");
			problems.forEach(f -> sb.append(f.format()).append('\n'));
		}
		if (!hints.isEmpty()) {
			sb.append(problems.isEmpty() ? "" : "\n")
					.append("Hints (style; apply them where they make the code clearer, do not rework working code "
							+ "just for them):\n");
			hints.forEach(f -> sb.append(f.format()).append('\n'));
		}
		return sb.toString();
	}

	/**
	 * A note for the model when {@link AbapLintFix} can fix some of {@code findings} of {@code source}; empty
	 * otherwise. Fixes of things that are no finding (e.g. split lines) are not counted.
	 */
	public static String fixHint(String source, Target target, List<Finding> findings) {
		Set<String> rules = new HashSet<>();
		findings.forEach(f -> rules.add(f.rule()));
		rules.retainAll(AbapLintFix.RULES);
		if (rules.isEmpty()) {
			return "";
		}
		long fixable = AbapLintFix.fix(source, target).applied().stream()
				.filter(a -> rules.contains(a.replaceFirst("^Line \\d+ ", "").replaceFirst(" .*", ""))).count();
		return fixable == 0 ? ""
				: fixable + " of them can be fixed mechanically: abap_lint with 'fix' true returns the corrected code.\n";
	}
}

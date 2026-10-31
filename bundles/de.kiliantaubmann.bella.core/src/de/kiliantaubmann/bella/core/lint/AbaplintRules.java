package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.lint.AbapLint.Finding;
import de.kiliantaubmann.bella.core.lint.AbapLint.Severity;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;

/**
 * Further rules after abaplint (rules.abaplint.org) that work on statements
 * alone: errors and security (missing authority and RFC checks, dynamic SQL,
 * ambiguous DELETE/MODIFY, IS ASSIGNED in loops, index 0, misplaced WHEN OTHERS,
 * empty blocks …), readability (Yoda conditions, chained assignments, ELSE IF,
 * loop guards, macros, classic exceptions …) and the forms {@link AbapLintFix}
 * can rewrite (IS NOT, RECEIVING, EXPORTING, xsdbool, ELSE after RETURN, one
 * statement per line). The replacement list of function modules is abaplint's
 * (MIT).
 */
final class AbaplintRules {

	/** {@code IF c. x = abap_true. ELSE. x = abap_false. ENDIF.}: statement indices and the assigned variable. */
	record BoolAssignment(int ifIndex, int endIndex, String variable, boolean negated) {
	}

	/** An IF whose branches before ELSE all end with RETURN, RAISE, EXIT or CONTINUE. */
	record ElseAfterExit(int ifIndex, int elseIndex, int endIndex, boolean elseIf) {
	}

	private static final Map<String, String> FUNCTION_MODULES = Map.ofEntries(
			Map.entry("CALCULATE_HASH_FOR_CHAR", "use CL_ABAP_MESSAGE_DIGEST"),
			Map.entry("CALCULATE_HASH_FOR_RAW", "use CL_ABAP_HMAC or CL_ABAP_MESSAGE_DIGEST"),
			Map.entry("CCU_TIMESTAMP_DIFFERENCE", "use CL_ABAP_TSTMP"),
			Map.entry("CLPB_EXPORT", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("CLPB_IMPORT", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("CONVERT_DATE_TO_EXTERNAL", "use CL_ABAP_DATFM"),
			Map.entry("CONVERT_TIME_INPUT", "use CL_ABAP_TIMEFM"),
			Map.entry("DOWNLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("ECATT_CONV_XSTRING_TO_STRING", "use CL_BINARY_CONVERT"),
			Map.entry("F4_FILENAME", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("FUNCTION_EXISTS", "surround with try-catch CX_SY_DYN_CALL_ILLEGAL_METHOD instead"),
			Map.entry("GUI_CREATE_DIRECTORY", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_DELETE_FILE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_DOWNLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_EXEC", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_FB_USAGE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_FILE_LOAD_DIALOG", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_FILE_SAVE_DIALOG", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_GET_DESKTOP_INFO", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_GET_FILE_INFO", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_GET_REGVALUE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_MULTIPLE_FILE_LOAD_DIALOG", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_REMOVE_DIRECTORY", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_RUN", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_SET_REGVALUE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_UPLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_VSS_DOWNLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUI_VSS_UPLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("GUID_CREATE", "use CL_SYSTEM_UUID"),
			Map.entry("IGN_TIMESTAMP_DIFFERENCE", "use CL_ABAP_TSTMP"),
			Map.entry("IGN_TIMESTAMP_PLUSMINUS", "use CL_ABAP_TSTMP"),
			Map.entry("ISM_SD_GET_PRICING_CONDITIONS", "use CL_PRC_RESULT_FACTORY as per note 2220005"),
			Map.entry("JOB_CREATE", "use CL_BP_ABAP_JOB"),
			Map.entry("JOB_SUBMIT", "use CL_BP_ABAP_JOB"),
			Map.entry("MD5_CALCULATE_HASH_FOR_CHAR", "use CL_ABAP_MESSAGE_DIGEST"),
			Map.entry("MD5_CALCULATE_HASH_FOR_RAW", "use CL_ABAP_MESSAGE_DIGEST"),
			Map.entry("POPUP_TO_CONFIRM_LOSS_OF_DATA", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_CONFIRM_STEP", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_CONFIRM_WITH_MESSAGE", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_CONFIRM_WITH_VALUE", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_CONFIRM_WITH_VALUE_2", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_DECIDE", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_DECIDE_INFO", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_DECIDE_WITH_MESSAGE", "use POPUP_TO_CONFIRM"),
			Map.entry("POPUP_TO_GET_VALUE", "use POPUP_GET_VALUES"),
			Map.entry("QF05_RANDOM_INTEGER", "use CL_ABAP_RANDOM_INT"),
			Map.entry("REGISTRY_GET", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("REGISTRY_SET", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("REUSE_ALV_GRID_DISPLAY", "use CL_SALV_TABLE=>FACTORY or CL_GUI_ALV_GRID"),
			Map.entry("ROUND", "use built in function: round()"),
			Map.entry("SAPGUI_PROGRESS_INDICATOR", "use CL_PROGRESS_INDICATOR"),
			Map.entry("SCMS_BASE64_DECODE_STR", "use class CL_HTTP_UTILITY methods"),
			Map.entry("SCMS_STRING_TO_XSTRING", "use CL_BINARY_CONVERT"),
			Map.entry("SO_NEW_DOCUMENT_ATT_SEND_API1", "use CL_BCS"),
			Map.entry("SSFC_BASE64_DECODE", "use class CL_HTTP_UTILITY methods"),
			Map.entry("SSFC_BASE64_ENCODE", "use class CL_HTTP_UTILITY methods"),
			Map.entry("SUBST_GET_FILE_LIST", "see note 1686357"),
			Map.entry("SYSTEM_GET_UNIQUE_ID", "use CL_SYSTEM_UUID"),
			Map.entry("SYSTEM_UUID_C_CREATE", "use CL_SYSTEM_UUID"),
			Map.entry("UPLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_DOWNLOAD", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_EXECUTE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_FILENAME_GET", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_FILE_ATTRIB", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_FILE_COPY", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_FILE_DELETE", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_QUERY", "use CL_GUI_FRONTEND_SERVICES"),
			Map.entry("WS_UPLOAD", "use CL_GUI_FRONTEND_SERVICES"));

	private static final Pattern DYNAMIC_SQL = Pattern.compile(
			"^(?:SELECT\\s+(?:SINGLE\\s+)?|(?:UPDATE|MODIFY|INSERT|DELETE)\\s+(?:FROM\\s+)?)\\([A-Z_<]"
					+ "|\\b(?:FROM|WHERE|SET|FIELDS|GROUP\\s+BY|ORDER\\s+BY)\\s+\\([A-Z_<]");
	private static final Set<String> DANGEROUS = Set.of("EXEC SQL", "INSERT REPORT", "GENERATE SUBROUTINE",
			"DELETE REPORT", "INSERT TEXTPOOL", "DELETE TEXTPOOL", "DELETE DYNPRO", "EDITOR-CALL", "SYSTEM-CALL");
	private static final Pattern AMBIGUOUS = Pattern.compile("^(DELETE|MODIFY)\\s+(\\S+)\\s+FROM\\s+([^\\s@]\\S*)$");
	private static final Pattern IDENTICAL_MOVE = Pattern.compile("^(\\S+) = (\\S+)$");
	private static final Pattern INDEX_ZERO = Pattern.compile("\\[\\s*0\\s*\\]|\\bINDEX\\s+0\\b");
	private static final Pattern SY_CHANGE = Pattern.compile("^(?:CLEAR\\s+)?SY-(?!TVAR)[A-Z0-9_]+(?:\\s*[-+*/]?=|$)");
	private static final Pattern CHAINED_ASSIGNMENT = Pattern.compile("^[^\\s(]+ = [^\\s(]+ = \\S");
	private static final String CONSTANT = "(?:''|-?\\d+|ABAP_TRUE|ABAP_FALSE|SPACE)";
	private static final Pattern YODA = Pattern.compile("(?:^(?:IF|ELSEIF|CHECK|WHILE)|\\bAND|\\bOR|\\bNOT|\\()\\s+"
			+ CONSTANT + "\\s+(?:=|<>|EQ|NE)\\s+(?!" + CONSTANT + "(?:\\s|\\)|$))\\S");
	static final Pattern NOT_IS = Pattern.compile("(?<=(?:^IF|^ELSEIF|^CHECK|^WHILE|\\bAND|\\bOR|\\bWHERE|\\()\\s)NOT\\s+"
			+ "([A-Za-z0-9_<>~\\-/\\[\\]]+)\\s+(?:IS\\s+(?!NOT\\b)|IN\\s)", Pattern.CASE_INSENSITIVE);
	private static final Pattern FS_ASSIGN = Pattern.compile("\\b(?:ASSIGNING|TO)\\s+(?:FIELD-SYMBOL\\(\\s*)?(<[A-Z0-9_]+>)");
	private static final Pattern FS_CHECK = Pattern.compile("(<[A-Z0-9_]+>)\\s+IS\\s+(?:NOT\\s+)?ASSIGNED");
	private static final Pattern LOOP_GUARD = Pattern.compile(
			"^IF\\s+(?:(\\S+)\\s+IS\\s+NOT\\s+INITIAL|LINES\\(\\s*(\\S+)\\s*\\)\\s*(?:>\\s*0|<>\\s*0|>=\\s*1|GT\\s+0|NE\\s+0))$");
	private static final Pattern LOOP_AT = Pattern.compile("^LOOP\\s+AT\\s+(\\S+)");
	private static final Pattern BOOL_TRUE = Pattern.compile("^(\\S+) = ABAP_TRUE$");
	private static final Pattern BOOL_FALSE = Pattern.compile("^(\\S+) = ABAP_FALSE$");
	private static final Pattern CATCH_INTO = Pattern.compile("^CATCH\\b.*\\bINTO\\s+(?:DATA\\(\\s*)?([A-Z0-9_]+)");

	private AbaplintRules() {
	}

	static void apply(String source, List<Statement> statements, List<String> codes, int[] lineStarts, Target target,
			List<Finding> out) {
		statementRules(source, statements, codes, lineStarts, out);
		blockRules(source, statements, codes, lineStarts, out);
		if (!target.below(740)) {
			for (BoolAssignment b : boolAssignments(codes)) {
				out.add(new Finding(line(lineStarts, statements, b.ifIndex()), "use_bool_expression", Severity.INFO,
						"IF … " + b.variable().toLowerCase(Locale.ROOT) + " = abap_true/abap_false … ENDIF can be one "
								+ "statement: " + b.variable().toLowerCase(Locale.ROOT) + " = xsdbool( … )."));
			}
		}
		for (ElseAfterExit e : elseAfterExits(codes)) {
			out.add(new Finding(line(lineStarts, statements, e.elseIndex()), "else_after_all_returns", Severity.INFO,
					"Every branch before ELSE leaves the block (RETURN, RAISE, EXIT, CONTINUE); drop the ELSE and "
							+ "write its code after ENDIF."));
		}
	}

	private static int line(int[] lineStarts, List<Statement> statements, int index) {
		return AbapLint.lineOf(lineStarts, statements.get(index).start());
	}

	private static void statementRules(String source, List<Statement> statements, List<String> codes, int[] lineStarts,
			List<Finding> out) {
		List<Integer> spaceBeforeDot = new ArrayList<>();
		List<Integer> emptyLines = new ArrayList<>();
		for (int i = 0; i < statements.size(); i++) {
			Statement st = statements.get(i);
			String code = codes.get(i);
			String[] w = code.split("\\s+");
			String first = w[0].replace(":", "");
			String second = w.length > 1 ? w[1] : "";
			int line = AbapLint.lineOf(lineStarts, st.start());
			boolean methods = first.equals("METHODS") || first.equals("CLASS-METHODS");

			// ---- errors and security
			if (first.equals("CALL") && second.equals("TRANSACTION") && !code.contains("WITH AUTHORITY-CHECK")) {
				out.add(new Finding(line, "call_transaction_authority_check", Severity.WARNING,
						"CALL TRANSACTION without WITH AUTHORITY-CHECK lets the user start the transaction without "
								+ "the authorization for it."));
			}
			if (DANGEROUS.contains(first + " " + second) || DANGEROUS.contains(first)
					|| List.of("SELECT", "UPDATE", "MODIFY", "INSERT", "DELETE").contains(first)
							&& DYNAMIC_SQL.matcher(code).find()) {
				out.add(new Finding(line, "dangerous_statement", Severity.WARNING, (DANGEROUS.contains(first)
						|| DANGEROUS.contains(first + " " + second) ? first + (first.contains("-") ? "" : " " + second)
								+ " generates or runs code or native SQL; avoid it or restrict who can reach it."
								: "Dynamic SQL clause; never build it from user input without checking it "
										+ "(cl_abap_dyn_prg).")));
			}
			if (first.equals("CALL") && second.equals("FUNCTION") && code.contains(" DESTINATION ")
					&& !code.contains("STARTING NEW TASK") && !code.contains("IN BACKGROUND")
					&& !(code.contains("SYSTEM_FAILURE") && code.contains("COMMUNICATION_FAILURE"))
					&& !code.matches(".*\\bEXCEPTIONS\\b.*\\bOTHERS\\b.*")) {
				out.add(new Finding(line, "rfc_error_handling", Severity.WARNING, "RFC call without the exceptions "
						+ "system_failure and communication_failure; a broken connection then dumps."));
			}
			Matcher ambiguous = AMBIGUOUS.matcher(code);
			if (ambiguous.matches() && !ambiguous.group(3).equals("TABLE")) {
				out.add(new Finding(line, "ambiguous_statement", Severity.WARNING, ambiguous.group(1) + " "
						+ ambiguous.group(2).toLowerCase(Locale.ROOT) + " FROM … can mean an internal or a database "
						+ "table; write " + ambiguous.group(1) + " TABLE … FROM … (internal) or use @ (database)."));
			}
			Matcher move = IDENTICAL_MOVE.matcher(code);
			if (move.matches() && move.group(1).equals(move.group(2))) {
				out.add(new Finding(line, "identical_move", Severity.WARNING,
						"Assigns " + move.group(1).toLowerCase(Locale.ROOT) + " to itself."));
			}
			if (INDEX_ZERO.matcher(code).find()) {
				out.add(new Finding(line, "invalid_table_index", Severity.ERROR,
						"Table index 0 never exists; ABAP counts table lines from 1."));
			}
			if (SY_CHANGE.matcher(code).find()) {
				out.add(new Finding(line, "sy_modification", Severity.WARNING,
						"Do not change system fields (sy-…); keep the value in a variable of your own."));
			}

			// ---- readability
			if (first.equals("DEFINE")) {
				out.add(new Finding(line, "no_macros", Severity.INFO,
						"Macros cannot be debugged or navigated; use a method instead."));
			}
			if (methods) {
				for (String part : AbapLint.topLevelParts(code.substring(first.length()).replaceFirst("^\\s*:", ""))) {
					String p = " " + part.trim() + " ";
					String name = part.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
					if (p.contains(" EXCEPTIONS ")) {
						out.add(new Finding(line, "use_class_based_exceptions", Severity.INFO, "Method " + name
								+ " has classic EXCEPTIONS; raise exception classes (RAISING) instead (Clean ABAP)."));
					}
					if (p.contains(" RETURNING ") && (p.contains(" EXPORTING ") || p.contains(" CHANGING "))) {
						out.add(new Finding(line, "mix_returning", Severity.INFO, "Method " + name + " mixes RETURNING "
								+ "with EXPORTING or CHANGING; return one result (a structure if needed) (Clean ABAP)."));
					}
				}
			}
			// a test class with test methods, not a test double
			if (first.equals("CLASS") && code.contains(" DEFINITION") && code.contains(" FOR TESTING")
					&& !code.contains(" DEFERRED") && !code.contains(" LOAD")
					&& !(code.contains("RISK LEVEL") && code.contains("DURATION")) && hasTestMethods(codes, i)) {
				out.add(new Finding(line, "add_test_attributes", Severity.INFO,
						"Test class without RISK LEVEL and DURATION; add them, e.g. DURATION SHORT RISK LEVEL HARMLESS."));
			}
			if (first.equals("CALL") && second.equals("FUNCTION")) {
				Matcher fm = Pattern.compile("^\\s*CALL\\s+FUNCTION\\s+'([^']+)'", Pattern.CASE_INSENSITIVE)
						.matcher(st.text());
				String replacement = fm.find() ? FUNCTION_MODULES.get(fm.group(1).toUpperCase(Locale.ROOT)) : null;
				if (replacement != null) {
					out.add(new Finding(line, "function_module_recommendations", Severity.INFO,
							fm.group(1).toUpperCase(Locale.ROOT) + " has a successor: " + replacement + "."));
				}
			}
			if (CHAINED_ASSIGNMENT.matcher(code).find()) {
				out.add(new Finding(line, "no_chained_assignment", Severity.INFO,
						"Chained assignment a = b = c; assign each variable in its own statement."));
			}
			if (YODA.matcher(code).find()) {
				out.add(new Finding(line, "no_yoda_conditions", Severity.INFO,
						"Constant on the left of a comparison; put the variable first (x = 0, not 0 = x)."));
			}

			// ---- forms abap_lint 'fix' rewrites
			if (NOT_IS.matcher(code).find()) {
				out.add(new Finding(line, "prefer_is_not", Severity.INFO,
						"Write x IS NOT INITIAL (or x NOT IN …) instead of NOT x IS … (Clean ABAP)."));
			}
			// as abaplint: functional calls only (CALL METHOD is call_method), and EXCEPTIONS need RECEIVING
			if (code.matches(".*\\bRECEIVING\\b.*") && !methods && !first.equals("CALL") && code.contains("(")
					&& !code.matches(".*\\bEXCEPTIONS\\b.*")) {
				out.add(new Finding(line, "omit_receiving", Severity.INFO,
						"Assign the result of the functional call instead of RECEIVING: x = obj->method( … )."));
			}
			if (!methods && omittableExporting(code) >= 0) {
				out.add(new Finding(line, "exporting", Severity.INFO,
						"The call passes only EXPORTING parameters; omit the keyword EXPORTING (Clean ABAP)."));
			}
			String slice = source.substring(st.start(), st.end());
			if (slice.matches("(?s).*\\S[ \\t]+\\.")) {
				spaceBeforeDot.add(line);
			}
			if (slice.matches("(?s).*\\n[ \\t]*\\r?\\n.*")) {
				emptyLines.add(line);
			}
			if (i + 1 < statements.size() && sameLine(source, st, statements.get(i + 1))) {
				out.add(new Finding(AbapLint.lineOf(lineStarts, statements.get(i + 1).start()), "max_one_statement",
						Severity.INFO, "More than one statement on this line; write one statement per line."));
			}
		}
		// layout findings once per source, as whitespace_end
		if (!spaceBeforeDot.isEmpty()) {
			out.add(new Finding(spaceBeforeDot.get(0), "space_before_dot", Severity.INFO, "Space before the period"
					+ (spaceBeforeDot.size() > 1 ? " in " + spaceBeforeDot.size() + " statements" : "") + "."));
		}
		if (!emptyLines.isEmpty()) {
			out.add(new Finding(emptyLines.get(0), "empty_line_in_statement", Severity.INFO, "Empty line inside the "
					+ "statement" + (emptyLines.size() > 1 ? " (" + emptyLines.size() + " statements)" : "") + "."));
		}
	}

	/** Whether the class definition starting at {@code index} declares a method FOR TESTING. */
	private static boolean hasTestMethods(List<String> codes, int index) {
		for (int i = index + 1; i < codes.size() && !codes.get(i).startsWith("ENDCLASS"); i++) {
			String code = codes.get(i);
			if ((code.startsWith("METHODS") || code.startsWith("CLASS-METHODS")) && code.contains(" FOR TESTING")) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code next} starts on the line where {@code st} ends, with only blanks between them. */
	static boolean sameLine(String source, Statement st, Statement next) {
		return source.substring(st.end(), next.start()).matches("[ \\t]+") && st.end() < next.start();
	}

	/**
	 * Offset in {@code code} of an {@code EXPORTING} that can be omitted, because the parentheses it opens hold
	 * no other parameter kind; -1 if there is none.
	 */
	static int omittableExporting(String code) {
		Matcher m = Pattern.compile("\\(\\s+EXPORTING\\b", Pattern.CASE_INSENSITIVE).matcher(code);
		while (m.find()) {
			int close = closingParenthesis(code, m.start());
			String inner = (close < 0 ? code.substring(m.start()) : code.substring(m.start(), close))
					.toUpperCase(Locale.ROOT);
			if (close >= 0 && !inner.matches("(?s).*\\b(?:IMPORTING|CHANGING|RECEIVING|EXCEPTIONS)\\b.*")) {
				return m.end() - "EXPORTING".length();
			}
		}
		return -1;
	}

	/** The index of the parenthesis that closes the one at {@code open}, or -1. */
	static int closingParenthesis(String s, int open) {
		int depth = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	private static final Set<String> OPENERS = Set.of("IF", "CASE", "LOOP", "DO", "WHILE", "TRY", "PROVIDE");

	private static void blockRules(String source, List<Statement> statements, List<String> codes, int[] lineStarts,
			List<Finding> out) {
		Deque<boolean[]> cases = new ArrayDeque<>();
		Deque<int[]> tries = new ArrayDeque<>();
		Deque<LoopScope> loops = new ArrayDeque<>();
		for (int i = 0; i < codes.size(); i++) {
			String code = codes.get(i);
			String[] w = code.split("\\s+");
			String first = w[0].replace(":", "");
			String second = w.length > 1 ? w[1] : "";
			int line = line(lineStarts, statements, i);
			String next = i + 1 < codes.size() ? codes.get(i + 1).split("\\s+")[0] : "";

			switch (first) {
			case "CASE" -> cases.push(new boolean[1]);
			case "WHEN" -> {
				if (!cases.isEmpty()) {
					if (cases.peek()[0]) {
						out.add(new Finding(line, "when_others_last", Severity.WARNING,
								"WHEN after WHEN OTHERS; WHEN OTHERS belongs last."));
					}
					cases.peek()[0] |= second.equals("OTHERS");
				}
			}
			case "ENDCASE" -> cases.poll();
			case "TRY" -> tries.push(new int[] { line, 0 });
			case "CATCH", "CLEANUP" -> {
				if (!tries.isEmpty()) {
					tries.peek()[1] = 1;
				}
			}
			case "ENDTRY" -> {
				int[] t = tries.poll();
				if (t != null && t[1] == 0) {
					out.add(new Finding(t[0], "try_without_catch", Severity.WARNING,
							"TRY without CATCH or CLEANUP does nothing; catch the exceptions or remove the TRY."));
				}
			}
			case "LOOP", "DO", "WHILE" -> loops.push(new LoopScope());
			case "ENDLOOP", "ENDDO", "ENDWHILE" -> {
				LoopScope scope = loops.poll();
				if (scope != null) {
					scope.report(out);
					if (!loops.isEmpty()) {
						loops.peek().unassigned.addAll(scope.unassigned);
					}
				}
			}
			default -> {
				// no block
			}
			}

			// IS ASSIGNED after a READ TABLE … ASSIGNING or ASSIGN in a loop sees the previous iteration's line
			if (!loops.isEmpty()) {
				LoopScope scope = loops.peek();
				if (first.equals("UNASSIGN")) {
					for (String fs : w) {
						scope.unassigned.add(fs.replace(",", "").replace(".", ""));
					}
				} else if (first.equals("ASSIGN") || first.equals("READ") && second.equals("TABLE")) {
					Matcher fs = FS_ASSIGN.matcher(code);
					if (fs.find()) {
						scope.assigned.add(fs.group(1));
					}
				}
				Matcher check = FS_CHECK.matcher(code);
				while (check.find()) {
					scope.checks.putIfAbsent(check.group(1), line);
				}
			}

			// CATCH … INTO x. RAISE EXCEPTION x. only passes the exception on
			Matcher into = CATCH_INTO.matcher(code);
			if (into.find() && i + 2 < codes.size() && codes.get(i + 1).equals("RAISE EXCEPTION " + into.group(1))
					&& List.of("CATCH", "CLEANUP", "ENDTRY").contains(codes.get(i + 2).split("\\s+")[0])) {
				out.add(new Finding(line, "catch_and_raise", Severity.INFO, "CATCH only raises the exception again; "
						+ "remove the CATCH (declare RAISING) or handle the exception."));
			}

			// an opener directly followed by its end or next branch
			if (emptyBlock(first, next) && !commentBetween(source, statements.get(i), statements.get(i + 1))
					&& !(first.equals("LOOP") && i + 2 < codes.size() && codes.get(i + 2).contains("SY-SUBRC"))) {
				out.add(new Finding(line, "empty_structure", first.equals("WHEN") ? Severity.INFO : Severity.WARNING,
						"Empty " + first + " block; fill it, add a comment why it stays empty, or remove it."));
			}

			// ELSE. IF … ENDIF. ENDIF. is an ELSEIF
			if (first.equals("ELSE") && next.equals("IF")) {
				int end = matchingEnd(codes, i + 1);
				if (end > 0 && end + 1 < codes.size() && codes.get(end + 1).equals("ENDIF")) {
					out.add(new Finding(line, "if_in_if", Severity.INFO,
							"An IF is the only statement in the ELSE branch; use ELSEIF instead."));
				}
			}

			// IF itab IS NOT INITIAL. LOOP AT itab … ENDLOOP. ENDIF.
			Matcher guard = LOOP_GUARD.matcher(code);
			if (guard.matches() && next.equals("LOOP")) {
				String table = guard.group(1) != null ? guard.group(1) : guard.group(2);
				Matcher loop = LOOP_AT.matcher(codes.get(i + 1));
				int endLoop = matchingEnd(codes, i + 1);
				if (loop.find() && loop.group(1).equals(table) && endLoop > 0 && endLoop + 1 < codes.size()
						&& codes.get(endLoop + 1).equals("ENDIF")) {
					out.add(new Finding(line, "unnecessary_loop_guard", Severity.INFO, "LOOP AT an empty table does "
							+ "nothing; the IF around it is not needed."));
				}
			}
		}
	}

	/** Field symbols a loop assigns, unassigns and tests with IS ASSIGNED. */
	private static final class LoopScope {
		final Set<String> assigned = new HashSet<>();
		final Set<String> unassigned = new HashSet<>();
		final Map<String, Integer> checks = new HashMap<>();

		void report(List<Finding> out) {
			checks.forEach((fs, line) -> {
				if (assigned.contains(fs) && !unassigned.contains(fs)) {
					out.add(new Finding(line, "is_assigned_in_loop", Severity.WARNING, fs.toLowerCase(Locale.ROOT)
							+ " IS ASSIGNED in a loop is still true from an earlier iteration when the READ TABLE or "
							+ "ASSIGN fails; UNASSIGN it before or check sy-subrc."));
				}
			});
		}
	}

	private static boolean emptyBlock(String first, String next) {
		return switch (first) {
		case "IF", "ELSEIF", "ELSE" -> List.of("ENDIF", "ELSEIF", "ELSE").contains(next);
		case "LOOP" -> next.equals("ENDLOOP");
		case "WHILE" -> next.equals("ENDWHILE");
		case "DO" -> next.equals("ENDDO");
		case "CASE" -> next.equals("ENDCASE");
		case "WHEN" -> next.equals("WHEN") || next.equals("ENDCASE");
		case "TRY" -> List.of("CATCH", "CLEANUP", "ENDTRY").contains(next);
		default -> false;
		};
	}

	/** Whether a comment stands between two statements. */
	static boolean commentBetween(String source, Statement a, Statement b) {
		String between = source.substring(a.end(), b.start());
		return between.contains("\"") || between.matches("(?s).*\\n\\*.*");
	}

	/** The index of the statement that closes the block opened at {@code open} (IF … ENDIF, LOOP … ENDLOOP); -1. */
	static int matchingEnd(List<String> codes, int open) {
		String opener = codes.get(open).split("\\s+")[0];
		int depth = 0;
		for (int i = open; i < codes.size(); i++) {
			String first = codes.get(i).split("\\s+")[0];
			if (first.equals(opener)) {
				depth++;
			} else if (first.equals("END" + opener) && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/** IF … ENDIF blocks that only set one variable to abap_true or abap_false. */
	static List<BoolAssignment> boolAssignments(List<String> codes) {
		List<BoolAssignment> result = new ArrayList<>();
		for (int i = 0; i + 4 < codes.size(); i++) {
			if (!codes.get(i).startsWith("IF ") || !codes.get(i + 2).equals("ELSE") || !codes.get(i + 4).equals("ENDIF")) {
				continue;
			}
			Matcher t1 = BOOL_TRUE.matcher(codes.get(i + 1));
			Matcher f1 = BOOL_FALSE.matcher(codes.get(i + 1));
			Matcher t2 = BOOL_TRUE.matcher(codes.get(i + 3));
			Matcher f2 = BOOL_FALSE.matcher(codes.get(i + 3));
			if (t1.matches() && f2.matches() && t1.group(1).equals(f2.group(1))) {
				result.add(new BoolAssignment(i, i + 4, t1.group(1), false));
			} else if (f1.matches() && t2.matches() && f1.group(1).equals(t2.group(1))) {
				result.add(new BoolAssignment(i, i + 4, f1.group(1), true));
			}
		}
		return result;
	}

	/** One IF … ENDIF while scanning for {@link ElseAfterExit}. */
	private static final class IfFrame {
		final int ifIndex;
		int inner;
		boolean lastExits;
		boolean allExit = true;
		boolean elseIf;
		int elseIndex = -1;

		IfFrame(int ifIndex) {
			this.ifIndex = ifIndex;
		}
	}

	/** IF blocks with an ELSE whose earlier branches all end with RETURN, RAISE EXCEPTION, EXIT or CONTINUE. */
	static List<ElseAfterExit> elseAfterExits(List<String> codes) {
		List<ElseAfterExit> result = new ArrayList<>();
		Deque<IfFrame> frames = new ArrayDeque<>();
		for (int i = 0; i < codes.size(); i++) {
			String[] w = codes.get(i).split("\\s+");
			String first = w[0].replace(":", "");
			IfFrame top = frames.peek();
			if (first.equals("IF")) {
				if (top != null && top.inner == 0) {
					top.lastExits = false;
				}
				frames.push(new IfFrame(i));
				continue;
			}
			if (top == null) {
				continue;
			}
			if (top.inner > 0) {
				if (OPENERS.contains(first)) {
					top.inner++;
				} else if (first.startsWith("END") && OPENERS.contains(first.substring(3))) {
					top.inner--;
				}
				continue;
			}
			switch (first) {
			case "ELSEIF", "ELSE" -> {
				top.allExit &= top.lastExits;
				top.lastExits = false;
				if (first.equals("ELSEIF")) {
					top.elseIf = true;
				} else {
					top.elseIndex = i;
				}
			}
			case "ENDIF" -> {
				frames.pop();
				if (top.elseIndex >= 0 && top.allExit) {
					result.add(new ElseAfterExit(top.ifIndex, top.elseIndex, i, top.elseIf));
				}
				IfFrame parent = frames.peek();
				if (parent != null && parent.inner == 0) {
					parent.lastExits = false;
				}
			}
			default -> {
				if (OPENERS.contains(first)) {
					top.inner++;
					top.lastExits = false;
				} else if (top.elseIndex < 0) {
					top.lastExits = AbapLint.terminates(w);
				}
			}
			}
		}
		return result;
	}
}

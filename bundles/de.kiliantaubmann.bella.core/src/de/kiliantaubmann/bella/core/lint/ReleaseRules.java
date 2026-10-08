package de.kiliantaubmann.bella.core.lint;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.lint.AbapLint.Finding;
import de.kiliantaubmann.bella.core.lint.AbapLint.Severity;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;

/**
 * Rules that depend on the system the code is for: syntax a release does not
 * know yet, what ABAP Cloud forbids (after ARC-1's cloud preset for abaplint),
 * and the modern forms Clean ABAP prefers where the release offers them.
 */
final class ReleaseRules {

	private static final Pattern INLINE_DECLARATION = Pattern.compile("\\b(?:DATA|FIELD-SYMBOL)\\(");
	private static final Pattern FINAL_DECLARATION = Pattern.compile("\\bFINAL\\(");
	private static final Pattern CONSTRUCTOR = Pattern.compile(
			"\\b(NEW|VALUE|CONV|COND|SWITCH|CORRESPONDING|REF|EXACT|REDUCE|FILTER|CAST)\\s+(?:#|[A-Z_/][A-Z0-9_/]*)\\(");
	private static final Pattern TABLE_EXPRESSION = Pattern.compile("[A-Z0-9_>]\\[\\s");
	private static final Pattern BUILTIN_740 = Pattern.compile("\\b(XSDBOOL|LINE_EXISTS|LINE_INDEX)\\(");
	private static final Pattern RAISE_NEW = Pattern.compile("^RAISE\\s+(?:RESUMABLE\\s+)?EXCEPTION\\s+NEW\\b");
	private static final Pattern INTO_HOST = Pattern.compile(
			"\\b(?:INTO|APPENDING)\\s+(?:CORRESPONDING\\s+FIELDS\\s+OF\\s+)?(?:TABLE\\s+)?([^\\s,]+)");
	private static final Pattern SIMPLE_DATA = Pattern.compile("^DATA\\s+([A-Z_][A-Z0-9_]*)\\s+TYPE\\s+(?!.*\\bVALUE\\b)[^,]*$");

	/** Rules whose findings are errors in ABAP Cloud, where the statements do not compile. */
	private static final Set<String> CLOUD_ERRORS = Set.of("obsolete_move", "obsolete_compute", "obsolete_arithmetic",
			"obsolete_refresh", "obsolete_ranges", "obsolete_occurs", "header_line", "obsolete_tables",
			"obsolete_statement", "form_routine", "sql_escape_host_variables");

	/** Statements of classic programs and dynpros, which ABAP Cloud does not have. */
	private static final Set<String> CLASSIC_ONLY = Set.of("WRITE", "SUBMIT", "SELECTION-SCREEN", "PARAMETERS",
			"PARAMETER", "SELECT-OPTIONS", "REPORT", "PROGRAM", "MODULE", "ULINE", "SKIP", "NEW-PAGE", "FORMAT");

	private ReleaseRules() {
	}

	static void apply(List<Statement> statements, List<String> codes, int[] lineStarts, Target target,
			List<Finding> out) {
		if (target.cloud()) {
			out.replaceAll(f -> CLOUD_ERRORS.contains(f.rule()) && f.severity() != Severity.ERROR
					? new Finding(f.line(), f.rule(), Severity.ERROR, f.message() + " Not allowed in ABAP Cloud.")
					: f);
		}
		boolean modern = !target.below(740);
		if (!modern) {
			// NEW #( ) and line_exists( ) do not exist yet; these hints would suggest them
			out.removeIf(f -> f.rule().equals("create_object") || f.rule().equals("read_table"));
		}
		Set<Integer> lineExists = new HashSet<>();
		int methodStart = -1;
		for (int i = 0; i < statements.size(); i++) {
			String code = codes.get(i);
			String[] w = code.split("\\s+");
			String first = w[0].replace(":", "");
			String second = w.length > 1 ? w[1] : "";
			int line = AbapLint.lineOf(lineStarts, statements.get(i).start());

			if (target.below(740)) {
				String feature = feature740(code, first);
				if (feature != null) {
					out.add(new Finding(line, "release_syntax", Severity.ERROR,
							feature + " needs SAP_BASIS 7.40 or later; this system has " + target.label() + "."));
				}
			}
			if (target.below(757) && FINAL_DECLARATION.matcher(code).find()) {
				out.add(new Finding(line, "release_syntax", Severity.ERROR,
						"FINAL( ) needs SAP_BASIS 7.57 or later; this system has " + target.label() + ". Use DATA( )."));
			}
			if (target.below(752) && RAISE_NEW.matcher(code).find()) {
				out.add(new Finding(line, "release_syntax", Severity.ERROR, "RAISE EXCEPTION NEW needs SAP_BASIS 7.52 "
						+ "or later; this system has " + target.label() + ". Use RAISE EXCEPTION TYPE."));
			}

			if (target.cloud()) {
				if (CLASSIC_ONLY.contains(first) || first.equals("CALL")
						&& (second.equals("TRANSACTION") || second.equals("SCREEN") || second.equals("DIALOG"))
						|| first.equals("LEAVE") && (second.equals("TO") || second.equals("SCREEN"))) {
					out.add(new Finding(line, "cloud_types", Severity.ERROR, (first.equals("CALL") || first.equals("LEAVE")
							? first + " " + second : first) + " does not exist in ABAP Cloud (no classic programs, "
							+ "lists or dynpros); use classes, released APIs and RAP or Fiori."));
				}
				if ((first.equals("SELECT") || first.equals("WITH")) && !code.contains("@")) {
					Matcher m = INTO_HOST.matcher(code);
					if (m.find() && !m.group(1).startsWith("(")) {
						out.add(new Finding(line, "strict_sql", Severity.ERROR, "ABAP Cloud needs strict Open SQL: "
								+ "escape host variables with @, e.g. INTO TABLE @" + m.group(1).toLowerCase(Locale.ROOT)
								+ "."));
					}
				}
			}

			if (!modern) {
				continue;
			}
			if (first.equals("METHOD")) {
				methodStart = i;
			} else if (first.equals("ENDMETHOD") && methodStart >= 0) {
				preferInline(statements, codes, methodStart, i, lineStarts, out);
				methodStart = -1;
			}
			if (code.contains("BOOLC(")) {
				out.add(new Finding(line, "prefer_xsdbool", Severity.INFO,
						"Use xsdbool( ) instead of boolc( ); it returns abap_bool and compares safely with abap_true."));
			}
			if (first.equals("MOVE-CORRESPONDING")) {
				out.add(new Finding(line, "prefer_corresponding", Severity.INFO,
						"Use target = CORRESPONDING #( BASE ( target ) source ) instead of MOVE-CORRESPONDING."));
			}
			if (!target.below(752) && code.matches("^RAISE\\s+(?:RESUMABLE\\s+)?EXCEPTION\\s+TYPE\\b.*")) {
				out.add(new Finding(line, "prefer_raise_exception_new", Severity.INFO,
						"Use RAISE EXCEPTION NEW cx_…( … ) instead of RAISE EXCEPTION TYPE … EXPORTING."));
			}
			if (first.equals("READ") && second.equals("TABLE") && code.contains("TRANSPORTING NO FIELDS")
					&& !code.contains("BINARY SEARCH") && i + 1 < statements.size()) {
				String next = codes.get(i + 1);
				if (next.matches("^(?:IF|CHECK|ELSEIF)\\b.*\\bSY-SUBRC\\b.*")) {
					lineExists.add(line);
					out.add(new Finding(line, "use_line_exists", Severity.INFO,
							"Use IF line_exists( itab[ … ] ) instead of READ TABLE … TRANSPORTING NO FIELDS and sy-subrc."));
				}
			}
		}
		out.removeIf(f -> f.rule().equals("read_table") && lineExists.contains(f.line()));
	}

	/** The 7.40 feature a statement uses, or {@code null}. */
	private static String feature740(String code, String first) {
		if (INLINE_DECLARATION.matcher(code).find()) {
			return "The inline declaration DATA( )";
		}
		Matcher c = CONSTRUCTOR.matcher(code);
		if (c.find()) {
			return "The constructor expression " + c.group(1) + " #( )";
		}
		Matcher b = BUILTIN_740.matcher(code);
		if (b.find()) {
			return b.group(1).toLowerCase(Locale.ROOT) + "( )";
		}
		if (TABLE_EXPRESSION.matcher(code).find()) {
			return "The table expression itab[ … ]";
		}
		if ((first.equals("SELECT") || first.equals("WITH") || first.equals("INSERT") || first.equals("UPDATE")
				|| first.equals("MODIFY") || first.equals("DELETE")) && code.contains("@")) {
			return "The host variable escape @";
		}
		return null;
	}

	/**
	 * A local {@code DATA x TYPE t} whose first use is an assignment {@code x = …} can be declared there inline
	 * (Clean ABAP "prefer inline to up-front declarations"); only simple single declarations are reported.
	 */
	private static void preferInline(List<Statement> statements, List<String> codes, int start, int end,
			int[] lineStarts, List<Finding> out) {
		for (int j = start + 1; j < end; j++) {
			Matcher d = SIMPLE_DATA.matcher(codes.get(j));
			if (!d.matches()) {
				continue;
			}
			String name = d.group(1);
			Pattern use = Pattern.compile("(?<![\\w<>/~-])" + Pattern.quote(name) + "(?![\\w>])");
			for (int k = j + 1; k < end; k++) {
				String code = codes.get(k);
				if (!use.matcher(code).find()) {
					continue;
				}
				if (code.matches("^" + Pattern.quote(name) + "\\s*=\\s.*") && !code.matches(".*\\b" + Pattern.quote(name)
						+ "\\b.*\\b" + Pattern.quote(name) + "\\b.*")) {
					out.add(new Finding(AbapLint.lineOf(lineStarts, statements.get(j).start()), "prefer_inline",
							Severity.INFO, name.toLowerCase(Locale.ROOT) + " is first assigned in line "
									+ AbapLint.lineOf(lineStarts, statements.get(k).start()) + "; declare it there with DATA( "
									+ name.toLowerCase(Locale.ROOT) + " ) = …."));
				}
				break;
			}
		}
	}
}

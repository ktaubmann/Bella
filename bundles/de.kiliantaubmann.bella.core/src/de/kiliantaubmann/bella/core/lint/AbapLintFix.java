package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;

/**
 * Fixes for style findings whose correction is fixed: obsolete MOVE, COMPUTE,
 * ADD/SUBTRACT/MULTIPLY/DIVIDE, REFRESH, CALL METHOD, CREATE OBJECT,
 * DESCRIBE TABLE … LINES and trailing spaces. A statement is only changed when
 * it stands alone (no chain, no comment inside) and has the plain form; anything
 * else stays a finding for the model.
 */
public final class AbapLintFix {

	/** The fixed source and one entry per change ({@code "Line 3 obsolete_move"}). */
	public record Result(String source, List<String> applied) {
	}

	private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
	private static final Pattern MOVE = Pattern.compile("MOVE\\s+(\\S+)\\s+(\\??TO)\\s+(\\S+)\\.", FLAGS);
	private static final Pattern COMPUTE = Pattern.compile("COMPUTE\\s+(?!EXACT\\b)(.+)", FLAGS);
	private static final Pattern ADD = Pattern.compile("ADD\\s+(\\S+)\\s+TO\\s+(\\S+)\\.", FLAGS);
	private static final Pattern SUBTRACT = Pattern.compile("SUBTRACT\\s+(\\S+)\\s+FROM\\s+(\\S+)\\.", FLAGS);
	private static final Pattern MULTIPLY_DIVIDE = Pattern.compile("(MULTIPLY|DIVIDE)\\s+(\\S+)\\s+BY\\s+(\\S+)\\.",
			FLAGS);
	private static final Pattern REFRESH = Pattern.compile("REFRESH\\s+([^\\s.]+)\\.", FLAGS);
	private static final Pattern DESCRIBE = Pattern.compile("DESCRIBE\\s+TABLE\\s+(\\S+)\\s+LINES\\s+([^\\s.]+)\\.",
			FLAGS);
	private static final Pattern CALL_METHOD = Pattern.compile("CALL\\s+METHOD\\s+([^\\s.]+)(\\s.*)?\\.", FLAGS);
	private static final Pattern CREATE_OBJECT = Pattern.compile(
			"CREATE\\s+OBJECT\\s+([^\\s.]+)(?:\\s+TYPE\\s+([^\\s.(]+))?(?:\\s+EXPORTING(\\s.*))?\\.", FLAGS);
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
		boolean headerLines = HEADER_LINE.matcher(source).find();
		int[] lineStarts = AbapLint.lineStarts(source);
		List<String> applied = new ArrayList<>();
		StringBuilder out = new StringBuilder(source);
		List<Statement> statements = AbapStructureScanner.statements(source);
		// from the end, so the offsets of earlier statements stay valid
		for (int i = statements.size() - 1; i >= 0; i--) {
			Statement st = statements.get(i);
			String slice = source.substring(st.start(), st.end());
			if (!standalone(slice)) {
				continue;
			}
			Fix f = fixStatement(slice, t, headerLines);
			if (f != null) {
				out.replace(st.start(), st.end(), f.text());
				applied.add(0, "Line " + AbapLint.lineOf(lineStarts, st.start()) + " " + f.rule());
			}
		}
		String fixed = trimLineEnds(out.toString(), applied);
		return new Result(fixed, List.copyOf(applied));
	}

	private record Fix(String rule, String text) {
	}

	/** No chain, no comment and no literal that could hide one of the keywords. */
	private static boolean standalone(String slice) {
		return slice.endsWith(".") && slice.indexOf(':') < 0 && slice.indexOf('"') < 0 && slice.indexOf('\'') < 0
				&& slice.indexOf('`') < 0 && slice.indexOf('|') < 0 && !slice.contains("\n*");
	}

	private static Fix fixStatement(String s, Target t, boolean headerLines) {
		String first = s.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
		boolean compoundAssignment = t.cloud() || t.release() >= 754;
		return switch (first) {
		case "MOVE" -> match(MOVE, s, m -> new Fix("obsolete_move",
				m.group(3) + (m.group(2).startsWith("?") ? " ?= " : " = ") + m.group(1) + "."));
		case "COMPUTE" -> match(COMPUTE, s, m -> new Fix("obsolete_compute", m.group(1)));
		case "ADD" -> match(ADD, s, m -> arithmetic(m.group(2), "+", m.group(1), compoundAssignment));
		case "SUBTRACT" -> match(SUBTRACT, s, m -> arithmetic(m.group(2), "-", m.group(1), compoundAssignment));
		case "MULTIPLY", "DIVIDE" -> match(MULTIPLY_DIVIDE, s, m -> arithmetic(m.group(2),
				m.group(1).equalsIgnoreCase("MULTIPLY") ? "*" : "/", m.group(3), compoundAssignment));
		case "REFRESH" -> headerLines ? null
				: match(REFRESH, s, m -> new Fix("obsolete_refresh", "CLEAR " + m.group(1) + "."));
		case "DESCRIBE" -> headerLines ? null
				: match(DESCRIBE, s, m -> new Fix("describe_lines", m.group(2) + " = lines( " + m.group(1) + " )."));
		case "CALL" -> match(CALL_METHOD, s, AbapLintFix::callMethod);
		case "CREATE" -> t.below(740) ? null : match(CREATE_OBJECT, s, AbapLintFix::createObject);
		default -> null;
		};
	}

	private static Fix match(Pattern p, String s, Function<Matcher, Fix> fix) {
		Matcher m = p.matcher(s);
		return m.matches() ? fix.apply(m) : null;
	}

	/** {@code b += a} from 7.54 on, {@code b = b + a} before or when the release is unknown. */
	private static Fix arithmetic(String target, String operator, String operand, boolean compound) {
		return new Fix("obsolete_arithmetic", compound ? target + " " + operator + "= " + operand + "."
				: target + " = " + target + " " + operator + " " + operand + ".");
	}

	private static Fix callMethod(Matcher m) {
		String method = m.group(1);
		String rest = m.group(2) == null ? "" : m.group(2);
		// dynamic calls, OLE (CALL METHOD OF) and RECEIVING keep their form
		if (method.contains("(") || method.equalsIgnoreCase("OF")
				|| rest.toUpperCase(Locale.ROOT).matches("(?s).*\\bRECEIVING\\b.*")) {
			return null;
		}
		return new Fix("call_method", method + "(" + (rest.isBlank() ? " " : rest + " ") + ").");
	}

	private static Fix createObject(Matcher m) {
		String parameters = m.group(3) == null ? "" : m.group(3);
		String upper = parameters.toUpperCase(Locale.ROOT);
		// EXCEPTIONS and shared-objects AREA HANDLE have no NEW form
		if (upper.matches("(?s).*\\b(?:EXCEPTIONS|AREA)\\b.*")) {
			return null;
		}
		String type = m.group(2) == null ? "#" : m.group(2);
		return new Fix("create_object", m.group(1) + " = NEW " + type + "(" + (parameters.isBlank() ? " " : parameters + " ")
				+ ").");
	}

	/** Removes trailing spaces, except on lines inside a string template that goes on. */
	private static String trimLineEnds(String source, List<String> applied) {
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
			applied.add("Line " + firstTrimmed + " whitespace_end" + (trimmed > 1 ? " (" + trimmed + " lines)" : ""));
		}
		return String.join("\n", lines);
	}
}

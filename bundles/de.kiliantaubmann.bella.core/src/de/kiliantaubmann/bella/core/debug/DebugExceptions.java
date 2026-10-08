package de.kiliantaubmann.bella.core.debug;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Problem;
import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Variable;

/**
 * Recognises that the debugger stopped at an exception. Eclipse's debug
 * framework has no notion of ABAP exceptions, so this reads what the ADT
 * debugger shows: an exception entry among the variables, or an exception
 * class in the label of the thread or frame.
 */
public final class DebugExceptions {

	/** An exception class: CX_…, ZCX_…, YCX_… or /NS/CX_…. */
	private static final Pattern CLASS = Pattern.compile("(?<![A-Z0-9_/])((?:/[A-Z0-9_]+/)?[ZY]?CX_[A-Z0-9_]+)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern EXCEPTION_NAME = Pattern.compile("[{<]?\\s*(exception|ausnahme)(\\s*object)?\\s*[}>]?",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern RUNTIME_ERROR = Pattern.compile("(?i)(exception|ausnahme|runtime error|laufzeitfehler)");

	private DebugExceptions() {
	}

	/**
	 * The exception the session stopped at, if it shows one.
	 *
	 * @param labels labels of the thread and the top frame as the Debug view shows them
	 */
	public static Optional<Problem> detect(List<Variable> variables, List<String> labels) {
		for (Variable v : variables) {
			if (EXCEPTION_NAME.matcher(v.name().strip()).matches()) {
				String type = exceptionClass(v.type()).or(() -> exceptionClass(v.value())).orElse(v.type());
				return Optional.of(new Problem(type.isEmpty() ? "exception" : type, text(v)));
			}
		}
		for (String label : labels) {
			if (label != null && RUNTIME_ERROR.matcher(label).find()) {
				Optional<String> type = exceptionClass(label);
				if (type.isPresent()) {
					return Optional.of(new Problem(type.get(), ""));
				}
			}
		}
		return Optional.empty();
	}

	static Optional<String> exceptionClass(String s) {
		if (s == null) {
			return Optional.empty();
		}
		Matcher m = CLASS.matcher(s);
		return m.find() ? Optional.of(m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
	}

	/** The message of an exception entry: a child holding the text, else nothing. */
	private static String text(Variable exception) {
		for (Variable c : exception.children()) {
			String n = c.name().toUpperCase(Locale.ROOT);
			if (n.equals("TEXT") || n.equals("GET_TEXT") || n.equals("MESSAGE") || n.equals("GET_TEXT( )")) {
				return c.value();
			}
		}
		return "";
	}
}

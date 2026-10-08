package de.kiliantaubmann.bella.core.debug;

import java.util.List;

/**
 * The state of a stopped ABAP debug session as the model sees it: where the
 * program stands, how it got there, the variables of the top frame and the
 * exception it stopped at, if any.
 *
 * @param object    program, class or function group the debugger stands in, e.g. {@code ZCL_ORDER}
 * @param include   include or method of the current line, empty if unknown
 * @param line      current line, 0 if unknown
 * @param stack     call stack, the current frame first
 * @param variables variables of the current frame
 * @param exception the exception the debugger stopped at, {@code null} for none
 */
public record DebugSnapshot(String object, String include, int line, List<Frame> stack, List<Variable> variables,
		Problem exception) {

	/** Most variables listed at one level. */
	public static final int MAX_VARIABLES = 50;
	/** Most rows or components listed below a variable. */
	public static final int MAX_CHILDREN = 10;
	/** Levels below a top level variable a backend loads on its own. */
	public static final int DEPTH = 2;
	/** Longest value shown. */
	public static final int MAX_VALUE = 500;

	public DebugSnapshot {
		object = object == null ? "" : object;
		include = include == null ? "" : include;
		stack = stack == null ? List.of() : List.copyOf(stack);
		variables = variables == null ? List.of() : List.copyOf(variables);
	}

	/** One frame of the call stack. */
	public record Frame(String name, String object, int line) {
	}

	/**
	 * A variable with its value as the debugger shows it.
	 *
	 * @param children rows of a table, components of a structure, attributes of an object
	 * @param more     the variable has further children that were not loaded
	 */
	public record Variable(String name, String type, String value, List<Variable> children, boolean more) {

		public Variable {
			name = name == null ? "" : name;
			type = type == null ? "" : type;
			value = value == null ? "" : value;
			children = children == null ? List.of() : List.copyOf(children);
		}

		public static Variable of(String name, String type, String value) {
			return new Variable(name, type, value, List.of(), false);
		}

		/** An entry without type and value that only holds variables, such as "Locals". */
		public boolean isGroup() {
			return type.isEmpty() && value.isEmpty() && !children.isEmpty();
		}
	}

	/** An exception the program stopped at. */
	public record Problem(String type, String text) {
	}

	/** The snapshot as compact text, within the limits above. */
	public String format() {
		StringBuilder sb = new StringBuilder("Debugger stopped in ").append(object);
		if (!include.isEmpty() && !include.equalsIgnoreCase(object)) {
			sb.append(" (").append(include).append(')');
		}
		if (line > 0) {
			sb.append(" at line ").append(line);
		}
		sb.append(".\n");
		if (exception != null) {
			sb.append("Exception: ").append(exception.type());
			if (exception.text() != null && !exception.text().isBlank()) {
				sb.append(": ").append(cut(exception.text()));
			}
			sb.append('\n');
		}
		if (!stack.isEmpty()) {
			sb.append("\nCall stack (current first):\n");
			for (Frame f : stack) {
				sb.append("- ").append(f.name());
				if (f.object() != null && !f.object().isBlank() && !f.object().equalsIgnoreCase(f.name())) {
					sb.append(" in ").append(f.object());
				}
				if (f.line() > 0) {
					sb.append(", line ").append(f.line());
				}
				sb.append('\n');
			}
		}
		sb.append("\nVariables:\n");
		if (variables.isEmpty()) {
			sb.append("(none)\n");
		}
		appendVariables(sb, variables, 0, MAX_VARIABLES);
		return sb.toString();
	}

	/** One variable with its loaded children, for {@code debug_context} with a variable path. */
	public static String format(Variable v) {
		StringBuilder sb = new StringBuilder();
		appendVariables(sb, List.of(v), 0, 1);
		return sb.toString();
	}

	private static void appendVariables(StringBuilder sb, List<Variable> vars, int depth, int max) {
		appendVariables(sb, vars, 0, depth, max);
	}

	/**
	 * @param indent nesting shown
	 * @param depth  levels below a variable of the frame; a group such as "Locals" is no level of its own
	 */
	private static void appendVariables(StringBuilder sb, List<Variable> vars, int indent, int depth, int max) {
		int shown = 0;
		for (Variable v : vars) {
			if (shown == max) {
				sb.append("  ".repeat(indent)).append("- … ").append(vars.size() - max).append(" more\n");
				return;
			}
			shown++;
			sb.append("  ".repeat(indent)).append("- ").append(v.name());
			if (!v.type().isEmpty()) {
				sb.append(" (").append(v.type()).append(')');
			}
			if (!v.value().isEmpty()) {
				sb.append(" = ").append(cut(v.value()));
			}
			sb.append('\n');
			if (v.isGroup()) {
				appendVariables(sb, v.children(), indent + 1, depth, MAX_VARIABLES);
			} else if (depth < DEPTH) {
				appendVariables(sb, v.children(), indent + 1, depth + 1, MAX_CHILDREN);
			}
			if (v.more() || depth >= DEPTH && !v.isGroup() && !v.children().isEmpty()) {
				sb.append("  ".repeat(indent + 1)).append("- … (load with 'variable')\n");
			}
		}
	}

	static String cut(String s) {
		String one = s.replace("\r", "").replace('\n', ' ');
		return one.length() > MAX_VALUE ? one.substring(0, MAX_VALUE) + " …" : one;
	}
}

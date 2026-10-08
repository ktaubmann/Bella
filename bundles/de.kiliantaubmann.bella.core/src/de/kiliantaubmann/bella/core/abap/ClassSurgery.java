package de.kiliantaubmann.bella.core.abap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Block;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Kind;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;

/**
 * Targeted changes to a class source: add, re-sign, move or delete one method
 * and replace the definition, each keeping the rest of the source as it is.
 * Works on the text with {@link AbapStructureScanner}; the refusals follow
 * ARC-1's class surgery ({@code src/handlers/write/class-surgery.ts}, MIT):
 * a change that would leave a declared method without implementation, or an
 * implementation without declaration, is refused.
 */
public final class ClassSurgery {

	/** A change that cannot be made; the message tells the model what to do instead. */
	public static final class SurgeryException extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		public SurgeryException(String message) {
			super(message);
		}
	}

	/** A method declared in the class definition. */
	public record Declaration(String name, String visibility, Statement statement, boolean chained,
			boolean needsImplementation) {
	}

	public static final List<String> VISIBILITIES = List.of("public", "protected", "private");

	private ClassSurgery() {
	}

	// ---- reading ---------------------------------------------------------------

	static Block definition(String src, String cls) {
		return block(src, Kind.CLASS_DEFINITION, cls)
				.orElseThrow(() -> new SurgeryException("CLASS " + cls.toUpperCase(Locale.ROOT)
						+ " DEFINITION is not in this source."));
	}

	static Optional<Block> implementation(String src, String cls) {
		return block(src, Kind.CLASS_IMPLEMENTATION, cls);
	}

	private static Optional<Block> block(String src, Kind kind, String name) {
		return AbapStructureScanner.blocks(src).stream()
				.filter(b -> b.kind() == kind && b.name().equalsIgnoreCase(name)).findFirst();
	}

	/** The methods declared in the definition of {@code cls}, with their section. */
	public static List<Declaration> declarations(String src, String cls) {
		Block def = definition(src, cls);
		List<Declaration> out = new ArrayList<>();
		String section = "public";
		for (Statement st : AbapStructureScanner.statements(src)) {
			if (st.start() < def.headerEnd() || st.start() >= def.footerStart()) {
				continue;
			}
			String code = st.text().trim().toUpperCase(Locale.ROOT);
			Matcher sec = SECTION.matcher(code);
			if (sec.matches()) {
				section = sec.group(1).toLowerCase(Locale.ROOT);
				continue;
			}
			if (!code.startsWith("METHODS") && !code.startsWith("CLASS-METHODS")) {
				continue;
			}
			String rest = code.substring(code.indexOf("METHODS") + 7).trim();
			boolean chained = rest.startsWith(":");
			for (String part : topLevel(chained ? rest.substring(1) : rest)) {
				String p = part.trim();
				if (p.isEmpty()) {
					continue;
				}
				String name = p.split("[\\s.]+")[0];
				boolean noBody = p.matches("(?s).*\\bABSTRACT\\b.*");
				out.add(new Declaration(name, section, st, chained, !noBody));
			}
		}
		return out;
	}

	private static final Pattern SECTION = Pattern.compile("(PUBLIC|PROTECTED|PRIVATE)\\s+SECTION\\s*\\.");

	/** Whether the implementation of {@code cls} has a METHOD block for {@code method}. */
	static Optional<Block> methodBlock(String src, String cls, String method) {
		Optional<Block> impl = implementation(src, cls);
		if (impl.isEmpty()) {
			return Optional.empty();
		}
		return AbapStructureScanner.blocks(src).stream()
				.filter(b -> b.kind() == Kind.METHOD && b.name().equalsIgnoreCase(method)
						&& b.start() > impl.get().start() && b.end() <= impl.get().end())
				.findFirst();
	}

	// ---- changes ---------------------------------------------------------------

	/**
	 * Adds a method: the {@code METHODS} clause at the end of a section and an
	 * empty {@code METHOD … ENDMETHOD} in the implementation (none for
	 * abstract methods).
	 */
	public static String addMethod(String src, String cls, String clause, String visibility) {
		String vis = visibility(visibility);
		String decl = clause(clause);
		String name = methodName(decl);
		boolean isAbstract = decl.toUpperCase(Locale.ROOT).matches("(?s).*\\bABSTRACT\\b.*");
		if (declarations(src, cls).stream().anyMatch(d -> d.name().equalsIgnoreCase(name))) {
			throw new SurgeryException("Method " + name + " is already declared; change its signature with "
					+ "edit_method_signature.");
		}
		if (methodBlock(src, cls, name).isPresent()) {
			throw new SurgeryException("The class already implements " + name + "; change the body with "
					+ "adt_write_source 'method'.");
		}
		if (!isAbstract && implementation(src, cls).isEmpty()) {
			throw new SurgeryException("The class has no IMPLEMENTATION; declare the method ABSTRACT or add the "
					+ "implementation with edit_class_definition first.");
		}
		String withDecl = insertInSection(src, cls, vis, decl);
		return isAbstract ? withDecl : insertStub(withDecl, cls, name);
	}

	/** Replaces the {@code METHODS} clause of one method; the body stays as it is. */
	public static String editMethodSignature(String src, String cls, String method, String clause) {
		Declaration d = declared(src, cls, method);
		String decl = clause(clause);
		if (!methodName(decl).equalsIgnoreCase(d.name())) {
			throw new SurgeryException("The new clause declares " + methodName(decl) + ", not " + d.name()
					+ "; renaming a method is not a signature change.");
		}
		Statement st = d.statement();
		return src.substring(0, st.start()) + decl + src.substring(st.end());
	}

	/** Removes the declaration and the implementation of one method. */
	public static String deleteMethod(String src, String cls, String method) {
		Declaration d = declared(src, cls, method);
		Optional<Block> body = methodBlock(src, cls, d.name());
		Statement st = d.statement();
		if (body.isEmpty()) {
			return removeLines(src, st.start(), st.end());
		}
		// remove the later range first, so the offsets of the earlier one stay valid
		if (body.get().start() > st.start()) {
			return removeLines(removeLines(src, body.get().start(), body.get().end()), st.start(), st.end());
		}
		return removeLines(removeLines(src, st.start(), st.end()), body.get().start(), body.get().end());
	}

	/** Moves the declaration of one method to another section; the body stays as it is. */
	public static String changeVisibility(String src, String cls, String method, String visibility) {
		String vis = visibility(visibility);
		Declaration d = declared(src, cls, method);
		if (d.visibility().equals(vis)) {
			return src;
		}
		if (d.statement().text().toUpperCase(Locale.ROOT).contains(" REDEFINITION")) {
			throw new SurgeryException("A redefinition keeps the visibility of the superclass.");
		}
		// the ABAP Doc comment moves with the declaration
		String decl = src.substring(docStart(src, lineStart(src, d.statement().start())), d.statement().end()).strip();
		String without = removeLines(src, d.statement().start(), d.statement().end());
		return insertInSection(without, cls, vis, decl);
	}

	/**
	 * Replaces {@code CLASS cls DEFINITION … ENDCLASS.} Refused when a method
	 * would be declared without implementation or implemented without
	 * declaration.
	 */
	public static String editDefinition(String src, String cls, String newDefinition) {
		Block def = definition(src, cls);
		String text = newDefinition.strip();
		Block newDef = AbapStructureScanner.blocks(text).stream().filter(b -> b.kind() == Kind.CLASS_DEFINITION)
				.findFirst().orElseThrow(() -> new SurgeryException("The new source must be CLASS "
						+ cls.toUpperCase(Locale.ROOT) + " DEFINITION … ENDCLASS."));
		if (!newDef.name().equalsIgnoreCase(cls) || newDef.start() != 0 || newDef.end() != text.length()) {
			throw new SurgeryException("Give only the block CLASS " + cls.toUpperCase(Locale.ROOT)
					+ " DEFINITION … ENDCLASS., without the implementation.");
		}
		Set<String> oldNames = names(declarations(src, cls), false);
		List<Declaration> newDecls = declarations(text, cls);
		Set<String> newNames = names(newDecls, false);
		List<String> missing = new ArrayList<>();
		for (Declaration d : newDecls) {
			if (d.needsImplementation() && !oldNames.contains(d.name()) && methodBlock(src, cls, d.name()).isEmpty()
					&& implementation(src, cls).isPresent()) {
				missing.add(d.name());
			}
		}
		List<String> orphans = new ArrayList<>();
		for (String n : oldNames) {
			if (!newNames.contains(n) && methodBlock(src, cls, n).isPresent()) {
				orphans.add(n);
			}
		}
		if (!missing.isEmpty() || !orphans.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			if (!missing.isEmpty()) {
				sb.append("The new definition declares ").append(String.join(", ", missing))
						.append(" without implementation; use add_method for new methods. ");
			}
			if (!orphans.isEmpty()) {
				sb.append("It drops ").append(String.join(", ", orphans))
						.append(", which are still implemented; use delete_method to remove a method.");
			}
			throw new SurgeryException(sb.toString().trim());
		}
		return src.substring(0, def.start()) + text + src.substring(def.end());
	}

	// ---- helpers ---------------------------------------------------------------

	private static Set<String> names(List<Declaration> decls, boolean withAbstract) {
		Set<String> out = new LinkedHashSet<>();
		decls.stream().filter(d -> withAbstract || d.needsImplementation()).forEach(d -> out.add(d.name()));
		return out;
	}

	private static Declaration declared(String src, String cls, String method) {
		String wanted = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
		List<Declaration> decls = declarations(src, cls);
		Declaration d = decls.stream().filter(x -> x.name().equals(wanted)).findFirst()
				.orElseThrow(() -> new SurgeryException("Method " + wanted + " is not declared in "
						+ cls.toUpperCase(Locale.ROOT) + (wanted.contains("~") ? " (interface methods are declared in "
								+ "the interface)" : "") + ". Declared: "
						+ String.join(", ", decls.stream().map(Declaration::name).toList()) + "."));
		if (d.chained()) {
			throw new SurgeryException(d.name() + " is declared in a chained METHODS: statement; change it with "
					+ "edit_class_definition.");
		}
		return d;
	}

	private static String visibility(String visibility) {
		String v = visibility == null || visibility.isBlank() ? "public" : visibility.trim().toLowerCase(Locale.ROOT);
		if (!VISIBILITIES.contains(v)) {
			throw new SurgeryException("visibility is public, protected or private.");
		}
		return v;
	}

	private static String clause(String clause) {
		String c = clause == null ? "" : clause.strip();
		String upper = c.toUpperCase(Locale.ROOT);
		if (upper.matches("(?s)(CLASS-)?METHODS\\s*:.*")) {
			throw new SurgeryException("Give one METHODS clause without the chain colon.");
		}
		if (!upper.matches("(?s)(CLASS-)?METHODS\\s+\\S.*")) {
			throw new SurgeryException("Give the complete METHODS clause, e.g. METHODS run IMPORTING iv_id TYPE i.");
		}
		return c.endsWith(".") ? c : c + ".";
	}

	private static String methodName(String clause) {
		String[] w = clause.trim().split("[\\s.]+");
		return w.length > 1 ? w[1].toUpperCase(Locale.ROOT) : "";
	}

	/** Splits at commas outside parentheses and literals (literals are not expected in declarations). */
	private static List<String> topLevel(String s) {
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

	/** Inserts a declaration as the last statement of a section. */
	private static String insertInSection(String src, String cls, String visibility, String decl) {
		Block def = definition(src, cls);
		Statement header = null;
		Statement next = null;
		for (Statement st : AbapStructureScanner.statements(src)) {
			if (st.start() < def.headerEnd() || st.start() >= def.end()) {
				continue;
			}
			String code = st.text().trim().toUpperCase(Locale.ROOT);
			boolean isSection = SECTION.matcher(code).matches();
			if (header == null) {
				if (isSection && code.startsWith(visibility.toUpperCase(Locale.ROOT))) {
					header = st;
				}
			} else if (isSection || st.start() >= def.footerStart()) {
				next = st;
				break;
			}
		}
		if (header == null) {
			throw new SurgeryException("There is no " + visibility.toUpperCase(Locale.ROOT)
					+ " SECTION; add it with edit_class_definition first.");
		}
		int at = lineStart(src, next == null ? def.footerStart() : next.start());
		String indent = AbapEdit.indentationOfLine(src, header.start()) + "  ";
		StringBuilder lines = new StringBuilder();
		for (String l : decl.split("\n")) {
			lines.append(indent).append(l.strip()).append('\n');
		}
		return src.substring(0, at) + lines + src.substring(at);
	}

	/** Adds an empty METHOD … ENDMETHOD before the ENDCLASS of the implementation. */
	private static String insertStub(String src, String cls, String name) {
		Block impl = implementation(src, cls).orElseThrow();
		int at = lineStart(src, impl.footerStart());
		String indent = AbapEdit.indentationOfLine(src, impl.start()) + "  ";
		String before = src.substring(0, at);
		boolean afterHeader = before.strip().toUpperCase(Locale.ROOT).endsWith("IMPLEMENTATION.");
		String gap = before.endsWith("\n\n") || afterHeader ? "" : "\n";
		return before + gap + indent + "METHOD " + name.toLowerCase(Locale.ROOT) + ".\n" + indent + "ENDMETHOD.\n"
				+ src.substring(at);
	}

	/**
	 * Removes {@code [from, to)}; whole lines when nothing but blanks share
	 * them, and the ABAP Doc comment lines ({@code "!}) right above.
	 */
	static String removeLines(String src, int from, int to) {
		int start = lineStart(src, from);
		int end = src.indexOf('\n', to);
		end = end < 0 ? src.length() : end + 1;
		boolean ownLines = src.substring(start, from).isBlank()
				&& src.substring(to, end).replaceFirst("\".*", "").isBlank();
		if (!ownLines) {
			return src.substring(0, from) + src.substring(to);
		}
		return src.substring(0, docStart(src, start)) + src.substring(end);
	}

	/** The start of the ABAP Doc comment lines ({@code "!}) right above the line starting at {@code lineStart}. */
	private static int docStart(String src, int lineStart) {
		int start = lineStart;
		while (start > 0) {
			int prev = lineStart(src, start - 1);
			if (!src.substring(prev, start).trim().startsWith("\"!")) {
				break;
			}
			start = prev;
		}
		return start;
	}

	private static int lineStart(String src, int offset) {
		int nl = src.lastIndexOf('\n', Math.max(0, offset - 1));
		return offset == 0 ? 0 : nl + 1;
	}
}

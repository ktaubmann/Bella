package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Block;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Kind;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Statement;
import de.kiliantaubmann.bella.core.conventions.NamingRules;

/**
 * Checks declared names against the project's naming rules: data, constants,
 * field symbols and types by where they are declared (routine, class
 * definition, program), method parameters by their kind, methods, local and
 * test classes, and the names of programs, global classes and interfaces.
 */
final class NamingCheck {

	private static final Pattern LITERAL = Pattern.compile("'(?:[^']|'')*'|`(?:[^`]|``)*`|\\|[^|]*\\|");
	private static final Pattern INLINE_DATA = Pattern.compile("\\b(?:DATA|FINAL)\\(\\s*([A-Za-z0-9_/]+)\\s*\\)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern INLINE_FS = Pattern.compile("\\bFIELD-SYMBOL\\(\\s*(<[^>\\s]+>)\\s*\\)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern PARAM = Pattern.compile(
			"(?:VALUE|REFERENCE)\\(\\s*([A-Za-z0-9_/]+)\\s*\\)|\\b([A-Za-z0-9_/]+)\\s+(?=TYPE\\b|LIKE\\b)",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SECTION = Pattern.compile("\\b(IMPORTING|EXPORTING|CHANGING|RETURNING|RAISING|EXCEPTIONS)\\b",
			Pattern.CASE_INSENSITIVE);

	/** Where a declaration stands. */
	private enum Place {
		ROUTINE, CLASS_DEFINITION, INTERFACE, PROGRAM
	}

	private record Name(NamingRules.Kind kind, String name, int offset) {
	}

	private NamingCheck() {
	}

	static List<AbapLint.Finding> check(String source, NamingRules rules, int[] lineStarts) {
		List<AbapLint.Finding> out = new ArrayList<>();
		if (rules == null || rules.isEmpty()) {
			return out;
		}
		List<Block> blocks = AbapStructureScanner.blocks(source);
		Set<String> reported = new LinkedHashSet<>();
		for (Name n : names(source, blocks)) {
			Optional<NamingRules.Rule> rule = rules.rule(n.kind());
			if (rule.isEmpty() || rule.get().matches(n.name()) || !reported.add(n.kind() + " " + n.name().toUpperCase(Locale.ROOT))) {
				continue;
			}
			out.add(new AbapLint.Finding(AbapLint.lineOf(lineStarts, n.offset()), "naming", AbapLint.Severity.WARNING,
					n.kind().label() + " \"" + n.name() + "\" should match " + String.join(", ", rule.get().patterns())));
		}
		return out;
	}

	private static Place place(List<Block> blocks, int offset) {
		Block best = null;
		for (Block b : blocks) {
			if (b.contains(offset) && b.start() < offset && (best == null || b.start() > best.start())) {
				best = b;
			}
		}
		if (best == null || best.kind() == Kind.CLASS_IMPLEMENTATION) {
			return Place.PROGRAM;
		}
		return switch (best.kind()) {
		case METHOD, FORM, FUNCTION, MODULE -> Place.ROUTINE;
		case CLASS_DEFINITION -> Place.CLASS_DEFINITION;
		case INTERFACE -> Place.INTERFACE;
		default -> Place.PROGRAM;
		};
	}

	static List<Name> names(String source, List<Block> blocks) {
		List<Name> out = new ArrayList<>();
		for (Statement st : AbapStructureScanner.statements(source)) {
			String text = LITERAL.matcher(st.text()).replaceAll("''").trim();
			if (text.endsWith(".")) {
				text = text.substring(0, text.length() - 1);
			}
			List<String> w = st.words(3);
			if (w.isEmpty()) {
				continue;
			}
			Place place = place(blocks, st.start());
			String first = w.get(0).replace(":", "");
			int at = st.start();
			switch (first) {
			case "DATA" -> {
				if (!text.toUpperCase(Locale.ROOT).startsWith("DATA(")) {
					declared(out, text, at, switch (place) {
					case ROUTINE -> NamingRules.Kind.LOCAL_DATA;
					case CLASS_DEFINITION, INTERFACE -> NamingRules.Kind.ATTRIBUTE;
					case PROGRAM -> NamingRules.Kind.GLOBAL_DATA;
					});
				}
			}
			case "CLASS-DATA" -> declared(out, text, at, NamingRules.Kind.STATIC_ATTRIBUTE);
			case "CONSTANTS" -> declared(out, text, at, NamingRules.Kind.CONSTANT);
			case "FIELD-SYMBOLS" -> declared(out, text, at, NamingRules.Kind.FIELD_SYMBOL);
			case "TYPES" -> declared(out, text, at, NamingRules.Kind.LOCAL_TYPE);
			case "METHODS", "CLASS-METHODS" -> methods(out, text, at);
			case "CLASS" -> {
				String third = w.size() > 2 ? w.get(2) : "";
				String upper = text.toUpperCase(Locale.ROOT);
				if (third.equals("DEFINITION") && !upper.contains(" DEFERRED") && !upper.matches(".*\\sLOAD$")) {
					NamingRules.Kind kind = upper.contains(" PUBLIC") ? NamingRules.Kind.CLASS
							: upper.contains("FOR TESTING") ? NamingRules.Kind.TEST_CLASS : NamingRules.Kind.LOCAL_CLASS;
					out.add(new Name(kind, rawWord(text, 1), at));
				}
			}
			case "INTERFACE" -> {
				if (text.toUpperCase(Locale.ROOT).contains(" PUBLIC")) {
					out.add(new Name(NamingRules.Kind.INTERFACE, rawWord(text, 1), at));
				}
			}
			case "REPORT", "PROGRAM" -> out.add(new Name(NamingRules.Kind.PROGRAM, rawWord(text, 1), at));
			default -> {
				// ordinary statement
			}
			}
			if (place == Place.ROUTINE || place == Place.PROGRAM) {
				NamingRules.Kind data = place == Place.ROUTINE ? NamingRules.Kind.LOCAL_DATA : NamingRules.Kind.GLOBAL_DATA;
				Matcher m = INLINE_DATA.matcher(text);
				while (m.find()) {
					out.add(new Name(data, m.group(1), at));
				}
				Matcher fs = INLINE_FS.matcher(text);
				while (fs.find()) {
					out.add(new Name(NamingRules.Kind.FIELD_SYMBOL, fs.group(1), at));
				}
			}
		}
		return out;
	}

	/** The n-th whitespace separated word, as written (without a trailing comma or colon). */
	private static String rawWord(String text, int n) {
		String[] words = text.trim().split("\\s+");
		return n < words.length ? words[n].replaceAll("[,:.]$", "") : "";
	}

	/** Chain elements of {@code KEYWORD: a ..., b ....} (or a single declaration) at top level. */
	static List<String> chain(String text) {
		int colon = text.indexOf(':');
		String body;
		if (colon >= 0) {
			body = text.substring(colon + 1);
		} else {
			int space = text.indexOf(' ');
			body = space < 0 ? "" : text.substring(space + 1);
		}
		List<String> parts = new ArrayList<>();
		int depth = 0;
		StringBuilder cur = new StringBuilder();
		for (char ch : body.toCharArray()) {
			if (ch == '(') {
				depth++;
			} else if (ch == ')') {
				depth--;
			}
			if (ch == ',' && depth == 0) {
				parts.add(cur.toString().trim());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		if (!cur.toString().isBlank()) {
			parts.add(cur.toString().trim());
		}
		return parts;
	}

	/** Names declared by DATA, CONSTANTS, TYPES, … skipping the components of BEGIN OF … END OF. */
	private static void declared(List<Name> out, String text, int at, NamingRules.Kind kind) {
		int depth = 0;
		for (String part : chain(text)) {
			String[] words = part.split("\\s+");
			if (words.length == 0 || words[0].isEmpty()) {
				continue;
			}
			String head = words[0].toUpperCase(Locale.ROOT);
			if (head.equals("BEGIN") && words.length > 2) {
				if (depth == 0) {
					out.add(new Name(kind, words[2], at));
				}
				depth++;
			} else if (head.equals("END")) {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0) {
				out.add(new Name(kind, words[0], at));
			}
		}
	}

	private static void methods(List<Name> out, String text, int at) {
		for (String part : chain(text)) {
			String[] words = part.split("\\s+");
			if (words.length == 0 || words[0].isEmpty() || words[0].contains("~")) {
				continue;
			}
			String upper = part.toUpperCase(Locale.ROOT);
			if (upper.contains(" REDEFINITION") || upper.contains(" FOR TESTING")) {
				continue;
			}
			out.add(new Name(NamingRules.Kind.METHOD, words[0], at));
			Matcher sections = SECTION.matcher(part);
			List<int[]> bounds = new ArrayList<>();
			List<String> kinds = new ArrayList<>();
			while (sections.find()) {
				bounds.add(new int[] { sections.start(), sections.end() });
				kinds.add(sections.group(1).toUpperCase(Locale.ROOT));
			}
			for (int i = 0; i < bounds.size(); i++) {
				NamingRules.Kind kind = switch (kinds.get(i)) {
				case "IMPORTING" -> NamingRules.Kind.IMPORTING;
				case "EXPORTING" -> NamingRules.Kind.EXPORTING;
				case "CHANGING" -> NamingRules.Kind.CHANGING;
				case "RETURNING" -> NamingRules.Kind.RETURNING;
				default -> null;
				};
				if (kind == null) {
					continue;
				}
				String section = part.substring(bounds.get(i)[1], i + 1 < bounds.size() ? bounds.get(i + 1)[0] : part.length());
				Matcher p = PARAM.matcher(section);
				while (p.find()) {
					String name = p.group(1) != null ? p.group(1) : p.group(2);
					String up = name.toUpperCase(Locale.ROOT);
					if (!up.equals("REF") && !up.equals("OPTIONAL") && !up.equals("DEFAULT") && !up.equals("TO")) {
						out.add(new Name(kind, name, at));
					}
				}
			}
		}
	}
}

package de.kiliantaubmann.bella.core.conventions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Naming rules of a project: per kind of name one or more patterns with
 * {@code *} and {@code ?}, case-insensitive. Written one rule per line as
 * {@code kind = pattern, pattern}; {@code #} starts a comment.
 */
public final class NamingRules {

	/** What a rule names. */
	public enum Kind {
		CLASS, INTERFACE, PROGRAM, FUNCTION_GROUP, FUNCTION_MODULE, TABLE, STRUCTURE, DATA_ELEMENT, DOMAIN, CDS_VIEW,
		PACKAGE, MESSAGE_CLASS, LOCAL_DATA, GLOBAL_DATA, ATTRIBUTE, STATIC_ATTRIBUTE, CONSTANT, FIELD_SYMBOL, IMPORTING,
		EXPORTING, CHANGING, RETURNING, METHOD, LOCAL_CLASS, TEST_CLASS, LOCAL_TYPE;

		/** The name used in the rule text, e.g. {@code local_data}. */
		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}

		/** Readable name for messages, e.g. {@code local data}. */
		public String label() {
			return key().replace('_', ' ');
		}

		static Optional<Kind> of(String key) {
			String k = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
			for (Kind kind : values()) {
				if (kind.name().equals(k)) {
					return Optional.of(kind);
				}
			}
			return Optional.empty();
		}
	}

	/** Patterns for one kind. */
	public record Rule(Kind kind, List<String> patterns) {

		public boolean matches(String name) {
			for (String p : patterns) {
				if (regex(p).matcher(name).matches()) {
					return true;
				}
			}
			return false;
		}

		static Pattern regex(String pattern) {
			StringBuilder sb = new StringBuilder();
			for (char ch : pattern.toCharArray()) {
				switch (ch) {
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				default -> sb.append(Pattern.quote(String.valueOf(ch)));
				}
			}
			return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
		}
	}

	/** Parse result: the rules and a message per line that could not be read. */
	public record Parsed(NamingRules rules, List<String> errors) {
	}

	public static final NamingRules NONE = new NamingRules(new EnumMap<>(Kind.class));

	/** Common SAP conventions, offered as a template. */
	public static final String TEMPLATE = """
			# kind = pattern, pattern   (* any characters, ? one character)
			class = ZCL_*, YCL_*
			interface = ZIF_*, YIF_*
			local_data = lv_*, lt_*, ls_*, lo_*, lr_*, lx_*
			global_data = gv_*, gt_*, gs_*, go_*, gr_*
			attribute = mv_*, mt_*, ms_*, mo_*, mr_*
			static_attribute = gv_*, gt_*, gs_*, go_*, gr_*
			constant = lc_*, gc_*, co_*
			field_symbol = <lv_*>, <lt_*>, <ls_*>, <lo_*>, <fs_*>
			importing = iv_*, it_*, is_*, io_*, ir_*
			exporting = ev_*, et_*, es_*, eo_*, er_*
			changing = cv_*, ct_*, cs_*, co_*, cr_*
			returning = rv_*, rt_*, rs_*, ro_*, rr_*
			local_class = lcl_*
			test_class = ltc_*
			local_type = ty_*, tt_*
			""";

	private final Map<Kind, Rule> rules;

	private NamingRules(Map<Kind, Rule> rules) {
		this.rules = rules;
	}

	public static Parsed parse(String text) {
		Map<Kind, Rule> rules = new EnumMap<>(Kind.class);
		List<String> errors = new ArrayList<>();
		if (text == null) {
			return new Parsed(NONE, errors);
		}
		String[] lines = text.replace("\r\n", "\n").split("\n");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int hash = line.indexOf('#');
			if (hash >= 0) {
				line = line.substring(0, hash);
			}
			if (line.isBlank()) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq < 0) {
				errors.add("Line " + (i + 1) + ": expected kind = pattern");
				continue;
			}
			Optional<Kind> kind = Kind.of(line.substring(0, eq));
			if (kind.isEmpty()) {
				errors.add("Line " + (i + 1) + ": unknown kind \"" + line.substring(0, eq).trim() + "\"");
				continue;
			}
			List<String> patterns = new ArrayList<>();
			for (String p : line.substring(eq + 1).split(",")) {
				if (!p.isBlank()) {
					patterns.add(p.trim());
				}
			}
			if (patterns.isEmpty()) {
				errors.add("Line " + (i + 1) + ": no pattern for " + kind.get().key());
				continue;
			}
			rules.put(kind.get(), new Rule(kind.get(), List.copyOf(patterns)));
		}
		return new Parsed(rules.isEmpty() ? NONE : new NamingRules(rules), errors);
	}

	public boolean isEmpty() {
		return rules.isEmpty();
	}

	public Optional<Rule> rule(Kind kind) {
		return Optional.ofNullable(rules.get(kind));
	}

	/** These rules with the rules of {@code more} replacing those of the same kind. */
	public NamingRules merge(NamingRules more) {
		if (more == null || more.isEmpty()) {
			return this;
		}
		Map<Kind, Rule> merged = new EnumMap<>(Kind.class);
		merged.putAll(rules);
		merged.putAll(more.rules);
		return new NamingRules(merged);
	}

	/** The rules in the text format, one per line. */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		for (Rule r : rules.values()) {
			sb.append(r.kind().key()).append(" = ").append(String.join(", ", r.patterns())).append('\n');
		}
		return sb.toString();
	}

	/** All kinds, for the help text of the preference page. */
	public static String kinds() {
		List<String> keys = new ArrayList<>();
		for (Kind k : Kind.values()) {
			keys.add(k.key());
		}
		return String.join(", ", keys);
	}
}

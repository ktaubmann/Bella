package de.kiliantaubmann.bella.core.mask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces confidential names and data in Bella's log with placeholders, so
 * the log file can be attached to a bug report. What goes to the model is not
 * changed.
 * <p>
 * Masked are customer objects (names starting with Z or Y, e.g.
 * {@code ZCL_ACME_ORDER} → {@code ZCL_MASK1}), the developer's own terms
 * (company, project, namespace), system data (SID, ABAP project, logon user)
 * and personal data that can be recognized by its shape (e-mail addresses,
 * IBANs). A value keeps its placeholder for the Eclipse session, so entries
 * about the same object can still be followed through the log.
 */
public final class Masker {

	/**
	 * What to mask.
	 *
	 * @param enabled  masking on at all
	 * @param objects  customer objects (Z*, Y*)
	 * @param personal e-mail addresses and IBANs
	 * @param terms    own terms, matched regardless of case; a term like
	 *                 {@code /ACME/} is treated as a namespace
	 * @param system   system ids and project names, matched as written
	 * @param users    user names, matched as written
	 */
	public record Settings(boolean enabled, boolean objects, boolean personal, List<String> terms, List<String> system,
			List<String> users) {

		public static final Settings OFF = new Settings(false, false, false, List.of(), List.of(), List.of());

		public Settings {
			terms = clean(terms);
			system = clean(system);
			users = clean(users);
		}

		private static List<String> clean(List<String> values) {
			List<String> out = new ArrayList<>();
			if (values != null) {
				for (String v : values) {
					if (v != null && v.strip().length() >= 2 && !out.contains(v.strip())) {
						out.add(v.strip());
					}
				}
			}
			return List.copyOf(out);
		}

		/** Terms from preference text: one per line or separated by commas; {@code #} starts a comment. */
		public static List<String> parseTerms(String text) {
			List<String> out = new ArrayList<>();
			if (text != null) {
				for (String line : text.split("\\R")) {
					String l = line.strip();
					if (l.isEmpty() || l.startsWith("#")) {
						continue;
					}
					for (String t : l.split(",")) {
						if (!t.isBlank()) {
							out.add(t.strip());
						}
					}
				}
			}
			return out;
		}
	}

	/** A masker that never masks. */
	public static final Masker NONE = new Masker(() -> Settings.OFF);

	private enum Kind {
		OBJECT, NAMESPACE, TERM, SYSTEM, USER, MAIL, IBAN
	}

	private record Entry(String original, String placeholder, Kind kind) {
	}

	private static final String IDENT = "A-Za-z0-9_";
	/** Roots of CDS annotations, which look like the domain of an address after a name ({@code x@Semantics.amount}). */
	private static final String CDS_ANNOTATIONS = "(?:AbapCatalog|AccessControl|Aggregation|Analytics|AnalyticsDetails"
			+ "|ClientHandling|Consumption|DataAging|DefaultAggregation|EndUserText|Environment|Hierarchy|Metadata"
			+ "|ObjectModel|OData|Search|Semantics|UI|VDM)";
	/**
	 * Escaped line breaks and tabs in JSON or ABAP strings ({@code \nZREPORT})
	 * also start a word, although the character before the name is a letter.
	 */
	private static final String AFTER_ESCAPE = "(?<=\\\\[nrt])";
	private static final Pattern CUSTOMER_OBJECT = Pattern.compile("(?:(?<![" + IDENT + "])|" + AFTER_ESCAPE + ")[ZzYy][" + IDENT + "]{2,39}(?![" + IDENT + "])");
	/**
	 * Not right after a backslash: in JSON {@code \n@EndUserText.label} is a
	 * line break before a CDS annotation, no address.
	 */
	private static final Pattern MAIL = Pattern.compile("(?:(?<![" + IDENT + ".+\\\\-])|" + AFTER_ESCAPE + ")[A-Za-z0-9._%+-]+@(?!" + CDS_ANNOTATIONS
			+ "\\.)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![" + IDENT + "])");
	private static final Pattern IBAN = Pattern.compile("(?:(?<![" + IDENT + "])|" + AFTER_ESCAPE + ")[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?(?![" + IDENT + "])");
	/** Upper-case words starting with Z or Y that are no customer objects. */
	private static final Set<String> NOT_OBJECTS = Set.of("YES", "YEAR", "YEARS", "YET", "YOU", "YOUR", "YOURS", "YTD",
			"ZERO", "ZEROS", "ZONE", "ZONES", "ZIP", "ZOOM", "YAML", "YIELD");

	private final Supplier<Settings> settings;
	private final Map<String, Entry> byOriginal = new HashMap<>();
	private final Map<String, Entry> byPlaceholder = new HashMap<>();
	private int counter;

	public Masker(Supplier<Settings> settings) {
		this.settings = settings;
	}

	public boolean active() {
		return settings.get().enabled();
	}

	/** Text with confidential values replaced by placeholders. */
	public synchronized String mask(String text) {
		Settings s = settings.get();
		if (!s.enabled() || text == null || text.isEmpty()) {
			return text;
		}
		String out = text;
		for (String term : byLength(s.terms())) {
			out = replaceLiteral(out, term, true, isNamespace(term) ? Kind.NAMESPACE : Kind.TERM);
		}
		for (String sys : byLength(s.system())) {
			out = replaceLiteral(out, sys, false, Kind.SYSTEM);
		}
		for (String user : byLength(s.users())) {
			// as written: a user called ADMIN must not turn every "admin" into a placeholder
			out = replaceLiteral(out, user, false, Kind.USER);
		}
		if (s.personal()) {
			out = replacePattern(out, MAIL, Kind.MAIL);
			out = replacePattern(out, IBAN, Kind.IBAN);
		}
		if (s.objects()) {
			out = maskObjects(out);
		}
		return out;
	}

	private static List<String> byLength(List<String> values) {
		return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
	}

	private static boolean isNamespace(String term) {
		return term.matches("/[A-Za-z0-9]{1,10}/");
	}

	private String replaceLiteral(String text, String value, boolean ignoreCase, Kind kind) {
		Pattern p = Pattern.compile(boundaryBefore(value) + Pattern.quote(value) + boundaryAfter(value),
				ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
		Matcher m = p.matcher(text);
		if (!m.find()) {
			return text;
		}
		Entry e = entry(kind == Kind.NAMESPACE ? value.toUpperCase(Locale.ROOT) : value, kind);
		StringBuilder sb = new StringBuilder();
		do {
			m.appendReplacement(sb, Matcher.quoteReplacement(sameCase(m.group(), e)));
		} while (m.find());
		m.appendTail(sb);
		return sb.toString();
	}

	private String replacePattern(String text, Pattern p, Kind kind) {
		Matcher m = p.matcher(text);
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		while (m.find()) {
			any = true;
			String found = m.group();
			String replacement = isPlaceholder(found) ? found : entry(found, kind).placeholder();
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		if (!any) {
			return text;
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private String maskObjects(String text) {
		Matcher m = CUSTOMER_OBJECT.matcher(text);
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		while (m.find()) {
			String found = m.group();
			String upper = found.toUpperCase(Locale.ROOT);
			Entry known = byOriginal.get(upper);
			String replacement = found;
			if (known != null) {
				replacement = sameCase(found, known);
			} else if (isCustomerObject(found) && !isPlaceholder(found)) {
				replacement = sameCase(found, entry(upper, Kind.OBJECT));
			}
			any |= !replacement.equals(found);
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		}
		if (!any) {
			return text;
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Z or Y names: upper case as in {@code ZREPORT}, or lower case with an
	 * underscore as in {@code zcl_order} (code is often written in lower case;
	 * a lower-case word without underscore is masked only once it is known).
	 */
	private static boolean isCustomerObject(String token) {
		if (token.equals(token.toUpperCase(Locale.ROOT))) {
			return !NOT_OBJECTS.contains(token) && token.chars().anyMatch(Character::isLetter);
		}
		return token.equals(token.toLowerCase(Locale.ROOT)) && token.indexOf('_') > 0;
	}

	private Entry entry(String original, Kind kind) {
		String key = kind == Kind.SYSTEM || kind == Kind.USER || kind == Kind.IBAN ? original
				: original.toUpperCase(Locale.ROOT);
		Entry e = byOriginal.get(key);
		if (e != null) {
			return e;
		}
		counter++;
		String placeholder = switch (kind) {
		case OBJECT -> objectPrefix(original) + "MASK" + counter;
		case NAMESPACE -> "/MASK" + counter + "/";
		case TERM -> "MASKTERM" + counter;
		case SYSTEM -> "MASKSYS" + counter;
		case USER -> "MASKUSER" + counter;
		case MAIL -> "maskmail" + counter + "@example.invalid";
		case IBAN -> "MASKIBAN" + counter;
		};
		e = new Entry(original, placeholder, kind);
		byOriginal.put(key, e);
		byPlaceholder.put(placeholder.toUpperCase(Locale.ROOT), e);
		return e;
	}

	/** {@code ZCL_ACME_ORDER} → {@code ZCL_}; {@code ZACME_REPORT} → {@code Z}. */
	private static String objectPrefix(String upper) {
		int underscore = upper.indexOf('_');
		return underscore > 0 && underscore <= 4 ? upper.substring(0, underscore + 1) : upper.substring(0, 1);
	}

	/** Keeps lower case code lower case: {@code zcl_acme} → {@code zcl_mask1}. */
	private static String sameCase(String found, Entry e) {
		boolean lower = found.equals(found.toLowerCase(Locale.ROOT)) && !found.equals(found.toUpperCase(Locale.ROOT));
		return lower && (e.kind() == Kind.OBJECT || e.kind() == Kind.NAMESPACE)
				? e.placeholder().toLowerCase(Locale.ROOT)
				: e.placeholder();
	}

	private boolean isPlaceholder(String token) {
		return byPlaceholder.containsKey(token.toUpperCase(Locale.ROOT));
	}

	private static String boundaryBefore(String value) {
		return Character.isLetterOrDigit(value.charAt(0)) || value.charAt(0) == '_'
				? "(?:(?<![" + IDENT + "])|" + AFTER_ESCAPE + ")"
				: "";
	}

	private static String boundaryAfter(String value) {
		char last = value.charAt(value.length() - 1);
		return Character.isLetterOrDigit(last) || last == '_' ? "(?![" + IDENT + "])" : "";
	}
}

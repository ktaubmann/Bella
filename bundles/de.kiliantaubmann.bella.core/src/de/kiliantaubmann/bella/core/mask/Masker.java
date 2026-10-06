package de.kiliantaubmann.bella.core.mask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Replaces sensitive names and data with placeholders before text goes to the
 * model, and puts the originals back into what comes from the model (answers,
 * code, tool inputs). The mapping is stable for the Eclipse session, so the
 * model sees the same placeholder for an object in every message, and code it
 * writes with placeholders lands in SAP and the editor with the real names.
 * <p>
 * Masked are customer objects (names starting with Z or Y, e.g.
 * {@code ZCL_ACME_ORDER} → {@code ZCL_MASK1}), the developer's own terms
 * (company, project, namespace), system data (SID, ABAP project, logon user)
 * and personal data that can be recognized by its shape (e-mail addresses,
 * IBANs). Names the model brings up itself, such as a class it wants to
 * create, are not secret to it and stay as they are.
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

	/** Tells the model what the placeholders are; added to the system prompt while masking is on. */
	public static final String PROMPT_NOTE = """

			Masking: confidential names and data in this conversation are replaced by placeholders such as \
			ZCL_MASK1, ZMASK2, /MASK3/, MASKTERM4, MASKSYS5, MASKUSER6, maskmail7@example.invalid or MASKIBAN8. \
			Treat each placeholder as the real name: use it exactly as written in code, tool calls and answers \
			(Bella puts the real values back before anything reaches the editor or the SAP system). Do not ask for \
			or guess the real values, and do not invent new names that look like placeholders.
			""";

	/** A masker that never masks. */
	public static final Masker NONE = new Masker(() -> Settings.OFF);

	private enum Kind {
		OBJECT, NAMESPACE, TERM, SYSTEM, USER, MAIL, IBAN
	}

	private record Entry(String original, String placeholder, Kind kind) {
	}

	private static final String IDENT = "A-Za-z0-9_";
	private static final Pattern CUSTOMER_OBJECT = Pattern.compile("(?<![" + IDENT + "/])[ZzYy][" + IDENT + "]{2,39}(?![" + IDENT + "])");
	private static final Pattern MAIL = Pattern.compile("(?<![" + IDENT + ".+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?![" + IDENT + "])");
	private static final Pattern IBAN = Pattern.compile("(?<![" + IDENT + "])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?(?![" + IDENT + "])");
	/** Upper-case words starting with Z or Y that are no customer objects. */
	private static final Set<String> NOT_OBJECTS = Set.of("YES", "YEAR", "YEARS", "YET", "YOU", "YOUR", "YOURS", "YTD",
			"ZERO", "ZEROS", "ZONE", "ZONES", "ZIP", "ZOOM", "YAML", "YIELD");
	/** Characters a placeholder can consist of; text ending in them is held back while streaming. */
	private static final Pattern TRAILING_TOKEN = Pattern.compile("[" + IDENT + "/@.%+-]+$");
	private static final int MAX_HOLD_BACK = 200;

	private final Supplier<Settings> settings;
	private final Map<String, Entry> byOriginal = new HashMap<>();
	private final Map<String, Entry> byPlaceholder = new HashMap<>();
	/** Customer names the model introduced itself (upper case); they are not masked. */
	private final Set<String> modelKnown = new HashSet<>();
	private int counter;
	private Pattern placeholderPattern;
	private int placeholderPatternSize = -1;

	public Masker(Supplier<Settings> settings) {
		this.settings = settings;
	}

	public boolean active() {
		return settings.get().enabled();
	}

	/** Number of values masked so far in this session. */
	public synchronized int size() {
		return byOriginal.size();
	}

	// ---- masking ----------------------------------------------------------------

	/** Text for the model: sensitive values replaced by placeholders. */
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
			// as written: a user called ADMIN must not turn every "admin" in the text into a placeholder
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
		Entry e = entry(kind == Kind.NAMESPACE || kind == Kind.USER ? value.toUpperCase(Locale.ROOT) : value, kind);
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
			} else if (isCustomerObject(found) && !isPlaceholder(found) && !modelKnown.contains(upper)) {
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
		String key = key(original, kind);
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

	private static String key(String original, Kind kind) {
		return kind == Kind.SYSTEM || kind == Kind.IBAN ? original : original.toUpperCase(Locale.ROOT);
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
		return Character.isLetterOrDigit(value.charAt(0)) || value.charAt(0) == '_' ? "(?<![" + IDENT + "])" : "";
	}

	private static String boundaryAfter(String value) {
		char last = value.charAt(value.length() - 1);
		return Character.isLetterOrDigit(last) || last == '_' ? "(?![" + IDENT + "])" : "";
	}

	// ---- unmasking ----------------------------------------------------------------

	/**
	 * Text from the model with the originals put back. Customer names the model
	 * wrote itself are remembered and not masked later. Works also after
	 * masking was switched off, for placeholders still in a running chat.
	 */
	public synchronized String unmask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		Settings s = settings.get();
		if (s.enabled() && s.objects()) {
			Matcher m = CUSTOMER_OBJECT.matcher(text);
			while (m.find()) {
				String upper = m.group().toUpperCase(Locale.ROOT);
				if (!isPlaceholder(upper) && !byOriginal.containsKey(upper) && isCustomerObject(m.group())) {
					modelKnown.add(upper);
				}
			}
		}
		if (byPlaceholder.isEmpty()) {
			return text;
		}
		Matcher m = placeholders().matcher(text);
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		while (m.find()) {
			Entry e = byPlaceholder.get(m.group().toUpperCase(Locale.ROOT));
			if (e == null) {
				continue;
			}
			any = true;
			String found = m.group();
			boolean lower = found.equals(found.toLowerCase(Locale.ROOT))
					&& (e.kind() == Kind.OBJECT || e.kind() == Kind.NAMESPACE);
			m.appendReplacement(sb, Matcher.quoteReplacement(lower ? e.original().toLowerCase(Locale.ROOT) : e.original()));
		}
		if (!any) {
			return text;
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private Pattern placeholders() {
		if (placeholderPattern == null || placeholderPatternSize != byPlaceholder.size()) {
			StringBuilder alt = new StringBuilder();
			byPlaceholder.values().stream().map(Entry::placeholder)
					.sorted(Comparator.comparingInt(String::length).reversed()).forEach(p -> {
						if (alt.length() > 0) {
							alt.append('|');
						}
						alt.append(boundaryBefore(p)).append(Pattern.quote(p)).append(boundaryAfter(p));
					});
			placeholderPattern = Pattern.compile(alt.toString(), Pattern.CASE_INSENSITIVE);
			placeholderPatternSize = byPlaceholder.size();
		}
		return placeholderPattern;
	}

	// ---- JSON and streams ------------------------------------------------------------

	/** A copy of {@code json} with every string value masked; keys stay. */
	public JsonElement mask(JsonElement json) {
		return map(json, true);
	}

	/** A copy of {@code json} with every string value unmasked; keys stay. */
	public JsonElement unmask(JsonElement json) {
		return map(json, false);
	}

	public JsonObject mask(JsonObject json) {
		return json == null ? null : map(json, true).getAsJsonObject();
	}

	public JsonObject unmask(JsonObject json) {
		return json == null ? null : map(json, false).getAsJsonObject();
	}

	/** Keys of a message in Messages format whose values are protocol data, not content. */
	private static final Set<String> PROTOCOL_KEYS = Set.of("type", "role", "id", "tool_use_id", "signature", "data",
			"cache_control");

	/** A message in Anthropic Messages format with its content masked; ids and thinking signatures stay. */
	public JsonObject maskMessage(JsonObject message) {
		return message == null ? null : map(message, true, PROTOCOL_KEYS).getAsJsonObject();
	}

	/** A message from the model with its content unmasked; ids and thinking signatures stay. */
	public JsonObject unmaskMessage(JsonObject message) {
		return message == null ? null : map(message, false, PROTOCOL_KEYS).getAsJsonObject();
	}

	private JsonElement map(JsonElement e, boolean mask) {
		return map(e, mask, Set.of());
	}

	private JsonElement map(JsonElement e, boolean mask, Set<String> skip) {
		if (e == null || e.isJsonNull() || (mask && !active())) {
			return e;
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			return p.isString() ? new JsonPrimitive(mask ? mask(p.getAsString()) : unmask(p.getAsString())) : p;
		}
		if (e.isJsonArray()) {
			JsonArray out = new JsonArray();
			e.getAsJsonArray().forEach(x -> out.add(map(x, mask, skip)));
			return out;
		}
		JsonObject out = new JsonObject();
		e.getAsJsonObject().entrySet().forEach(x -> out.add(x.getKey(),
				skip.contains(x.getKey()) ? x.getValue().deepCopy() : map(x.getValue(), mask, skip)));
		return out;
	}

	/**
	 * Unmasks streamed text. A placeholder can be split across two deltas, so
	 * the trailing word of each delta is held back until the next one (or
	 * {@link UnmaskStream#flush()}) completes it.
	 */
	public UnmaskStream stream(Consumer<String> out) {
		return new UnmaskStream(out);
	}

	public final class UnmaskStream {
		private final Consumer<String> out;
		private final StringBuilder pending = new StringBuilder();

		private UnmaskStream(Consumer<String> out) {
			this.out = out;
		}

		public void accept(String delta) {
			if (delta == null || delta.isEmpty()) {
				return;
			}
			if (!active() && size() == 0) {
				flush();
				out.accept(delta);
				return;
			}
			String ready;
			synchronized (pending) {
				pending.append(delta);
				Matcher m = TRAILING_TOKEN.matcher(pending);
				int cut = m.find() && pending.length() - m.start() <= MAX_HOLD_BACK ? m.start() : pending.length();
				ready = pending.substring(0, cut);
				pending.delete(0, cut);
			}
			if (!ready.isEmpty()) {
				out.accept(unmask(ready));
			}
		}

		public void flush() {
			String rest;
			synchronized (pending) {
				rest = pending.toString();
				pending.setLength(0);
			}
			if (!rest.isEmpty()) {
				out.accept(unmask(rest));
			}
		}
	}
}

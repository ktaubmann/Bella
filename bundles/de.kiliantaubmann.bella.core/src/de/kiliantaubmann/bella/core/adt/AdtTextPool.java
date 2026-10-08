package de.kiliantaubmann.bella.core.adt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text formats of ADT's textelements service: what a write sends and how
 * what SAP reads back compares with it.
 */
final class AdtTextPool {

	private static final Pattern MAX_LENGTH = Pattern.compile("@MaxLength\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ENTRY = Pattern.compile("([^=]+?)\\s*=(.*)");
	/** Longest room for translations Bella proposes on its own. */
	static final int MAX_SYMBOL_LENGTH = 132;

	private AdtTextPool() {
	}

	/**
	 * The texts in the form SAP accepts, as ADT and ARC-1 send them: each text
	 * symbol gets a {@code @MaxLength} line (SAP rejects a pool without one
	 * with HTTP 406 "Text elements contain errors"), one shorter than the text
	 * is raised, and the symbols are separated by blank lines; selection names
	 * are sent upper case and unpadded, the list heading is followed by a blank
	 * line. Every line ends with a line break.
	 */
	static String normalize(String part, String texts) {
		StringBuilder out = new StringBuilder();
		Integer pending = null;
		for (String raw : texts.replace("\r\n", "\n").split("\n")) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			Matcher max = MAX_LENGTH.matcher(line);
			if (max.matches()) {
				pending = Integer.valueOf(max.group(1));
				continue;
			}
			Matcher e = ENTRY.matcher(line);
			if (!e.matches()) {
				out.append(line).append('\n');
				continue;
			}
			String key = e.group(1).strip();
			String text = e.group(2);
			switch (part) {
			case "symbols" -> {
				if (!out.isEmpty()) {
					out.append('\n');
				}
				int length = text.length();
				int limit = pending == null ? defaultMaxLength(length) : Math.max(pending, length);
				out.append("@MaxLength:").append(limit).append('\n').append(key).append('=').append(text)
						.append('\n');
			}
			case "selections" -> out.append(key.toUpperCase(Locale.ROOT)).append('=').append(text).append('\n');
			default -> {
				out.append(key).append('=').append(text).append('\n');
				if (key.equalsIgnoreCase("listHeader")) {
					out.append('\n');
				}
			}
			}
			pending = null;
		}
		return out.toString();
	}

	/**
	 * The room left for translations when none is given, after ADT: short
	 * texts get 10 characters more, longer ones half their length.
	 */
	static int defaultMaxLength(int length) {
		int limit = length < 20 ? length + 10 : length + (length + 1) / 2;
		return Math.min(Math.max(limit, 10), Math.max(MAX_SYMBOL_LENGTH, length));
	}

	/** The entries of a part, key to text; {@code @MaxLength} lines and blank lines left out. */
	static Map<String, String> entries(String texts) {
		Map<String, String> out = new LinkedHashMap<>();
		if (texts == null) {
			return out;
		}
		for (String raw : texts.replace("\r\n", "\n").split("\n")) {
			String line = raw.strip();
			if (line.isEmpty() || MAX_LENGTH.matcher(line).matches()) {
				continue;
			}
			Matcher e = ENTRY.matcher(line);
			if (e.matches()) {
				out.put(e.group(1).strip().toUpperCase(Locale.ROOT), e.group(2).strip());
			}
		}
		return out;
	}

	/**
	 * Entries SAP did not store as written, one line each ({@code KEY: wrote
	 * 'x', reads 'y'}); empty when all came back.
	 */
	static List<String> differences(String written, String readBack) {
		Map<String, String> read = entries(readBack);
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, String> w : entries(written).entrySet()) {
			String got = read.get(w.getKey());
			if (!w.getValue().equals(got)) {
				out.add(w.getKey() + ": wrote '" + w.getValue() + "', reads "
						+ (got == null ? "nothing" : "'" + got + "'"));
			}
		}
		return out;
	}
}

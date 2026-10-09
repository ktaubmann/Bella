package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Text symbols that code names as {@code 'Text'(001)}: which ones the text
 * pool lacks, and adding them. Editor actions use it, so that a literal they
 * turn into a text symbol also gets its entry in the pool.
 */
public final class AdtTextSymbols {

	/**
	 * What the code needs from the pool.
	 *
	 * @param missing   id to text, for ids the pool does not have
	 * @param differing id to the pool's text, for ids the pool has with another text than the code
	 */
	public record Plan(Map<String, String> missing, Map<String, String> differing) {

		public boolean isEmpty() {
			return missing.isEmpty() && differing.isEmpty();
		}
	}

	private AdtTextSymbols() {
	}

	/**
	 * Whether an editor object has a text pool of its own: programs and
	 * classes. Includes and function modules use the pool of their main
	 * program or function group.
	 */
	public static boolean hasOwnPool(AdtEditorObject object) {
		String type = object.type() == null ? "" : object.type().toUpperCase(Locale.ROOT);
		return type.startsWith("PROG/P") || type.startsWith("CLAS");
	}

	/**
	 * The text symbols with a literal in the code, id (upper case) to text;
	 * comments are skipped. For an id used with several texts the first counts.
	 */
	public static Map<String, String> inCode(String code) {
		Map<String, String> out = new LinkedHashMap<>();
		if (code == null) {
			return out;
		}
		for (String line : code.replace("\r\n", "\n").split("\n")) {
			if (line.startsWith("*")) {
				continue;
			}
			scanLine(line, out);
		}
		return out;
	}

	private static void scanLine(String line, Map<String, String> out) {
		int i = 0;
		int n = line.length();
		while (i < n) {
			char c = line.charAt(i);
			if (c == '"') {
				return;
			}
			if (c == '`' || c == '|') {
				// string templates and backquote literals cannot carry a text symbol id
				int end = line.indexOf(c, i + 1);
				if (end < 0) {
					return;
				}
				i = end + 1;
				continue;
			}
			if (c != '\'') {
				i++;
				continue;
			}
			StringBuilder text = new StringBuilder();
			int j = i + 1;
			boolean closed = false;
			while (j < n) {
				char d = line.charAt(j);
				if (d == '\'') {
					if (j + 1 < n && line.charAt(j + 1) == '\'') {
						text.append('\'');
						j += 2;
						continue;
					}
					closed = true;
					break;
				}
				text.append(d);
				j++;
			}
			if (!closed) {
				return;
			}
			i = j + 1;
			if (i + 4 < n && line.charAt(i) == '(' && line.charAt(i + 4) == ')') {
				String id = line.substring(i + 1, i + 4);
				if (id.chars().allMatch(Character::isLetterOrDigit)) {
					out.putIfAbsent(id.toUpperCase(Locale.ROOT), text.toString());
					i += 5;
				}
			}
		}
	}

	/** What the pool ({@code symbols} part as ADT reads it) lacks for the code. */
	public static Plan plan(String code, String pool) {
		Map<String, String> existing = AdtTextPool.entries(pool);
		Map<String, String> missing = new LinkedHashMap<>();
		Map<String, String> differing = new LinkedHashMap<>();
		inCode(code).forEach((id, text) -> {
			String have = existing.get(id);
			if (have == null) {
				missing.put(id, text);
			} else if (!have.equals(text.strip())) {
				differing.put(id, have);
			}
		});
		return new Plan(missing, differing);
	}

	/** The pool with the entries added at its end; existing entries stay as they are. */
	static String withAdded(String pool, Map<String, String> added) {
		StringBuilder sb = new StringBuilder(pool == null ? "" : pool.replace("\r\n", "\n").strip());
		added.forEach((id, text) -> {
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			sb.append(id).append('=').append(text);
		});
		return sb.toString();
	}

	/** The {@code symbols} part of the object's pool; empty if it has none. */
	public static String read(AdtClient client, AdtEditorObject object, CancelToken cancel) throws IOException {
		return client.textElements(object.type(), object.name(), "symbols", cancel);
	}

	/**
	 * Adds text symbols to the pool and activates it. The pool is read again
	 * right before the write, so that texts maintained meanwhile are kept.
	 *
	 * @return the transport request used, empty for local objects
	 * @throws AdtException when SAP does not keep the texts as written
	 */
	public static String add(AdtTransport.Session session, AdtClient client, AdtEditorObject object,
			Map<String, String> added, CancelToken cancel) throws IOException {
		String pool = read(client, object, cancel);
		Map<String, String> existing = AdtTextPool.entries(pool);
		Map<String, String> toAdd = new LinkedHashMap<>(added);
		toAdd.keySet().removeAll(existing.keySet());
		if (toAdd.isEmpty()) {
			return "";
		}
		String texts = withAdded(pool, toAdd);
		String tr = AdtClient.writeTextElements(session, object.type(), object.name(), "symbols", texts, null, cancel);
		List<String> lost = AdtTextPool.differences(texts, read(client, object, cancel));
		if (!lost.isEmpty()) {
			throw new AdtException(500, "SAP did not keep the text symbols as written: " + String.join("; ", lost));
		}
		AdtObjectRef poolRef = new AdtObjectRef(AdtClient.textElementsUri(object.type(), object.name()),
				object.name(), AdtClient.TEXT_POOL, "", "");
		List<String> errors = new ArrayList<>();
		for (AdtClient.Message m : client.activate(List.of(poolRef), cancel)) {
			if (m.severity().equals("Error")) {
				errors.add(m.format());
			}
		}
		if (!errors.isEmpty()) {
			throw new AdtException(500, "The text symbols are saved but inactive: " + String.join("; ", errors));
		}
		return tr;
	}
}

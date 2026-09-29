package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.abap.AbapReferences.Hint;
import de.kiliantaubmann.bella.core.abap.AbapReferences.Reference;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Collects the definitions of the objects a piece of code uses, so the model
 * works with real field names and signatures instead of guessing: the public
 * section of classes, interfaces, table and structure definitions, function
 * module signatures and summaries of data elements, domains and table types.
 */
public final class AdtContext {

	/**
	 * Limits for one context build.
	 *
	 * @param maxObjects   objects to read at most
	 * @param timeoutMillis stop reading after this time; what was read so far is kept
	 * @param maxChars     total size of the text
	 */
	public record Limits(int maxObjects, long timeoutMillis, int maxChars) {

		public static final Limits DEFAULT = new Limits(12, 8_000, 40_000);
	}

	/**
	 * @param text     Markdown blocks, one per object, empty when nothing was found
	 * @param used     names of the objects in {@code text}
	 * @param notFound candidates that do not exist in the system (or could not be read)
	 * @param skipped  candidates left out because a limit was reached
	 */
	public record Result(String text, List<String> used, List<String> notFound, List<String> skipped) {

		public boolean isEmpty() {
			return used.isEmpty();
		}
	}

	/** Characters kept per table, structure, CDS view or interface. */
	static final int SOURCE_CHARS = 6_000;
	/** Characters kept per data element, domain, table type or function module signature. */
	static final int SHORT_CHARS = 3_000;

	/** Search types preferred per hint; the first exact hit in this order wins. */
	private static final Map<Hint, List<String>> PREFERENCE = Map.of(
			Hint.TABLE, List.of("TABL", "DDLS", "VIEW"),
			Hint.CLASS, List.of("CLAS", "INTF"),
			Hint.TYPE, List.of("TABL", "DTEL", "TTYP", "CLAS", "INTF", "DDLS", "DOMA"),
			Hint.FUNCTION, List.of("FUGR/FF"),
			Hint.ANY, List.of("TABL", "DDLS", "CLAS", "INTF", "DTEL", "TTYP", "FUGR/FF", "DOMA", "VIEW", "MSAG"));

	private AdtContext() {
	}

	public static Result build(AdtClient client, List<Reference> candidates, Limits limits, CancelToken cancel)
			throws CancelledException {
		long start = System.nanoTime();
		long deadline = start + limits.timeoutMillis() * 1_000_000L;
		StringBuilder text = new StringBuilder();
		List<String> used = new ArrayList<>();
		List<String> notFound = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Reference ref : candidates) {
			cancel.throwIfCancelled();
			if (used.size() >= limits.maxObjects() || text.length() >= limits.maxChars()
					|| System.nanoTime() >= deadline) {
				skipped.add(ref.name());
				continue;
			}
			try {
				AdtObjectRef obj = find(client, ref, cancel);
				if (obj == null) {
					notFound.add(ref.name());
					continue;
				}
				String body = content(client, obj, cancel);
				String block = block(obj, body);
				if (text.length() + block.length() > limits.maxChars()) {
					int room = limits.maxChars() - text.length();
					if (room < 500) {
						skipped.add(ref.name());
						continue;
					}
					block = block(obj, AdtXml.truncate(body, room - 200));
				}
				text.append(block);
				used.add(obj.name());
			} catch (IOException | RuntimeException e) {
				cancel.throwIfCancelled();
				Log.info("adt", "context: " + ref.name() + " not readable: " + e.getMessage());
				notFound.add(ref.name());
			}
		}
		if (!notFound.isEmpty() && !used.isEmpty()) {
			text.append("Not found: ").append(String.join(", ", notFound)).append('\n');
		}
		Log.info("adt", "context: " + candidates.size() + " candidates, used " + used + ", not found " + notFound
				+ (skipped.isEmpty() ? "" : ", skipped " + skipped) + ", " + text.length() + " chars ("
				+ Log.millisSince(start) + " ms)");
		return new Result(text.toString(), List.copyOf(used), List.copyOf(notFound), List.copyOf(skipped));
	}

	/** Exact-name search; among several hits the object type the code position suggests wins. */
	static AdtObjectRef find(AdtClient client, Reference ref, CancelToken cancel) throws IOException {
		List<AdtObjectRef> exact = new ArrayList<>();
		for (AdtObjectRef r : client.search(ref.name(), null, 20, cancel)) {
			if (r.name().equalsIgnoreCase(ref.name()) && rank(r.type(), PREFERENCE.get(Hint.ANY)) < Integer.MAX_VALUE) {
				exact.add(r);
			}
		}
		AdtObjectRef best = null;
		int bestRank = Integer.MAX_VALUE;
		for (AdtObjectRef r : exact) {
			int rank = rank(r.type(), PREFERENCE.get(ref.hint()));
			if (rank == Integer.MAX_VALUE) {
				rank = 100 + rank(r.type(), PREFERENCE.get(Hint.ANY));
			}
			if (rank < bestRank) {
				best = r;
				bestRank = rank;
			}
		}
		return best;
	}

	private static int rank(String type, List<String> order) {
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
		for (int i = 0; i < order.size(); i++) {
			String o = order.get(i);
			if (o.contains("/") ? t.equals(o) : t.equals(o) || t.startsWith(o + "/")) {
				return i;
			}
		}
		return Integer.MAX_VALUE;
	}

	/** The part of an object's definition the model needs to use it. */
	static String content(AdtClient client, AdtObjectRef obj, CancelToken cancel) throws IOException {
		String type = obj.type() == null ? "" : obj.type().toUpperCase(Locale.ROOT);
		String uri = AdtObjectRef.objectUri(obj.uri());
		if (type.startsWith("CLAS")) {
			return publicSection(client.readSource(uri, null, cancel), obj.name());
		}
		if (type.startsWith("INTF")) {
			return AdtXml.truncate(client.readSource(uri, null, cancel).strip(), SOURCE_CHARS);
		}
		if (type.equals("FUGR/FF")) {
			return AdtXml.truncate(functionSignature(client.readSource(uri, null, cancel)), SHORT_CHARS);
		}
		String def = client.readDefinition(obj, cancel).strip();
		return AdtXml.truncate(def, AdtClient.xmlOnly(type) ? SHORT_CHARS : SOURCE_CHARS);
	}

	/** {@code CLASS … DEFINITION} up to the protected or private section. */
	static String publicSection(String source, String className) {
		String def = AbapStructureScanner.classDefinition(source, className).orElse(null);
		if (def == null) {
			return AdtXml.truncate(source.strip(), SOURCE_CHARS);
		}
		for (AbapStructureScanner.Statement st : AbapStructureScanner.statements(def)) {
			List<String> w = st.words(2);
			if (w.size() == 2 && w.get(1).equals("SECTION")
					&& (w.get(0).equals("PROTECTED") || w.get(0).equals("PRIVATE"))) {
				return AdtXml.truncate(def.substring(0, st.start()).stripTrailing(), SOURCE_CHARS) + "\n  \" …\nENDCLASS.";
			}
		}
		return AdtXml.truncate(def.strip(), SOURCE_CHARS);
	}

	/** The {@code FUNCTION} statement with its parameters and the "Local Interface" comment below it. */
	static String functionSignature(String source) {
		for (AbapStructureScanner.Statement st : AbapStructureScanner.statements(source)) {
			List<String> w = st.words(1);
			if (!w.isEmpty() && w.get(0).equals("FUNCTION")) {
				int end = st.end();
				int pos = end;
				// the *" comment block follows the statement
				while (pos < source.length()) {
					int nl = source.indexOf('\n', pos);
					int lineEnd = nl < 0 ? source.length() : nl;
					String line = source.substring(pos, lineEnd).trim();
					if (!line.isEmpty() && !line.startsWith("*\"")) {
						break;
					}
					end = lineEnd;
					if (nl < 0) {
						break;
					}
					pos = nl + 1;
				}
				return source.substring(0, end).strip() + "\n  \" …\nENDFUNCTION.";
			}
		}
		return source.strip();
	}

	private static String block(AdtObjectRef obj, String body) {
		String lang = AdtClient.xmlOnly(obj.type()) ? "text" : "abap";
		StringBuilder sb = new StringBuilder("### ").append(obj.name()).append(" (").append(obj.type());
		if (obj.description() != null && !obj.description().isBlank()) {
			sb.append(" – ").append(obj.description());
		}
		return sb.append(")\n```").append(lang).append('\n').append(body).append("\n```\n\n").toString();
	}

	/** Candidates from code and, when given, the names in an instruction. */
	public static List<Reference> candidates(String code, String instruction) {
		return AbapReferences.merge(AbapReferences.fromInstruction(instruction), AbapReferences.extract(code));
	}
}

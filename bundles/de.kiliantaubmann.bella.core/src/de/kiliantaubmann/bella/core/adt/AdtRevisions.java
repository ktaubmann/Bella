package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * Version history of a source ({@code …/versions}) and the choice of the two
 * versions a transport review compares: the one written under the transport
 * and the one before it.
 * <p>
 * The selection follows ARC-1's transport diff ({@code src/adt/transport-diff.ts},
 * MIT license), which was checked against live systems: the request and all
 * its tasks count as "this transport"; ties in the timestamp are broken by the
 * version number; the work states 00000 (active) and 99999 (inactive) are never
 * a baseline; versions of the same transport are skipped when walking back.
 */
public final class AdtRevisions {

	/** One version of a source. */
	public record Revision(String id, String uri, String timestamp, String author, String transport) {

		/** The five-digit version number, from the id or the content URI. */
		public String number() {
			for (String v : new String[] { id, uri }) {
				Matcher m = NUMBER.matcher(v == null ? "" : v);
				if (m.find()) {
					return m.group(1);
				}
			}
			return "";
		}
	}

	/** How the "after" version was found. */
	public enum Selection {
		/** written under this transport */
		EXACT,
		/** no version names the transport; the newest stored version is used */
		LATEST_FALLBACK,
		/** only work states exist */
		ACTIVE_ONLY,
		NONE
	}

	/** What the "before" side is. */
	public enum Baseline {
		/** the version before this transport */
		PRIOR,
		/** a version before, but the "after" side is not proven to be this transport's */
		PRIOR_UNVERIFIED,
		/** created by this transport */
		CREATED,
		/** no predecessor found although the object may be older */
		AMBIGUOUS,
		UNAVAILABLE
	}

	public record Pair(Revision current, Revision previous, Selection selection, List<String> skipped) {
	}

	private static final Pattern NUMBER = Pattern.compile("(?:^|/)(\\d{5})(?:$|[/?#])");
	private static final Set<String> WORK_STATES = Set.of("00000", "99999");

	private AdtRevisions() {
	}

	public static List<Revision> parse(String xml) throws IOException {
		List<Revision> out = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return out;
		}
		for (Element entry : AdtXml.elements(AdtXml.parse(xml), "entry")) {
			String transport = "";
			for (Element link : AdtXml.elements(entry, "link")) {
				if (AdtXml.attr(link, "rel").endsWith("/transport/request")) {
					transport = AdtXml.attr(link, "name").trim().toUpperCase(Locale.ROOT);
					if (!transport.isEmpty()) {
						break;
					}
				}
			}
			List<Element> content = AdtXml.elements(entry, "content");
			List<Element> names = AdtXml.elements(entry, "name");
			out.add(new Revision(first(entry, "id"), content.isEmpty() ? "" : AdtXml.attr(content.get(0), "src"),
					first(entry, "updated"), names.isEmpty() ? "" : AdtXml.text(names.get(0)).trim(), transport));
		}
		return out;
	}

	private static String first(Element e, String localName) {
		// direct entry children only: the feed has its own id/updated, entries do not nest
		for (Element c : AdtXml.elements(e, localName)) {
			if (c.getParentNode() == e) {
				return AdtXml.text(c).trim();
			}
		}
		return "";
	}

	/** Newest first: timestamp, then version number; undated versions after dated ones. */
	static List<Revision> newestFirst(List<Revision> revisions) {
		List<Revision> sorted = new ArrayList<>(revisions);
		sorted.sort(Comparator.comparing((Revision r) -> time(r)).thenComparing(r -> num(r)).reversed());
		return sorted;
	}

	private static Instant time(Revision r) {
		try {
			return Instant.parse(r.timestamp());
		} catch (DateTimeParseException | NullPointerException e) {
			return Instant.MIN;
		}
	}

	private static long num(Revision r) {
		String n = r.number();
		return n.isEmpty() ? Long.MIN_VALUE : Long.parseLong(n);
	}

	public static Pair select(List<Revision> revisions, Set<String> transportIds) {
		List<Revision> sorted = newestFirst(revisions);
		if (sorted.isEmpty()) {
			return new Pair(null, null, Selection.NONE, List.of());
		}
		int current = -1;
		Selection selection = Selection.EXACT;
		for (int i = 0; i < sorted.size() && current < 0; i++) {
			if (belongs(sorted.get(i), transportIds)) {
				current = i;
			}
		}
		if (current < 0) {
			selection = Selection.LATEST_FALLBACK;
			for (int i = 0; i < sorted.size() && current < 0; i++) {
				if (!WORK_STATES.contains(sorted.get(i).number())) {
					current = i;
				}
			}
		}
		if (current < 0) {
			current = 0;
			selection = Selection.ACTIVE_ONLY;
		}
		List<String> skipped = new ArrayList<>();
		int previous = current + 1;
		while (previous < sorted.size()) {
			Revision c = sorted.get(previous);
			if (WORK_STATES.contains(c.number())) {
				skipped.add(c.number() + ": work state, not a transported version");
			} else if (belongs(c, transportIds)) {
				skipped.add(c.number() + ": same transport");
			} else {
				break;
			}
			previous++;
		}
		return new Pair(sorted.get(current), previous < sorted.size() ? sorted.get(previous) : null, selection,
				skipped);
	}

	private static boolean belongs(Revision r, Set<String> transportIds) {
		return !r.transport().isEmpty() && transportIds.contains(r.transport().toUpperCase(Locale.ROOT));
	}

	public static Baseline baseline(Pair pair, List<Revision> all) {
		if (pair.current() == null) {
			return Baseline.UNAVAILABLE;
		}
		boolean matched = pair.selection() == Selection.EXACT;
		if (pair.previous() != null) {
			return matched ? Baseline.PRIOR : Baseline.PRIOR_UNVERIFIED;
		}
		if (!matched) {
			return Baseline.AMBIGUOUS;
		}
		long current = num(pair.current());
		boolean older = all.stream().anyMatch(r -> !r.number().isEmpty() && !WORK_STATES.contains(r.number())
				&& num(r) < current);
		return older ? Baseline.AMBIGUOUS : Baseline.CREATED;
	}
}

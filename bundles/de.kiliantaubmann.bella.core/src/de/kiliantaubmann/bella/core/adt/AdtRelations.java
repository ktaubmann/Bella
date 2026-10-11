package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * What a CDS view builds on and what builds on it, and the includes and
 * append structures of a table or structure, after ARC-1's
 * {@code SAPContext impact} and {@code structure}.
 */
final class AdtRelations {

	/** Levels of includes followed at most. */
	static final int MAX_DEPTH = 5;
	/** Append candidates whose source is read at most. */
	static final int MAX_APPEND_CANDIDATES = 20;
	/** Entries listed per group of the impact. */
	static final int MAX_PER_GROUP = 50;

	private AdtRelations() {
	}

	// ---- CDS impact ------------------------------------------------------------

	private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*|--[^\\n]*");
	private static final String NAME = "([A-Za-z0-9_/]+)";
	/** Cardinality words of the newer syntax, e.g. {@code association of many to one}. */
	private static final String CARD = "(?:many|one|exact\\s+one)\\s+";
	private static final Pattern FROM = Pattern.compile("(?i)\\b(?:from|join|projection\\s+on)\\s+" + NAME);
	private static final Pattern ASSOCIATION = Pattern
			.compile("(?i)\\bassociation\\s*(?:\\[[^\\]]*\\])?\\s*(?:of\\s+" + CARD + ")?to\\s+(?:" + CARD + ")?(?:parent\\s+)?"
					+ NAME);
	private static final Pattern COMPOSITION = Pattern
			.compile("(?i)\\bcomposition\\s*(?:\\[[^\\]]*\\])?\\s*of\\s+(?:" + CARD + ")?" + NAME);
	private static final Pattern REDIRECTED = Pattern
			.compile("(?i)\\bredirected\\s+to\\s+(?:composition\\s+child\\s+|parent\\s+)?" + NAME);
	private static final Pattern EXTEND = Pattern.compile("(?i)\\bextend\\s+view\\s+(?:entity\\s+)?" + NAME);

	/** What a CDS source builds on, by kind; names upper case. */
	record Upstream(Set<String> sources, Set<String> associations, Set<String> compositions, Set<String> extended) {
	}

	static Upstream upstream(String ddl) {
		String s = COMMENTS.matcher(ddl == null ? "" : ddl).replaceAll(" ");
		Set<String> compositions = names(COMPOSITION, s);
		Set<String> associations = names(ASSOCIATION, s);
		associations.addAll(names(REDIRECTED, s));
		associations.removeAll(compositions);
		return new Upstream(names(FROM, s), associations, compositions, names(EXTEND, s));
	}

	private static Set<String> names(Pattern p, String s) {
		Set<String> out = new LinkedHashSet<>();
		Matcher m = p.matcher(s);
		while (m.find()) {
			String n = m.group(1).toUpperCase(Locale.ROOT);
			// $projection, $parameters and keywords are no objects
			if (!n.isEmpty() && !n.startsWith("$") && !n.equals("PARENT") && !n.equals("COMPOSITION")) {
				out.add(n);
			}
		}
		return out;
	}

	/** Downstream groups in the order they are listed. */
	private static final Map<String, String> GROUPS = new LinkedHashMap<>();
	static {
		GROUPS.put("DDLS", "CDS views on top of it");
		GROUPS.put("BDEF", "Behavior definitions");
		GROUPS.put("SRVD", "Service definitions");
		GROUPS.put("SRVB", "Service bindings");
		GROUPS.put("DCLS", "Access controls");
		GROUPS.put("DDLX", "Metadata extensions");
		GROUPS.put("ABAP", "ABAP code (classes, interfaces, programs, function groups)");
		GROUPS.put("TABL", "Tables and structures");
		GROUPS.put("SKTD", "Documentation (knowledge transfer documents)");
		GROUPS.put("OTHER", "Other");
	}

	private static String group(String type) {
		String main = type == null ? "" : type.toUpperCase(Locale.ROOT);
		int slash = main.indexOf('/');
		main = slash < 0 ? main : main.substring(0, slash);
		return switch (main) {
		case "CLAS", "INTF", "PROG", "FUGR" -> "ABAP";
		case "DDLS", "BDEF", "SRVD", "SRVB", "DCLS", "DDLX", "TABL", "SKTD" -> main;
		default -> "OTHER";
		};
	}

	/**
	 * Impact of changing a CDS view: what it reads and associates (from its
	 * source) and what uses it (where-used list), grouped for RAP.
	 *
	 * @param indirect also uses through other objects
	 * @param allowed  customer objects that may be listed; the others are counted
	 */
	static String impact(AdtClient c, AdtObjectRef ddls, boolean indirect, Predicate<AdtObjectRef> allowed,
			CancelToken cancel) throws IOException {
		String objectUri = AdtObjectRef.objectUri(ddls.uri());
		Upstream up = upstream(c.readSource(objectUri, null, cancel));
		StringBuilder sb = new StringBuilder("Impact of ").append(ddls.name()).append(":\n\nBuilds on (from its source):\n");
		line(sb, "Data sources (FROM, JOIN, projection)", up.sources());
		line(sb, "Associations", up.associations());
		line(sb, "Compositions", up.compositions());
		line(sb, "Extends", up.extended());
		if (up.sources().isEmpty() && up.associations().isEmpty() && up.compositions().isEmpty()
				&& up.extended().isEmpty()) {
			sb.append("- nothing found\n");
		}
		Map<String, List<String>> groups = new LinkedHashMap<>();
		GROUPS.keySet().forEach(k -> groups.put(k, new ArrayList<>()));
		Set<String> seen = new LinkedHashSet<>();
		int direct = 0;
		int skippedIndirect = 0;
		int hidden = 0;
		for (AdtClient.Usage u : c.usages(objectUri, cancel)) {
			AdtObjectRef r = u.ref();
			// a root behavior definition carries the view's name; only the view itself is left out
			if (u.container() || r.type().toUpperCase(Locale.ROOT).startsWith("DEVC")
					|| r.name().equalsIgnoreCase(ddls.name()) && group(r.type()).equals("DDLS")) {
				continue;
			}
			if (!u.direct() && !indirect) {
				skippedIndirect++;
				continue;
			}
			if (!seen.add(r.type() + " " + r.name())) {
				continue;
			}
			if (!allowed.test(r)) {
				hidden++;
				continue;
			}
			if (u.direct()) {
				direct++;
			}
			groups.get(group(r.type())).add(r.name() + " (" + r.type()
					+ (r.packageName().isEmpty() ? "" : ", " + r.packageName()) + (u.direct() ? "" : ", indirect") + ")");
		}
		int total = groups.values().stream().mapToInt(List::size).sum();
		sb.append("\nUsed by (where-used list): ").append(total == 0 ? "nothing" : total + " objects, " + direct + " direct")
				.append('\n');
		groups.forEach((k, list) -> {
			if (!list.isEmpty()) {
				sb.append(GROUPS.get(k)).append(":\n");
				list.stream().limit(MAX_PER_GROUP).forEach(e -> sb.append("- ").append(e).append('\n'));
				if (list.size() > MAX_PER_GROUP) {
					sb.append("- … ").append(list.size() - MAX_PER_GROUP).append(" more\n");
				}
			}
		});
		if (skippedIndirect > 0) {
			sb.append("Not listed: ").append(skippedIndirect)
					.append(" indirect uses; 'include_indirect' true lists them.\n");
		}
		if (hidden > 0) {
			sb.append(AdtToolProvider.OUTSIDE_PACKAGE).append(hidden).append(" objects\n");
		}
		return sb.toString();
	}

	private static void line(StringBuilder sb, String label, Set<String> names) {
		if (!names.isEmpty()) {
			sb.append("- ").append(label).append(": ").append(String.join(", ", names)).append('\n');
		}
	}

	// ---- structure -------------------------------------------------------------

	private static final Pattern CLASSIC_INCLUDE = Pattern.compile("(?i)^\\.include\\s+([a-z0-9_/]+)");
	private static final Pattern NAMED_INCLUDE = Pattern.compile("(?i)^([a-z][a-z0-9_]*)\\s*:\\s*include\\s+([a-z0-9_/]+)");
	private static final Pattern INCLUDE = Pattern.compile("(?i)^include\\s+([a-z0-9_/]+)");

	/** An include of a table or structure; {@code group} is the group name of a named include, else empty. */
	record Include(String name, String group) {
	}

	static List<Include> includes(String source) {
		List<Include> out = new ArrayList<>();
		for (String raw : (source == null ? "" : source).split("\\r?\\n")) {
			String line = raw.replaceAll("//.*$", "").trim();
			if (line.isEmpty() || line.startsWith("@") || line.startsWith("*")) {
				continue;
			}
			Matcher m = CLASSIC_INCLUDE.matcher(line);
			if (m.find()) {
				out.add(new Include(m.group(1).toUpperCase(Locale.ROOT), ""));
				continue;
			}
			m = NAMED_INCLUDE.matcher(line);
			if (m.find()) {
				out.add(new Include(m.group(2).toUpperCase(Locale.ROOT), m.group(1).toLowerCase(Locale.ROOT)));
				continue;
			}
			m = INCLUDE.matcher(line);
			if (m.find()) {
				out.add(new Include(m.group(1).toUpperCase(Locale.ROOT), ""));
			}
		}
		return out;
	}

	/** Whether an append structure's source extends {@code base}. */
	static boolean extendsType(String source, String base) {
		return Pattern.compile("(?i)\\bextend\\s+type\\s+" + Pattern.quote(base) + "\\s+with\\b")
				.matcher(source == null ? "" : source).find();
	}

	/**
	 * Includes (followed down to {@link #MAX_DEPTH} levels) and append
	 * structures of a table or structure, as an indented tree.
	 *
	 * @param appends also look for append structures through the where-used list
	 */
	static String structure(AdtClient c, AdtObjectRef tabl, boolean appends, CancelToken cancel) throws IOException {
		StringBuilder sb = new StringBuilder(tabl.name()).append(" (").append(tabl.type()).append(")\n");
		int[] counts = new int[3]; // includes, appends, unreadable
		List<String> notes = new ArrayList<>();
		node(c, tabl, 1, new LinkedHashSet<>(List.of(tabl.name())), appends, sb, counts, notes, cancel);
		sb.append("\n").append(counts[0]).append(" includes, ").append(counts[1]).append(" append structures");
		if (!appends) {
			sb.append(" (not looked for)");
		}
		sb.append(".\n");
		notes.forEach(n -> sb.append(n).append('\n'));
		return sb.toString();
	}

	private static void node(AdtClient c, AdtObjectRef ref, int depth, Set<String> path, boolean appends,
			StringBuilder sb, int[] counts, List<String> notes, CancelToken cancel) throws IOException {
		String objectUri = AdtObjectRef.objectUri(ref.uri());
		String source = c.readSource(objectUri, null, cancel);
		String indent = "  ".repeat(depth);
		for (Include inc : includes(source)) {
			counts[0]++;
			sb.append(indent).append("include ").append(inc.name())
					.append(inc.group().isEmpty() ? "" : " as group " + inc.group());
			if (path.contains(inc.name())) {
				sb.append(" (cycle)\n");
				continue;
			}
			if (depth >= MAX_DEPTH) {
				sb.append(" (not followed, deeper than ").append(MAX_DEPTH).append(" levels)\n");
				continue;
			}
			AdtObjectRef child;
			try {
				child = c.resolve(inc.name(), "TABL", cancel);
			} catch (AdtException e) {
				sb.append(" (not found)\n");
				counts[2]++;
				continue;
			}
			sb.append('\n');
			Set<String> deeper = new LinkedHashSet<>(path);
			deeper.add(inc.name());
			node(c, child, depth + 1, deeper, false, sb, counts, notes, cancel);
		}
		if (!appends) {
			return;
		}
		List<AdtClient.Usage> uses;
		try {
			uses = c.usages(objectUri, cancel);
		} catch (AdtException e) {
			notes.add("Append structures could not be looked up: " + e.getMessage());
			return;
		}
		int checked = 0;
		Set<String> seen = new LinkedHashSet<>();
		for (AdtClient.Usage u : uses) {
			AdtObjectRef r = u.ref();
			if (u.container() || !r.type().toUpperCase(Locale.ROOT).startsWith("TABL") || !seen.add(r.name())
					|| r.name().equalsIgnoreCase(ref.name())) {
				continue;
			}
			if (checked >= MAX_APPEND_CANDIDATES) {
				notes.add("More structures use " + ref.name() + " than were checked for appends ("
						+ MAX_APPEND_CANDIDATES + ").");
				break;
			}
			checked++;
			String candidate;
			try {
				candidate = c.readSource(AdtObjectRef.objectUri(r.uri()), null, cancel);
			} catch (AdtException e) {
				continue;
			}
			if (extendsType(candidate, ref.name())) {
				counts[1]++;
				sb.append(indent).append("append ").append(r.name())
						.append(r.packageName().isEmpty() ? "" : " (" + r.packageName() + ")").append('\n');
			}
		}
	}
}

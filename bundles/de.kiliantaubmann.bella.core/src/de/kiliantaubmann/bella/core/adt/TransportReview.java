package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.LineDiff;

/**
 * Collects what a reviewer needs to judge a transport request: its objects,
 * per source the change against the version before the transport, the
 * results of syntax check, ATC and ABAP Unit, inactive objects and objects
 * the changed code uses but that are missing or held in another request.
 * Read only.
 */
public final class TransportReview {

	/** Bounds that keep the dossier within a model's context. */
	public record Limits(int maxObjects, int maxDiffChars, int maxTotalChars, int maxDependencies) {

		public static final Limits DEFAULT = new Limits(40, 6_000, 60_000, 20);

		/** For one object: its whole diff. */
		public static final Limits SINGLE = new Limits(1, 40_000, 45_000, 20);
	}

	/**
	 * A reviewable object: entries of the request rolled up, e.g. {@code LIMU METH}
	 * entries to their class.
	 */
	record Item(String type, String name, String description, List<AdtTransportRequest.Entry> components) {
	}

	/** One source of an item with its version history URI. */
	record Part(String label, String objectUri, String versionsUri) {
	}

	/** Types whose ADT history this review reads. */
	static final Set<String> SOURCE_TYPES = Set.of("CLAS", "PROG", "INTF", "INCL", "FUNC", "DDLS", "DCLS", "BDEF",
			"SRVD");

	private static final Set<String> CLASS_COMPONENTS = Set.of("CINC", "CPRI", "CPRO", "CPUB", "CLSD", "METH");
	private static final Map<String, String> CLASS_POOL_INCLUDES = Map.of("CCDEF", "definitions", "CCIMP",
			"implementations", "CCMAC", "macros", "CCAU", "testclasses");
	static final List<String> CLASS_INCLUDES = List.of("main", "definitions", "implementations", "macros",
			"testclasses");

	private TransportReview() {
	}

	// ---- rollup -------------------------------------------------------------------

	/** Owning class of a LIMU class component (name padded to 30 characters), or empty. */
	static String classOwner(AdtTransportRequest.Entry e) {
		if (!e.pgmid().equalsIgnoreCase("LIMU")) {
			return "";
		}
		String type = e.type().toUpperCase(Locale.ROOT);
		String wb = e.wbtype().toUpperCase(Locale.ROOT);
		boolean component = CLASS_COMPONENTS.contains(type) || wb.startsWith("CLAS/")
				|| (type.equals("REPT") && e.name().contains("="));
		if (!component) {
			return "";
		}
		String head = e.name().length() > 30 ? e.name().substring(0, 30) : e.name();
		int eq = head.indexOf('=');
		return (eq >= 0 ? head.substring(0, eq) : head).strip().toUpperCase(Locale.ROOT);
	}

	static List<Item> rollup(List<AdtTransportRequest.Entry> entries) {
		Map<String, Item> items = new LinkedHashMap<>();
		for (AdtTransportRequest.Entry e : entries) {
			String type = e.type().toUpperCase(Locale.ROOT);
			String name = e.name().strip().toUpperCase(Locale.ROOT);
			String wb = e.wbtype().toUpperCase(Locale.ROOT);
			String owner = classOwner(e);
			if (!owner.isEmpty()) {
				type = "CLAS";
				name = owner;
			} else if (wb.startsWith("FUGR/") && !wb.startsWith("FUGR/FF")) {
				type = "FUGR_PART";
			} else if (e.pgmid().equalsIgnoreCase("LIMU") && (type.equals("REPS") || type.equals("REPT"))) {
				type = wb.startsWith("PROG/I") ? "INCL" : "PROG";
			} else if (type.equals("PROG") && wb.startsWith("PROG/I")) {
				type = "INCL";
			}
			if (type.isEmpty() || name.isEmpty()) {
				continue;
			}
			String key = type + " " + name;
			Item existing = items.get(key);
			if (existing == null) {
				List<AdtTransportRequest.Entry> comps = new ArrayList<>();
				comps.add(e);
				items.put(key, new Item(type, name, e.description(), comps));
			} else {
				existing.components().add(e);
			}
		}
		return new ArrayList<>(items.values());
	}

	/** Class includes the request touched; a whole-class entry means all of them. */
	static List<String> classIncludes(Item item) {
		Set<String> out = new LinkedHashSet<>();
		for (AdtTransportRequest.Entry c : item.components()) {
			if (!c.pgmid().equalsIgnoreCase("LIMU")) {
				return CLASS_INCLUDES;
			}
			String suffix = c.name().length() > 30 ? c.name().substring(30).strip().toUpperCase(Locale.ROOT) : "";
			out.add(CLASS_POOL_INCLUDES.getOrDefault(suffix, "main"));
		}
		return out.isEmpty() ? CLASS_INCLUDES : new ArrayList<>(out);
	}

	/** Methods named by LIMU METH entries, for the overview. */
	static List<String> methods(Item item) {
		List<String> out = new ArrayList<>();
		for (AdtTransportRequest.Entry c : item.components()) {
			if (c.type().equalsIgnoreCase("METH") && c.name().length() > 30) {
				out.add(c.name().substring(30).strip().toUpperCase(Locale.ROOT));
			}
		}
		return out;
	}

	/** ADT URI of an item, {@code null} if it has none Bella can address. */
	static String objectUri(AdtClient c, Item item, CancelToken cancel) throws IOException {
		if (item.type().equals("FUNC")) {
			for (AdtObjectRef r : c.search(item.name(), "FUGR/FF", 10, cancel)) {
				if (r.name().equalsIgnoreCase(item.name())) {
					return AdtObjectRef.objectUri(r.uri());
				}
			}
			return null;
		}
		if (item.type().equals("TABL")) {
			return c.resolve(item.name(), "TABL", cancel).uri();
		}
		return AdtObjectRef.uriFor(item.name(), item.type());
	}

	static List<Part> parts(Item item, String objectUri) {
		if (objectUri == null || !SOURCE_TYPES.contains(item.type())) {
			return List.of();
		}
		return switch (item.type()) {
		case "CLAS" -> classIncludes(item).stream()
				.map(inc -> new Part(item.name() + (inc.equals("main") ? "" : " (" + inc + ")"), objectUri,
						objectUri + "/includes/" + inc + "/versions"))
				.toList();
		case "DDLS", "DCLS" -> List.of(new Part(item.name(), objectUri, objectUri + "/versions"));
		default -> List.of(new Part(item.name(), objectUri, objectUri + "/source/main/versions"));
		};
	}

	// ---- dossier ------------------------------------------------------------------

	/**
	 * Builds the review dossier.
	 *
	 * @param onlyObject name of one object to show completely, or {@code null} for all
	 * @param checks     run syntax check, ATC and ABAP Unit
	 */
	public static String build(AdtClient c, AdtTransportRequest tr, String onlyObject, boolean checks, Limits limits,
			CancelToken cancel) throws IOException, CancelToken.CancelledException {
		List<Item> items = rollup(tr.entries());
		if (onlyObject != null && !onlyObject.isBlank()) {
			String wanted = onlyObject.strip().toUpperCase(Locale.ROOT);
			items = items.stream().filter(i -> i.name().equals(wanted)).toList();
			if (items.isEmpty()) {
				return "Request " + tr.id() + " contains no object " + wanted + ".";
			}
		}
		StringBuilder sb = new StringBuilder();
		header(sb, tr);
		sb.append("\n## Objects (").append(items.size()).append(")\n");
		for (Item i : items) {
			sb.append("- ").append(i.type()).append(' ').append(i.name());
			if (!i.description().isBlank()) {
				sb.append(" – ").append(i.description());
			}
			List<String> methods = methods(i);
			if (!methods.isEmpty()) {
				sb.append(" (methods ").append(String.join(", ", methods)).append(')');
			}
			sb.append('\n');
		}

		Set<String> ids = tr.ids();
		List<String> checkUris = new ArrayList<>();
		List<String> unitUris = new ArrayList<>();
		Map<String, String> uriToName = new LinkedHashMap<>();
		List<String> newSources = new ArrayList<>();
		List<String> notShown = new ArrayList<>();
		sb.append("\n## Changes\n");
		int shown = 0;
		for (Item item : items) {
			cancel.throwIfCancelled();
			String uri;
			try {
				uri = objectUri(c, item, cancel);
			} catch (AdtException e) {
				uri = null;
			}
			if (uri != null) {
				uriToName.put(uri, item.name());
				if (!item.type().equals("FUGR_PART") && checkable(item.type())) {
					checkUris.add(uri);
				}
				if (item.type().equals("CLAS") || item.type().equals("PROG")) {
					unitUris.add(uri);
				}
			}
			if (shown >= limits.maxObjects() || sb.length() > limits.maxTotalChars()) {
				notShown.add(item.name());
				continue;
			}
			shown++;
			List<Part> parts = parts(item, uri);
			if (parts.isEmpty()) {
				sb.append("\n### ").append(item.type()).append(' ').append(item.name());
				definition(sb, c, item, uri, cancel);
				continue;
			}
			for (Part p : parts) {
				diff(sb, c, item, p, ids, limits, newSources, cancel);
			}
		}
		if (!notShown.isEmpty()) {
			sb.append("\nNot shown (limit reached; call adt_transport_review with 'object' for one of them): ")
					.append(String.join(", ", notShown)).append('\n');
		}

		List<String> inactive = c.inactiveObjects(cancel);
		if (checks) {
			sb.append("\n## Checks\n");
			checks(sb, c, checkUris, unitUris, uriToName, cancel);
		}
		List<String> inactiveHere = items.stream().map(Item::name).filter(inactive::contains).toList();
		sb.append("\n## Completeness\n");
		if (!inactiveHere.isEmpty()) {
			sb.append("- Not activated (the request would transport the active version only): ")
					.append(String.join(", ", inactiveHere)).append('\n');
		}
		dependencies(sb, c, items, newSources, ids, inactive, limits, cancel);
		return sb.toString();
	}

	private static boolean checkable(String type) {
		return SOURCE_TYPES.contains(type) || type.equals("FUGR");
	}

	private static void header(StringBuilder sb, AdtTransportRequest tr) {
		sb.append("# Transport request ").append(tr.id()).append(" – ").append(tr.description()).append('\n');
		sb.append("Owner ").append(tr.owner()).append(", ")
				.append(tr.released() ? "released" : "modifiable (not released)")
				.append(", type ").append(switch (tr.type().toUpperCase(Locale.ROOT)) {
				case "K" -> "Workbench";
				case "W" -> "Customizing";
				case "T" -> "transport of copies";
				default -> tr.type();
				});
		if (!tr.target().isBlank()) {
			sb.append(", target ").append(tr.target());
		}
		sb.append('\n');
		if (!tr.tasks().isEmpty()) {
			sb.append("Tasks: ");
			sb.append(String.join(", ", tr.tasks().stream()
					.map(t -> t.id() + " (" + t.owner() + (t.description().isBlank() ? "" : ", " + t.description()) + ")")
					.toList()));
			sb.append('\n');
		}
	}

	private static void diff(StringBuilder sb, AdtClient c, Item item, Part p, Set<String> ids, Limits limits,
			List<String> newSources, CancelToken cancel) throws IOException {
		sb.append("\n### ").append(item.type()).append(' ').append(p.label());
		List<AdtRevisions.Revision> revisions;
		try {
			revisions = c.revisions(p.versionsUri(), cancel);
		} catch (AdtException e) {
			sb.append(": version history not readable (").append(e.getMessage()).append(")\n");
			return;
		}
		AdtRevisions.Pair pair = AdtRevisions.select(revisions, ids);
		AdtRevisions.Baseline baseline = AdtRevisions.baseline(pair, revisions, ids);
		if (pair.current() == null) {
			sb.append(": no version history in the system\n");
			return;
		}
		String after;
		String before;
		try {
			after = c.revisionText(pair.current().uri(), cancel);
			before = pair.previous() == null ? "" : c.revisionText(pair.previous().uri(), cancel);
		} catch (AdtException e) {
			sb.append(": version source not readable (").append(e.getMessage()).append(")\n");
			return;
		}
		newSources.add(after);
		LineDiff.Result d = LineDiff.unified(before, after,
				pair.previous() == null ? "(none)" : "version " + pair.previous().number(),
				"version " + pair.current().number(), 3);
		sb.append(": ").append(switch (baseline) {
		case CREATED -> "created by this request";
		case PRIOR -> "changed";
		case PRIOR_UNVERIFIED -> "changed (NOTE: the newest version is not recorded under this request; "
				+ "the diff may include other changes)";
		case AMBIGUOUS -> "no earlier version found, shown as new (the object may be older)";
		case UNAVAILABLE -> "no version";
		});
		if (d.unchanged()) {
			sb.append(", no source difference\n");
			return;
		}
		sb.append(", +").append(d.added()).append(" −").append(d.removed()).append(" lines\n");
		String text = d.text();
		if (text.length() > limits.maxDiffChars()) {
			text = text.substring(0, limits.maxDiffChars()) + "\n… (diff cut; adt_transport_review with object="
					+ item.name() + " shows all of it)";
		}
		sb.append("```diff\n").append(text).append(text.endsWith("\n") ? "" : "\n").append("```\n");
	}

	private static void definition(StringBuilder sb, AdtClient c, Item item, String uri, CancelToken cancel) {
		if (item.type().equals("FUGR_PART")) {
			sb.append(": part of a function group (no version history here)\n");
			return;
		}
		if (uri == null) {
			sb.append(": not readable through ADT (non-source object, e.g. customizing or table content)\n");
			return;
		}
		try {
			String def = c.readDefinition(new AdtObjectRef(uri, item.name(), item.type(), "", ""), cancel);
			sb.append(": current definition (no version history for this type)\n```\n")
					.append(def.length() > 3_000 ? def.substring(0, 3_000) + "\n…" : def).append("\n```\n");
		} catch (IOException e) {
			sb.append(": definition not readable (").append(e.getMessage()).append(")\n");
		}
	}

	private static void checks(StringBuilder sb, AdtClient c, List<String> checkUris, List<String> unitUris,
			Map<String, String> uriToName, CancelToken cancel) {
		if (checkUris.isEmpty()) {
			sb.append("No source objects to check.\n");
			return;
		}
		try {
			List<AdtClient.Message> syntax = c.syntaxCheck(checkUris, cancel);
			sb.append("### Syntax check\n").append(syntax.isEmpty() ? "No messages.\n" : messages(syntax, uriToName));
		} catch (IOException e) {
			sb.append("### Syntax check\nNot possible: ").append(e.getMessage()).append('\n');
		}
		try {
			List<AdtClient.Message> atc = c.atcCheck(checkUris, null, cancel);
			sb.append("### ATC\n").append(atc.isEmpty() ? "No findings.\n" : messages(atc, uriToName));
		} catch (IOException e) {
			sb.append("### ATC\nNot possible: ").append(e.getMessage()).append('\n');
		}
		if (!unitUris.isEmpty()) {
			try {
				sb.append("### ABAP Unit\n").append(c.runUnitTests(unitUris, cancel));
			} catch (IOException e) {
				sb.append("### ABAP Unit\nNot possible: ").append(e.getMessage()).append('\n');
			}
		}
	}

	static String messages(List<AdtClient.Message> msgs, Map<String, String> uriToName) {
		StringBuilder sb = new StringBuilder();
		for (AdtClient.Message m : msgs) {
			String object = "";
			int best = -1;
			String uri = m.uri() == null ? "" : m.uri().toLowerCase(Locale.ROOT);
			for (Map.Entry<String, String> e : uriToName.entrySet()) {
				String key = e.getKey().toLowerCase(Locale.ROOT);
				if (key.length() > best && belongsTo(uri, key)) {
					object = e.getValue() + " ";
					best = key.length();
				}
			}
			sb.append("- ").append(object).append(m.format()).append('\n');
		}
		return sb.toString();
	}

	/** Whether {@code uri} is {@code objectUri} or below it ({@code /source/main#start=…}). */
	static boolean belongsTo(String uri, String objectUri) {
		if (!uri.startsWith(objectUri)) {
			return false;
		}
		if (uri.length() == objectUri.length()) {
			return true;
		}
		char next = uri.charAt(objectUri.length());
		return next == '/' || next == '#' || next == '?';
	}

	/** Customer objects: Z*, Y* or a namespace /…/. */
	static boolean customerObject(String name) {
		return name.startsWith("Z") || name.startsWith("Y") || name.startsWith("/");
	}

	private static void dependencies(StringBuilder sb, AdtClient c, List<Item> items, List<String> newSources,
			Set<String> ids, List<String> inactive, Limits limits, CancelToken cancel)
			throws IOException, CancelToken.CancelledException {
		Set<String> inRequest = new LinkedHashSet<>();
		items.forEach(i -> inRequest.add(i.name()));
		List<AbapReferences.Reference> refs = new ArrayList<>();
		for (String src : newSources) {
			refs = AbapReferences.merge(refs, AbapReferences.extract(src));
		}
		List<AbapReferences.Reference> candidates = refs.stream()
				.filter(r -> customerObject(r.name()) && !inRequest.contains(r.name())).limit(limits.maxDependencies())
				.toList();
		int problems = 0;
		for (AbapReferences.Reference r : candidates) {
			cancel.throwIfCancelled();
			AdtObjectRef obj;
			try {
				obj = AdtContext.find(c, r, cancel);
			} catch (AdtException e) {
				sb.append("- ").append(r.name()).append(": not checked (").append(e.getMessage()).append(")\n");
				problems++;
				continue;
			}
			if (obj == null) {
				sb.append("- ").append(r.name()).append(" is used but does not exist in this system.\n");
				problems++;
				continue;
			}
			if (inactive.contains(obj.name())) {
				sb.append("- ").append(obj.name()).append(" (").append(obj.type())
						.append(") is used but not activated, and it is not in this request.\n");
				problems++;
			}
			String lock;
			try {
				lock = c.lockedIn(obj.uri(), cancel);
			} catch (AdtException e) {
				sb.append("- ").append(obj.name()).append(": transport lock not checked (").append(e.getMessage())
						.append(")\n");
				problems++;
				continue;
			}
			if (!lock.isEmpty() && !ids.contains(lock.toUpperCase(Locale.ROOT))) {
				sb.append("- ").append(obj.name()).append(" (").append(obj.type())
						.append(") is used and has unreleased changes in request ").append(lock)
						.append("; transport that request first or together.\n");
				problems++;
			}
		}
		if (problems == 0) {
			sb.append(candidates.isEmpty() ? "No customer objects used outside this request.\n"
					: "Checked " + candidates.size() + " customer objects used by the changed code: "
							+ "all exist, are active and have no changes in other open requests.\n");
		}
	}
}

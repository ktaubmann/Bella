package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * ABAP Unit and ATC over the objects of a package, optionally with its
 * subpackages. The objects come from ADT's repository search, so the result
 * names exactly what was run; large packages run in several requests.
 */
final class PackageRuns {

	/** Rows one package search returns at most. */
	static final int SEARCH_LIMIT = 1000;
	/** Objects one call runs at most; a larger selection is cut and says so. */
	static final int MAX_OBJECTS = 300;
	/** Packages visited at most with subpackages. */
	static final int MAX_PACKAGES = 50;
	/** Objects per ABAP Unit or ATC request. */
	static final int CHUNK = 50;
	/** Findings one ATC request returns at most. */
	static final int ATC_VERDICTS = 1000;
	/** Findings listed in the result; the counts cover all. */
	static final int MAX_FINDINGS_SHOWN = 200;

	/** Object types that can hold ABAP Unit tests. */
	private static final Set<String> UNIT_TYPES = Set.of("CLAS", "PROG", "FUGR");
	/** Object types ATC checks, as in ARC-1's ATC batches. */
	private static final Set<String> ATC_TYPES = Set.of("CLAS", "INTF", "PROG", "FUGR", "DDLS", "DCLS", "DDLX",
			"BDEF", "SRVD", "SRVB", "TABL", "DTEL", "DOMA");
	/** DDIC objects ATC addresses through its own object URIs. */
	private static final Set<String> ATC_DDIC_TYPES = Set.of("TABL", "DTEL", "DOMA");

	private PackageRuns() {
	}

	/**
	 * The objects selected from a package.
	 *
	 * @param pkg        the package asked for
	 * @param packages   the packages searched
	 * @param objects    the objects to run, at most {@link #MAX_OBJECTS}
	 * @param ignored    customer objects of other packages left out
	 * @param incomplete why the selection may miss objects; empty if it is complete
	 */
	record Selection(String pkg, List<String> packages, List<AdtObjectRef> objects, int ignored, String incomplete) {
	}

	static Selection forUnitTests(AdtClient c, String pkg, boolean subpackages, Predicate<AdtObjectRef> allowed,
			CancelToken cancel) throws IOException {
		return select(c, pkg, subpackages, UNIT_TYPES, allowed, cancel);
	}

	static Selection forAtc(AdtClient c, String pkg, boolean subpackages, Predicate<AdtObjectRef> allowed,
			CancelToken cancel) throws IOException {
		return select(c, pkg, subpackages, ATC_TYPES, allowed, cancel);
	}

	private static Selection select(AdtClient c, String pkg, boolean subpackages, Set<String> types,
			Predicate<AdtObjectRef> allowed, CancelToken cancel) throws IOException {
		String root = pkg.trim().toUpperCase(Locale.ROOT);
		AdtResponse exists = c.exchange(AdtRequest.get(AdtManage.packageUri(root), "application/*"), cancel);
		if (exists.status() == 404) {
			throw new AdtException(404, "Package " + root + " does not exist.");
		}
		if (!exists.ok()) {
			throw new AdtException(exists.status(), "Could not read package " + root + ": " + AdtErrors.message(exists));
		}
		Set<String> visited = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>(List.of(root));
		Map<String, AdtObjectRef> objects = new LinkedHashMap<>();
		Set<String> seen = new LinkedHashSet<>();
		List<String> incomplete = new ArrayList<>();
		int ignored = 0;
		while (!queue.isEmpty()) {
			String current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}
			List<AdtObjectRef> found = c.packageContents(current, SEARCH_LIMIT, cancel);
			if (found.size() >= SEARCH_LIMIT) {
				incomplete.add("the search in " + current + " stopped at " + SEARCH_LIMIT + " objects");
			}
			for (AdtObjectRef r : found) {
				String own = r.packageName().trim().toUpperCase(Locale.ROOT);
				// the search lists objects of subpackages, too, and releases without the packageName filter
				// objects of any package (ARC-1 #999); subpackages are searched on their own
				if (!own.isEmpty() && !own.equals(current)) {
					continue;
				}
				String main = mainType(r.type());
				if (main.equals("DEVC")) {
					String sub = r.name().trim().toUpperCase(Locale.ROOT);
					if (subpackages && !sub.equals(current) && !visited.contains(sub)) {
						if (visited.size() + queue.size() < MAX_PACKAGES) {
							queue.add(sub);
						} else if (!queue.contains(sub)) {
							incomplete.add("subpackage " + sub + " was left out (more than " + MAX_PACKAGES
									+ " packages)");
						}
					}
					continue;
				}
				if (!runnable(r.type(), types) || !seen.add(r.uri())) {
					continue;
				}
				if (!allowed.test(r)) {
					ignored++;
					continue;
				}
				objects.put(r.uri(), r);
			}
		}
		List<AdtObjectRef> list = new ArrayList<>(objects.values());
		list.sort(Comparator.comparing((AdtObjectRef r) -> mainType(r.type())).thenComparing(AdtObjectRef::name));
		if (list.size() > MAX_OBJECTS) {
			incomplete.add("only the first " + MAX_OBJECTS + " of " + list.size() + " objects ran; run the "
					+ "subpackages one by one or check single objects");
			list = new ArrayList<>(list.subList(0, MAX_OBJECTS));
		}
		return new Selection(root, List.copyOf(visited), list, ignored, String.join("; ", incomplete));
	}

	/** Main programs, classes and groups; not includes or function modules, which run with their main object. */
	static boolean runnable(String type, Set<String> types) {
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		if (t.equals("PROG/I") || t.startsWith("FUGR/") && !t.equals("FUGR/F")) {
			return false;
		}
		return types.contains(mainType(t));
	}

	private static String mainType(String type) {
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		int slash = t.indexOf('/');
		return slash < 0 ? t : t.substring(0, slash);
	}

	static String unitTests(AdtClient c, Selection s, CancelToken cancel) throws IOException {
		StringBuilder head = new StringBuilder("ABAP Unit over ").append(scope(s)).append(": ");
		if (s.objects().isEmpty()) {
			return head.append("no classes, programs or function groups found.").append(notes(s)).toString();
		}
		int methods = 0;
		int failed = 0;
		int done = 0;
		StringBuilder details = new StringBuilder();
		for (List<AdtObjectRef> chunk : chunks(s.objects())) {
			if (cancel.isCancelled()) {
				details.append("Cancelled after ").append(done).append(" objects.\n");
				break;
			}
			AdtClient.UnitResult r = c.unitTestResult(uris(chunk, false), true, cancel);
			methods += r.methods();
			failed += r.failed();
			done += chunk.size();
			details.append(r.details());
		}
		head.append(done).append(" objects run; ");
		head.append(methods == 0 ? "no test methods were executed.\n"
				: (methods - failed) + " of " + methods + " test methods passed.\n");
		return head.append(details).append(notes(s)).toString();
	}

	static String atc(AdtClient c, Selection s, String variant, CancelToken cancel) throws IOException {
		StringBuilder head = new StringBuilder("ATC over ").append(scope(s)).append(": ");
		if (s.objects().isEmpty()) {
			return head.append("no objects ATC checks found.").append(notes(s)).toString();
		}
		List<AdtClient.Message> findings = new ArrayList<>();
		Set<String> atcNotes = new LinkedHashSet<>();
		int done = 0;
		String cancelled = "";
		for (List<AdtObjectRef> chunk : chunks(s.objects())) {
			if (cancel.isCancelled()) {
				cancelled = "Cancelled after " + done + " objects.\n";
				break;
			}
			findings.addAll(c.atcCheck(uris(chunk, true), variant, ATC_VERDICTS, cancel));
			if (!c.atcNote().isEmpty()) {
				atcNotes.add(c.atcNote());
			}
			done += chunk.size();
		}
		findings.sort(Comparator.comparingInt((AdtClient.Message m) -> rank(m.severity())));
		int[] counts = new int[3];
		findings.forEach(m -> counts[rank(m.severity())]++);
		head.append(done).append(" objects checked; ");
		if (findings.isEmpty()) {
			head.append("no findings.\n");
		} else {
			head.append("priority 1: ").append(counts[0]).append(", priority 2: ").append(counts[1])
					.append(", priority 3: ").append(counts[2]).append(".\n");
		}
		StringBuilder sb = new StringBuilder();
		atcNotes.forEach(n -> sb.append(n).append("\n\n"));
		sb.append(head).append(cancelled);
		List<AdtClient.Message> shown = findings.size() > MAX_FINDINGS_SHOWN
				? findings.subList(0, MAX_FINDINGS_SHOWN)
				: findings;
		sb.append(AdtToolProvider.formatAtc(shown, true));
		if (shown.size() < findings.size()) {
			sb.append("… ").append(findings.size() - shown.size())
					.append(" more findings of lower priority; check single objects with adt_atc_check 'name'.\n");
		}
		return sb.append(notes(s)).toString();
	}

	private static String scope(Selection s) {
		return "package " + s.pkg() + (s.packages().size() > 1 ? " and " + (s.packages().size() - 1)
				+ " subpackage" + (s.packages().size() == 2 ? "" : "s") : "");
	}

	private static String notes(Selection s) {
		StringBuilder sb = new StringBuilder();
		if (s.ignored() > 0) {
			sb.append("\nLeft out: ").append(s.ignored())
					.append(" customer objects outside the development package.");
		}
		if (!s.incomplete().isEmpty()) {
			sb.append("\nIncomplete: ").append(s.incomplete()).append('.');
		}
		return sb.toString();
	}

	private static List<List<AdtObjectRef>> chunks(List<AdtObjectRef> objects) {
		List<List<AdtObjectRef>> out = new ArrayList<>();
		for (int i = 0; i < objects.size(); i += CHUNK) {
			out.add(objects.subList(i, Math.min(objects.size(), i + CHUNK)));
		}
		return out;
	}

	static List<String> uris(List<AdtObjectRef> objects, boolean atc) {
		List<String> out = new ArrayList<>();
		for (AdtObjectRef r : objects) {
			String main = mainType(r.type());
			if (atc && ATC_DDIC_TYPES.contains(main)) {
				out.add("/sap/bc/adt/atc/objects/R3TR/" + main + "/"
						+ AdtClient.enc(r.name().trim().toUpperCase(Locale.ROOT)));
			} else {
				out.add(AdtObjectRef.objectUri(r.uri()));
			}
		}
		return out;
	}

	private static int rank(String severity) {
		return switch (severity) {
		case "Error" -> 0;
		case "Warning" -> 1;
		default -> 2;
		};
	}
}

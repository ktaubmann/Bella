package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Git through the SAP system: gCTS (read only) and abapGit's ADT backend
 * (repositories, clone, pull, branches, stage and push). Requests follow
 * ARC-1 ({@code src/adt/gcts.ts}, {@code src/adt/abapgit.ts}, MIT). Bella
 * passes no Git credentials; repositories that need them must have them
 * stored in the system.
 */
final class AdtGit {

	static final String GCTS = "/sap/bc/cts_abapvcs";
	static final String ABAPGIT = "/sap/bc/adt/abapgit";
	static final String REPOS_V2 = "application/abapgit.adt.repos.v2+xml";
	static final String REPO_V3 = "application/abapgit.adt.repo.v3+xml";
	static final String OBJECTS = "application/abapgit.adt.repo.object.v2+xml, application/abapgit.adt.repo.object.v1+xml";
	static final String STAGE_V1 = "application/abapgit.adt.repo.stage.v1+xml";
	static final String NS_REPO = "http://www.sap.com/adt/abapgit/repositories";
	static final String NS_STAGING = "http://www.sap.com/adt/abapgit/staging";

	private AdtGit() {
	}

	/** gCTS answers JSON below {@code /sap/bc/cts_abapvcs}. */
	static String gcts(AdtClient c, String action, String repo, int limit, CancelToken cancel) throws IOException {
		String r = repo == null ? "" : AdtClient.enc(repo.trim());
		String path = switch (action) {
		case "repos" -> GCTS + "/repository";
		case "system" -> GCTS + "/system";
		case "branches" -> GCTS + "/repository/" + required(r) + "/branches";
		case "history" -> GCTS + "/repository/" + required(r) + "/getCommit?limit=" + Math.max(1, Math.min(100, limit));
		case "objects" -> GCTS + "/repository/" + required(r) + "/objects";
		default -> throw new AdtException(400, "gCTS actions: repos, system, branches, history, objects.");
		};
		AdtResponse resp = c.exchange(AdtRequest.get(path, "application/json"), cancel);
		if (resp.status() == 404) {
			throw new AdtException(404, "gCTS is not available on this system (or not through the ADT connection).");
		}
		if (!resp.ok()) {
			throw new AdtException(resp.status(), AdtErrors.message(resp));
		}
		return AdtDiagnostics.json(resp.body());
	}

	private static String required(String repo) throws AdtException {
		if (repo.isEmpty()) {
			throw new AdtException(400, "Give 'repo', the repository id.");
		}
		return repo;
	}

	/** An abapGit repository with the links its actions go to. */
	record Repo(String key, String pkg, String url, String branch, List<String[]> links) {

		String link(String kind) throws AdtException {
			for (String[] l : links) {
				String rel = l[0].toLowerCase(Locale.ROOT);
				String href = l[1];
				if (l[2].equalsIgnoreCase(kind + "_link") || rel.endsWith("/" + kind) || rel.contains("/" + kind + "/")
						|| href.toLowerCase(Locale.ROOT).endsWith("/" + kind)) {
					// only links below abapGit's ADT resources are followed
					String path = href.replaceFirst("^https?://[^/]+", "");
					if (!path.startsWith(ABAPGIT + "/") || path.contains("..")) {
						throw new AdtException(400, "Unexpected abapGit link " + href);
					}
					return path;
				}
			}
			throw new AdtException(404, "The repository offers no " + kind + " link.");
		}
	}

	static List<Repo> repos(AdtClient c, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.get(ABAPGIT + "/repos", REPOS_V2), cancel);
		if (r.status() == 404) {
			throw new AdtException(404, "abapGit's ADT backend is not installed on this system.");
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		List<Repo> out = new ArrayList<>();
		for (Element e : AdtXml.elements(AdtXml.parse(r.body()), "repository")) {
			List<String[]> links = new ArrayList<>();
			for (Element l : AdtXml.elements(e, "link")) {
				links.add(new String[] { AdtXml.attr(l, "rel"), AdtXml.attr(l, "href"), AdtXml.attr(l, "type") });
			}
			out.add(new Repo(field(e, "key"), field(e, "package"), field(e, "url"), field(e, "branchName"), links));
		}
		return out;
	}

	static Repo repo(AdtClient c, String keyOrPackage, CancelToken cancel) throws IOException {
		String wanted = keyOrPackage == null ? "" : keyOrPackage.trim();
		for (Repo r : repos(c, cancel)) {
			if (r.key().equals(wanted) || r.pkg().equalsIgnoreCase(wanted)) {
				return r;
			}
		}
		throw new AdtException(404, "No abapGit repository with key or package " + wanted + ".");
	}

	private static String field(Element e, String name) {
		String child = AdtDiagnostics.child(e, name);
		return child.isEmpty() ? AdtXml.attr(e, name) : child;
	}

	static String repoXml(String pkg, String url, String branch, String transport) {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<abapgitrepo:repository xmlns:abapgitrepo=\"" + NS_REPO + "\">\n"
				+ "  <abapgitrepo:package>" + AdtXml.escape(pkg.toUpperCase(Locale.ROOT)) + "</abapgitrepo:package>\n"
				+ "  <abapgitrepo:url>" + AdtXml.escape(url) + "</abapgitrepo:url>\n"
				+ (branch == null || branch.isBlank() ? ""
						: "  <abapgitrepo:branchName>" + AdtXml.escape(branch) + "</abapgitrepo:branchName>\n")
				+ (transport == null || transport.isBlank() ? ""
						: "  <abapgitrepo:transportRequest>" + AdtXml.escape(transport) + "</abapgitrepo:transportRequest>\n")
				+ "</abapgitrepo:repository>";
	}

	/** Objects an abapGit clone or pull reports, with errors first. */
	static String objectResult(String xml) throws IOException {
		if (xml == null || xml.isBlank()) {
			return "No objects changed.";
		}
		StringBuilder errors = new StringBuilder();
		int count = 0;
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "abapObject")) {
			count++;
			String type = field(e, "msgType");
			if (type.matches("[EAX]")) {
				errors.append("- ").append(field(e, "type")).append(' ').append(field(e, "name")).append(": ")
						.append(field(e, "msgText")).append('\n');
			}
		}
		return count + " objects processed." + (errors.isEmpty() ? "" : "\nErrors:\n" + errors);
	}

	/**
	 * The push payload: the objects abapGit reports as unstaged (all, or those
	 * named) become staged, with the commit message and author.
	 */
	static String stagingPayload(String stageXml, List<String> only, String comment, String authorName,
			String authorEmail) throws IOException {
		StringBuilder staged = new StringBuilder();
		int count = 0;
		for (Element unstaged : AdtXml.elements(AdtXml.parse(stageXml), "unstaged_objects")) {
			for (Node n = unstaged.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (!(n instanceof Element o) || !AdtDiagnostics.local(o).equals("abapgitobject")) {
					continue;
				}
				String name = AdtXml.attr(o, "name");
				if (!only.isEmpty() && only.stream().noneMatch(name::equalsIgnoreCase)) {
					continue;
				}
				count++;
				staged.append("    <abapgitstaging:abapgitobject").append(a("adtcore:name", name))
						.append(a("adtcore:type", AdtXml.attr(o, "type"))).append(a("adtcore:uri", AdtXml.attr(o, "uri")))
						.append(a("abapgitstaging:wbkey", AdtXml.attr(o, "wbkey"))).append(">\n");
				for (Element f : AdtXml.elements(o, "abapgitfile")) {
					staged.append("      <abapgitstaging:abapgitfile").append(a("abapgitstaging:name", AdtXml.attr(f, "name")))
							.append(a("abapgitstaging:path", AdtXml.attr(f, "path")))
							.append(a("abapgitstaging:localState", AdtXml.attr(f, "localState")))
							.append(a("abapgitstaging:remoteState", AdtXml.attr(f, "remoteState"))).append("/>\n");
				}
				staged.append("    </abapgitstaging:abapgitobject>\n");
			}
		}
		if (count == 0) {
			throw new AdtException(400, "Nothing to push: abapGit reports no changed objects"
					+ (only.isEmpty() ? "." : " among " + String.join(", ", only) + "."));
		}
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<abapgitstaging:abapgitstaging xmlns:abapgitstaging=\""
				+ NS_STAGING + "\" xmlns:adtcore=\"http://www.sap.com/adt/core\">\n  <abapgitstaging:unstaged_objects/>\n"
				+ "  <abapgitstaging:staged_objects>\n" + staged + "  </abapgitstaging:staged_objects>\n"
				+ "  <abapgitstaging:ignored_objects/>\n  <abapgitstaging:abapgit_comment" + a("abapgitstaging:comment", comment)
				+ ">\n    <abapgitstaging:author" + a("abapgitstaging:name", authorName) + a("abapgitstaging:email", authorEmail)
				+ "/>\n    <abapgitstaging:committer" + a("abapgitstaging:name", authorName)
				+ a("abapgitstaging:email", authorEmail) + "/>\n  </abapgitstaging:abapgit_comment>\n"
				+ "</abapgitstaging:abapgitstaging>";
	}

	private static String a(String name, String value) {
		return value == null || value.isEmpty() ? "" : " " + name + "=\"" + AdtXml.escape(value) + "\"";
	}
}

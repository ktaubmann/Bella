package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * ADT REST calls used by Bella's tools. Pure request building and response
 * parsing; the transport (and thus the logon) comes from the ADT bundle.
 */
public final class AdtClient {

	private final AdtTransport transport;
	private final SourceCache cache;
	private final String cacheScope;
	/** Check variant for ATC runs without one; empty for the system default. */
	private String atcVariant = "";

	public AdtClient(AdtTransport transport) {
		this(transport, null, null);
	}

	/**
	 * @param cache      source cache shared across calls, or {@code null}
	 * @param cacheScope key of the system in the cache (e.g. the destination id)
	 */
	public AdtClient(AdtTransport transport, SourceCache cache, String cacheScope) {
		this.transport = transport;
		this.cache = cache;
		this.cacheScope = cacheScope;
	}

	/**
	 * Sets the check variant ATC runs use when the caller names none, e.g. one
	 * without the remote checks of a central check system.
	 *
	 * @param variant check variant; {@code null} or empty for the system default
	 */
	public AdtClient atcVariant(String variant) {
		this.atcVariant = variant == null ? "" : variant.trim().toUpperCase(Locale.ROOT);
		return this;
	}

	static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** Sends a request through this client's transport and returns the response whatever its status. */
	AdtResponse exchange(AdtRequest r, CancelToken cancel) throws IOException {
		return exchange(transport, r, cancel);
	}

	private AdtResponse send(AdtRequest r, CancelToken cancel) throws IOException {
		AdtResponse response = exchange(transport, r, cancel);
		if (!response.ok()) {
			throw new AdtException(response.status(), AdtErrors.message(response));
		}
		return response;
	}

	/** Sends a request and writes method, path, status and duration to Bella's log. */
	static AdtResponse exchange(AdtTransport t, AdtRequest r, CancelToken cancel) throws IOException {
		long start = System.nanoTime();
		String what = (t.isStateful() ? "[stateful] " : "") + r.method() + " " + r.path();
		Log.debug(AREA, () -> r.body() == null ? what : what + " body:\n" + Log.clip(r.body(), 4_000));
		AdtResponse response;
		try {
			response = t.send(r, cancel);
		} catch (IOException | RuntimeException e) {
			Log.warn(AREA, what + " failed after " + Log.millisSince(start) + " ms: " + e);
			throw e;
		}
		if (response.ok()) {
			Log.info(AREA, what + " -> " + response.status() + " (" + Log.millisSince(start) + " ms)");
		} else {
			Log.warn(AREA, what + " -> " + response.status() + " (" + Log.millisSince(start) + " ms): "
					+ AdtErrors.message(response));
			Log.debug(AREA, () -> "response body:\n" + Log.clip(response.body(), 4_000));
		}
		return response;
	}

	private static final String AREA = "adt";

	// ---- search ----------------------------------------------------------

	public List<AdtObjectRef> search(String query, String type, int max, CancelToken cancel) throws IOException {
		StringBuilder path = new StringBuilder("/sap/bc/adt/repository/informationsystem/search?operation=quickSearch&query=")
				.append(enc(query)).append("&maxResults=").append(max);
		if (type != null && !type.isBlank()) {
			path.append("&objectType=").append(enc(type.toUpperCase(Locale.ROOT)));
		}
		AdtResponse r = send(AdtRequest.get(path.toString(), "application/xml"), cancel);
		return parseObjectReferences(r.body());
	}

	/** One object with lines that contain the searched text. */
	public record SourceHit(String name, String type, String uri, List<SourceLine> lines) {
	}

	/** A matching line, counted from 1 (0 if ADT does not say). */
	public record SourceLine(int line, String text) {
	}

	/**
	 * Full-text search in ABAP sources (ADT's text search, SAP_BASIS 7.51 and later; the request follows ARC-1).
	 *
	 * @param type    object type filter, e.g. CLAS, or {@code null}
	 * @param pkg     package filter, or {@code null}
	 */
	public List<SourceHit> searchSource(String text, String type, String pkg, int max, CancelToken cancel)
			throws IOException {
		StringBuilder path = new StringBuilder("/sap/bc/adt/repository/informationsystem/textsearch?searchString=")
				.append(enc(text)).append("&searchFromIndex=1&searchToIndex=").append(max);
		if (type != null && !type.isBlank()) {
			String t = type.trim().toUpperCase(Locale.ROOT);
			// the filter takes the short form; function modules are FUNC, not FUGR/FF
			path.append("&objectType=").append(enc(t.equals("FUGR/FF") ? "FUNC" : t.replaceFirst("/.*", "")));
		}
		if (pkg != null && !pkg.isBlank()) {
			path.append("&packageName=").append(enc(pkg.trim().toUpperCase(Locale.ROOT)));
		}
		AdtResponse r = exchange(transport, AdtRequest.get(path.toString(), "application/xml"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), textSearchError(r));
		}
		return parseSourceHits(r.body());
	}

	private static String textSearchError(AdtResponse r) {
		String msg = AdtErrors.message(r);
		String body = r.body() == null ? "" : r.body();
		// the message itself, not the whole body: a body may mention "020" anywhere, e.g. in a date
		if (UNSUPPORTED_ID.matcher(body).find() && UNSUPPORTED_NO.matcher(body).find()
				|| msg.toLowerCase(Locale.ROOT).contains("not supported")) {
			return "This SAP system does not support source code search (SADT_REST 020).";
		}
		return switch (r.status()) {
		case 401, 403 -> "No authorization for source code search (authorization object S_ADT_RES): " + msg;
		case 404 -> "This SAP system has no source code search (ADT text search is missing).";
		case 501 -> "Source code search needs SAP_BASIS 7.51 or later.";
		default -> "Source code search failed: " + msg;
		};
	}

	/** T100 key SADT_REST 020 in an ADT exception: the system has no text search. */
	private static final Pattern UNSUPPORTED_ID = Pattern.compile("T100KEY-ID\"[^>]*>\\s*SADT_REST\\s*<");
	private static final Pattern UNSUPPORTED_NO = Pattern.compile("T100KEY-NO\"[^>]*>\\s*0*20\\s*<");
	private static final Pattern START_LINE = Pattern.compile("#start=(\\d+)|\\bposition:(\\d+)");
	private static final Pattern OBJECT_NAME = Pattern.compile("(?i)objectName:([^,#]+)");

	static List<SourceHit> parseSourceHits(String xml) throws IOException {
		List<SourceHit> out = new ArrayList<>();
		Document d = AdtXml.parse(xml);
		List<Element> objects = AdtXml.elements(d, "textSearchObject");
		for (Element o : objects) {
			List<SourceLine> lines = new ArrayList<>();
			for (Element l : AdtXml.elements(o, "textLine")) {
				// nested objects (a class and its include) list their own lines; count each line once
				if (nearestSearchObject(l) != o) {
					continue;
				}
				String content = "";
				for (Element c : AdtXml.elements(l, "content")) {
					content = AdtXml.text(c);
					break;
				}
				lines.add(new SourceLine(lineOfUri(AdtXml.attr(l, "uri")), snippet(content)));
			}
			// parent nodes only give the path to the hits
			if (lines.isEmpty()) {
				continue;
			}
			String uri = AdtXml.attr(o, "uri");
			String name = objectNameOfUri(uri);
			String type = "";
			for (Element m : AdtXml.elements(o, "adtMainObject")) {
				type = AdtXml.attr(m, "type");
				name = name.isEmpty() ? AdtXml.attr(m, "name") : name;
				break;
			}
			out.add(new SourceHit(name, type, uri, lines));
		}
		if (!objects.isEmpty()) {
			return out;
		}
		// older releases answer with object references
		for (Element r : AdtXml.elements(d, "objectReference")) {
			List<SourceLine> lines = new ArrayList<>();
			for (Element m : AdtXml.elements(r, "textSearchResult")) {
				String line = AdtXml.attr(m, "line");
				String snip = AdtXml.attr(m, "snippet");
				lines.add(new SourceLine(line.matches("\\d+") ? Integer.parseInt(line) : 0,
						snippet(snip.isEmpty() ? AdtXml.text(m) : snip)));
			}
			out.add(new SourceHit(AdtXml.attr(r, "name"), AdtXml.attr(r, "type"), AdtXml.attr(r, "uri"), lines));
		}
		return out;
	}

	private static Element nearestSearchObject(Element e) {
		for (Node n = e.getParentNode(); n != null; n = n.getParentNode()) {
			if (n instanceof Element p && "textSearchObject".equals(p.getLocalName())) {
				return p;
			}
		}
		return null;
	}

	private static String decode(String s) {
		try {
			return java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return s;
		}
	}

	private static int lineOfUri(String uri) {
		Matcher m = START_LINE.matcher(decode(uri));
		return m.find() ? Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)) : 0;
	}

	/** The object name inside a proxy URI ({@code …?content=objectName:ZCL_X,…}). */
	private static String objectNameOfUri(String uri) {
		int i = uri.indexOf("content=");
		if (i < 0) {
			return "";
		}
		String content = decode(uri.substring(i + 8).replaceFirst("&.*", ""));
		Matcher m = OBJECT_NAME.matcher(content);
		String raw = m.find() ? m.group(1) : content.split("#")[0];
		return raw.replaceFirst("=+.*$", "").trim();
	}

	private static String snippet(String raw) {
		return raw.replaceAll("(?i)</?b>", "").replaceAll("\\s+", " ").trim();
	}

	/** Objects of a package (search by package name; nodestructure mixes up descriptions on real systems). */
	public List<AdtObjectRef> packageContents(String packageName, int max, CancelToken cancel) throws IOException {
		String path = "/sap/bc/adt/repository/informationsystem/search?operation=quickSearch&query=*&packageName="
				+ enc(packageName.trim().toUpperCase(Locale.ROOT)) + "&maxResults=" + max;
		return parseObjectReferences(send(AdtRequest.get(path, "application/xml"), cancel).body());
	}

	static List<AdtObjectRef> parseObjectReferences(String xml) throws IOException {
		List<AdtObjectRef> refs = new ArrayList<>();
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "objectReference")) {
			refs.add(new AdtObjectRef(AdtXml.attr(e, "uri"), AdtXml.attr(e, "name"), AdtXml.attr(e, "type"),
					AdtXml.attr(e, "packageName"), AdtXml.attr(e, "description")));
		}
		return refs;
	}

	/**
	 * Resolves an object to its ADT URI: directly from the type when possible,
	 * otherwise via an exact-name search.
	 */
	public AdtObjectRef resolve(String name, String type, CancelToken cancel) throws IOException {
		type = searchType(type);
		String direct = AdtObjectRef.uriFor(name, type);
		if (direct != null) {
			return new AdtObjectRef(direct, name.toUpperCase(Locale.ROOT), type, "", "");
		}
		for (AdtObjectRef ref : search(name, type, 20, cancel)) {
			if (ref.name().equalsIgnoreCase(name)) {
				return ref;
			}
		}
		throw new AdtException(404, "Object not found: " + name + (type == null ? "" : " (" + type + ")"));
	}

	/** Maps names models commonly use to ADT search types, e.g. {@code FUNC} to {@code FUGR/FF}. */
	static String searchType(String type) {
		if (type == null || type.isBlank()) {
			return null;
		}
		String t = type.trim().toUpperCase(Locale.ROOT);
		return switch (t) {
		case "FUNC", "FUNCTION", "FM", "FUNCTION_MODULE" -> "FUGR/FF";
		case "TABLE" -> "TABL/DT";
		case "STRUCTURE", "STRU" -> "TABL/DS";
		case "DATA_ELEMENT" -> "DTEL";
		case "DOMAIN" -> "DOMA";
		case "TABLE_TYPE" -> "TTYP";
		case "CLASS" -> "CLAS";
		case "INTERFACE" -> "INTF";
		case "PROGRAM", "REPORT" -> "PROG";
		case "CDS" -> "DDLS";
		default -> t;
		};
	}

	// ---- source ------------------------------------------------------------

	public String readSource(String objectUri, String include, CancelToken cancel) throws IOException {
		return readSource(objectUri, include, null, cancel);
	}

	/**
	 * Source text of an object or class include.
	 *
	 * @param version {@code active}, {@code inactive} or {@code null} for what
	 *                ADT returns by default (the inactive version if there is one)
	 */
	public String readSource(String objectUri, String include, String version, CancelToken cancel) throws IOException {
		String path = AdtObjectRef.sourceUri(objectUri, include);
		if (version != null && !version.isBlank()) {
			path += "?version=" + enc(version.toLowerCase(Locale.ROOT));
		}
		AdtRequest request = AdtRequest.get(path, "text/plain");
		SourceCache.Entry cached = cache == null ? null : cache.get(cacheScope, path);
		if (cached != null) {
			request = request.withHeader("If-None-Match", cached.etag());
		}
		AdtResponse r = exchange(transport, request, cancel);
		if (r.status() == 304 && cached != null) {
			return cached.text();
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		String etag = r.header("ETag");
		if (cache != null && etag != null && !etag.isBlank()) {
			cache.put(cacheScope, path, etag, r.body());
		}
		return r.body();
	}

	/** Drops cached sources of an object after it was written or activated. */
	public void invalidate(String objectUri) {
		if (cache != null) {
			cache.invalidate(cacheScope, objectUri);
		}
	}

	/**
	 * Names (upper case) of the objects with changes that are saved but not
	 * activated, from the developer's inactive-objects list. Empty when the
	 * system does not offer the list.
	 */
	public List<String> inactiveObjects(CancelToken cancel) throws IOException {
		AdtResponse r = exchange(transport, AdtRequest.get("/sap/bc/adt/activation/inactiveobjects",
				"application/vnd.sap.adt.inactivectsobjects.v1+xml, application/xml;q=0.8"), cancel);
		if (!r.ok()) {
			return List.of();
		}
		return parseInactiveObjects(r.body());
	}

	static List<String> parseInactiveObjects(String xml) throws IOException {
		List<String> out = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return out;
		}
		Document doc = AdtXml.parse(xml);
		// ioc:ref on newer releases, a flat list of objectReference on 7.50
		List<Element> refs = new ArrayList<>(AdtXml.elements(doc, "ref"));
		refs.addAll(AdtXml.elements(doc, "objectReference"));
		for (Element e : refs) {
			String name = AdtXml.attr(e, "name").toUpperCase(Locale.ROOT);
			if (AdtXml.attr(e, "uri").contains("/cts/")) {
				continue; // the transport request an object is recorded in
			}
			if (!name.isEmpty() && !out.contains(name)) {
				out.add(name);
			}
		}
		return out;
	}

	/** Main types ADT describes only as XML, without a source text. */
	private static final List<String> XML_ONLY = List.of("DTEL", "DOMA", "TTYP", "MSAG", "VIEW", "SHLP", "ENQU");

	/** Characters of an XML description kept by {@link #readDefinition}. */
	static final int XML_SUMMARY_CHARS = 8_000;

	static boolean xmlOnly(String type) {
		if (type == null) {
			return false;
		}
		String t = type.toUpperCase(Locale.ROOT);
		int slash = t.indexOf('/');
		return XML_ONLY.contains(slash > 0 ? t.substring(0, slash) : t);
	}

	/**
	 * Source or definition of any repository object: the source text where ADT
	 * has one (classes, programs, CDS, function modules and, on newer releases,
	 * tables and structures), otherwise a compact summary of the object's XML
	 * description (data elements, domains, table types, message classes, …).
	 */
	public String readDefinition(AdtObjectRef ref, CancelToken cancel) throws IOException {
		String uri = AdtObjectRef.objectUri(ref.uri());
		if (!xmlOnly(ref.type())) {
			try {
				return readSource(uri, null, cancel);
			} catch (AdtException e) {
				if (!isMissingEndpoint(e)) {
					throw e;
				}
			}
		}
		try {
			AdtResponse r = send(AdtRequest.get(uri, "application/*"), cancel);
			return AdtXml.summarize(r.body(), XML_SUMMARY_CHARS);
		} catch (AdtException e) {
			if (!isMissingEndpoint(e)) {
				throw e;
			}
			throw new AdtException(e.status(), "The definition of " + ref.name() + " (" + ref.type()
					+ ") cannot be read through ADT on this SAP release. ARC-1 may be able to read it.");
		}
	}

	private static boolean isMissingEndpoint(AdtException e) {
		return e.status() == 404 || e.status() == 406 || e.status() == 415 || e.status() == 405;
	}

	/**
	 * Package of an object from its ADT description (upper case), or empty if
	 * the description names none. Function modules take their group's package.
	 */
	public String packageOf(String objectUri, CancelToken cancel) throws IOException {
		String uri = AdtObjectRef.objectUri(objectUri);
		String pkg = parsePackage(send(AdtRequest.get(uri, "application/*"), cancel).body());
		int fm = uri.indexOf("/fmodules/");
		if (pkg.isEmpty() && fm > 0) {
			pkg = parsePackage(send(AdtRequest.get(uri.substring(0, fm), "application/*"), cancel).body());
		}
		return pkg;
	}

	static String parsePackage(String xml) throws IOException {
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "packageRef")) {
			String name = AdtXml.attr(e, "name");
			if (!name.isEmpty()) {
				return name.toUpperCase(Locale.ROOT);
			}
		}
		return "";
	}

	// ---- system, transports, dumps -------------------------------------------

	/** Release and kind of a system, from its installed software components. */
	public record SystemInfo(String basisRelease, boolean cloud) {

		/** Release and cloud flag for Bella's style check. */
		public de.kiliantaubmann.bella.core.lint.AbapLint.Target lintTarget() {
			return de.kiliantaubmann.bella.core.lint.AbapLint.Target.of(basisRelease, cloud);
		}

		/** e.g. "SAP_BASIS 758, on-premise" or "SAP BTP ABAP Environment (ABAP Cloud only)". */
		public String describe() {
			if (cloud) {
				return "SAP BTP ABAP Environment (ABAP Cloud only, released APIs)";
			}
			return basisRelease.isEmpty() ? "on-premise, release unknown" : "SAP_BASIS " + basisRelease + ", on-premise";
		}
	}

	public SystemInfo systemInfo(CancelToken cancel) throws IOException {
		return parseComponents(send(AdtRequest.get("/sap/bc/adt/system/components", "application/atom+xml;type=feed"),
				cancel).body());
	}

	static SystemInfo parseComponents(String xml) throws IOException {
		String basis = "";
		boolean cloud = false;
		for (Element entry : AdtXml.elements(AdtXml.parse(xml), "entry")) {
			List<Element> ids = AdtXml.elements(entry, "id");
			List<Element> titles = AdtXml.elements(entry, "title");
			String id = ids.isEmpty() ? "" : AdtXml.text(ids.get(0)).trim().toUpperCase(Locale.ROOT);
			String title = titles.isEmpty() ? "" : AdtXml.text(titles.get(0));
			if (id.equals("SAP_BASIS")) {
				basis = title.split(";")[0].trim();
			} else if (id.equals("SAP_CLOUD")) {
				cloud = true;
			}
		}
		return new SystemInfo(basis, cloud);
	}

	/** Result of SAP's transport check for changing an object. */
	public record TransportCheck(String packageName, boolean local, boolean recordingRequired, String lockedIn,
			List<String> candidates, List<String> errors) {
	}

	/**
	 * Asks SAP which transport request a change of the object needs: whether it
	 * is recorded at all, the request it is already locked in and the
	 * developer's open requests that fit.
	 *
	 * @param operation {@code I} for creating the object, empty for changing it
	 */
	public TransportCheck transportCheck(String objectUri, String packageName, String operation, CancelToken cancel)
			throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><asx:abap xmlns:asx=\"http://www.sap.com/abapxml\" version=\"1.0\">"
				+ "<asx:values><DATA><DEVCLASS>" + AdtXml.escape(packageName) + "</DEVCLASS><URI>"
				+ AdtXml.escape(AdtObjectRef.objectUri(objectUri)) + "</URI><OPERATION>" + AdtXml.escape(operation)
				+ "</OPERATION></DATA></asx:values></asx:abap>";
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/cts/transportchecks", "application/vnd.sap.as+xml", body,
				"application/vnd.sap.as+xml;charset=UTF-8;dataname=com.sap.adt.transport.service.checkData"), cancel);
		return parseTransportCheck(r.body());
	}

	static TransportCheck parseTransportCheck(String xml) throws IOException {
		Document doc = AdtXml.parse(xml);
		String pkg = first(doc, "DEVCLASS");
		boolean local = first(doc, "DLVUNIT").equals("LOCAL") || pkg.startsWith("$");
		boolean recording = first(doc, "RECORDING").equals("X") || first(doc, "KORRFLAG").equals("X");
		String locked = "";
		for (Element locks : AdtXml.elements(doc, "LOCKS")) {
			for (Element h : AdtXml.elements(locks, "TRKORR")) {
				locked = AdtXml.text(h).trim();
				break;
			}
		}
		List<String> candidates = new ArrayList<>();
		for (Element requests : AdtXml.elements(doc, "REQUESTS")) {
			for (Element header : AdtXml.elements(requests, "REQ_HEADER")) {
				String id = first(header, "TRKORR");
				if (!id.isEmpty() && !candidates.stream().anyMatch(c -> c.startsWith(id + " "))) {
					candidates.add(id + " " + first(header, "AS4TEXT") + " (" + first(header, "AS4USER") + ")");
				}
			}
		}
		List<String> errors = new ArrayList<>();
		for (Element m : AdtXml.elements(doc, "CTS_MESSAGE")) {
			String sev = first(m, "SEVERITY").toUpperCase(Locale.ROOT);
			if (sev.equals("E") || sev.equals("A") || sev.equals("X")) {
				errors.add(first(m, "TEXT"));
			}
		}
		return new TransportCheck(pkg, local, recording, locked, candidates, errors);
	}

	private static String first(Node root, String localName) {
		List<Element> e = AdtXml.elements(root, localName);
		return e.isEmpty() ? "" : AdtXml.text(e.get(0)).trim();
	}

	/** One short dump (ST22). */
	public record Dump(String id, String time, String user, String error, String program) {
	}

	/** Newest short dumps, optionally only those of one user. */
	public List<Dump> dumps(String user, int max, CancelToken cancel) throws IOException {
		StringBuilder path = new StringBuilder("/sap/bc/adt/runtime/dumps?$top=").append(max);
		if (user != null && !user.isBlank()) {
			path.append("&$query=").append(enc("and(equals(user," + user.trim().toUpperCase(Locale.ROOT) + "))"));
		}
		return parseDumps(send(AdtRequest.get(path.toString(), "application/atom+xml;type=feed"), cancel).body());
	}

	static List<Dump> parseDumps(String xml) throws IOException {
		List<Dump> out = new ArrayList<>();
		for (Element entry : AdtXml.elements(AdtXml.parse(xml), "entry")) {
			String id = dumpId(entry);
			if (id.isEmpty()) {
				continue;
			}
			String error = "";
			String program = "";
			for (Element c : AdtXml.elements(entry, "category")) {
				String label = AdtXml.attr(c, "label").toLowerCase(Locale.ROOT);
				String term = AdtXml.attr(c, "term");
				if (label.contains("program")) {
					program = term;
				} else if (error.isEmpty()) {
					error = term;
				}
			}
			List<Element> names = AdtXml.elements(entry, "name");
			String published = first(entry, "published");
			out.add(new Dump(id, published.isEmpty() ? first(entry, "updated") : published,
					names.isEmpty() ? "" : AdtXml.text(names.get(0)).trim(), error, program));
		}
		return out;
	}

	private static String dumpId(Element entry) {
		for (Element link : AdtXml.elements(entry, "link")) {
			String href = AdtXml.attr(link, "href");
			int i = href.indexOf("/runtime/dump/");
			if (i >= 0 && "self".equals(AdtXml.attr(link, "rel"))) {
				return href.substring(i + "/runtime/dump/".length());
			}
		}
		String id = first(entry, "id");
		int i = id.indexOf("/runtime/dumps/");
		return i >= 0 ? id.substring(i + "/runtime/dumps/".length()) : "";
	}

	/** Formatted text of a short dump, as ST22 shows it. */
	public String dumpText(String id, CancelToken cancel) throws IOException {
		String segment = id.trim().contains("%") ? id.trim() : enc(id.trim()).replace("+", "%20");
		return send(AdtRequest.get("/sap/bc/adt/runtime/dump/" + segment + "/formatted", "text/plain"), cancel).body();
	}

	// ---- transport requests and versions ----------------------------------------

	/**
	 * Transport requests of a user (Workbench, Customizing, transport of copies).
	 *
	 * @param status {@code D} modifiable (default), {@code R} released
	 */
	public List<AdtTransportRequest> transports(String user, String status, CancelToken cancel) throws IOException {
		String st = status == null || status.isBlank() ? "D" : status.trim().toUpperCase(Locale.ROOT);
		String path = "/sap/bc/adt/cts/transportrequests?user=" + enc(user == null ? "*" : user.toUpperCase(Locale.ROOT))
				+ "&target=true&requestType=KWT&requestStatus=" + enc(st);
		List<AdtTransportRequest> all = AdtTransportRequest.parse(send(AdtRequest.get(path,
				"application/vnd.sap.adt.transportorganizertree.v1+xml"), cancel).body());
		// some releases ignore requestStatus
		return all.stream().filter(t -> st.equals("R") ? t.released() : !t.released()).toList();
	}

	/** One transport request with its tasks and objects; empty if it does not exist. */
	public Optional<AdtTransportRequest> transport(String id, CancelToken cancel) throws IOException {
		String wanted = id.trim().toUpperCase(Locale.ROOT);
		AdtResponse r = exchange(transport, AdtRequest.get("/sap/bc/adt/cts/transportrequests/" + enc(wanted),
				"application/vnd.sap.adt.transportorganizer.v1+xml"), cancel);
		if (r.status() == 404) {
			return Optional.empty();
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		// 7.50 answers an unknown number with the user's whole list
		return AdtTransportRequest.parse(r.body()).stream().filter(t -> t.id().equalsIgnoreCase(wanted)).findFirst();
	}

	/** Version history of a source; empty when the object has none or the type keeps none. */
	public List<AdtRevisions.Revision> revisions(String versionsUri, CancelToken cancel) throws IOException {
		AdtResponse r = exchange(transport, AdtRequest.get(versionsUri, "application/atom+xml;type=feed"), cancel);
		if (r.status() == 404) {
			return List.of();
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		return AdtRevisions.parse(r.body());
	}

	/** Source text of one version. */
	public String revisionText(String contentUri, CancelToken cancel) throws IOException {
		return send(AdtRequest.get(contentUri, "text/plain"), cancel).body();
	}

	/** The request an object is currently locked in (empty if none or unknown). */
	public String lockedIn(String objectUri, CancelToken cancel) throws IOException {
		AdtResponse r = exchange(transport, AdtRequest.get(AdtObjectRef.objectUri(objectUri) + "/transports",
				"application/vnd.sap.as+xml"), cancel);
		if (!r.ok() || r.body() == null || r.body().isBlank()) {
			return "";
		}
		Matcher m = CORRNR.matcher(r.body());
		return m.find() ? m.group(1).trim() : "";
	}

	/** Lock result: handle plus the transport the object is already assigned to (if any). */
	public record Lock(String handle, String transport, boolean local) {
	}

	private static final Pattern LOCK_HANDLE = Pattern.compile("<LOCK_HANDLE>([^<]*)</LOCK_HANDLE>");
	private static final Pattern CORRNR = Pattern.compile("<CORRNR>([^<]*)</CORRNR>");
	private static final Pattern IS_LOCAL = Pattern.compile("<IS_LOCAL>([^<]*)</IS_LOCAL>");

	static Lock parseLock(String body) throws IOException {
		Matcher h = LOCK_HANDLE.matcher(body);
		if (!h.find()) {
			throw new IOException("ADT lock response without lock handle");
		}
		Matcher c = CORRNR.matcher(body);
		Matcher l = IS_LOCAL.matcher(body);
		return new Lock(h.group(1), c.find() ? c.group(1) : "", l.find() && "X".equals(l.group(1).trim()));
	}

	/**
	 * Writes source code to the SAP system: lock, PUT, unlock, all in one
	 * stateful session. Does not activate.
	 *
	 * @param transport transport request, or {@code null} to use the object's
	 *                  current one (required for non-local objects)
	 */
	public static String writeSource(AdtTransport.Session session, String objectUri, String include, String source,
			String transport, CancelToken cancel) throws IOException {
		return withLock(session, objectUri, transport, cancel, (handle, tr) -> {
			StringBuilder path = new StringBuilder(AdtObjectRef.sourceUri(objectUri, include)).append("?lockHandle=")
					.append(enc(handle));
			if (!tr.isBlank()) {
				path.append("&corrNr=").append(enc(tr));
			}
			AdtResponse put = exchange(session, AdtRequest.put(path.toString(), source, "text/plain; charset=utf-8"),
					cancel);
			if (!put.ok()) {
				throw new AdtException(put.status(), "Could not write source: " + AdtErrors.message(put));
			}
		});
	}

	/** A change made while an object is locked. */
	private interface LockedChange {
		void apply(String lockHandle, String transport) throws IOException;
	}

	/**
	 * Locks {@code objectUri}, runs {@code change} with the lock handle and the
	 * transport request to use, and unlocks, all in one stateful session.
	 *
	 * @return the transport request used, empty for local objects
	 */
	private static String withLock(AdtTransport.Session session, String objectUri, String transport,
			CancelToken cancel, LockedChange change) throws IOException {
		AdtRequest lockReq = AdtRequest.post(objectUri + "?_action=LOCK&accessMode=MODIFY",
				"application/*,application/vnd.sap.as+xml;charset=UTF-8;dataname=com.sap.adt.lock.result", null,
				null);
		AdtResponse lockResp = exchange(session, lockReq, cancel);
		if (!lockResp.ok()) {
			throw new AdtException(lockResp.status(), "Could not lock object: " + AdtErrors.message(lockResp));
		}
		Lock lock = parseLock(lockResp.body());
		try {
			String tr = transport != null && !transport.isBlank() ? transport : lock.transport();
			if (!lock.local() && (tr == null || tr.isBlank())) {
				throw new AdtException(400,
						"The object is not local ($TMP) and has no transport request. Ask the developer for one.");
			}
			change.apply(lock.handle(), tr == null ? "" : tr);
			return tr == null ? "" : tr;
		} finally {
			// an unlock failure must not hide the outcome of the write; the lock ends with the session anyway
			try {
				exchange(session, AdtRequest.post(objectUri + "?_action=UNLOCK&lockHandle=" + enc(lock.handle()), null,
						null, null), CancelToken.NONE);
			} catch (IOException e) {
				Log.warn("adt", "unlock of " + objectUri + " failed: " + e.getMessage());
			}
		}
	}

	// ---- text elements ---------------------------------------------------------

	/** Parts of a text pool: text symbols, selection texts, list headings. */
	public static final List<String> TEXT_PARTS = List.of("symbols", "selections", "headings");

	/**
	 * URI of the text pool of a program, class or function group on ADT's
	 * textelements service; the pool is locked and written on its own, apart
	 * from the source.
	 */
	static String textElementsUri(String type, String name) throws AdtException {
		String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
		String collection = switch (t.contains("/") ? t.substring(0, t.indexOf('/')) : t) {
		case "PROG" -> "programs";
		case "CLAS" -> "classes";
		case "FUGR" -> "functiongroups";
		default -> throw new AdtException(400,
				"Text elements exist for programs (PROG), classes (CLAS) and function groups (FUGR), not for " + type
						+ ".");
		};
		return "/sap/bc/adt/textelements/" + collection + "/" + enc(name.trim().toLowerCase(Locale.ROOT));
	}

	private static String textMediaType(String part) throws AdtException {
		if (!TEXT_PARTS.contains(part)) {
			throw new AdtException(400, "Unknown text element part '" + part + "'; use one of " + TEXT_PARTS + ".");
		}
		return "application/vnd.sap.adt.textelements." + part + ".v1";
	}

	/**
	 * One part of a text pool as ADT sends it: {@code @MaxLength:20} and
	 * {@code 001=Text} lines for symbols, {@code P_NAME=Text} for selection
	 * texts, {@code listHeader=…} and {@code columnHeader_1=…} for headings.
	 */
	public String textElements(String type, String name, String part, CancelToken cancel) throws IOException {
		String mediaType = textMediaType(part);
		return send(AdtRequest.get(textElementsUri(type, name) + "/source/" + part, mediaType), cancel).body();
	}

	/**
	 * Replaces one part of a text pool. The texts are active right away; the
	 * object itself needs no activation for them.
	 *
	 * @return the transport request used, empty for local objects
	 */
	public static String writeTextElements(AdtTransport.Session session, String type, String name, String part,
			String texts, String transport, CancelToken cancel) throws IOException {
		String mediaType = textMediaType(part);
		if (type.toUpperCase(Locale.ROOT).startsWith("CLAS") && !part.equals("symbols")) {
			throw new AdtException(400, "Classes only have text symbols; selection texts and headings belong to programs.");
		}
		String uri = textElementsUri(type, name);
		return withLock(session, uri, transport, cancel, (handle, tr) -> {
			StringBuilder path = new StringBuilder(uri).append("/source/").append(part).append("?lockHandle=")
					.append(enc(handle));
			if (!tr.isBlank()) {
				path.append("&corrNr=").append(enc(tr));
			}
			// SAP wants the part's media type as Accept as well, else it answers 400
			AdtResponse put = exchange(session,
					AdtRequest.put(path.toString(), texts, mediaType).withHeader("Accept", mediaType), cancel);
			if (!put.ok()) {
				throw new AdtException(put.status(), "Could not write the text elements: " + AdtErrors.message(put));
			}
		});
	}

	// ---- checks --------------------------------------------------------------

	/** One message of a syntax check, ATC run or activation. */
	public record Message(String severity, String text, String uri, int line) {

		public String format() {
			String include = include();
			return severity + (line > 0 ? " line " + line : "") + (include.isEmpty() ? "" : " in include " + include)
					+ ": " + text;
		}

		/**
		 * The class include the message is about ({@code testclasses},
		 * {@code definitions} …); empty for the main source. Line numbers
		 * count within it.
		 */
		public String include() {
			Matcher m = INCLUDE.matcher(uri == null ? "" : uri);
			return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
		}
	}

	private static final Pattern INCLUDE = Pattern.compile("/includes/([A-Za-z_]+)");

	private static final Pattern START = Pattern.compile("#start=(\\d+)");

	static int lineOf(String uri) {
		Matcher m = START.matcher(uri == null ? "" : uri);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	/**
	 * Syntax check. With {@code source} the given (e.g. unsaved) code is
	 * checked instead of the saved version.
	 */
	public List<Message> syntaxCheck(String objectUri, String source, CancelToken cancel) throws IOException {
		return syntaxCheck(objectUri, source, true, cancel);
	}

	/**
	 * Syntax check. Without {@code source}, {@code inactive} picks the saved
	 * version to check: the inactive one only exists after a save without
	 * activation; checking it for an active-only object reports an empty
	 * source ("REPORT/PROGRAM statement is missing").
	 */
	public List<Message> syntaxCheck(String objectUri, String source, boolean inactive, CancelToken cancel)
			throws IOException {
		StringBuilder body = new StringBuilder()
				.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
				.append("<chkrun:checkObjectList xmlns:chkrun=\"http://www.sap.com/adt/checkrun\" xmlns:adtcore=\"http://www.sap.com/adt/core\">")
				.append("<chkrun:checkObject adtcore:uri=\"").append(AdtXml.escape(objectUri))
				.append("\" chkrun:version=\"").append(source != null || inactive ? "inactive" : "active").append("\">");
		if (source != null) {
			body.append("<chkrun:artifacts><chkrun:artifact chkrun:contentType=\"text/plain; charset=utf-8\" chkrun:uri=\"")
					.append(AdtXml.escape(AdtObjectRef.sourceUri(objectUri, null))).append("\"><chkrun:content>")
					.append(Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)))
					.append("</chkrun:content></chkrun:artifact></chkrun:artifacts>");
		}
		body.append("</chkrun:checkObject></chkrun:checkObjectList>");
		return runCheck(body.toString(), cancel);
	}

	/**
	 * Syntax check of several saved objects in one run; objects matching
	 * {@code inactive} are checked in their inactive version, the others in
	 * the active one.
	 */
	public List<Message> syntaxCheck(List<String> objectUris, Predicate<String> inactive, CancelToken cancel)
			throws IOException {
		StringBuilder body = new StringBuilder()
				.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
				.append("<chkrun:checkObjectList xmlns:chkrun=\"http://www.sap.com/adt/checkrun\" xmlns:adtcore=\"http://www.sap.com/adt/core\">");
		for (String uri : objectUris) {
			body.append("<chkrun:checkObject adtcore:uri=\"").append(AdtXml.escape(uri))
					.append("\" chkrun:version=\"").append(inactive.test(uri) ? "inactive" : "active").append("\"/>");
		}
		body.append("</chkrun:checkObjectList>");
		return runCheck(body.toString(), cancel);
	}

	private List<Message> runCheck(String body, CancelToken cancel) throws IOException {
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/checkruns?reporters=abapCheckRun",
				"application/vnd.sap.adt.checkmessages+xml", body, "application/vnd.sap.adt.checkobjects+xml"),
				cancel);
		return parseCheckMessages(r.body());
	}

	static List<Message> parseCheckMessages(String xml) throws IOException {
		List<Message> out = new ArrayList<>();
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "checkMessage")) {
			String uri = AdtXml.attr(e, "uri");
			out.add(new Message(severity(AdtXml.attr(e, "type")), AdtXml.attr(e, "shortText"), uri, lineOf(uri)));
		}
		return out;
	}

	static String severity(String code) {
		return switch (code.toUpperCase(Locale.ROOT)) {
		case "E", "A", "X" -> "Error";
		case "W" -> "Warning";
		default -> "Info";
		};
	}

	/** Newer systems reject a plain application/xml Accept header with HTTP 406. */
	static final String UNIT_RESULT_TYPES = "application/vnd.sap.adt.abapunit.testruns.result.v2+xml, "
			+ "application/vnd.sap.adt.abapunit.testruns.result.v1+xml;q=0.9, application/xml;q=0.8";

	/** ABAP Unit run; returns a readable summary. */
	public String runUnitTests(String objectUri, CancelToken cancel) throws IOException {
		return runUnitTests(List.of(objectUri), cancel);
	}

	/** ABAP Unit run over several objects; returns a readable summary. */
	public String runUnitTests(List<String> objectUris, CancelToken cancel) throws IOException {
		StringBuilder refs = new StringBuilder();
		for (String uri : objectUris) {
			refs.append("<adtcore:objectReference adtcore:uri=\"").append(AdtXml.escape(uri)).append("\"/>");
		}
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<aunit:runConfiguration xmlns:aunit=\"http://www.sap.com/adt/aunit\">"
				+ "<external><coverage active=\"false\"/></external>"
				+ "<options><uriType value=\"semantic\"/>"
				+ "<testDeterminationStrategy sameProgram=\"true\" assignedTests=\"false\"/>"
				+ "<testRiskLevels harmless=\"true\" dangerous=\"true\" critical=\"true\"/>"
				+ "<testDurations short=\"true\" medium=\"true\" long=\"true\"/>"
				+ "<withNavigationUri enabled=\"false\"/></options>"
				+ "<adtcore:objectSets xmlns:adtcore=\"http://www.sap.com/adt/core\"><objectSet kind=\"inclusive\">"
				+ "<adtcore:objectReferences>" + refs
				+ "</adtcore:objectReferences></objectSet></adtcore:objectSets></aunit:runConfiguration>";
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/abapunit/testruns", UNIT_RESULT_TYPES, body,
				"application/vnd.sap.adt.abapunit.testruns.config.v4+xml"), cancel);
		return summarizeUnitResult(r.body());
	}

	static String summarizeUnitResult(String xml) throws IOException {
		Document doc = AdtXml.parse(xml);
		StringBuilder sb = new StringBuilder();
		int methods = 0;
		int failed = 0;
		for (Element cls : AdtXml.elements(doc, "testClass")) {
			for (Element m : AdtXml.elements(cls, "testMethod")) {
				methods++;
				List<Element> alerts = AdtXml.elements(m, "alert");
				if (!alerts.isEmpty()) {
					failed++;
					sb.append("FAILED ").append(AdtXml.attr(cls, "name")).append("->").append(AdtXml.attr(m, "name"))
							.append('\n');
					for (Element a : alerts) {
						sb.append("  [").append(AdtXml.attr(a, "severity")).append("] ");
						List<Element> titles = AdtXml.elements(a, "title");
						sb.append(titles.isEmpty() ? AdtXml.attr(a, "kind") : AdtXml.text(titles.get(0))).append('\n');
						for (Element d : AdtXml.elements(a, "detail")) {
							String t = AdtXml.attr(d, "text");
							if (!t.isEmpty()) {
								sb.append("    ").append(t).append('\n');
							}
						}
					}
				}
			}
		}
		// Alerts on program or class level (e.g. no tests found, syntax errors)
		for (Element prog : AdtXml.elements(doc, "program")) {
			for (Element a : AdtXml.elements(prog, "alert")) {
				if (a.getParentNode() != null && a.getParentNode().getParentNode() == prog) {
					List<Element> titles = AdtXml.elements(a, "title");
					sb.append("Note: ").append(titles.isEmpty() ? AdtXml.attr(a, "kind") : AdtXml.text(titles.get(0)))
							.append('\n');
				}
			}
		}
		String head = methods == 0 ? "No test methods were executed.\n"
				: (methods - failed) + " of " + methods + " test methods passed.\n";
		return head + sb;
	}

	/** ATC check with the given variant (or the configured one, or the system default). */
	public List<Message> atcCheck(String objectUri, String variant, CancelToken cancel) throws IOException {
		return atcCheck(List.of(objectUri), variant, cancel);
	}

	/** One ATC run over several objects with the given variant (or the configured one, or the system default). */
	public List<Message> atcCheck(List<String> objectUris, String variant, CancelToken cancel) throws IOException {
		String v = variant == null || variant.isBlank() ? atcVariant : variant;
		if (v.isBlank()) {
			v = atcDefaultVariant(cancel);
		}
		String worklist = send(AdtRequest.post("/sap/bc/adt/atc/worklists?checkVariant=" + enc(v), "text/plain", null,
				null), cancel).body().trim();
		String run = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><atc:run maximumVerdicts=\"100\" xmlns:atc=\"http://www.sap.com/adt/atc\">"
				+ "<objectSets xmlns:adtcore=\"http://www.sap.com/adt/core\"><objectSet kind=\"inclusive\"><adtcore:objectReferences>"
				+ objectUris.stream().map(u -> "<adtcore:objectReference adtcore:uri=\"" + AdtXml.escape(u) + "\"/>")
						.collect(Collectors.joining())
				+ "</adtcore:objectReferences></objectSet></objectSets></atc:run>";
		send(AdtRequest.post("/sap/bc/adt/atc/runs?worklistId=" + enc(worklist), "application/xml", run,
				"application/xml"), cancel);
		AdtResponse result = send(AdtRequest.get(
				"/sap/bc/adt/atc/worklists/" + enc(worklist) + "?includeExemptedFindings=false",
				"application/atc.worklist.v1+xml"), cancel);
		return parseAtcFindings(result.body());
	}

	private String atcDefaultVariant(CancelToken cancel) {
		try {
			AdtResponse r = exchange(transport, AdtRequest.get("/sap/bc/adt/atc/customizing", "application/xml"), cancel);
			if (r.ok()) {
				for (Element p : AdtXml.elements(AdtXml.parse(r.body()), "property")) {
					if ("systemCheckVariant".equals(AdtXml.attr(p, "name"))) {
						return AdtXml.attr(p, "value");
					}
				}
			}
		} catch (IOException e) {
			// fall back below
		}
		return "DEFAULT";
	}

	static List<Message> parseAtcFindings(String xml) throws IOException {
		List<Message> out = new ArrayList<>();
		for (Element f : AdtXml.elements(AdtXml.parse(xml), "finding")) {
			String prio = AdtXml.attr(f, "priority");
			String sev = switch (prio) {
			case "1" -> "Error";
			case "2" -> "Warning";
			default -> "Info";
			};
			String location = AdtXml.attr(f, "location");
			String title = AdtXml.attr(f, "messageTitle");
			String check = AdtXml.attr(f, "checkTitle");
			out.add(new Message(sev, check.isEmpty() ? title : check + ": " + title, location, lineOf(location)));
		}
		// errors first, then warnings, then infos; within a priority by object and line
		out.sort(Comparator.comparingInt((Message m) -> atcRank(m.severity()))
				.thenComparing(m -> objectUri(m.uri()))
				.thenComparingInt(Message::line));
		return out;
	}

	private static int atcRank(String severity) {
		return switch (severity) {
		case "Error" -> 0;
		case "Warning" -> 1;
		default -> 2;
		};
	}

	private static String objectUri(String uri) {
		return uri == null ? "" : AdtObjectRef.objectUri(uri);
	}

	/** Where-used list. */
	public List<AdtObjectRef> whereUsed(String objectUri, CancelToken cancel) throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<usagereferences:usageReferenceRequest xmlns:usagereferences=\"http://www.sap.com/adt/ris/usageReferences\">"
				+ "<usagereferences:affectedObjects/></usagereferences:usageReferenceRequest>";
		AdtResponse r = send(AdtRequest.post(
				"/sap/bc/adt/repository/informationsystem/usageReferences?uri=" + enc(objectUri),
				"application/vnd.sap.adt.repository.usagereferences.result.v1+xml", body,
				"application/vnd.sap.adt.repository.usagereferences.request.v1+xml"), cancel);
		List<AdtObjectRef> out = new ArrayList<>();
		for (Element e : AdtXml.elements(AdtXml.parse(r.body()), "referencedObject")) {
			String uri = AdtXml.attr(e, "uri");
			List<Element> adt = AdtXml.elements(e, "adtObject");
			Element src = adt.isEmpty() ? e : adt.get(0);
			String name = AdtXml.attr(src, "name");
			if (name.isEmpty()) {
				continue;
			}
			out.add(new AdtObjectRef(uri.isEmpty() ? AdtXml.attr(src, "uri") : uri, name, AdtXml.attr(src, "type"),
					"", AdtXml.attr(src, "description")));
		}
		return out;
	}

	/**
	 * Rows of a table or view read through ADT's data preview (the SQL console
	 * of ADT). Only SELECT statements are sent; SAP checks the developer's
	 * authorization for the data.
	 *
	 * @param sql     ABAP SQL SELECT, e.g. {@code SELECT matnr, mtart FROM mara WHERE mtart = 'FERT'}
	 * @param maxRows rows SAP returns at most
	 */
	public TableData tableContents(String sql, int maxRows, CancelToken cancel) throws IOException {
		String statement = sql == null ? "" : sql.strip();
		while (statement.endsWith(".") || statement.endsWith(";")) {
			statement = statement.substring(0, statement.length() - 1).strip();
		}
		String head = statement.toUpperCase(Locale.ROOT);
		String outsideLiterals = SQL_LITERAL.matcher(statement).replaceAll("''");
		if (!(head.startsWith("SELECT ") || head.startsWith("WITH ")) || outsideLiterals.contains(";")) {
			throw new AdtException(400, "Only a single SELECT statement can be run.");
		}
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/datapreview/freestyle?rowNumber=" + maxRows,
				"application/xml, application/vnd.sap.adt.datapreview.table.v1+xml", statement, "text/plain"), cancel);
		return parseTableData(r.body());
	}

	/**
	 * Result of the data preview.
	 *
	 * @param columns   column names in order
	 * @param rows      cell values per row
	 * @param totalRows rows the statement found in total; may exceed {@code rows.size()}
	 */
	/** ABAP SQL string literals ('…' with '' as escape, and `…`), so a ';' inside one is allowed. */
	private static final Pattern SQL_LITERAL = Pattern.compile("'(?:[^']|'')*'|`(?:[^`]|``)*`");

	public record TableData(List<String> columns, List<List<String>> rows, int totalRows) {
	}

	/** The data preview sends the values column by column; this turns them into rows. */
	static TableData parseTableData(String xml) throws IOException {
		Document doc = AdtXml.parse(xml);
		List<String> columns = new ArrayList<>();
		List<List<String>> values = new ArrayList<>();
		for (Element col : AdtXml.elements(doc, "columns")) {
			List<Element> meta = AdtXml.elements(col, "metadata");
			columns.add(meta.isEmpty() ? "COL" + (columns.size() + 1) : AdtXml.attr(meta.get(0), "name"));
			List<String> cells = new ArrayList<>();
			for (Element d : AdtXml.elements(col, "data")) {
				cells.add(d.getTextContent() == null ? "" : d.getTextContent());
			}
			values.add(cells);
		}
		int rowCount = values.stream().mapToInt(List::size).max().orElse(0);
		List<List<String>> rows = new ArrayList<>();
		for (int i = 0; i < rowCount; i++) {
			List<String> row = new ArrayList<>();
			for (List<String> cells : values) {
				row.add(i < cells.size() ? cells.get(i) : "");
			}
			rows.add(row);
		}
		int total = rowCount;
		List<Element> totals = AdtXml.elements(doc, "totalRows");
		if (!totals.isEmpty()) {
			try {
				total = Math.max(rowCount, Integer.parseInt(AdtXml.text(totals.get(0))));
			} catch (NumberFormatException e) {
				// keep the number of rows read
			}
		}
		return new TableData(columns, rows, total);
	}

	/** Activates objects; returns the activation messages (empty on success). */
	public List<Message> activate(List<AdtObjectRef> objects, CancelToken cancel) throws IOException {
		StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
				.append("<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">");
		for (AdtObjectRef o : objects) {
			body.append("<adtcore:objectReference adtcore:uri=\"").append(AdtXml.escape(o.uri()))
					.append("\" adtcore:name=\"").append(AdtXml.escape(o.name().toUpperCase(Locale.ROOT)))
					.append("\"/>");
		}
		body.append("</adtcore:objectReferences>");
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/activation?method=activate&preauditRequested=true",
				"application/xml", body.toString(), "application/xml"), cancel);
		return parseActivationMessages(r.body());
	}

	static List<Message> parseActivationMessages(String xml) throws IOException {
		List<Message> out = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return out;
		}
		for (Element m : AdtXml.elements(AdtXml.parse(xml), "msg")) {
			List<Element> texts = AdtXml.elements(m, "txt");
			String text = texts.isEmpty() ? AdtXml.attr(m, "shortText") : AdtXml.text(texts.get(0));
			String href = AdtXml.attr(m, "href");
			out.add(new Message(severity(AdtXml.attr(m, "type")), text, href, lineOf(href)));
		}
		return out;
	}

	/**
	 * Creates an object without source (see {@link AdtDdic#create}). Metadata
	 * that SAP ignores on the create POST is written afterwards by the caller
	 * ({@link #writeMetadata}).
	 *
	 * @return the new object
	 */
	AdtObjectRef create(AdtDdic.CreateRequest r, String name, String packageName, String description,
			CancelToken cancel) throws IOException {
		AdtResponse resp = exchange(transport, AdtRequest.post(r.collection() + r.query(), "application/*", r.body(),
				r.contentType()), cancel);
		if (resp.status() == 415 && AdtDdic.DATAELEMENT_TYPE.equals(r.contentType())) {
			// releases before data element v2
			resp = exchange(transport, AdtRequest.post(r.collection() + r.query(), "application/*", r.body(),
					AdtDdic.DATAELEMENT_TYPE_V1), cancel);
		}
		if (!resp.ok()) {
			throw new AdtException(resp.status(), "Could not create " + name.toUpperCase(Locale.ROOT) + ": "
					+ AdtErrors.message(resp));
		}
		String upper = name.trim().toUpperCase(Locale.ROOT);
		return new AdtObjectRef(r.objectUri(), upper, r.type(), packageName == null ? "" : packageName, description);
	}

	/**
	 * Deletes an object: lock, DELETE, all in one stateful session (the lock
	 * ends with the deletion).
	 *
	 * @return the transport request used, empty for local objects
	 */
	public static String delete(AdtTransport.Session session, String objectUri, String transport, CancelToken cancel)
			throws IOException {
		return withLock(session, objectUri, transport, cancel, (handle, tr) -> {
			AdtResponse r = exchange(session, AdtRequest.delete(objectUri + "?lockHandle=" + enc(handle)
					+ (tr.isBlank() ? "" : "&corrNr=" + enc(tr))), cancel);
			if (!r.ok()) {
				throw new AdtException(r.status(), "Could not delete: " + AdtErrors.message(r));
			}
		});
	}

	/** The metadata XML of an object (data element, domain, message class …). */
	public String readMetadata(String objectUri, CancelToken cancel) throws IOException {
		return send(AdtRequest.get(objectUri, "application/*"), cancel).body();
	}

	/**
	 * Replaces the metadata XML of an object: lock, PUT, unlock.
	 *
	 * @return the transport request used, empty for local objects
	 */
	public static String writeMetadata(AdtTransport.Session session, String objectUri, String body, String contentType,
			String transport, CancelToken cancel) throws IOException {
		return withLock(session, objectUri, transport, cancel, (handle, tr) -> {
			String path = objectUri + "?lockHandle=" + enc(handle) + (tr.isBlank() ? "" : "&corrNr=" + enc(tr));
			AdtResponse put = exchange(session, AdtRequest.put(path, body, contentType), cancel);
			if (put.status() == 415 && AdtDdic.DATAELEMENT_TYPE.equals(contentType)) {
				put = exchange(session, AdtRequest.put(path, body, AdtDdic.DATAELEMENT_TYPE_V1), cancel);
			}
			if (!put.ok()) {
				throw new AdtException(put.status(), "Could not write the metadata: " + AdtErrors.message(put));
			}
		});
	}
}

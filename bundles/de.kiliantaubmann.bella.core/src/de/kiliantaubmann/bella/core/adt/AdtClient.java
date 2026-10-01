package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

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

	static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "ref")) {
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
			StringBuilder path = new StringBuilder(AdtObjectRef.sourceUri(objectUri, include)).append("?lockHandle=")
					.append(enc(lock.handle()));
			if (tr != null && !tr.isBlank()) {
				path.append("&corrNr=").append(enc(tr));
			}
			AdtResponse put = exchange(session, AdtRequest.put(path.toString(), source, "text/plain; charset=utf-8"),
					cancel);
			if (!put.ok()) {
				throw new AdtException(put.status(), "Could not write source: " + AdtErrors.message(put));
			}
			return tr == null ? "" : tr;
		} finally {
			exchange(session, AdtRequest.post(objectUri + "?_action=UNLOCK&lockHandle=" + enc(lock.handle()), null, null,
					null), CancelToken.NONE);
		}
	}

	// ---- checks --------------------------------------------------------------

	/** One message of a syntax check, ATC run or activation. */
	public record Message(String severity, String text, String uri, int line) {

		public String format() {
			return severity + (line > 0 ? " line " + line : "") + ": " + text;
		}
	}

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
		StringBuilder body = new StringBuilder()
				.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
				.append("<chkrun:checkObjectList xmlns:chkrun=\"http://www.sap.com/adt/checkrun\" xmlns:adtcore=\"http://www.sap.com/adt/core\">")
				.append("<chkrun:checkObject adtcore:uri=\"").append(AdtXml.escape(objectUri))
				.append("\" chkrun:version=\"inactive\">");
		if (source != null) {
			body.append("<chkrun:artifacts><chkrun:artifact chkrun:contentType=\"text/plain; charset=utf-8\" chkrun:uri=\"")
					.append(AdtXml.escape(AdtObjectRef.sourceUri(objectUri, null))).append("\"><chkrun:content>")
					.append(Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)))
					.append("</chkrun:content></chkrun:artifact></chkrun:artifacts>");
		}
		body.append("</chkrun:checkObject></chkrun:checkObjectList>");
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/checkruns?reporters=abapCheckRun",
				"application/vnd.sap.adt.checkmessages+xml", body.toString(), "application/vnd.sap.adt.checkobjects+xml"),
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

	/** ABAP Unit run; returns a readable summary. */
	public String runUnitTests(String objectUri, CancelToken cancel) throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<aunit:runConfiguration xmlns:aunit=\"http://www.sap.com/adt/aunit\">"
				+ "<external><coverage active=\"false\"/></external>"
				+ "<options><uriType value=\"semantic\"/>"
				+ "<testDeterminationStrategy sameProgram=\"true\" assignedTests=\"false\"/>"
				+ "<testRiskLevels harmless=\"true\" dangerous=\"true\" critical=\"true\"/>"
				+ "<testDurations short=\"true\" medium=\"true\" long=\"true\"/>"
				+ "<withNavigationUri enabled=\"false\"/></options>"
				+ "<adtcore:objectSets xmlns:adtcore=\"http://www.sap.com/adt/core\"><objectSet kind=\"inclusive\">"
				+ "<adtcore:objectReferences><adtcore:objectReference adtcore:uri=\"" + AdtXml.escape(objectUri)
				+ "\"/></adtcore:objectReferences></objectSet></adtcore:objectSets></aunit:runConfiguration>";
		AdtResponse r = send(AdtRequest.post("/sap/bc/adt/abapunit/testruns", "application/xml", body,
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

	/** ATC check with the given variant (or the system default). */
	public List<Message> atcCheck(String objectUri, String variant, CancelToken cancel) throws IOException {
		String v = variant;
		if (v == null || v.isBlank()) {
			v = atcDefaultVariant(cancel);
		}
		String worklist = send(AdtRequest.post("/sap/bc/adt/atc/worklists?checkVariant=" + enc(v), "text/plain", null,
				null), cancel).body().trim();
		String run = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><atc:run maximumVerdicts=\"100\" xmlns:atc=\"http://www.sap.com/adt/atc\">"
				+ "<objectSets xmlns:adtcore=\"http://www.sap.com/adt/core\"><objectSet kind=\"inclusive\"><adtcore:objectReferences>"
				+ "<adtcore:objectReference adtcore:uri=\"" + AdtXml.escape(objectUri)
				+ "\"/></adtcore:objectReferences></objectSet></objectSets></atc:run>";
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
		return out;
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
	 * Creates an empty class, interface or program.
	 *
	 * @return the new object
	 */
	public AdtObjectRef create(String type, String name, String description, String packageName, String transport,
			String responsible, CancelToken cancel) throws IOException {
		String t = type.toUpperCase(Locale.ROOT);
		String upperName = name.toUpperCase(Locale.ROOT);
		String common = " adtcore:description=\"" + AdtXml.escape(description) + "\" adtcore:name=\""
				+ AdtXml.escape(upperName) + "\""
				+ (responsible == null ? "" : " adtcore:responsible=\"" + AdtXml.escape(responsible) + "\"");
		String pkg = "<adtcore:packageRef adtcore:name=\"" + AdtXml.escape(packageName.toUpperCase(Locale.ROOT))
				+ "\"/>";
		String path;
		String xml;
		switch (t.contains("/") ? t.substring(0, t.indexOf('/')) : t) {
		case "CLAS" -> {
			path = "/sap/bc/adt/oo/classes";
			xml = "<class:abapClass xmlns:class=\"http://www.sap.com/adt/oo/classes\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
					+ common + " adtcore:type=\"CLAS/OC\" class:final=\"true\" class:visibility=\"public\">" + pkg
					+ "<class:include adtcore:name=\"CLAS/OC\" adtcore:type=\"CLAS/OC\" class:includeType=\"testclasses\"/>"
					+ "<class:superClassRef/></class:abapClass>";
		}
		case "INTF" -> {
			path = "/sap/bc/adt/oo/interfaces";
			xml = "<intf:abapInterface xmlns:intf=\"http://www.sap.com/adt/oo/interfaces\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
					+ common + " adtcore:type=\"INTF/OI\">" + pkg + "</intf:abapInterface>";
		}
		case "PROG" -> {
			path = "/sap/bc/adt/programs/programs";
			xml = "<program:abapProgram xmlns:program=\"http://www.sap.com/adt/programs/programs\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
					+ common + " adtcore:type=\"PROG/P\">" + pkg + "</program:abapProgram>";
		}
		default -> throw new AdtException(400, "Creating objects of type " + type
				+ " is not supported; supported are CLAS, INTF and PROG.");
		}
		String query = transport == null || transport.isBlank() ? "" : "?corrNr=" + enc(transport);
		send(AdtRequest.post(path + query, "application/*", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml,
				"application/*"), cancel);
		return new AdtObjectRef(AdtObjectRef.uriFor(upperName, t), upperName, t, packageName, description);
	}
}

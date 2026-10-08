package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Runtime diagnostics through ADT: system messages, gateway errors, ABAP
 * profiler traces, the SQL trace (ST05), the authorization trace and ATC
 * variants. Endpoints follow ARC-1 ({@code src/adt/diagnostics.ts},
 * {@code src/adt/authorization-trace.ts}, {@code src/adt/atc.ts}, MIT).
 */
final class AdtDiagnostics {

	static final String TRACES = "/sap/bc/adt/runtime/traces/abaptraces";
	static final String ST05_STATE = "/sap/bc/adt/st05/trace/state";
	static final String ST05_STATE_TYPE = "application/vnd.sap.adt.perf.trace.state.v1+xml";
	static final String FEED = "application/atom+xml;type=feed";
	/** Characters of a diagnostic result handed to the model at most. */
	static final int MAX_CHARS = 20_000;

	private AdtDiagnostics() {
	}

	static String get(AdtClient c, String path, String accept, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.get(path, accept), cancel);
		if (r.status() == 404) {
			throw new AdtException(404, "Not available on this system (" + path.replaceFirst("\\?.*", "") + ").");
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		return r.body() == null ? "" : r.body();
	}

	// ---- generic readable text from feeds, XML and JSON --------------------------

	/** One line per Atom entry: title, id, time, author and summary. */
	static String atom(String xml, int max) throws IOException {
		if (xml.isBlank()) {
			return "No entries.";
		}
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "entry")) {
			if (n++ == max) {
				sb.append("… more entries not shown\n");
				break;
			}
			sb.append("- ").append(child(e, "title"));
			String updated = child(e, "updated");
			if (!updated.isEmpty()) {
				sb.append(" | ").append(updated);
			}
			List<Element> names = AdtXml.elements(e, "name");
			if (!names.isEmpty()) {
				sb.append(" | ").append(names.get(0).getTextContent().trim());
			}
			String summary = child(e, "summary");
			if (!summary.isEmpty()) {
				sb.append(" | ").append(summary.replaceAll("\\s+", " "));
			}
			String id = child(e, "id");
			if (!id.isEmpty()) {
				sb.append(" | id ").append(id);
			}
			sb.append('\n');
		}
		return n == 0 ? "No entries." : sb.toString();
	}

	/** Elements with attributes or text, one per line, as {@code name: attr=value …}; for analysis results. */
	static String compact(String xml, int maxLines) throws IOException {
		if (xml.isBlank()) {
			return "Empty.";
		}
		StringBuilder sb = new StringBuilder();
		int[] lines = { 0 };
		walk(AdtXml.parse(xml).getDocumentElement(), sb, lines, maxLines);
		return sb.isEmpty() ? "Empty." : sb.toString();
	}

	private static void walk(Element e, StringBuilder sb, int[] lines, int max) {
		if (lines[0] >= max) {
			return;
		}
		NamedNodeMap attrs = e.getAttributes();
		boolean hasChildElements = false;
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element) {
				hasChildElements = true;
				break;
			}
		}
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < attrs.getLength(); i++) {
			Node a = attrs.item(i);
			String name = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
			if (a.getNodeName().startsWith("xmlns") || a.getNodeValue().isBlank()) {
				continue;
			}
			line.append(' ').append(name).append('=').append(a.getNodeValue());
		}
		String text = hasChildElements ? "" : e.getTextContent().trim();
		if (!line.isEmpty() || !text.isEmpty()) {
			sb.append(local(e)).append(':').append(line).append(text.isEmpty() ? "" : " " + text).append('\n');
			lines[0]++;
			if (lines[0] == max) {
				sb.append("… cut after ").append(max).append(" lines\n");
				return;
			}
		}
		for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element c) {
				walk(c, sb, lines, max);
			}
		}
	}

	/** JSON pretty-printed and cut, for OData and gCTS answers. */
	static String json(String body) {
		String text;
		try {
			JsonElement e = JsonParser.parseString(body);
			text = Json.PRETTY.toJson(e);
		} catch (JsonSyntaxException e) {
			text = body;
		}
		return cut(text);
	}

	static String cut(String text) {
		return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n… (cut after " + MAX_CHARS + " characters)"
				: text;
	}

	static String local(Element e) {
		return e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
	}

	static String child(Element parent, String localName) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && localName.equals(local(e))) {
				return e.getTextContent().trim();
			}
		}
		return "";
	}

	// ---- profiler traces -------------------------------------------------------

	static final List<String> PROCESS_TYPES = List.of("any", "http", "dialog", "batch", "rfc");
	static final List<String> OBJECT_TYPES = List.of("any", "url", "transaction", "report", "functionmodule");

	/**
	 * Arms a profiler trace: capture parameters first (their id comes back in
	 * the Location header), then the request saying whose next execution of
	 * what is traced.
	 */
	static String startTrace(AdtClient c, String user, String client, String processType, String objectType,
			boolean sqlTrace, int maxExecutions, int expiresHours, String description, CancelToken cancel)
			throws IOException {
		String process = processType == null || processType.isBlank() ? "http" : processType.toLowerCase(Locale.ROOT);
		String object = objectType == null || objectType.isBlank() ? defaultObject(process)
				: objectType.toLowerCase(Locale.ROOT).replace("function_module", "functionmodule");
		if (!PROCESS_TYPES.contains(process) || !OBJECT_TYPES.contains(object)) {
			throw new AdtException(400, "process_type is one of " + PROCESS_TYPES + ", object_type one of "
					+ OBJECT_TYPES + ".");
		}
		String params = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<trc:parameters xmlns:trc=\"http://www.sap.com/adt/runtime/traces/abaptraces\">\n"
				+ "  <trc:allMiscAbapStatements value=\"false\"/>\n  <trc:allProceduralUnits value=\"true\"/>\n"
				+ "  <trc:allInternalTableEvents value=\"false\"/>\n  <trc:allDynproEvents value=\"false\"/>\n"
				+ "  <trc:description value=\"" + AdtXml.escape(description) + "\"/>\n  <trc:aggregate value=\"true\"/>\n"
				+ "  <trc:explicitOnOff value=\"false\"/>\n  <trc:withRfcTracing value=\"false\"/>\n"
				+ "  <trc:allSystemKernelEvents value=\"false\"/>\n  <trc:sqlTrace value=\"" + sqlTrace + "\"/>\n"
				+ "  <trc:allDbEvents value=\"" + sqlTrace + "\"/>\n  <trc:maxSizeForTraceFile value=\"100\"/>\n"
				+ "  <trc:maxTimeForTracing value=\"600\"/>\n</trc:parameters>";
		AdtResponse p = c.exchange(AdtRequest.post(TRACES + "/parameters", "application/xml", params, "application/xml"),
				cancel);
		if (!p.ok()) {
			throw new AdtException(p.status(), "Could not store the trace parameters: " + AdtErrors.message(p));
		}
		String parametersId = p.header("Location");
		if (parametersId == null || parametersId.isBlank()) {
			throw new AdtException(500, "SAP returned no id for the trace parameters.");
		}
		String expires = Instant.now().plus(Math.max(1, expiresHours), ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
				.toString();
		String query = "server=*&description=" + AdtClient.enc(description) + "&traceUser="
				+ AdtClient.enc(user.toUpperCase(Locale.ROOT)) + "&traceClient=" + AdtClient.enc(client == null ? "" : client)
				+ "&processType=" + AdtClient.enc(TRACES + "/processtypes/" + process) + "&objectType="
				+ AdtClient.enc(TRACES + "/objecttypes/" + object) + "&expires=" + AdtClient.enc(expires)
				+ "&maximalExecutions=" + Math.max(1, maxExecutions) + "&parametersId=" + AdtClient.enc(parametersId);
		AdtResponse r = c.exchange(AdtRequest.post(TRACES + "/requests?" + query, "application/xml", "", "application/xml"),
				cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not arm the trace: " + AdtErrors.message(r));
		}
		return atom(r.body(), 5);
	}

	private static String defaultObject(String process) {
		return switch (process) {
		case "http" -> "url";
		case "dialog" -> "transaction";
		case "batch" -> "report";
		case "rfc" -> "functionmodule";
		default -> "any";
		};
	}

	/** Path of a trace request to cancel; only ids below the requests collection are accepted. */
	static String traceRequestPath(String id) throws AdtException {
		String prefix = TRACES + "/requests/";
		String raw = id == null ? "" : id.trim();
		String path = raw.startsWith("/") ? raw : prefix + raw;
		String tail = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
		String decoded = java.net.URLDecoder.decode(tail, java.nio.charset.StandardCharsets.UTF_8);
		if (tail.isEmpty() || decoded.contains("/") || decoded.contains("..") || decoded.matches(".*[\\s?#\\\\].*")) {
			throw new AdtException(400, "Not a trace request id: " + id);
		}
		return path;
	}

	// ---- SQL trace (ST05) ------------------------------------------------------

	/** The ST05 state with the SQL trace switched on or off for all instances, optionally for one user. */
	static String withSqlTrace(String stateXml, boolean on, String user) {
		String body = stateXml.replaceAll("<ts:sqlOn>[^<]*</ts:sqlOn>", "<ts:sqlOn>" + on + "</ts:sqlOn>");
		if (user != null) {
			String u = user.trim();
			body = body.replaceAll("<ts:traceUser/>|<ts:traceUser>[^<]*</ts:traceUser>",
					u.isEmpty() ? "<ts:traceUser/>" : "<ts:traceUser>" + AdtXml.escape(u.toUpperCase(Locale.ROOT))
							+ "</ts:traceUser>");
		}
		return body;
	}

	// ---- authorization trace ---------------------------------------------------

	/** The SQL reading the long-term authorization trace (STUSERTRACE, table SUAUTHVALTRC). */
	static String authorizationTraceSql(String user, String authObject, boolean onlyFailures) throws AdtException {
		List<String> where = new ArrayList<>();
		if (user != null && !user.isBlank()) {
			where.add("username = '" + name(user) + "'");
		}
		if (authObject != null && !authObject.isBlank()) {
			where.add("object = '" + name(authObject) + "'");
		}
		if (onlyFailures) {
			where.add("rc <> 0");
		}
		return "SELECT username, object, rc, field1, field2, field3, field4, field5, abapprog, abapline, firstcall "
				+ "FROM suauthvaltrc" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
				+ " ORDER BY firstcall DESCENDING";
	}

	private static String name(String s) throws AdtException {
		String n = s.trim().toUpperCase(Locale.ROOT);
		if (!n.matches("[A-Z0-9_/$*.-]{1,40}")) {
			throw new AdtException(400, "Invalid name " + s + ".");
		}
		return n;
	}

	/** Names of the items of a named item list (ATC variants, transport layers, targets). */
	static List<String[]> namedItems(String xml) throws IOException {
		List<String[]> out = new ArrayList<>();
		if (xml.isBlank()) {
			return out;
		}
		Set<String> seen = new LinkedHashSet<>();
		for (Element e : AdtXml.elements(AdtXml.parse(xml), "namedItem")) {
			String name = child(e, "name");
			if (seen.add(name)) {
				out.add(new String[] { name, child(e, "description").replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ")
						.trim(), child(e, "data").replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim() });
			}
		}
		return out;
	}
}

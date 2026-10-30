package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Read-only objects outside the repository's source and DDIC model:
 * authorization fields (AUTH), feature toggles and BOR object types (SOBJ,
 * through table SWOTLV, as ADT has no service for them). Routes and shapes
 * follow ARC-1 ({@code src/adt/client.ts}, {@code src/adt/xml-parser.ts},
 * {@code src/handlers/read.ts}, MIT).
 */
final class AdtSpecialObjects {

	static final String AUTH = "/sap/bc/adt/aps/iam/auth/";
	static final String AUTH_TYPE = "application/vnd.sap.adt.blues.v1+xml";
	static final String TOGGLES = "/sap/bc/adt/sfw/featuretoggles/";
	static final String TOGGLE_TYPE = "application/vnd.sap.adt.states.v1+asjson";

	private AdtSpecialObjects() {
	}

	static String authUri(String name) {
		return AUTH + URLEncoder.encode(name.trim().toUpperCase(Locale.ROOT), StandardCharsets.UTF_8);
	}

	static String toggleUri(String name) {
		return TOGGLES + URLEncoder.encode(name.trim().toUpperCase(Locale.ROOT), StandardCharsets.UTF_8);
	}

	/** An authorization field: its data element, domain, check table and organizational level. */
	static String describeAuth(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		StringBuilder sb = new StringBuilder("Authorization field ").append(AdtXml.attr(root, "name"));
		if (!AdtXml.attr(root, "description").isEmpty()) {
			sb.append(" (").append(AdtXml.attr(root, "description")).append(')');
		}
		if (!pkg.isEmpty()) {
			sb.append(", package ").append(pkg);
		}
		sb.append('.');
		line(sb, d, "rollName", "Data element");
		line(sb, d, "domname", "Domain");
		line(sb, d, "checkTable", "Check table");
		String length = first(d, "outputlen").replaceFirst("^0+(?=\\d)", "");
		if (!length.isEmpty()) {
			sb.append("\nOutput length: ").append(length);
		}
		line(sb, d, "convexit", "Conversion routine");
		line(sb, d, "exitFB", "Exit function module");
		line(sb, d, "orglvlinfo", "Organizational level");
		return sb.toString();
	}

	private static void line(StringBuilder sb, Document d, String element, String label) {
		String v = first(d, element);
		if (!v.isEmpty()) {
			sb.append('\n').append(label).append(": ").append(v);
		}
	}

	private static String first(Document d, String localName) {
		List<Element> found = AdtXml.elements(d, localName);
		return found.isEmpty() ? "" : AdtXml.text(found.get(0));
	}

	/** A feature toggle's state overall, per client and per user. */
	static String describeToggle(String json, String name) throws AdtException {
		JsonObject o;
		try {
			JsonElement e = Json.GSON.fromJson(json, JsonElement.class);
			o = e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (JsonParseException e) {
			o = null;
		}
		if (o == null) {
			throw new AdtException(500, "SAP's answer about feature toggle " + name + " is not the expected JSON.");
		}
		JsonObject states = o.has("STATES") && o.get("STATES").isJsonObject() ? o.getAsJsonObject("STATES") : o;
		StringBuilder sb = new StringBuilder("Feature toggle ").append(str(states, "NAME", name.toUpperCase(Locale.ROOT)))
				.append(": ").append(str(states, "CLIENT_STATE", "unknown")).append(" in this client");
		String user = str(states, "USER_STATE", "");
		if (!user.isEmpty() && !user.equals("undefined")) {
			sb.append(", ").append(user).append(" for the logged-on user");
		}
		sb.append('.');
		JsonArray clients = Json.arr(states, "CLIENT_STATES");
		if (clients != null && !clients.isEmpty()) {
			sb.append("\nPer client:");
			for (JsonElement e : clients) {
				if (e.isJsonObject()) {
					JsonObject c = e.getAsJsonObject();
					String descr = str(c, "DESCRIPTION", "");
					sb.append("\n- ").append(str(c, "CLIENT", "?")).append(": ").append(str(c, "STATE", "?"))
							.append(descr.isEmpty() ? "" : " (" + descr + ")");
				}
			}
		}
		JsonArray users = Json.arr(states, "USER_STATES");
		if (users != null && !users.isEmpty()) {
			sb.append("\nPer user:");
			for (JsonElement e : users) {
				if (e.isJsonObject()) {
					JsonObject u = e.getAsJsonObject();
					sb.append("\n- ").append(str(u, "USER", "?")).append(" in client ").append(str(u, "CLIENT", "?"))
							.append(": ").append(str(u, "STATE", "?"));
				}
			}
		}
		return sb.toString();
	}

	private static String str(JsonObject o, String key, String fallback) {
		String v = Json.str(o, key);
		return v == null || v.isBlank() ? fallback : v.trim();
	}

	/**
	 * The methods of a BOR object type from SWOTLV, or with {@code method} the
	 * source of the program implementing it. Names are checked to be plain
	 * BOR names before they go into the SQL.
	 */
	static String bor(AdtClient c, String name, String method, CancelToken cancel) throws IOException {
		String type = name.trim().toUpperCase(Locale.ROOT);
		if (!type.matches("[A-Z0-9_/]{1,32}")) {
			throw new AdtException(400, "'" + name + "' is no BOR object type name (letters, digits, _ and /).");
		}
		if (method == null || method.isBlank()) {
			AdtClient.TableData t = c.tableContents("SELECT verb, progname, formname, descript FROM swotlv WHERE "
					+ "lobjtype = '" + type + "'", 200, cancel);
			if (t.rows().isEmpty()) {
				throw new AdtException(404, "BOR object type " + type + " has no methods in SWOTLV, or does not exist.");
			}
			StringBuilder sb = new StringBuilder("Methods of BOR object type " + type
					+ " (read one with 'method'):");
			for (List<String> row : t.rows()) {
				sb.append("\n- ").append(row.get(0).trim());
				if (!row.get(3).isBlank()) {
					sb.append(": ").append(row.get(3).trim());
				}
				if (!row.get(1).isBlank()) {
					sb.append(" (program ").append(row.get(1).trim())
							.append(row.get(2).isBlank() ? "" : ", form " + row.get(2).trim()).append(')');
				}
			}
			return sb.toString();
		}
		String verb = method.trim().toUpperCase(Locale.ROOT);
		if (!verb.matches("[A-Z0-9_]{1,32}")) {
			throw new AdtException(400, "'" + method + "' is no BOR method name.");
		}
		AdtClient.TableData t = c.tableContents("SELECT progname, formname FROM swotlv WHERE lobjtype = '" + type
				+ "' AND verb = '" + verb + "'", 1, cancel);
		if (t.rows().isEmpty()) {
			throw new AdtException(404, "BOR object type " + type + " has no method " + verb
					+ "; read the type without 'method' for its methods.");
		}
		String program = t.rows().get(0).get(0).trim();
		if (program.isEmpty()) {
			throw new AdtException(404, "BOR method " + type + "." + verb + " has no program.");
		}
		String form = t.rows().get(0).get(1).trim();
		return "BOR method " + type + "." + verb + ": program " + program + (form.isEmpty() ? "" : ", form " + form)
				+ "\n\n" + c.readSource(AdtObjectRef.uriFor(program, "PROG"), null, cancel);
	}
}

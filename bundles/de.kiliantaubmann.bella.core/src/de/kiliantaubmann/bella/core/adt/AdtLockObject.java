package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * DDIC lock objects (ENQU): one metadata document without source, created
 * with its tables in the POST and changed with lock, PUT, unlock. Shape and
 * rules follow ARC-1 ({@code src/adt/lock-object.ts}, MIT), verified there on
 * 7.58, 8.16 and BTP; the collection is missing on 7.50.
 */
final class AdtLockObject {

	static final String TYPE = "application/vnd.sap.adt.lockobjects.v1+xml";
	static final String COLLECTION = "/sap/bc/adt/ddic/lockobjects/sources";

	/** Lock modes of a definition; the optimistic O is a runtime mode that activation refuses. */
	static final List<String> MODES = List.of("E", "S", "X", "");

	private static final Pattern NAME = Pattern.compile("(?:/[A-Z0-9_]+/)?[A-Z0-9_]+");

	private AdtLockObject() {
	}

	record Table(String name, String mode) {
	}

	/** {@code wanted=false}: the field is a parameter of the lock object but not of ENQUEUE_. */
	record Parameter(String name, String table, String field, boolean wanted) {
	}

	record Definition(boolean allowRfc, List<Table> tables, List<Parameter> parameters) {
	}

	record Stored(String name, String description, String pkg, String language, Definition definition,
			List<String> modules) {
	}

	static Stored parse(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		List<Table> tables = new ArrayList<>();
		for (Element t : AdtXml.elements(d, "primaryTable")) {
			tables.add(table(t));
		}
		for (Element t : AdtXml.elements(d, "secondaryTable")) {
			tables.add(table(t));
		}
		List<Parameter> params = new ArrayList<>();
		for (Element p : AdtXml.elements(d, "lockParameter")) {
			params.add(new Parameter(child(p, "parameterName"), child(p, "tableName"), child(p, "fieldName"),
					!"false".equals(child(p, "parameterWanted"))));
		}
		List<String> modules = new ArrayList<>();
		for (Element m : AdtXml.elements(d, "lockModule")) {
			modules.add(AdtXml.attr(m, "name"));
		}
		boolean rfc = false;
		for (Element e : AdtXml.elements(d, "allowRFC")) {
			rfc = "true".equals(AdtXml.text(e));
		}
		String language = AdtXml.attr(root, "masterLanguage");
		return new Stored(AdtXml.attr(root, "name"), AdtXml.attr(root, "description"), pkg,
				language.isEmpty() ? AdtXml.attr(root, "language") : language, new Definition(rfc, tables, params),
				modules);
	}

	private static Table table(Element t) {
		return new Table(child(t, "tableName"), child(t, "lockMode"));
	}

	private static String child(Element e, String localName) {
		NodeList nodes = e.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node n = nodes.item(i);
			if (n instanceof Element c && localName.equals(c.getLocalName())) {
				return AdtXml.text(c);
			}
		}
		return "";
	}

	/**
	 * The document for create and update, without XML declaration; the
	 * generated lock modules are left out, SAP keeps them.
	 */
	static String xml(String name, String description, String pkg, String language, String responsible,
			Definition def) {
		StringBuilder sb = new StringBuilder("<enqu:lockobject"
				+ " xmlns:enqu=\"http://www.sap.com/adt/ddic/enqu\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " adtcore:type=\"ENQU/DL\" adtcore:name=\"" + x(name) + "\" adtcore:description=\"" + x(description)
				+ "\" adtcore:masterLanguage=\"" + x(language) + "\"");
		if (responsible != null && !responsible.isBlank() && responsible.length() <= 12) {
			sb.append(" adtcore:responsible=\"").append(x(responsible.trim().toUpperCase(Locale.ROOT))).append('"');
		}
		sb.append(">\n  <adtcore:packageRef adtcore:name=\"").append(x(pkg)).append("\"/>\n  <enqu:content>")
				.append("<enqu:allowRFC>").append(def.allowRfc()).append("</enqu:allowRFC>");
		appendTable(sb, "primaryTable", def.tables().get(0));
		sb.append("<enqu:secondaryTables>");
		for (Table t : def.tables().subList(1, def.tables().size())) {
			appendTable(sb, "secondaryTable", t);
		}
		sb.append("</enqu:secondaryTables><enqu:lockParameters>");
		for (Parameter p : def.parameters()) {
			sb.append("<enqu:lockParameter><enqu:parameterWanted>").append(p.wanted())
					.append("</enqu:parameterWanted><enqu:parameterName>").append(x(p.name()))
					.append("</enqu:parameterName><enqu:tableName>").append(x(p.table()))
					.append("</enqu:tableName><enqu:fieldName>").append(x(p.field()))
					.append("</enqu:fieldName></enqu:lockParameter>");
		}
		return sb.append("</enqu:lockParameters></enqu:content>\n</enqu:lockobject>").toString();
	}

	private static void appendTable(StringBuilder sb, String tag, Table t) {
		sb.append("<enqu:").append(tag).append("><enqu:tableName>").append(x(t.name()))
				.append("</enqu:tableName><enqu:lockMode>").append(x(t.mode())).append("</enqu:lockMode></enqu:")
				.append(tag).append('>');
	}

	/** {@code tables} of a call (the first is the primary table), {@code null} if not given. */
	static List<Table> tables(JsonObject in) throws AdtException {
		JsonArray arr = Json.arr(in, "tables");
		if (arr == null) {
			return null;
		}
		List<Table> out = new ArrayList<>();
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				throw new AdtException(400, "Every entry of 'tables' is an object {table, lock_mode}.");
			}
			JsonObject o = e.getAsJsonObject();
			String mode = Json.str(o, "lock_mode");
			// required: a default would silently replace S, X or no lock
			if (mode == null || !MODES.contains(mode.trim().toUpperCase(Locale.ROOT))) {
				throw new AdtException(400, "Table " + Json.str(o, "table") + ": lock_mode is E (exclusive), "
						+ "S (shared), X (exclusive, not cumulative) or \"\" (no lock, only the foreign key link); "
						+ "got " + mode + ". O (optimistic) is a mode of the ENQUEUE_ call, not of the definition.");
			}
			out.add(new Table(name(Json.str(o, "table"), "table"), mode.trim().toUpperCase(Locale.ROOT)));
		}
		if (out.isEmpty()) {
			throw new AdtException(400, "'tables' needs at least the primary table.");
		}
		return out;
	}

	/** {@code lock_parameters} of a call, {@code null} if not given. */
	static List<Parameter> parameters(JsonObject in) throws AdtException {
		JsonArray arr = Json.arr(in, "lock_parameters");
		if (arr == null) {
			return null;
		}
		List<Parameter> out = new ArrayList<>();
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				throw new AdtException(400, "Every entry of 'lock_parameters' is an object {name, table, field, wanted}.");
			}
			JsonObject o = e.getAsJsonObject();
			if (!o.has("wanted") || !o.get("wanted").isJsonPrimitive()) {
				throw new AdtException(400, "Lock parameter " + Json.str(o, "field") + " needs 'wanted' (true or false).");
			}
			String field = name(Json.str(o, "field"), "field");
			String n = Json.str(o, "name");
			out.add(new Parameter(n == null || n.isBlank() ? field : name(n, "name"), name(Json.str(o, "table"), "table"),
					field, o.get("wanted").getAsBoolean()));
		}
		return out;
	}

	private static String name(String value, String key) throws AdtException {
		String n = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (!NAME.matcher(n).matches()) {
			throw new AdtException(400, "'" + key + "' must be a DDIC name, got '" + value + "'.");
		}
		return n;
	}

	private static Boolean bool(JsonObject in, String key) {
		return in.has(key) && in.get(key).isJsonPrimitive() ? in.get(key).getAsBoolean() : null;
	}

	/** The definition of a new lock object; SAP derives the parameters from the keys when none are given. */
	static Definition created(JsonObject in) throws AdtException {
		List<Table> tables = tables(in);
		if (tables == null) {
			throw new AdtException(400, "Give 'tables', e.g. [{\"table\":\"ZORDERS\",\"lock_mode\":\"E\"}]; the first "
					+ "is the primary table.");
		}
		List<Parameter> params = parameters(in);
		Boolean rfc = bool(in, "allow_rfc");
		return new Definition(rfc != null && rfc, tables, params == null ? List.of() : params);
	}

	/**
	 * The definition after a change: fields not given keep their value. A
	 * change of the tables needs the parameters, and an empty list is
	 * refused, because SAP then derives every parameter with wanted=false and
	 * ENQUEUE_ would lock generically (ARC-1, 8.16).
	 */
	static Definition merged(Definition stored, JsonObject in) throws AdtException {
		List<Table> tables = tables(in);
		List<Parameter> params = parameters(in);
		Boolean rfc = bool(in, "allow_rfc");
		if (params != null && params.isEmpty()) {
			throw new AdtException(400, "'lock_parameters': [] is refused: SAP would derive every parameter with "
					+ "wanted=false, so ENQUEUE_ would lock the whole table. List each parameter; wanted=false leaves "
					+ "one out.");
		}
		List<Table> newTables = tables == null ? stored.tables() : tables;
		boolean changed = !newTables.stream().map(Table::name).toList()
				.equals(stored.tables().stream().map(Table::name).toList());
		if (changed && params == null) {
			throw new AdtException(400, "Changing the tables needs 'lock_parameters' for them (one {name, table, field, "
					+ "wanted} per key field); without them SAP derives the parameters with wanted=false.");
		}
		return new Definition(rfc == null ? stored.allowRfc() : rfc, newTables,
				params == null ? stored.parameters() : params);
	}

	/** As {@code adt_read_source} shows it: the keys {@code adt_write_metadata} takes. */
	static String describe(Stored s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.name());
		o.addProperty("type", "ENQU");
		o.addProperty("description", s.description());
		o.addProperty("package", s.pkg());
		o.addProperty("allow_rfc", s.definition().allowRfc());
		JsonArray tables = new JsonArray();
		for (Table t : s.definition().tables()) {
			JsonObject j = new JsonObject();
			j.addProperty("table", t.name());
			j.addProperty("lock_mode", t.mode());
			tables.add(j);
		}
		o.add("tables", tables);
		JsonArray params = new JsonArray();
		for (Parameter p : s.definition().parameters()) {
			JsonObject j = new JsonObject();
			j.addProperty("name", p.name());
			j.addProperty("table", p.table());
			j.addProperty("field", p.field());
			j.addProperty("wanted", p.wanted());
			params.add(j);
		}
		o.add("lock_parameters", params);
		JsonArray modules = new JsonArray();
		s.modules().forEach(modules::add);
		o.add("lock_modules", modules);
		return "Lock object " + s.name() + "; the first table is the primary one. Change it with adt_write_metadata "
				+ "(same keys; lock_modules are generated at activation).\n" + Json.PRETTY.toJson(o);
	}

	private static String x(String s) {
		return AdtXml.escape(s == null ? "" : s);
	}
}

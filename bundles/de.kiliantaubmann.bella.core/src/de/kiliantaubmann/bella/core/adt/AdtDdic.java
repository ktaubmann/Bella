package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * Creation requests and metadata of the object types beyond classes,
 * interfaces and programs: function groups and modules, includes, DDIC
 * objects, message classes, CDS and RAP sources. The XML envelopes, collection
 * paths and media types follow ARC-1 ({@code src/handlers/write-helpers.ts},
 * {@code src/adt/ddic-xml.ts}, MIT), where they are verified against 7.50,
 * 7.58 and 8.16 systems.
 */
final class AdtDdic {

	static final String DOMAIN_TYPE = "application/vnd.sap.adt.domains.v2+xml; charset=utf-8";
	static final String DATAELEMENT_TYPE = "application/vnd.sap.adt.dataelements.v2+xml; charset=utf-8";
	static final String DATAELEMENT_TYPE_V1 = "application/vnd.sap.adt.dataelements.v1+xml; charset=utf-8";
	static final String MESSAGECLASS_TYPE = "application/vnd.sap.adt.mc.messageclass+xml";
	static final String TABLETYPE_TYPE = "application/vnd.sap.adt.tabletype.v1+xml";
	static final String BDEF_TYPE = "application/vnd.sap.adt.blues.v1+xml";
	static final String FUNCTION_GROUP_TYPE = "application/vnd.sap.adt.functions.groups.v3+xml";
	static final String FUNCTION_MODULE_TYPE = "application/vnd.sap.adt.functions.fmodules+xml";
	static final String FUNCTION_INCLUDE_TYPE = "application/vnd.sap.adt.functions.fincludes.v2+xml";

	/** Types Bella can create. */
	static final List<String> CREATABLE = List.of("CLAS", "INTF", "PROG", "INCL", "FUGR", "FUNC", "MSAG", "DTEL",
			"DOMA", "TTYP", "TABL/DT", "TABL/DS", "DDLS", "DCLS", "DDLX", "BDEF", "SRVD", "SRVB");

	/** Types without source code: everything is in the metadata XML. */
	static final Set<String> METADATA_ONLY = Set.of("MSAG", "DTEL", "DOMA", "TTYP", "SRVB");

	/** Types whose source Bella's style check understands. */
	static final Set<String> ABAP_SOURCE = Set.of("CLAS", "INTF", "PROG", "INCL", "FUGR", "FUNC");

	/** DTEL field label lengths. */
	private static final int SHORT = 10;
	private static final int MEDIUM = 20;
	private static final int LONG = 40;
	private static final int HEADING = 55;

	private AdtDdic() {
	}

	/** What a create sends: the collection to POST to, the body, its media type and extra query parameters. */
	record CreateRequest(String collection, String body, String contentType, String query, String objectUri,
			String type) {
	}

	/**
	 * Normalises the type the model gives: {@code TABL} is a transparent
	 * table, {@code STRU} a structure, {@code FUNCTION} a function module.
	 */
	static String normalizeType(String type) {
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		return switch (t) {
		case "TABL", "TABLE", "TABL/DT" -> "TABL/DT";
		case "STRU", "STRUCTURE", "TABL/DS" -> "TABL/DS";
		case "FUNC", "FUNCTION", "FUGR/FF", "FM" -> "FUNC";
		case "FUGR", "FUGR/F", "FUNCTION_GROUP" -> "FUGR";
		case "CLASS", "CLAS/OC" -> "CLAS";
		case "INTERFACE", "INTF/OI" -> "INTF";
		case "PROGRAM", "REPORT", "PROG/P" -> "PROG";
		case "PROG/I", "INCLUDE" -> "INCL";
		case "DTEL/DE", "DATA_ELEMENT" -> "DTEL";
		case "DOMA/DD", "DOMAIN" -> "DOMA";
		case "TTYP/DA", "TABLE_TYPE" -> "TTYP";
		case "MSAG/N", "MESSAGE_CLASS" -> "MSAG";
		default -> t.contains("/") ? t.substring(0, t.indexOf('/')) : t;
		};
	}

	/** URI of an object of a creatable type; function modules and group includes need {@code group}. */
	static String objectUri(String type, String name, String group) throws AdtException {
		String t = normalizeType(type);
		if (t.equals("FUNC") || (t.equals("INCL") && group != null && !group.isBlank())) {
			if (group == null || group.isBlank()) {
				throw new AdtException(400, "Give 'group', the function group of the function module.");
			}
			return groupUri(group) + (t.equals("FUNC") ? "/fmodules/" : "/includes/") + AdtObjectRef.encodeName(name);
		}
		if (t.equals("DDLX")) {
			return "/sap/bc/adt/ddic/ddlx/sources/" + AdtObjectRef.encodeName(name);
		}
		if (t.equals("SRVB")) {
			return "/sap/bc/adt/businessservices/bindings/" + AdtObjectRef.encodeName(name);
		}
		String uri = AdtObjectRef.uriFor(name, t);
		if (uri == null) {
			throw new AdtException(400, "Unknown object type " + type + ".");
		}
		return uri;
	}

	static String groupUri(String group) {
		return "/sap/bc/adt/functions/groups/" + AdtObjectRef.encodeName(group.trim());
	}

	/**
	 * The create request for one object.
	 *
	 * @param props type specific input of {@code adt_create_object} (group,
	 *              messages, data_type …)
	 */
	static CreateRequest create(String type, String name, String description, String pkg, String transport,
			String language, String responsible, JsonObject props) throws AdtException {
		String t = normalizeType(type);
		if (!CREATABLE.contains(t)) {
			throw new AdtException(400, "Creating objects of type " + type + " is not supported; supported are "
					+ String.join(", ", CREATABLE) + ".");
		}
		String n = name.trim().toUpperCase(Locale.ROOT);
		String p = pkg == null ? "" : pkg.trim().toUpperCase(Locale.ROOT);
		String lang = language(language);
		String group = Json.str(props, "group");
		String uri = objectUri(t, n, group);
		String collection = uri.substring(0, uri.lastIndexOf('/'));
		String head = " adtcore:description=\"" + x(description) + "\" adtcore:name=\"" + x(n) + "\"";
		String master = " adtcore:masterLanguage=\"" + lang + "\"" + responsible(responsible);
		String pkgRef = "\n  <adtcore:packageRef adtcore:name=\"" + x(p) + "\"/>";
		String ns = " xmlns:adtcore=\"http://www.sap.com/adt/core\"";
		String body;
		String contentType = "application/*";
		boolean packageParam = false;
		switch (t) {
		case "CLAS" -> body = "<class:abapClass xmlns:class=\"http://www.sap.com/adt/oo/classes\"" + ns + head
				+ " adtcore:type=\"CLAS/OC\" class:final=\"true\" class:visibility=\"public\"" + master + ">" + pkgRef
				+ "\n  <class:include adtcore:name=\"CLAS/OC\" adtcore:type=\"CLAS/OC\" class:includeType=\"testclasses\"/>"
				+ "\n  <class:superClassRef/>\n</class:abapClass>";
		case "INTF" -> body = "<intf:abapInterface xmlns:intf=\"http://www.sap.com/adt/oo/interfaces\"" + ns + head
				+ " adtcore:type=\"INTF/OI\"" + master + ">" + pkgRef + "\n</intf:abapInterface>";
		case "PROG" -> body = "<program:abapProgram xmlns:program=\"http://www.sap.com/adt/programs/programs\"" + ns
				+ head + " adtcore:type=\"PROG/P\"" + master + ">" + pkgRef + "\n</program:abapProgram>";
		case "INCL" -> {
			if (group != null && !group.isBlank()) {
				String g = group.trim().toUpperCase(Locale.ROOT);
				body = "<finclude:abapFunctionGroupInclude xmlns:finclude=\"http://www.sap.com/adt/functions/fincludes\""
						+ ns + head + " adtcore:type=\"FUGR/I\">\n  <adtcore:containerRef adtcore:name=\"" + x(g)
						+ "\" adtcore:type=\"FUGR/F\" adtcore:uri=\"" + groupUri(g)
						+ "\"/>\n</finclude:abapFunctionGroupInclude>";
				contentType = FUNCTION_INCLUDE_TYPE;
			} else {
				body = "<include:abapInclude xmlns:include=\"http://www.sap.com/adt/programs/includes\"" + ns + head
						+ " adtcore:type=\"PROG/I\"" + master + ">" + pkgRef + "\n</include:abapInclude>";
			}
		}
		case "FUGR" -> {
			body = "<group:abapFunctionGroup xmlns:group=\"http://www.sap.com/adt/functions/groups\"" + ns + head
					+ " adtcore:language=\"" + lang + "\" adtcore:type=\"FUGR/F\" adtcore:masterLanguage=\"" + lang
					+ "\">" + pkgRef + "\n</group:abapFunctionGroup>";
			contentType = FUNCTION_GROUP_TYPE;
		}
		case "FUNC" -> {
			// no package: a function module belongs to the package of its group
			String g = group.trim().toUpperCase(Locale.ROOT);
			body = "<fmodule:abapFunctionModule xmlns:fmodule=\"http://www.sap.com/adt/functions/fmodules\"" + ns
					+ head + " adtcore:type=\"FUGR/FF\">\n  <adtcore:containerRef adtcore:name=\"" + x(g)
					+ "\" adtcore:type=\"FUGR/F\" adtcore:uri=\"" + groupUri(g) + "\"/>\n</fmodule:abapFunctionModule>";
			contentType = FUNCTION_MODULE_TYPE;
		}
		case "DDLS" -> body = "<ddl:ddlSource xmlns:ddl=\"http://www.sap.com/adt/ddic/ddlsources\"" + ns + head
				+ " adtcore:type=\"DDLS/DF\"" + master + ">" + pkgRef + "\n</ddl:ddlSource>";
		case "DCLS" -> body = "<dcl:dclSource xmlns:dcl=\"http://www.sap.com/adt/acm/dclsources\"" + ns + head
				+ " adtcore:type=\"DCLS/DL\"" + master + ">" + pkgRef + "\n</dcl:dclSource>";
		case "DDLX" -> body = "<ddlx:ddlxSource xmlns:ddlx=\"http://www.sap.com/adt/ddic/ddlxsources\"" + ns + head
				+ " adtcore:type=\"DDLX/EX\"" + master + ">" + pkgRef + "\n</ddlx:ddlxSource>";
		case "SRVD" -> body = "<srvd:srvdSource xmlns:srvd=\"http://www.sap.com/adt/ddic/srvdsources\"" + ns + head
				+ " adtcore:type=\"SRVD/SRV\"" + master + " srvd:srvdSourceType=\"S\">" + pkgRef + "\n</srvd:srvdSource>";
		case "TABL/DT", "TABL/DS" -> {
			body = "<blue:blueSource xmlns:blue=\"http://www.sap.com/wbobj/blue\"" + ns + head + " adtcore:type=\"" + t
					+ "\"" + master + ">" + pkgRef + "\n</blue:blueSource>";
			packageParam = true;
		}
		case "BDEF" -> {
			body = "<blue:blueSource xmlns:blue=\"http://www.sap.com/wbobj/blue\"" + ns + head
					+ " adtcore:type=\"BDEF/BDO\"" + master + ">" + pkgRef + "\n</blue:blueSource>";
			contentType = BDEF_TYPE;
			packageParam = true;
		}
		case "MSAG" -> {
			body = messageClassXml(n, description, p, lang, List.of());
			contentType = MESSAGECLASS_TYPE;
		}
		case "DTEL" -> {
			body = dataElementXml(n, description, p, lang, responsible, dataElementFields(props, Map.of()));
			contentType = DATAELEMENT_TYPE;
		}
		case "DOMA" -> {
			body = domainXml(n, description, p, lang, responsible, domainFields(props, null));
			contentType = DOMAIN_TYPE;
		}
		case "TTYP" -> {
			String rowType = Json.str(props, "row_type");
			if (rowType == null || rowType.isBlank()) {
				throw new AdtException(400, "Give 'row_type', a built-in type (STRING, I …) or a DDIC structure.");
			}
			body = tableTypeXml(n, description, p, lang, responsible, rowType, Json.str(props, "row_type_kind"));
			contentType = TABLETYPE_TYPE;
		}
		case "SRVB" -> body = serviceBindingXml(n, description, p, lang, responsible, props);
		default -> throw new AdtException(400, "Creating objects of type " + type + " is not supported.");
		}
		List<String> query = new ArrayList<>();
		if (transport != null && !transport.isBlank()) {
			query.add("corrNr=" + AdtClient.enc(transport.trim()));
		}
		if (packageParam) {
			query.add("_package=" + AdtClient.enc(p));
		}
		return new CreateRequest(collection, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + body, contentType,
				query.isEmpty() ? "" : "?" + String.join("&", query), uri, t);
	}

	private static String responsible(String user) {
		// ADT's person responsible is a user name of at most 12 characters
		return user == null || user.isBlank() || user.length() > 12 ? ""
				: " adtcore:responsible=\"" + x(user.trim().toUpperCase(Locale.ROOT)) + "\"";
	}

	static String language(String language) {
		String l = language == null ? "" : language.trim().toUpperCase(Locale.ROOT);
		return l.matches("[A-Z0-9]{2}") ? l : "EN";
	}

	private static String x(String s) {
		return AdtXml.escape(s == null ? "" : s);
	}

	/** Description, package and language from the root of a metadata XML. */
	record Header(String description, String pkg, String language) {
	}

	static Header header(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		String language = AdtXml.attr(root, "language");
		return new Header(AdtXml.attr(root, "description"), pkg,
				language.isEmpty() ? AdtXml.attr(root, "masterLanguage") : language);
	}

	// ---- message classes -------------------------------------------------------

	/**
	 * One message of a message class.
	 *
	 * @param selfExplanatory no long text needed (T100U-SELFDEF)
	 * @param documented      has a long text
	 */
	record Message(String number, String text, boolean selfExplanatory, boolean documented) {

		/** A new message: self-explanatory, without long text. */
		Message(String number, String text) {
			this(number, text, true, false);
		}
	}

	static String messageClassXml(String name, String description, String pkg, String language, List<Message> messages) {
		StringBuilder sb = new StringBuilder("<mc:messageClass xmlns:mc=\"http://www.sap.com/adt/MessageClass\""
				+ " xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:description=\"" + x(description)
				+ "\" adtcore:name=\"" + x(name) + "\" adtcore:language=\"" + language + "\" adtcore:masterLanguage=\""
				+ language + "\">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>");
		for (Message m : messages) {
			// adtcore:language keys the texts (T100-SPRSL); without it they are stored under a blank language
			sb.append("\n  <mc:messages mc:msgno=\"").append(x(m.number())).append("\" mc:msgtext=\"")
					.append(x(m.text())).append("\" mc:selfexplainatory=\"").append(m.selfExplanatory())
					.append("\" mc:documented=\"").append(m.documented()).append("\"/>");
		}
		return sb.append("\n</mc:messageClass>").toString();
	}

	/** Messages from the tool input: an array of {number, text}. */
	static List<Message> messages(JsonObject props) throws AdtException {
		List<Message> out = new ArrayList<>();
		JsonArray arr = Json.arr(props, "messages");
		if (arr == null) {
			return out;
		}
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String number = Json.str(o, "number");
			String text = Json.str(o, "text");
			if (number == null || !number.trim().matches("\\d{1,3}")) {
				throw new AdtException(400, "Message numbers have up to three digits, e.g. 001; got " + number + ".");
			}
			if (text == null) {
				throw new AdtException(400, "Message " + number + " has no 'text'.");
			}
			if (text.length() > 73) {
				throw new AdtException(400, "Message " + number + " is longer than 73 characters.");
			}
			out.add(new Message(String.format("%03d", Integer.parseInt(number.trim())), text));
		}
		return out;
	}

	/** Description, package, language and messages of a message class as ADT returns it. */
	record MessageClass(String description, String pkg, String language, List<Message> messages) {
	}

	static MessageClass parseMessageClass(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		List<Message> messages = new ArrayList<>();
		for (Element e : AdtXml.elements(d, "messages")) {
			// flags missing in the response keep the defaults of a new message
			String self = AdtXml.attr(e, "selfexplainatory");
			messages.add(new Message(AdtXml.attr(e, "msgno"), AdtXml.attr(e, "msgtext"), !"false".equals(self),
					"true".equals(AdtXml.attr(e, "documented"))));
		}
		String language = AdtXml.attr(root, "language");
		return new MessageClass(AdtXml.attr(root, "description"), pkg,
				language.isEmpty() ? AdtXml.attr(root, "masterLanguage") : language, messages);
	}

	/** The messages after adding or changing {@code changes} and removing {@code remove}, sorted by number. */
	static List<Message> mergeMessages(List<Message> current, List<Message> changes, List<String> remove) {
		Map<String, Message> byNumber = new java.util.TreeMap<>();
		current.forEach(m -> byNumber.put(m.number(), m));
		// a changed text keeps the flags (long text) of the existing message
		changes.forEach(m -> byNumber.merge(m.number(), m,
				(old, neu) -> new Message(old.number(), neu.text(), old.selfExplanatory(), old.documented())));
		remove.forEach(n -> byNumber.remove(n.matches("\\d{1,3}") ? String.format("%03d", Integer.parseInt(n)) : n));
		return new ArrayList<>(byNumber.values());
	}

	// ---- data elements ---------------------------------------------------------

	/** DTEL fields by the element names of ADT's data element XML. */
	static Map<String, String> dataElementFields(JsonObject props, Map<String, String> current) {
		Map<String, String> f = new LinkedHashMap<>(current);
		String domain = Json.str(props, "domain");
		String dataType = Json.str(props, "data_type");
		if (domain != null && !domain.isBlank()) {
			f.put("typeKind", "domain");
			f.put("typeName", domain.trim().toUpperCase(Locale.ROOT));
			f.put("dataType", "");
		} else if (dataType != null && !dataType.isBlank()) {
			f.put("typeKind", "predefinedAbapType");
			f.put("typeName", "");
			f.put("dataType", dataType.trim().toUpperCase(Locale.ROOT));
		}
		putNumber(f, "dataTypeLength", props, "length", 6);
		putNumber(f, "dataTypeDecimals", props, "decimals", 6);
		putLabel(f, "shortField", props, "short_label", SHORT);
		putLabel(f, "mediumField", props, "medium_label", MEDIUM);
		putLabel(f, "longField", props, "long_label", LONG);
		putLabel(f, "headingField", props, "heading_label", HEADING);
		String searchHelp = Json.str(props, "search_help");
		if (searchHelp != null) {
			f.put("searchHelp", searchHelp.trim().toUpperCase(Locale.ROOT));
		}
		return f;
	}

	private static void putNumber(Map<String, String> f, String key, JsonObject props, String prop, int width) {
		String v = Json.str(props, prop);
		if (v != null && v.trim().matches("\\d+")) {
			f.put(key, pad(v.trim(), width));
		}
	}

	private static void putLabel(Map<String, String> f, String prefix, JsonObject props, String prop, int max) {
		String v = Json.str(props, prop);
		if (v != null) {
			String label = v.length() > max ? v.substring(0, max) : v;
			f.put(prefix + "Label", label);
			f.put(prefix + "Length", pad(String.valueOf(label.isEmpty() ? max : Math.min(label.length(), max)), 2));
		}
	}

	private static String pad(String digits, int width) {
		String d = digits.replaceFirst("^0+(?=\\d)", "");
		return "0".repeat(Math.max(0, width - d.length())) + d;
	}

	private static String field(Map<String, String> f, String key, String def) {
		String v = f.get(key);
		return x(v == null ? def : v);
	}

	static String dataElementXml(String name, String description, String pkg, String language, String responsible,
			Map<String, String> f) {
		String typeKind = f.getOrDefault("typeKind",
				f.getOrDefault("dataType", "").isEmpty() ? "domain" : "predefinedAbapType");
		return "<blue:wbobj xmlns:blue=\"http://www.sap.com/wbobj/dictionary/dtel\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " adtcore:description=\"" + x(description) + "\" adtcore:name=\"" + x(name)
				+ "\" adtcore:type=\"DTEL/DE\" adtcore:masterLanguage=\"" + language + "\""
				+ responsible(responsible) + ">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>"
				+ "\n  <dtel:dataElement xmlns:dtel=\"http://www.sap.com/adt/dictionary/dataelements\">"
				+ "\n    <dtel:typeKind>" + x(typeKind) + "</dtel:typeKind>"
				+ "\n    <dtel:typeName>" + field(f, "typeName", "") + "</dtel:typeName>"
				+ "\n    <dtel:dataType>" + field(f, "dataType", "") + "</dtel:dataType>"
				+ "\n    <dtel:dataTypeLength>" + field(f, "dataTypeLength", "000000") + "</dtel:dataTypeLength>"
				+ "\n    <dtel:dataTypeDecimals>" + field(f, "dataTypeDecimals", "000000") + "</dtel:dataTypeDecimals>"
				+ label(f, "shortField", SHORT) + label(f, "mediumField", MEDIUM) + label(f, "longField", LONG)
				+ label(f, "headingField", HEADING)
				+ "\n    <dtel:searchHelp>" + field(f, "searchHelp", "") + "</dtel:searchHelp>"
				+ "\n    <dtel:searchHelpParameter>" + field(f, "searchHelpParameter", "") + "</dtel:searchHelpParameter>"
				+ "\n    <dtel:setGetParameter>" + field(f, "setGetParameter", "") + "</dtel:setGetParameter>"
				+ "\n    <dtel:defaultComponentName>" + field(f, "defaultComponentName", "") + "</dtel:defaultComponentName>"
				+ "\n    <dtel:deactivateInputHistory>" + field(f, "deactivateInputHistory", "false") + "</dtel:deactivateInputHistory>"
				+ "\n    <dtel:changeDocument>" + field(f, "changeDocument", "false") + "</dtel:changeDocument>"
				+ "\n    <dtel:leftToRightDirection>" + field(f, "leftToRightDirection", "false") + "</dtel:leftToRightDirection>"
				+ "\n    <dtel:deactivateBIDIFiltering>" + field(f, "deactivateBIDIFiltering", "false") + "</dtel:deactivateBIDIFiltering>"
				+ "\n  </dtel:dataElement>\n</blue:wbobj>";
	}

	private static String label(Map<String, String> f, String prefix, int max) {
		return "\n    <dtel:" + prefix + "Label>" + field(f, prefix + "Label", "") + "</dtel:" + prefix + "Label>"
				+ "\n    <dtel:" + prefix + "Length>" + field(f, prefix + "Length", pad(String.valueOf(max), 2))
				+ "</dtel:" + prefix + "Length>"
				+ "\n    <dtel:" + prefix + "MaxLength>" + pad(String.valueOf(max), 2) + "</dtel:" + prefix + "MaxLength>";
	}

	/** The fields of a data element as ADT returns it, keyed by element name. */
	static Map<String, String> parseDataElement(String xml) throws IOException {
		Map<String, String> f = new LinkedHashMap<>();
		Document d = AdtXml.parse(xml);
		for (Element de : AdtXml.elements(d, "dataElement")) {
			for (org.w3c.dom.Node n = de.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n instanceof Element e) {
					f.put(e.getLocalName() == null ? e.getNodeName() : e.getLocalName(), e.getTextContent());
				}
			}
		}
		return f;
	}

	// ---- domains ---------------------------------------------------------------

	/** Fields of a domain. */
	record Domain(String dataType, String length, String decimals, String outputLength, String conversionExit,
			boolean signExists, boolean lowercase, String valueTable, List<String[]> fixedValues) {
	}

	static Domain domainFields(JsonObject props, Domain current) throws AdtException {
		Domain c = current == null ? new Domain("CHAR", "000000", "000000", null, "", false, false, "", List.of())
				: current;
		String dataType = Json.str(props, "data_type");
		String length = Json.str(props, "length");
		String decimals = Json.str(props, "decimals");
		if (current == null && (dataType == null || length == null)) {
			throw new AdtException(400, "A domain needs 'data_type' (e.g. CHAR) and 'length'.");
		}
		List<String[]> fixed = c.fixedValues();
		JsonArray arr = Json.arr(props, "fixed_values");
		if (arr != null) {
			fixed = new ArrayList<>();
			for (JsonElement e : arr) {
				if (e.isJsonObject()) {
					JsonObject o = e.getAsJsonObject();
					fixed.add(new String[] { nz(Json.str(o, "low")), nz(Json.str(o, "high")), nz(Json.str(o, "text")) });
				}
			}
		}
		String lengthNew = length != null && length.trim().matches("\\d+") ? pad(length.trim(), 6) : c.length();
		String output = Json.str(props, "output_length");
		return new Domain(dataType == null ? c.dataType() : dataType.trim().toUpperCase(Locale.ROOT), lengthNew,
				decimals != null && decimals.trim().matches("\\d+") ? pad(decimals.trim(), 6) : c.decimals(),
				output != null && output.trim().matches("\\d+") ? pad(output.trim(), 6)
						: length != null || c.outputLength() == null ? lengthNew : c.outputLength(),
				Json.str(props, "conversion_exit") == null ? c.conversionExit()
						: Json.str(props, "conversion_exit").trim().toUpperCase(Locale.ROOT),
				bool(props, "sign", c.signExists()), bool(props, "lowercase", c.lowercase()),
				Json.str(props, "value_table") == null ? c.valueTable()
						: Json.str(props, "value_table").trim().toUpperCase(Locale.ROOT),
				fixed);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static boolean bool(JsonObject o, String key, boolean def) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
	}

	static String domainXml(String name, String description, String pkg, String language, String responsible,
			Domain d) {
		StringBuilder fixed = new StringBuilder();
		if (d.fixedValues().isEmpty()) {
			fixed.append("\n      <doma:fixValues/>");
		} else {
			fixed.append("\n      <doma:fixValues>");
			int pos = 1;
			for (String[] v : d.fixedValues()) {
				fixed.append("\n        <doma:fixValue><doma:position>").append(String.format("%04d", pos++))
						.append("</doma:position><doma:low>").append(x(v[0])).append("</doma:low><doma:high>")
						.append(x(v[1])).append("</doma:high><doma:text>").append(x(v[2]))
						.append("</doma:text></doma:fixValue>");
			}
			fixed.append("\n      </doma:fixValues>");
		}
		return "<doma:domain xmlns:doma=\"http://www.sap.com/dictionary/domain\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " adtcore:description=\"" + x(description) + "\" adtcore:name=\"" + x(name)
				+ "\" adtcore:type=\"DOMA/DD\" adtcore:masterLanguage=\"" + language + "\""
				+ responsible(responsible) + ">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>"
				+ "\n  <doma:content>\n    <doma:typeInformation>"
				+ "\n      <doma:datatype>" + x(d.dataType()) + "</doma:datatype>"
				+ "\n      <doma:length>" + d.length() + "</doma:length>"
				+ "\n      <doma:decimals>" + d.decimals() + "</doma:decimals>"
				+ "\n    </doma:typeInformation>\n    <doma:outputInformation>"
				+ "\n      <doma:length>" + d.outputLength() + "</doma:length>"
				+ "\n      <doma:style>00</doma:style>"
				+ "\n      <doma:conversionExit>" + x(d.conversionExit()) + "</doma:conversionExit>"
				+ "\n      <doma:signExists>" + d.signExists() + "</doma:signExists>"
				+ "\n      <doma:lowercase>" + d.lowercase() + "</doma:lowercase>"
				+ "\n      <doma:ampmFormat>false</doma:ampmFormat>"
				+ "\n    </doma:outputInformation>\n    <doma:valueInformation>"
				+ (d.valueTable().isEmpty() ? ""
						: "\n      <doma:valueTableRef adtcore:type=\"TABL/DT\" adtcore:name=\"" + x(d.valueTable()) + "\"/>")
				+ "\n      <doma:appendExists>false</doma:appendExists>" + fixed
				+ "\n    </doma:valueInformation>\n  </doma:content>\n</doma:domain>";
	}

	static Domain parseDomain(String xml) throws IOException {
		Document doc = AdtXml.parse(xml);
		String dataType = "";
		String length = "000000";
		String decimals = "000000";
		String output = null;
		for (Element e : AdtXml.elements(doc, "typeInformation")) {
			dataType = child(e, "datatype");
			length = child(e, "length");
			decimals = child(e, "decimals");
		}
		String exit = "";
		boolean sign = false;
		boolean lower = false;
		for (Element e : AdtXml.elements(doc, "outputInformation")) {
			output = child(e, "length");
			exit = child(e, "conversionExit");
			sign = "true".equals(child(e, "signExists"));
			lower = "true".equals(child(e, "lowercase"));
		}
		String valueTable = "";
		for (Element e : AdtXml.elements(doc, "valueTableRef")) {
			valueTable = AdtXml.attr(e, "name");
		}
		List<String[]> fixed = new ArrayList<>();
		for (Element e : AdtXml.elements(doc, "fixValue")) {
			fixed.add(new String[] { child(e, "low"), child(e, "high"), child(e, "text") });
		}
		return new Domain(dataType, length, decimals, output, exit, sign, lower, valueTable, fixed);
	}

	private static String child(Element parent, String localName) {
		for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && localName.equals(e.getLocalName() == null ? e.getNodeName() : e.getLocalName())) {
				return e.getTextContent();
			}
		}
		return "";
	}

	// ---- table types -----------------------------------------------------------

	private static final Set<String> BUILTIN = Set.of("STRING", "XSTRING", "I", "INT8", "F", "P", "D", "T", "C", "N",
			"X", "B", "S", "DECFLOAT16", "DECFLOAT34", "UTCLONG");
	private static final Pattern DDIC_NAME = Pattern.compile("(?:/[A-Z0-9_]+/)?[A-Z0-9_]+");

	static String tableTypeXml(String name, String description, String pkg, String language, String responsible,
			String rowType, String rowTypeKind) throws AdtException {
		String rowXml = tableTypeRowXml(rowType, rowTypeKind);
		return "<ttyp:tableType xmlns:ttyp=\"http://www.sap.com/dictionary/tabletype\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " adtcore:description=\"" + x(description) + "\" adtcore:name=\"" + x(name)
				+ "\" adtcore:type=\"TTYP/DA\" adtcore:masterLanguage=\"" + language + "\"" + responsible(responsible)
				+ ">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>\n  <ttyp:rowType>" + rowXml
				+ "</ttyp:rowType>\n  <ttyp:initialRowCount>00000</ttyp:initialRowCount>"
				+ "\n  <ttyp:accessType>standard</ttyp:accessType>"
				+ "\n  <ttyp:primaryKey ttyp:isVisible=\"true\" ttyp:isEditable=\"true\"><ttyp:definition>standard</ttyp:definition>"
				+ "<ttyp:kind>nonUnique</ttyp:kind><ttyp:components ttyp:isVisible=\"false\"/><ttyp:alias/></ttyp:primaryKey>"
				+ "\n  <ttyp:secondaryKeys ttyp:isVisible=\"true\" ttyp:isEditable=\"true\"><ttyp:allowed>notSpecified</ttyp:allowed>"
				+ "</ttyp:secondaryKeys>\n</ttyp:tableType>";
	}

	private static final Pattern TTYP_ROW = Pattern.compile("<(\\w+:)?rowType\\b[^>]*>.*?</\\1?rowType>",
			Pattern.DOTALL);
	private static final Pattern ROOT_DESCRIPTION = Pattern.compile("(adtcore:description=\")[^\"]*(\")");

	/**
	 * Changes row type and description of an existing table type and keeps everything else (access type, keys,
	 * initial row count) as read from the system.
	 */
	static String updateTableTypeXml(String current, String description, String rowType, String rowTypeKind)
			throws AdtException {
		String xml = current.replaceFirst("^\\s*<\\?xml[^>]*\\?>\\s*", "");
		Matcher m = TTYP_ROW.matcher(xml);
		if (!m.find()) {
			throw new AdtException(500, "The table type has no row type element Bella can change.");
		}
		// the system may use another prefix for the table type namespace
		String prefix = m.group(1) == null ? "" : m.group(1);
		String row = ("<ttyp:rowType>" + tableTypeRowXml(rowType, rowTypeKind) + "</ttyp:rowType>")
				.replace("<ttyp:", "<" + prefix).replace("</ttyp:", "</" + prefix);
		xml = xml.substring(0, m.start()) + row + xml.substring(m.end());
		Matcher d = ROOT_DESCRIPTION.matcher(xml);
		if (d.find()) {
			xml = xml.substring(0, d.start()) + d.group(1) + x(description) + d.group(2) + xml.substring(d.end());
		}
		return xml;
	}

	private static String tableTypeRowXml(String rowType, String rowTypeKind) throws AdtException {
		String row = rowType.trim().toUpperCase(Locale.ROOT);
		if (!DDIC_NAME.matcher(row).matches()) {
			throw new AdtException(400, "Invalid row type " + rowType + ".");
		}
		boolean builtin = rowTypeKind == null || rowTypeKind.isBlank() ? BUILTIN.contains(row)
				: rowTypeKind.trim().equalsIgnoreCase("builtin");
		return builtin
				? "<ttyp:typeKind>predefinedAbapType</ttyp:typeKind><ttyp:typeName/><ttyp:builtInType><ttyp:dataType>"
						+ x(row) + "</ttyp:dataType><ttyp:length>000000</ttyp:length><ttyp:decimals>000000</ttyp:decimals>"
						+ "</ttyp:builtInType><ttyp:rangeType/>"
				: "<ttyp:typeKind>dictionaryType</ttyp:typeKind><ttyp:typeName>" + x(row)
						+ "</ttyp:typeName><ttyp:builtInType><ttyp:dataType>STRU</ttyp:dataType><ttyp:length>000000"
						+ "</ttyp:length><ttyp:decimals>000000</ttyp:decimals></ttyp:builtInType><ttyp:rangeType/>";
	}

	// ---- service bindings ------------------------------------------------------

	static String serviceBindingXml(String name, String description, String pkg, String language, String responsible,
			JsonObject props) throws AdtException {
		String definition = Json.str(props, "service_definition");
		if (definition == null || definition.isBlank()) {
			throw new AdtException(400, "A service binding needs 'service_definition' (the SRVD).");
		}
		String bindingType = nz(Json.str(props, "binding_type")).toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
		String odata = nz(Json.str(props, "odata_version")).trim().toUpperCase(Locale.ROOT);
		if (odata.isEmpty()) {
			odata = bindingType.contains("V4") ? "V4" : "V2";
		}
		String category = nz(Json.str(props, "category")).trim();
		if (!category.equals("0") && !category.equals("1")) {
			category = bindingType.contains("API") ? "1" : "0";
		}
		return "<srvb:serviceBinding xmlns:srvb=\"http://www.sap.com/adt/ddic/ServiceBindings\" xmlns:adtcore=\"http://www.sap.com/adt/core\""
				+ " adtcore:description=\"" + x(description) + "\" adtcore:name=\"" + x(name)
				+ "\" adtcore:type=\"SRVB/SVB\" adtcore:language=\"" + language + "\" adtcore:masterLanguage=\"" + language
				+ "\"" + responsible(responsible) + ">\n  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>"
				+ "\n  <srvb:services srvb:name=\"" + x(name) + "\">\n    <srvb:content srvb:version=\"0001\">"
				+ "\n      <srvb:serviceDefinition adtcore:name=\"" + x(definition.trim().toUpperCase(Locale.ROOT))
				+ "\"/>\n    </srvb:content>\n  </srvb:services>\n  <srvb:binding srvb:category=\"" + category
				+ "\" srvb:type=\"ODATA\" srvb:version=\"" + x(odata) + "\">\n    <srvb:implementation adtcore:name=\"\"/>"
				+ "\n  </srvb:binding>\n</srvb:serviceBinding>";
	}

	// ---- function modules ------------------------------------------------------

	private static final Pattern FM_ROOT = Pattern.compile("<fmodule:abapFunctionModule\\b[^>]*>",
			Pattern.CASE_INSENSITIVE);

	/** The function module metadata with another processing type (normal, rfc, update). */
	static String withProcessingType(String xml, String processingType, String updateTaskKind) throws AdtException {
		Matcher m = FM_ROOT.matcher(xml);
		if (!m.find()) {
			throw new AdtException(500, "SAP did not return the function module's metadata.");
		}
		String root = m.group().replaceAll("\\s+fmodule:(?:processingType|updateTaskKind)\\s*=\\s*\"[^\"]*\"", "");
		String attrs = " fmodule:processingType=\"" + x(processingType) + "\""
				+ (updateTaskKind == null ? "" : " fmodule:updateTaskKind=\"" + x(updateTaskKind) + "\"");
		root = root.replaceFirst("(?i)<fmodule:abapFunctionModule", "<fmodule:abapFunctionModule" + attrs);
		return xml.substring(0, m.start()) + root + xml.substring(m.end());
	}

	/** The processing type stored in function module metadata, {@code normal} when absent. */
	static String processingType(String xml) {
		Matcher m = Pattern.compile("fmodule:processingType\\s*=\\s*\"([^\"]*)\"").matcher(xml);
		return m.find() ? m.group(1) : "normal";
	}

	/**
	 * Removes the parameter comment block SAP GUI shows above a function
	 * module's statements ({@code *"---…}); ADT rejects source that contains
	 * it, because the interface is part of the FUNCTION statement.
	 */
	static String stripParameterComments(String source) {
		return source.replaceAll("(?m)^[ \\t]*\\*\".*(?:\\r?\\n|$)", "");
	}
}

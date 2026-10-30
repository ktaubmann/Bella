package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * Server-driven objects of newer releases (most from SAP_BASIS 8.16, some
 * also on 7.58): one generic contract for many types. The metadata is a
 * small XML document ({@code blue:blueSource}, for DTDC
 * {@code dtdc:dtdcSource}) at the object URI, the content is the
 * {@code source/main}, AFF JSON or CDS text depending on the type. Create
 * POSTs the metadata, the source follows with lock, PUT, unlock. Registry
 * and media types follow ARC-1 ({@code src/adt/server-driven.ts}, MIT),
 * verified there per type on 7.58, 8.16 and BTP.
 */
final class AdtServerDriven {

	private static final String BLUES_V1 = "application/vnd.sap.adt.blues.v1+xml";
	private static final String BLUES_V2 = "application/vnd.sap.adt.blues.v2+xml";
	private static final String BLUE = "blue:blueSource http://www.sap.com/wbobj/blue";

	/**
	 * One type: its collection, the {@code adtcore:type} of a create, the
	 * metadata media type, the root element with its namespace, and whether
	 * the source is AFF JSON (else CDS text; the wrong one answers 415).
	 */
	record Type(String code, String collection, String label, String createType, String metadataType, String root,
			boolean json) {

		String rootName() {
			return root.substring(0, root.indexOf(' '));
		}

		String namespace() {
			return root.substring(root.indexOf(' ') + 1);
		}
	}

	static final Map<String, Type> TYPES = new LinkedHashMap<>();
	static {
		add("DESD", "/sap/bc/adt/ddic/desd", "CDS logical external schema", "DESD/TYP", BLUES_V1, BLUE, true);
		add("DTSC", "/sap/bc/adt/ddic/dtsc/sources", "CDS static cache", "DTSC/TYP", BLUES_V1, BLUE, false);
		add("DTDC", "/sap/bc/adt/ddic/dtdc/sources", "CDS dynamic cache", "DTDC/DF",
				"application/vnd.sap.adt.ddic.dtdc.v1+xml", "dtdc:dtdcSource http://www.sap.com/adt/ddic/dtdcsources",
				false);
		add("DSFD", "/sap/bc/adt/ddic/dsfd/sources", "CDS scalar function definition", "DSFD/SCF", BLUES_V1, BLUE,
				false);
		add("DRTY", "/sap/bc/adt/ddic/drty/sources", "CDS type (simple type or enumeration)", "DRTY/STY", BLUES_V1,
				BLUE, false);
		add("CSNM", "/sap/bc/adt/csn/csnm", "Core Schema Notation model", "CSNM/TYP", BLUES_V1, BLUE, true);
		add("EVTB", "/sap/bc/adt/businessservices/evtbevb", "RAP event binding", "EVTB/EVB", BLUES_V1, BLUE, true);
		add("EVTO", "/sap/bc/adt/businessservices/evtoevo", "RAP event object", "EVTO/EVO", BLUES_V2, BLUE, true);
		add("COTA", "/sap/bc/adt/conn/commtargets", "communication target", "COTA/TYP", BLUES_V1, BLUE, true);
		add("UIAD", "/sap/bc/adt/fiori/uiad", "launchpad app descriptor item", "UIAD/TYP", BLUES_V2, BLUE, true);
		add("APLO", "/sap/bc/adt/applicationlog/objects", "application log object", "APLO/TYP", BLUES_V1, BLUE,
				true);
		add("SAJC", "/sap/bc/adt/applicationjob/catalogs", "application job catalog entry", "SAJC", BLUES_V2, BLUE,
				true);
		add("SAJT", "/sap/bc/adt/applicationjob/templates", "application job template", "SAJT", BLUES_V2, BLUE,
				true);
	}

	private static void add(String code, String collection, String label, String createType, String metadataType,
			String root, boolean json) {
		TYPES.put(code, new Type(code, collection, label, createType, metadataType, root, json));
	}

	private AdtServerDriven() {
	}

	/** The type of a code (EVTB or EVTB/EVB), {@code null} for other types. */
	static Type type(String code) {
		if (code == null) {
			return null;
		}
		String c = code.trim().toUpperCase(Locale.ROOT);
		return TYPES.get(c.contains("/") ? c.substring(0, c.indexOf('/')) : c);
	}

	/** The type an object URI belongs to, {@code null} for other URIs. */
	static Type ofUri(String uri) {
		for (Type t : TYPES.values()) {
			if (uri != null && uri.startsWith(t.collection() + "/")) {
				return t;
			}
		}
		return null;
	}

	/** Object URI with the name in upper case; ARC-1 does not lower-case these names either. */
	static String uri(Type t, String name) {
		return t.collection() + "/" + URLEncoder.encode(name.trim().toUpperCase(Locale.ROOT), StandardCharsets.UTF_8);
	}

	/** Content-Type of a source PUT. */
	static String sourceType(Type t) {
		return t.json() ? "application/json" : "text/plain; charset=utf-8";
	}

	/** Short help when a system answers 404 for a whole type. */
	static String unavailable(Type t) {
		return t.code() + " (" + t.label() + ") is not available through ADT on this system. These types come with "
				+ "newer releases: most with SAP_BASIS 8.16 (ABAP Platform 2025), DTDC, DSFD, DRTY and EVTB also with "
				+ "7.58.";
	}

	/**
	 * The create body. No master language: SAP ignores it for these types and
	 * takes the logon language. Job catalogs and templates need the class or
	 * catalog they are made from already in the create (ARC-1, 7.58).
	 */
	static String createXml(Type t, String name, String description, String pkg, String source) throws AdtException {
		String extra = "";
		if (t.code().equals("SAJC") || t.code().equals("SAJT")) {
			String field = t.code().equals("SAJC") ? "className" : "catalogName";
			String reference = generalInformation(source, field);
			if (reference == null) {
				throw new AdtException(400, "Creating " + t.code() + " needs 'source' with generalInformation." + field
						+ " (AFF JSON).");
			}
			JsonObject props = new JsonObject();
			props.addProperty(field, reference);
			extra = "\n  <blue:additionalCreationProperties><adtcore:content adtcore:type=\"application/vnd.sap.adt."
					+ "objecttype.new.content.additional.v1+json\">" + AdtXml.escape(Json.GSON.toJson(props))
					+ "</adtcore:content></blue:additionalCreationProperties>";
		}
		String prefix = t.rootName().substring(0, t.rootName().indexOf(':'));
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + t.rootName() + " xmlns:" + prefix + "=\""
				+ t.namespace() + "\" xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:type=\"" + t.createType()
				+ "\" adtcore:name=\"" + AdtXml.escape(name.trim().toUpperCase(Locale.ROOT)) + "\" adtcore:description=\""
				+ AdtXml.escape(description == null ? "" : description) + "\">\n  <adtcore:packageRef adtcore:name=\""
				+ AdtXml.escape(pkg) + "\"/>" + extra + "\n</" + t.rootName() + ">";
	}

	private static String generalInformation(String source, String field) {
		try {
			JsonElement e = Json.GSON.fromJson(source == null ? "" : source, JsonElement.class);
			JsonObject info = e != null && e.isJsonObject() ? Json.obj(e.getAsJsonObject(), "generalInformation") : null;
			String value = Json.str(info, field);
			return value == null || value.isBlank() ? null : value.trim();
		} catch (JsonParseException notJson) {
			return null;
		}
	}

	/** Refuses a JSON type's source that is no JSON object before anything is written. */
	static void checkSource(Type t, String source) throws AdtException {
		if (!t.json() || source == null) {
			return;
		}
		try {
			JsonElement e = Json.GSON.fromJson(source, JsonElement.class);
			if (e == null || !e.isJsonObject()) {
				throw new AdtException(400, t.code() + " takes its source as an AFF JSON object.");
			}
		} catch (JsonParseException e) {
			throw new AdtException(400, t.code() + " takes its source as AFF JSON, which this is not: " + e.getMessage());
		}
	}

	/** Header line of a read: name, description, package, version. */
	static String header(Type t, String metadataXml) throws IOException {
		Document d = AdtXml.parse(metadataXml);
		Element root = d.getDocumentElement();
		String pkg = "";
		for (Element e : AdtXml.elements(d, "packageRef")) {
			pkg = AdtXml.attr(e, "name");
			break;
		}
		String version = AdtXml.attr(root, "version");
		return t.code() + " " + AdtXml.attr(root, "name") + " (" + t.label() + ")"
				+ (AdtXml.attr(root, "description").isEmpty() ? "" : ": " + AdtXml.attr(root, "description"))
				+ (pkg.isEmpty() ? "" : ", package " + pkg) + (version.isEmpty() ? "" : ", " + version) + ". Source ("
				+ (t.json() ? "AFF JSON" : "CDS") + "; change it with adt_write_source):";
	}
}

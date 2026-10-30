package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * Technical settings of a table (TABL/DTT): their own locked XML document
 * beside the table source, activated separately. A change replaces only the
 * given fields in SAP's document and keeps everything else as it is. Shape
 * and rules follow ARC-1 ({@code src/adt/table-settings.ts}, MIT), verified
 * there on 7.58, 8.16 and BTP; the collection is missing on 7.50.
 */
final class AdtTableSettings {

	static final String TYPE = "application/vnd.sap.adt.table.settings.v2+xml";
	static final String COLLECTION = "/sap/bc/adt/ddic/db/settings";

	private AdtTableSettings() {
	}

	static String uri(String table) {
		return COLLECTION + "/" + AdtObjectRef.encodeName(table.trim());
	}

	/** The table a settings URI belongs to, whose package counts. */
	static String tableUri(String settingsUri) {
		return "/sap/bc/adt/ddic/tables/" + settingsUri.substring(COLLECTION.length() + 1);
	}

	/**
	 * Read with {@code version=inactive}: SAP falls back to the active (or
	 * new) settings, and an unversioned GET hides another user's pending
	 * change, which a save would then overwrite (ARC-1, 7.58).
	 */
	static String readUri(String table) {
		return uri(table) + "?version=inactive";
	}

	/**
	 * @param version active, inactive or new (not activated with the table yet)
	 */
	record Settings(String table, String version, String dataClass, String sizeCategory, String bufferingAllowed,
			String bufferingType, String bufferingKeyFields, String logging, String storageType) {
	}

	static Settings parse(String xml) throws IOException {
		Document d = AdtXml.parse(xml);
		Element root = d.getDocumentElement();
		String table = AdtXml.attr(root, "name");
		for (Element e : AdtXml.elements(d, "containerRef")) {
			table = AdtXml.attr(e, "name");
		}
		return new Settings(table.toUpperCase(Locale.ROOT), AdtXml.attr(root, "version"), text(d, "dataClassCategory"),
				text(d, "sizeCategory"), text(d, "allowed"), text(d, "type"), text(d, "areaKeyFields"),
				text(d, "loggingEnabled"), text(d, "storageType"));
	}

	private static String text(Document d, String localName) {
		List<Element> found = AdtXml.elements(d, localName);
		return found.isEmpty() ? null : AdtXml.text(found.get(0));
	}

	static String describe(Settings s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.table());
		o.addProperty("type", "TABL/DTT");
		o.addProperty("version", s.version());
		o.addProperty("data_class", s.dataClass());
		if (s.sizeCategory() != null && s.sizeCategory().matches("\\d+")) {
			o.addProperty("size_category", Integer.parseInt(s.sizeCategory()));
		}
		if ("true".equals(s.logging()) || "false".equals(s.logging())) {
			o.addProperty("logging", Boolean.parseBoolean(s.logging()));
		}
		o.addProperty("buffering_allowed", s.bufferingAllowed());
		o.addProperty("buffering_type", s.bufferingType());
		if (s.bufferingKeyFields() != null && s.bufferingKeyFields().matches("\\d+")) {
			o.addProperty("buffering_key_fields", Integer.parseInt(s.bufferingKeyFields()));
		}
		if (s.storageType() != null && !s.storageType().isEmpty()) {
			o.addProperty("storage_type", s.storageType());
		}
		return "Technical settings of table " + s.table() + ". buffering_allowed: N not allowed, A allowed but off, X on; "
				+ "buffering_type: \"\" none, P single records, G generic (buffering_key_fields), X full. Change them "
				+ "with adt_write_metadata type TABL/DTT (same keys), then activate"
				+ ("new".equals(s.version()) ? " the table: new settings are activated with it." : " type TABL/DTT.")
				+ "\n" + Json.PRETTY.toJson(o);
	}

	/** Whether the call changes anything; {@code adt_write_metadata} refuses an empty change. */
	static boolean changes(JsonObject in) {
		return List.of("data_class", "size_category", "logging", "buffering_allowed", "buffering_type",
				"buffering_key_fields").stream().anyMatch(in::has);
	}

	/**
	 * SAP's document with the given fields replaced. SAP checks the values
	 * only at activation (an unknown data class saves fine), so only their
	 * form is checked here.
	 */
	static String patched(String xml, JsonObject in) throws IOException {
		if (xml.matches("(?s).*(<!--|<!\\[CDATA\\[|<!DOCTYPE).*")) {
			throw new AdtException(400, "The technical settings contain XML Bella does not change safely.");
		}
		Matcher root = Pattern.compile("<([A-Za-z_][\\w.-]*:)?tableSettings\\b").matcher(xml);
		String prefix = root.find() && root.group(1) != null ? root.group(1) : "";
		String out = xml;
		String dataClass = Json.str(in, "data_class");
		if (dataClass != null) {
			out = replace(out, prefix, "dataClassCategory", check(dataClass.trim().toUpperCase(Locale.ROOT),
					"[A-Z0-9_]{1,5}", "data_class is a data class such as APPL0, APPL1 or USER"));
		}
		if (in.has("size_category")) {
			out = replace(out, prefix, "sizeCategory", check(Json.str(in, "size_category").trim(), "[0-9]",
					"size_category is 0 to 9"));
		}
		if (in.has("logging")) {
			if (parse(xml).logging() == null) {
				throw new AdtException(400, "SAP shows no logging flag for this table, so Bella does not set it.");
			}
			out = replace(out, prefix, "loggingEnabled", String.valueOf(in.get("logging").getAsBoolean()));
		}
		String allowed = Json.str(in, "buffering_allowed");
		String type = Json.str(in, "buffering_type");
		boolean keys = in.has("buffering_key_fields");
		if (allowed != null || type != null || keys) {
			Matcher m = Pattern.compile("<" + Pattern.quote(prefix) + "buffering(?:\\s[^>]*?)?>[\\s\\S]*?</"
					+ Pattern.quote(prefix) + "buffering\\s*>").matcher(out);
			if (!m.find()) {
				throw new AdtException(400, "SAP's technical settings have no buffering to change.");
			}
			int start = m.start();
			int end = m.end();
			String block = m.group();
			if (m.find()) {
				throw new AdtException(400, "Cannot change the buffering safely: SAP's document has it more than once.");
			}
			if (allowed != null) {
				block = replace(block, prefix, "allowed", check(allowed.trim().toUpperCase(Locale.ROOT), "[NAX]",
						"buffering_allowed is N (not allowed), A (allowed, off) or X (on)"));
			}
			if (type != null) {
				block = replace(block, prefix, "type", check(type.trim().toUpperCase(Locale.ROOT), "[PGX]?",
						"buffering_type is \"\" (none), P (single records), G (generic) or X (full)"));
			}
			if (keys) {
				block = replace(block, prefix, "areaKeyFields", check(Json.str(in, "buffering_key_fields").trim(),
						"\\d{1,3}", "buffering_key_fields is the number of key fields of generic buffering"));
			}
			out = out.substring(0, start) + block + out.substring(end);
		}
		AdtXml.parse(out); // never PUT a broken document
		return out;
	}

	private static String check(String value, String regex, String message) throws AdtException {
		if (!value.matches(regex)) {
			throw new AdtException(400, message + "; got '" + value + "'.");
		}
		return value;
	}

	/** Replaces the text of the one element {@code prefix+field}, keeping its attributes. */
	private static String replace(String xml, String prefix, String field, String value) throws AdtException {
		String tag = Pattern.quote(prefix + field);
		Matcher m = Pattern.compile("<" + tag + "(\\s[^>]*?)?(?:>[^<]*</" + tag + "\\s*>|/>)").matcher(xml);
		if (!m.find()) {
			throw new AdtException(400, "SAP's technical settings have no " + field + " to change.");
		}
		int start = m.start();
		int end = m.end();
		String attrs = m.group(1) == null ? "" : m.group(1);
		if (m.find()) {
			throw new AdtException(400, "Cannot change " + field + " safely: SAP's document has it more than once.");
		}
		return xml.substring(0, start) + "<" + prefix + field + attrs + ">" + AdtXml.escape(value) + "</" + prefix
				+ field + ">" + xml.substring(end);
	}
}

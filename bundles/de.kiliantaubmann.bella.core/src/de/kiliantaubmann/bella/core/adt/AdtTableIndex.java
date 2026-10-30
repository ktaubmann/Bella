package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Secondary indexes of transparent tables (TABL/DTI), named
 * {@code TABLE~ID}: server-driven objects with AFF JSON source that belong
 * to their table. Contract follows ARC-1 ({@code src/adt/table-index.ts},
 * {@code src/adt/server-driven.ts}, MIT), verified there on 7.58, 8.16 and
 * BTP; 7.50 has no service for them.
 */
final class AdtTableIndex {

	static final String COLLECTION = "/sap/bc/adt/ddic/db/indexes";
	private static final String TREE = "application/vnd.sap.as+xml;charset=UTF-8;dataname=com.sap.adt.RepositoryObjectTreeContent";

	private AdtTableIndex() {
	}

	/** {table, id} of {@code TABLE~ID}; the ID has one to three letters or digits. */
	static String[] parse(String name) throws AdtException {
		String[] parts = name == null ? new String[0] : name.trim().toUpperCase(Locale.ROOT).split("~", -1);
		if (parts.length != 2 || !parts[1].matches("[A-Z0-9]{1,3}")
				|| !parts[0].matches("(?:/[A-Z0-9_]+/)?[A-Z][A-Z0-9_]*") || parts[0].length() > 30) {
			throw new AdtException(400, "An index is named TABLE~ID, e.g. ZORDERS~001 (ID: 1 to 3 letters or digits); "
					+ "got '" + name + "'.");
		}
		return parts;
	}

	/** Object URI: lower case with a plain {@code ~}, as ARC-1 sends it. */
	static String uri(String name) {
		return COLLECTION + "/" + AdtObjectRef.encodeName(name.trim()).replace("%7E", "~");
	}

	static String tableUri(String name) throws AdtException {
		return "/sap/bc/adt/ddic/tables/" + AdtObjectRef.encodeName(parse(name)[0]);
	}

	/** The reference to the table the create body carries after the package. */
	static String containerRef(String name) throws AdtException {
		String table = parse(name)[0];
		return "\n  <adtcore:containerRef adtcore:name=\"" + AdtXml.escape(table) + "\" adtcore:type=\"TABL/DT\" "
				+ "adtcore:uri=\"" + AdtXml.escape(tableUri(name)) + "\"/>";
	}

	/**
	 * The indexes of a table from the repository tree. The table is read
	 * first, so a missing table is not a table without indexes.
	 */
	static String list(AdtClient c, String table, CancelToken cancel) throws IOException {
		String t = table.trim().toUpperCase(Locale.ROOT);
		c.readMetadata("/sap/bc/adt/ddic/tables/" + AdtObjectRef.encodeName(t), cancel);
		AdtResponse r = c.exchange(AdtRequest.post("/sap/bc/adt/repository/nodestructure?parent_type=TABL%2FDT"
				+ "&parent_name=" + AdtClient.enc(t) + "&withShortDescriptions=true", TREE, "", "application/xml"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not list the indexes of " + t + ": " + AdtErrors.message(r));
		}
		String xml = r.body();
		List<String> lines = new ArrayList<>();
		Document d = AdtXml.parse(xml);
		if (AdtXml.elements(d, "TREE_CONTENT").isEmpty()) {
			throw new AdtException(500, "SAP's answer lists no repository tree for " + t + ".");
		}
		for (Element node : AdtXml.elements(d, "SEU_ADT_REPOSITORY_OBJ_NODE")) {
			if (!"TABL/DTI".equals(child(node, "OBJECT_TYPE"))) {
				continue;
			}
			// OBJECT_NAME is the ID; TECH_NAME is padded and not split
			String id = child(node, "OBJECT_NAME").toUpperCase(Locale.ROOT);
			String descr = child(node, "DESCRIPTION");
			lines.add("- " + t + "~" + id + (descr.isEmpty() ? "" : ": " + descr));
		}
		return lines.isEmpty() ? "Table " + t + " has no secondary indexes."
				: "Secondary indexes of " + t + " (read one with type TABL/DTI):\n" + String.join("\n", lines);
	}

	private static String child(Element e, String localName) {
		List<Element> found = AdtXml.elements(e, localName);
		return found.isEmpty() ? "" : AdtXml.text(found.get(0));
	}
}

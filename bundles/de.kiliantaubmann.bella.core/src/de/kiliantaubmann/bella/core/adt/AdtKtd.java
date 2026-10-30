package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writing knowledge transfer documents (SKTD): Markdown documentation of an
 * object, one base64 text per documented node ({@code <sktd:element>}) of
 * the object's {@code <sktd:docu>} envelope. A KTD is created for an
 * existing object and carries its name; the text is written into the
 * envelope SAP returns, changing nothing else. Envelope and parent types
 * follow ARC-1 ({@code src/handlers/write/create.ts}, {@code src/adt/ddic-xml.ts},
 * MIT), verified there on 7.58 and 8.16; SAP has KTDs from 7.55.
 */
final class AdtKtd {

	static final String TYPE = "application/vnd.sap.adt.sktdv2+xml";
	static final String COLLECTION = "/sap/bc/adt/documentation/ktd/documents";

	/**
	 * Parents a KTD can document, with their ADT collection. SAP registers
	 * more, but posting a parent without a documentation handler (classes,
	 * interfaces, programs) ends in a short dump.
	 */
	private static final Map<String, String> PARENTS = new LinkedHashMap<>();
	static {
		PARENTS.put("DDLS/DF", "/sap/bc/adt/ddic/ddl/sources/");
		PARENTS.put("BDEF/BDO", "/sap/bc/adt/bo/behaviordefinitions/");
		PARENTS.put("SRVD/SRV", "/sap/bc/adt/ddic/srvd/sources/");
		PARENTS.put("SRVB/SVB", "/sap/bc/adt/businessservices/bindings/");
		PARENTS.put("DEVC/K", "/sap/bc/adt/packages/");
	}

	private AdtKtd() {
	}

	/** The parent type as SAP names it, e.g. DDLS/DF for DDLS or CDS. */
	static String parentType(String type) throws AdtException {
		String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
		String full = switch (t) {
		case "DDLS", "CDS" -> "DDLS/DF";
		case "BDEF" -> "BDEF/BDO";
		case "SRVD" -> "SRVD/SRV";
		case "SRVB" -> "SRVB/SVB";
		case "DEVC", "PACKAGE" -> "DEVC/K";
		default -> t;
		};
		if (PARENTS.containsKey(full)) {
			return full;
		}
		if (full.matches("(CLAS|INTF|PROG)(/.*)?")) {
			throw new AdtException(400, "Classes, interfaces and programs have no knowledge transfer documents "
					+ "(SAP dumps on them); document them with ABAP Doc in the source.");
		}
		throw new AdtException(400, "Give 'documents_type', the type of the object the KTD documents: "
				+ String.join(", ", PARENTS.keySet()) + (full.isEmpty() ? "." : "; got " + full + "."));
	}

	/**
	 * The create body without XML declaration: an empty document referencing
	 * its parent, which has the same name.
	 */
	static String createXml(String name, String pkg, String language, String parentType) {
		String n = name.trim().toUpperCase(Locale.ROOT);
		return "<sktd:docu xmlns:sktd=\"http://www.sap.com/wbobj/texts/sktd\""
				+ " xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:language=\"" + x(language) + "\" adtcore:name=\""
				+ x(n) + "\" adtcore:type=\"SKTD/TYP\" adtcore:masterLanguage=\"" + x(language) + "\">\n"
				+ "  <adtcore:packageRef adtcore:name=\"" + x(pkg) + "\"/>\n"
				// without adtcore:description: an empty one fails "Check of condition failed"
				+ "  <sktd:refObject adtcore:name=\"" + x(n) + "\" adtcore:type=\"" + x(parentType) + "\" adtcore:uri=\""
				+ x(PARENTS.get(parentType) + AdtObjectRef.encodeName(n)) + "\"/>\n</sktd:docu>";
	}

	/** One {@code <sktd:element>} of the raw envelope, located for a splice that keeps everything else. */
	private record Node(String id, int start, int end, String xml) {
	}

	private static final Pattern ELEMENT = Pattern
			.compile("<sktd:element\\b(?:[^>]*/>|[\\s\\S]*?</sktd:element>)");
	private static final Pattern ID = Pattern.compile("<sktd:id>([\\s\\S]*?)</sktd:id>");
	private static final Pattern TEXT = Pattern.compile("<sktd:text([^>]*)>([\\s\\S]*?)</sktd:text>|<sktd:text([^>]*)/>");
	private static final Pattern NAME = Pattern.compile("<sktd:docu\\b[^>]*\\badtcore:name=\"([^\"]*)\"");
	private static final Pattern HEADING = Pattern.compile("(?m)^## (.+?)\\s*$");

	private static List<Node> nodes(String xml) {
		List<Node> out = new ArrayList<>();
		Matcher m = ELEMENT.matcher(xml);
		while (m.find()) {
			Matcher id = ID.matcher(m.group());
			out.add(new Node(id.find() ? id.group(1).trim() : "", m.start(), m.end(), m.group()));
		}
		return out;
	}

	private static boolean documented(String xml) {
		Matcher t = TEXT.matcher(xml);
		return t.find() && t.group(2) != null && !t.group(2).isBlank();
	}

	/**
	 * The envelope with new Markdown. Text under a {@code ## <node id>}
	 * heading (as {@code adt_read_source} shows several documented nodes)
	 * goes to that node; text without such headings to the one documented
	 * node, or to the object's own node when none is documented yet.
	 */
	static String rewrite(String envelope, String markdown) throws IOException {
		if (markdown == null || markdown.isBlank()) {
			throw new AdtException(400, "The documentation is empty; Bella does not erase a KTD with it. To clear one "
					+ "node, give its \"## <node id>\" heading with nothing below.");
		}
		List<Node> nodes = nodes(envelope);
		Map<Node, String> texts = split(markdown, nodes);
		if (texts == null) {
			List<Node> withText = nodes.stream().filter(n -> documented(n.xml())).toList();
			if (withText.size() > 1) {
				throw new AdtException(400, "This KTD documents " + withText.size() + " nodes; start each part with "
						+ "\"## <node id>\", as adt_read_source shows them: " + String.join(", ",
								withText.stream().map(Node::id).toList()) + ".");
			}
			if (nodes.isEmpty()) {
				return setText(envelope, markdown);
			}
			Matcher name = NAME.matcher(envelope);
			String own = name.find() ? name.group(1) : "";
			Node target = !withText.isEmpty() ? withText.get(0)
					: nodes.stream().filter(n -> n.id().equalsIgnoreCase(own)).findFirst().orElse(nodes.get(0));
			texts = Map.of(target, markdown);
		}
		StringBuilder sb = new StringBuilder(envelope);
		List<Map.Entry<Node, String>> byPosition = new ArrayList<>(texts.entrySet());
		byPosition.sort((a, b) -> Integer.compare(b.getKey().start(), a.getKey().start()));
		for (Map.Entry<Node, String> e : byPosition) {
			sb.replace(e.getKey().start(), e.getKey().end(), setText(e.getKey().xml(), e.getValue()));
		}
		AdtXml.parse(sb.toString()); // never PUT a broken envelope
		return sb.toString();
	}

	/** Markdown split at headings that name a node, {@code null} without any. */
	private static Map<Node, String> split(String markdown, List<Node> nodes) throws AdtException {
		List<int[]> cuts = new ArrayList<>();
		List<Node> targets = new ArrayList<>();
		Matcher h = HEADING.matcher(markdown);
		while (h.find()) {
			Node n = node(h.group(1).trim(), nodes);
			if (n != null) {
				cuts.add(new int[] { h.start(), h.end() });
				targets.add(n);
			}
		}
		if (targets.isEmpty()) {
			return null;
		}
		if (!markdown.substring(0, cuts.get(0)[0]).isBlank()) {
			throw new AdtException(400, "Text before the first \"## <node id>\" heading belongs to no node; put it "
					+ "under the heading of the node it documents.");
		}
		Map<Node, String> out = new LinkedHashMap<>();
		for (int i = 0; i < targets.size(); i++) {
			int end = i + 1 < cuts.size() ? cuts.get(i + 1)[0] : markdown.length();
			if (out.put(targets.get(i), markdown.substring(cuts.get(i)[1], end).strip()) != null) {
				throw new AdtException(400, "Node " + targets.get(i).id() + " appears twice.");
			}
		}
		return out;
	}

	/** The node a heading names: its exact id, else a unique case-insensitive match. */
	private static Node node(String heading, List<Node> nodes) {
		for (Node n : nodes) {
			if (n.id().equals(heading)) {
				return n;
			}
		}
		List<Node> folded = nodes.stream().filter(n -> !n.id().isEmpty() && n.id().equalsIgnoreCase(heading)).toList();
		return folded.size() == 1 ? folded.get(0) : null;
	}

	private static String setText(String xml, String markdown) throws AdtException {
		Matcher t = TEXT.matcher(xml);
		if (!t.find()) {
			throw new AdtException(400, "This node of the KTD takes no documentation text.");
		}
		String attrs = t.group(1) != null ? t.group(1) : t.group(3);
		String encoded = Base64.getEncoder().encodeToString(markdown.getBytes(StandardCharsets.UTF_8));
		return xml.substring(0, t.start()) + "<sktd:text" + attrs + ">" + encoded + "</sktd:text>"
				+ xml.substring(t.end());
	}

	private static String x(String s) {
		return AdtXml.escape(s == null ? "" : s);
	}
}

package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.kiliantaubmann.bella.core.abap.TextDeltas;
import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * ADT's quick fixes and pretty printer. Requests and responses follow ARC-1
 * ({@code src/adt/devtools.ts}, MIT).
 */
final class AdtQuickfix {

	private static final String PROPOSAL_PREFIX = "/sap/bc/adt/quickfixes/";

	private AdtQuickfix() {
	}

	/** One quick fix SAP proposes for a source position. */
	record Proposal(String uri, String name, String description, String userContent, String affectedObjects) {
	}

	/** A replacement in one source, as the quick fix returns it. */
	record Delta(String uri, TextDeltas.Delta delta) {
	}

	/** Proposals for the position (line from 1, column from 0) in {@code source}; empty when there are none. */
	static List<Proposal> proposals(AdtClient c, String sourceUri, String source, int line, int column,
			CancelToken cancel) throws IOException {
		String path = "/sap/bc/adt/quickfixes/evaluation?uri=" + AdtClient.enc(sourceUri + "#start=" + line + "," + column);
		AdtResponse r = c.exchange(AdtRequest.post(path, "application/*", source, "application/*"), cancel);
		if (r.status() == 404 || r.status() == 406) {
			// no quick fix service on this release, or nothing at this position
			return List.of();
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		return parseProposals(r.body());
	}

	static List<Proposal> parseProposals(String xml) throws IOException {
		List<Proposal> out = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return out;
		}
		for (Element result : AdtXml.elements(AdtXml.parse(xml), "evaluationResult")) {
			Element ref = child(result, "objectReference");
			if (ref == null || AdtXml.attr(ref, "uri").isEmpty()) {
				continue;
			}
			Element user = child(result, "userContent");
			Element affected = child(result, "affectedObjects");
			out.add(new Proposal(AdtXml.attr(ref, "uri"), AdtXml.attr(ref, "name"), AdtXml.attr(ref, "description"),
					user == null ? null : user.getTextContent(), affected == null ? null : affectedUnits(affected)));
		}
		return out;
	}

	/** The units of affected objects that carry content, serialised for the apply request. */
	private static String affectedUnits(Element affected) {
		StringBuilder sb = new StringBuilder();
		for (Element unit : AdtXml.elements(affected, "unit")) {
			Element ref = child(unit, "objectReference");
			Element content = child(unit, "content");
			if (ref == null || content == null) {
				continue;
			}
			sb.append("\n    <unit>\n      <content>").append(AdtXml.escape(content.getTextContent()))
					.append("</content>\n      <adtcore:objectReference adtcore:uri=\"")
					.append(AdtXml.escape(AdtXml.attr(ref, "uri"))).append('"');
			for (String a : List.of("type", "name", "description")) {
				if (!AdtXml.attr(ref, a).isEmpty()) {
					sb.append(" adtcore:").append(a).append("=\"").append(AdtXml.escape(AdtXml.attr(ref, a))).append('"');
				}
			}
			sb.append("/>\n    </unit>");
		}
		return sb.isEmpty() ? null : "\n  <affectedObjects>" + sb + "\n  </affectedObjects>";
	}

	/** Runs a proposal and returns the replacements it makes; the source is not written. */
	static List<Delta> apply(AdtClient c, Proposal p, String sourceUri, String source, int line, int column,
			CancelToken cancel) throws IOException {
		if (!p.uri().startsWith(PROPOSAL_PREFIX) || p.uri().contains("..")) {
			throw new AdtException(400, "Not a quick fix proposal: " + p.uri());
		}
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<quickfixes:proposalRequest xmlns:quickfixes=\"http://www.sap.com/adt/quickfixes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">\n"
				+ "  <input>\n    <content>" + AdtXml.escape(source) + "</content>\n"
				+ "    <adtcore:objectReference adtcore:uri=\"" + AdtXml.escape(sourceUri + "#start=" + line + "," + column)
				+ "\"/>\n  </input>" + (p.affectedObjects() == null ? "" : p.affectedObjects())
				+ (p.userContent() == null ? "" : "\n  <userContent>" + AdtXml.escape(p.userContent()) + "</userContent>")
				+ "\n</quickfixes:proposalRequest>";
		AdtResponse r = c.exchange(AdtRequest.post(p.uri(), "application/xml", body, "application/xml"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "The quick fix failed: " + AdtErrors.message(r));
		}
		return parseDeltas(r.body());
	}

	private static final Pattern START = Pattern.compile("[#;&]start=(\\d+),(\\d+)");
	private static final Pattern END = Pattern.compile("[#;&]end=(\\d+),(\\d+)");

	static List<Delta> parseDeltas(String xml) throws IOException {
		List<Delta> out = new ArrayList<>();
		if (xml == null || xml.isBlank()) {
			return out;
		}
		List<Element> units = new ArrayList<>();
		for (Element deltas : AdtXml.elements(AdtXml.parse(xml), "deltas")) {
			units.addAll(AdtXml.elements(deltas, "unit"));
		}
		for (Element unit : units) {
			Element ref = child(unit, "objectReference");
			String uri = ref != null ? AdtXml.attr(ref, "uri") : AdtXml.attr(unit, "uri");
			Element content = child(unit, "content");
			Matcher s = START.matcher(uri);
			if (!s.find()) {
				continue;
			}
			Matcher e = END.matcher(uri);
			boolean hasEnd = e.find();
			int sl = Integer.parseInt(s.group(1));
			int sc = Integer.parseInt(s.group(2));
			int el = hasEnd ? Integer.parseInt(e.group(1)) : sl;
			int ec = hasEnd ? Integer.parseInt(e.group(2)) : sc;
			out.add(new Delta(uri, new TextDeltas.Delta(sl, sc, el, ec, content == null ? "" : content.getTextContent())));
		}
		return out;
	}

	/** Whether a delta belongs to the source at {@code sourceUri} (compared without fragment and case). */
	static boolean sameSource(String deltaUri, String sourceUri) {
		String d = deltaUri.contains("#") ? deltaUri.substring(0, deltaUri.indexOf('#')) : deltaUri;
		return d.toLowerCase(Locale.ROOT).equals(sourceUri.toLowerCase(Locale.ROOT));
	}

	private static Element child(Element parent, String localName) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && localName.equals(e.getLocalName() == null ? e.getNodeName() : e.getLocalName())) {
				return e;
			}
		}
		return null;
	}

	// ---- pretty printer --------------------------------------------------------

	static final String SETTINGS_TYPE = "application/vnd.sap.adt.ppsettings.v2+xml";

	/** The source formatted by the system's pretty printer with its settings. */
	static String prettyPrint(AdtClient c, String source, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.post("/sap/bc/adt/abapsource/prettyprinter", "text/plain", source,
				"text/plain; charset=utf-8"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "The pretty printer failed: " + AdtErrors.message(r));
		}
		return r.body();
	}

	/** Indentation on/off and keyword style (keywordUpper, keywordLower, keywordAuto, none). */
	record Settings(boolean indentation, String style) {
	}

	static Settings settings(AdtClient c, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.get("/sap/bc/adt/abapsource/prettyprinter/settings", SETTINGS_TYPE),
				cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		Element root = AdtXml.parse(r.body()).getDocumentElement();
		String indentation = AdtXml.attr(root, "indentation");
		String style = AdtXml.attr(root, "style");
		return new Settings(!"false".equalsIgnoreCase(indentation), STYLES.contains(style) ? style : "keywordUpper");
	}

	static final List<String> STYLES = List.of("keywordUpper", "keywordLower", "keywordAuto", "none");

	static void writeSettings(AdtClient c, Settings s, CancelToken cancel) throws IOException {
		String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><abapformatter:PrettyPrinterSettings abapformatter:indentation=\""
				+ s.indentation() + "\" abapformatter:style=\"" + s.style()
				+ "\" xmlns:abapformatter=\"http://www.sap.com/adt/prettyprintersettings\"/>";
		AdtResponse r = c.exchange(AdtRequest.put("/sap/bc/adt/abapsource/prettyprinter/settings", body, SETTINGS_TYPE),
				cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), "Could not save the pretty printer settings: " + AdtErrors.message(r));
		}
	}
}

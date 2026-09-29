package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Namespace-agnostic XML helpers for ADT responses; external entities are disabled. */
public final class AdtXml {

	private AdtXml() {
	}

	public static Document parse(String xml) throws IOException {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setExpandEntityReferences(false);
			DocumentBuilder b = f.newDocumentBuilder();
			return b.parse(new InputSource(new StringReader(xml)));
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("Unexpected ADT response: " + e.getMessage(), e);
		}
	}

	/** All elements with the given local name, in document order. */
	public static List<Element> elements(Node root, String localName) {
		List<Element> out = new ArrayList<>();
		NodeList list = root instanceof Document d ? d.getElementsByTagNameNS("*", localName)
				: ((Element) root).getElementsByTagNameNS("*", localName);
		for (int i = 0; i < list.getLength(); i++) {
			out.add((Element) list.item(i));
		}
		return out;
	}

	/** Attribute by local name regardless of namespace prefix; empty string if absent. */
	public static String attr(Element e, String localName) {
		var attrs = e.getAttributes();
		for (int i = 0; i < attrs.getLength(); i++) {
			Node a = attrs.item(i);
			String ln = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
			if (ln.equals(localName) || ln.endsWith(":" + localName)) {
				return a.getNodeValue();
			}
		}
		return "";
	}

	public static String text(Element e) {
		return e.getTextContent() == null ? "" : e.getTextContent().trim();
	}

	/** Attributes that carry no meaning for the model. */
	private static final Set<String> NOISE_ATTRIBUTES = Set.of("uri", "href", "rel", "etag", "changedAt", "changedBy",
			"createdAt", "createdBy", "version", "masterLanguage", "masterSystem", "responsible", "language",
			"contentType", "abapLanguageVersion", "descriptionTextLimit", "lang", "parentUri");
	private static final Set<String> NOISE_ELEMENTS = Set.of("link", "packageRef", "adtTemplate", "syntaxConfiguration");

	/**
	 * Compact text of an ADT object document (data element, domain, table type,
	 * message class, …): one line per element with its meaningful attributes
	 * and leaf text, indented by depth, without namespaces, links and
	 * change metadata. Text that is not XML is returned as it is.
	 */
	public static String summarize(String xml, int maxChars) {
		Document doc;
		try {
			doc = parse(xml);
		} catch (IOException e) {
			return truncate(xml == null ? "" : xml.trim(), maxChars);
		}
		StringBuilder sb = new StringBuilder();
		summarize(doc.getDocumentElement(), 0, sb, maxChars);
		return truncate(sb.toString().stripTrailing(), maxChars);
	}

	private static void summarize(Element e, int depth, StringBuilder sb, int maxChars) {
		if (sb.length() > maxChars) {
			return;
		}
		String name = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
		if (NOISE_ELEMENTS.contains(name)) {
			return;
		}
		StringBuilder line = new StringBuilder();
		var attrs = e.getAttributes();
		for (int i = 0; i < attrs.getLength(); i++) {
			Node a = attrs.item(i);
			String an = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
			String qn = a.getNodeName();
			if (qn.equals("xmlns") || qn.startsWith("xmlns:") || NOISE_ATTRIBUTES.contains(an)
					|| a.getNodeValue().isBlank()) {
				continue;
			}
			line.append(line.isEmpty() ? " " : ", ").append(an).append('=').append(a.getNodeValue().trim());
		}
		List<Element> children = new ArrayList<>();
		NodeList nodes = e.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element c) {
				children.add(c);
			}
		}
		String text = children.isEmpty() ? text(e) : "";
		if (line.isEmpty() && text.isEmpty() && children.isEmpty()) {
			return;
		}
		sb.append("  ".repeat(depth)).append(name);
		if (!text.isEmpty()) {
			sb.append(": ").append(text.replaceAll("\\s+", " "));
		}
		sb.append(line).append('\n');
		for (Element c : children) {
			summarize(c, depth + 1, sb, maxChars);
		}
	}

	static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "\n…";
	}

	public static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}

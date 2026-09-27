package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

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

	public static String escape(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}

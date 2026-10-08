package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Code navigation, release state of APIs and program variants through ADT.
 * Requests follow ARC-1 ({@code src/adt/codeintel.ts}, {@code src/adt/client.ts},
 * MIT).
 */
final class AdtCodeIntel {

	private AdtCodeIntel() {
	}

	/** Where a symbol at a position is defined, or {@code null}. */
	record Target(String uri, String type, String name, int line, int column) {
	}

	static Target definition(AdtClient c, String sourceUri, String source, int line, int column, CancelToken cancel)
			throws IOException {
		// ADT reads the position from the uri fragment; separate line/column parameters are rejected
		String path = "/sap/bc/adt/navigation/target?uri=" + AdtClient.enc(sourceUri + "#start=" + line + "," + column)
				+ "&filter=definition";
		AdtResponse r = c.exchange(AdtRequest.post(path, "application/xml", source, "text/plain"), cancel);
		if (r.status() == 404) {
			return null;
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		Document d = AdtXml.parse(r.body());
		List<Element> refs = AdtXml.elements(d, "objectReference");
		if (refs.isEmpty()) {
			refs = AdtXml.elements(d, "navigation");
		}
		if (refs.isEmpty() || AdtXml.attr(refs.get(0), "uri").isEmpty()) {
			return null;
		}
		Element e = refs.get(0);
		String uri = AdtXml.attr(e, "uri");
		Matcher m = Pattern.compile("#start=(\\d+),(\\d+)").matcher(uri);
		boolean pos = m.find();
		return new Target(uri, AdtXml.attr(e, "type"), AdtXml.attr(e, "name"), pos ? Integer.parseInt(m.group(1)) : 0,
				pos ? Integer.parseInt(m.group(2)) : 0);
	}

	/** One code completion proposal. */
	record Proposal(String text, String type, String description) {
	}

	static List<Proposal> completion(AdtClient c, String sourceUri, String source, int line, int column,
			CancelToken cancel) throws IOException {
		String path = "/sap/bc/adt/abapsource/codecompletion/proposals?uri=" + AdtClient.enc(sourceUri) + "&line="
				+ line + "&column=" + column;
		AdtResponse r = c.exchange(AdtRequest.post(path, "application/xml", source, "text/plain"), cancel);
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		List<Proposal> out = new ArrayList<>();
		if (r.body() == null || r.body().isBlank()) {
			return out;
		}
		Document d = AdtXml.parse(r.body());
		for (Element e : AdtXml.elements(d, "proposal")) {
			String text = AdtXml.attr(e, "text");
			if (text.isEmpty()) {
				text = childText(e, "IDENTIFIER");
			}
			out.add(new Proposal(text, AdtXml.attr(e, "type"), AdtXml.attr(e, "description")));
		}
		// older releases answer with an asx:abap structure of SCC_COMPLETION entries
		for (Element e : AdtXml.elements(d, "SCC_COMPLETION")) {
			out.add(new Proposal(childText(e, "IDENTIFIER"), childText(e, "KIND"), ""));
		}
		return out;
	}

	private static String childText(Element parent, String localName) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && localName.equals(e.getLocalName() == null ? e.getNodeName() : e.getLocalName())) {
				return e.getTextContent().trim();
			}
		}
		return "";
	}

	/** One release contract (C0 extend, C1 use in key user and cloud apps, C2 remote, C3 config, C4 dev tools). */
	record Contract(String contract, String state, String stateDescription, boolean cloud, boolean keyUser,
			List<String> successors) {
	}

	static List<Contract> releaseState(AdtClient c, String objectUri, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.get("/sap/bc/adt/apireleases/" + AdtClient.enc(objectUri),
				"application/vnd.sap.adt.apirelease.v10+xml"), cancel);
		if (r.status() == 404) {
			return List.of();
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		return parseReleaseState(r.body());
	}

	static List<Contract> parseReleaseState(String xml) throws IOException {
		List<Contract> out = new ArrayList<>();
		Element root = AdtXml.parse(xml).getDocumentElement();
		for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (!(n instanceof Element e)) {
				continue;
			}
			String local = e.getLocalName() == null ? e.getNodeName() : e.getLocalName();
			if (!local.matches("c\\dRelease")) {
				continue;
			}
			String state = "";
			String description = "";
			List<String> successors = new ArrayList<>();
			for (Element s : AdtXml.elements(e, "status")) {
				state = AdtXml.attr(s, "state");
				description = AdtXml.attr(s, "stateDescription");
				break;
			}
			for (Element s : AdtXml.elements(e, "successor")) {
				successors.add(AdtXml.attr(s, "name"));
			}
			String contract = AdtXml.attr(e, "contract");
			out.add(new Contract(contract.isEmpty() ? local.substring(0, 2).toUpperCase(Locale.ROOT) : contract, state,
					description, "true".equals(AdtXml.attr(e, "useInSAPCloudPlatform")),
					"true".equals(AdtXml.attr(e, "useInKeyUserApps")), successors));
		}
		return out;
	}
}

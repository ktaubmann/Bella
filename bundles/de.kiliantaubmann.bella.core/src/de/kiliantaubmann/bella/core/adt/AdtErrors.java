package de.kiliantaubmann.bella.core.adt;

import java.util.List;

import org.w3c.dom.Element;

/** Extracts the message of an ADT exception response ({@code <exc:exception>}). */
final class AdtErrors {

	private AdtErrors() {
	}

	static String message(AdtResponse r) {
		String body = r.body() == null ? "" : r.body();
		try {
			org.w3c.dom.Document doc = AdtXml.parse(body);
			List<Element> msgs = AdtXml.elements(doc, "message");
			if (!msgs.isEmpty() && !AdtXml.text(msgs.get(0)).isEmpty()) {
				return "HTTP " + r.status() + ": " + AdtXml.text(msgs.get(0));
			}
			List<Element> texts = AdtXml.elements(doc, "localizedMessage");
			if (!texts.isEmpty()) {
				return "HTTP " + r.status() + ": " + AdtXml.text(texts.get(0));
			}
		} catch (Exception e) {
			// not XML
		}
		String plain = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
		return "HTTP " + r.status() + (plain.isEmpty() ? "" : ": " + (plain.length() > 300 ? plain.substring(0, 300) : plain));
	}
}

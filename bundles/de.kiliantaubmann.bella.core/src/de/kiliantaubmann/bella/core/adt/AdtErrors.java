package de.kiliantaubmann.bella.core.adt;

import java.util.List;

import org.w3c.dom.Element;

/** Extracts the message of an ADT exception response ({@code <exc:exception>}). */
final class AdtErrors {

	private AdtErrors() {
	}

	static String message(AdtResponse r) {
		String m = text(r);
		return r.status() == 423 && m.toLowerCase(java.util.Locale.ROOT).contains("lock handle") ? m + LOCK_HANDLE_HINT
				: m;
	}

	/**
	 * Why SAP does not know a lock handle it has just issued: the requests
	 * did not share one ABAP session. Releases below 7.51 ignore the stateful
	 * session header over plain HTTP (as ARC-1 found).
	 */
	static final String LOCK_HANDLE_HINT = " (SAP no longer knows the lock: lock and write did not run in one "
			+ "ABAP session. Releases below SAP_BASIS 7.51 ignore stateful sessions over plain HTTP.)";

	private static String text(AdtResponse r) {
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

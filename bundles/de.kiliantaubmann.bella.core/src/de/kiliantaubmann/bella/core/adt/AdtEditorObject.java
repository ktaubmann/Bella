package de.kiliantaubmann.bella.core.adt;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The repository object behind an ADT editor.
 *
 * @param destinationId system the object belongs to
 * @param uri           ADT URI, e.g. {@code /sap/bc/adt/oo/classes/zcl_order}
 * @param name          object name, upper case
 * @param type          ADT type, e.g. {@code CLAS/OC}
 */
public record AdtEditorObject(String destinationId, String uri, String name, String type) {

	/**
	 * Builds the object from what ADT reports for an editor. Class editors
	 * report the include they show ({@code CLAS/I}) with an empty name; the
	 * name and the class type then come from the URI.
	 *
	 * @return {@code null} when neither the reference nor the URI names an object
	 */
	public static AdtEditorObject of(String destinationId, String uri, String name, String type) {
		if (uri == null) {
			return null;
		}
		String n = name == null ? "" : name.trim();
		String t = type;
		if (n.isEmpty()) {
			String objectUri = AdtObjectRef.objectUri(uri);
			int slash = objectUri.lastIndexOf('/');
			n = URLDecoder.decode(objectUri.substring(slash + 1), StandardCharsets.UTF_8).trim();
			if (n.isEmpty()) {
				return null;
			}
			if (objectUri.contains("/oo/classes/")) {
				t = "CLAS/OC";
			} else if (objectUri.contains("/oo/interfaces/")) {
				t = "INTF/OI";
			}
		}
		return new AdtEditorObject(destinationId, uri, n.toUpperCase(Locale.ROOT), t);
	}
}

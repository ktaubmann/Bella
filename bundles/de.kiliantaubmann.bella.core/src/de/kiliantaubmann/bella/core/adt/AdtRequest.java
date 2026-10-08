package de.kiliantaubmann.bella.core.adt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ADT REST request.
 *
 * @param method      GET, POST, PUT, DELETE
 * @param path        path plus query, e.g. {@code /sap/bc/adt/oo/classes/zcl_x/source/main}
 * @param headers     extra headers (Accept etc.)
 * @param body        request body or {@code null}
 * @param contentType content type of the body or {@code null}
 */
public record AdtRequest(String method, String path, Map<String, String> headers, String body, String contentType) {

	public static AdtRequest get(String path, String accept) {
		return new AdtRequest("GET", path, accept(accept), null, null);
	}

	public static AdtRequest post(String path, String accept, String body, String contentType) {
		return new AdtRequest("POST", path, accept(accept), body, contentType);
	}

	public static AdtRequest put(String path, String body, String contentType) {
		return new AdtRequest("PUT", path, new LinkedHashMap<>(), body, contentType);
	}

	public static AdtRequest delete(String path) {
		return new AdtRequest("DELETE", path, new LinkedHashMap<>(), null, null);
	}

	/** A copy of this request with one more header. */
	public AdtRequest withHeader(String name, String value) {
		Map<String, String> h = new LinkedHashMap<>(headers);
		h.put(name, value);
		return new AdtRequest(method, path, h, body, contentType);
	}

	private static Map<String, String> accept(String accept) {
		Map<String, String> h = new LinkedHashMap<>();
		if (accept != null) {
			h.put("Accept", accept);
		}
		return h;
	}
}

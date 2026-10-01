package de.kiliantaubmann.bella.core.adt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Response of an ADT REST request. Error statuses are returned, not thrown.
 *
 * @param headers response headers that Bella uses (e.g. {@code etag}), names in lower case
 */
public record AdtResponse(int status, String contentType, String body, Map<String, String> headers) {

	public AdtResponse {
		Map<String, String> lower = new LinkedHashMap<>();
		if (headers != null) {
			headers.forEach((k, v) -> {
				if (k != null && v != null) {
					lower.put(k.toLowerCase(Locale.ROOT), v);
				}
			});
		}
		headers = Map.copyOf(lower);
	}

	public AdtResponse(int status, String contentType, String body) {
		this(status, contentType, body, Map.of());
	}

	public boolean ok() {
		return status >= 200 && status < 300;
	}

	/** Header value or {@code null}; the name is case-insensitive. */
	public String header(String name) {
		return headers.get(name.toLowerCase(Locale.ROOT));
	}
}

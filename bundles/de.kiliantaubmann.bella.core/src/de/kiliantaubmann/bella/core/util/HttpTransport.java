package de.kiliantaubmann.bella.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal HTTP abstraction so the providers and the MCP HTTP transport can be
 * tested with canned responses instead of a network.
 */
public interface HttpTransport {

	/** Sends a POST with the given headers and body. The caller closes the body stream. */
	Response post(URI uri, Map<String, String> headers, String body, CancelToken cancel) throws IOException;

	/** Sends a DELETE (used to end MCP sessions). */
	default void delete(URI uri, Map<String, String> headers) throws IOException {
	}

	interface Response extends AutoCloseable {
		int status();

		Optional<String> header(String name);

		InputStream body();

		@Override
		void close() throws IOException;
	}

	/** Scheme, host and path; user info and query may carry secrets and are left out. */
	static String display(URI uri) {
		return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
				+ (uri.getPath() == null ? "" : uri.getPath());
	}

	static HttpTransport jdk() {
		return new JdkHttpTransport();
	}

	/** Convenience used by error paths: headers that carry several values. */
	static String first(Map<String, List<String>> headers, String name) {
		for (Map.Entry<String, List<String>> e : headers.entrySet()) {
			if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
				return e.getValue().get(0);
			}
		}
		return null;
	}
}

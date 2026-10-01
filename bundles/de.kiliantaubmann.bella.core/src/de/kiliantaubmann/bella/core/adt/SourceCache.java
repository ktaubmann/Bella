package de.kiliantaubmann.bella.core.adt;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Source texts read through ADT, keyed by system and path, with the ETag SAP
 * sent along. A repeated read asks SAP with {@code If-None-Match}; on
 * {@code 304 Not Modified} the cached text is used, so the cache never serves
 * stale code. Writes and activations drop the object's entries.
 */
public final class SourceCache {

	/** Cached text and the ETag it was sent with. */
	public record Entry(String etag, String text) {
	}

	static final int MAX_ENTRIES = 200;

	private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
			return size() > MAX_ENTRIES;
		}
	};

	private static String key(String scope, String path) {
		return scope + "|" + path.toLowerCase(Locale.ROOT);
	}

	public synchronized Entry get(String scope, String path) {
		return entries.get(key(scope, path));
	}

	public synchronized void put(String scope, String path, String etag, String text) {
		entries.put(key(scope, path), new Entry(etag, text));
	}

	/** Drops every cached path of the object (all includes and versions). */
	public synchronized void invalidate(String scope, String objectUri) {
		String prefix = key(scope, AdtObjectRef.objectUri(objectUri));
		entries.keySet().removeIf(k -> k.equals(prefix) || k.startsWith(prefix + "/") || k.startsWith(prefix + "?"));
	}

	public synchronized int size() {
		return entries.size();
	}
}

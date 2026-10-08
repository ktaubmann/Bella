package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Release and kind (on-premise or ABAP Cloud) of each system, read once per
 * Eclipse session from its installed software components and shared by the
 * chat tools and the editor actions.
 */
public final class AdtSystemInfo {

	private static final Map<String, AdtClient.SystemInfo> KNOWN = new ConcurrentHashMap<>();
	/** When asking a system failed; it is not asked again for a while (old systems lack the endpoint). */
	private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
	static final long RETRY_AFTER_MILLIS = 10 * 60_000L;

	private AdtSystemInfo() {
	}

	/** Empty if the system does not tell (e.g. the endpoint is missing or the call fails). */
	public static Optional<AdtClient.SystemInfo> of(String destinationId, AdtClient client, CancelToken cancel) {
		AdtClient.SystemInfo known = KNOWN.get(destinationId);
		if (known != null) {
			return Optional.of(known);
		}
		Long failed = FAILED.get(destinationId);
		if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MILLIS) {
			return Optional.empty();
		}
		try {
			AdtClient.SystemInfo info = client.systemInfo(cancel);
			KNOWN.put(destinationId, info);
			FAILED.remove(destinationId);
			return Optional.of(info);
		} catch (IOException e) {
			FAILED.put(destinationId, System.currentTimeMillis());
			return Optional.empty();
		}
	}

	/** What is known already, without asking the system; for callers that must not wait (UI thread). */
	public static Optional<AdtClient.SystemInfo> known(String destinationId) {
		return Optional.ofNullable(destinationId == null ? null : KNOWN.get(destinationId));
	}

	static void clear() {
		KNOWN.clear();
		FAILED.clear();
	}
}

package de.kiliantaubmann.bella.ui.prefs;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import de.kiliantaubmann.bella.ui.BellaPlugin;

/** API keys and tokens in Eclipse secure storage (encrypted, per user). */
public final class SecureStore {

	public static final String ANTHROPIC_KEY = "anthropic.apiKey";
	public static final String OPENAI_KEY = "openai.apiKey";

	private static final String NODE = "de.kiliantaubmann.bella";

	private SecureStore() {
	}

	private static ISecurePreferences node() {
		return SecurePreferencesFactory.getDefault().node(NODE);
	}

	public static String get(String key) {
		try {
			return node().get(key, "");
		} catch (StorageException e) {
			BellaPlugin.log("Cannot read secure storage", e);
			return "";
		}
	}

	public static void put(String key, String value) {
		try {
			ISecurePreferences node = node();
			if (value == null || value.isEmpty()) {
				node.remove(key);
			} else {
				node.put(key, value, true);
			}
			node.flush();
		} catch (StorageException | java.io.IOException e) {
			BellaPlugin.log("Cannot write secure storage", e);
		}
	}

	public static String mcpTokenKey(String serverId) {
		return "mcp." + serverId + ".token";
	}
}

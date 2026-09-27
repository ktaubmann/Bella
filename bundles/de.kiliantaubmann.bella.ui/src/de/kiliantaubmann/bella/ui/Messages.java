package de.kiliantaubmann.bella.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import de.kiliantaubmann.bella.ui.prefs.Prefs;

/**
 * UI texts from {@code messages*.properties} (UTF-8). The language follows
 * Eclipse unless it is overridden in Bella's preferences.
 */
public final class Messages {

	private static final String BUNDLE = "de.kiliantaubmann.bella.ui.messages";
	private static volatile ResourceBundle bundle;

	private Messages() {
	}

	public static Locale locale() {
		String tag = BellaPlugin.getDefault() == null ? ""
				: BellaPlugin.getDefault().getPreferenceStore().getString(Prefs.UI_LANGUAGE);
		return tag == null || tag.isBlank() ? Locale.getDefault() : Locale.forLanguageTag(tag);
	}

	private static ResourceBundle bundle() {
		ResourceBundle b = bundle;
		if (b == null) {
			b = ResourceBundle.getBundle(BUNDLE, locale(), Messages.class.getClassLoader(),
					ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
			bundle = b;
		}
		return b;
	}

	/** Forget the cached bundle after the language preference changed. */
	public static void reload() {
		bundle = null;
		ResourceBundle.clearCache(Messages.class.getClassLoader());
	}

	public static String get(String key) {
		try {
			return bundle().getString(key);
		} catch (MissingResourceException e) {
			return '!' + key + '!';
		}
	}

	public static String fmt(String key, Object... args) {
		return new MessageFormat(get(key), locale()).format(args);
	}
}

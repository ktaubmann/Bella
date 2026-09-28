package de.kiliantaubmann.bella.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import de.kiliantaubmann.bella.ui.prefs.Prefs;

/**
 * UI texts from {@code messages*.properties} (UTF-8) and, for menus and view
 * titles, {@code plugin*.properties}. The language follows the language
 * Eclipse actually shows unless it is overridden in Bella's preferences.
 */
public final class Messages {

	private static final String BUNDLE = "de.kiliantaubmann.bella.ui.messages";
	private static volatile ResourceBundle bundle;
	private static volatile Properties plugin;
	private static volatile Locale eclipseLocale;

	private Messages() {
	}

	public static Locale locale() {
		String tag = BellaPlugin.getDefault() == null ? ""
				: BellaPlugin.getDefault().getPreferenceStore().getString(Prefs.UI_LANGUAGE);
		return tag == null || tag.isBlank() ? eclipseLocale() : Locale.forLanguageTag(tag);
	}

	/**
	 * The language Eclipse's own menus are in. Eclipse takes the operating
	 * system language, but without a language pack it still shows English;
	 * Bella then uses English too instead of a lone German (or other) plug-in.
	 */
	static Locale eclipseLocale() {
		Locale l = eclipseLocale;
		if (l == null) {
			Locale system = Locale.getDefault();
			l = system.getLanguage().isEmpty() || "en".equals(system.getLanguage())
					|| hasLanguagePack(system.getLanguage()) ? system : Locale.ENGLISH;
			eclipseLocale = l;
		}
		return l;
	}

	private static boolean hasLanguagePack(String language) {
		Bundle workbench = Platform.getBundle("org.eclipse.ui.workbench");
		if (workbench == null) {
			return true; // not inside Eclipse: trust the system locale
		}
		// Language packs (e.g. Babel) are fragments; findEntries also searches fragments.
		return found(workbench.findEntries("/", "plugin_" + language + "*.properties", false))
				|| found(workbench.findEntries("org/eclipse/ui/internal", "messages_" + language + "*.properties", false));
	}

	private static boolean found(Enumeration<URL> e) {
		return e != null && e.hasMoreElements();
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
		plugin = null;
		ResourceBundle.clearCache(Messages.class.getClassLoader());
	}

	public static String get(String key) {
		try {
			return bundle().getString(key);
		} catch (MissingResourceException e) {
			return '!' + key + '!';
		}
	}

	/**
	 * A text from {@code plugin*.properties} (menus, view title) in Bella's UI
	 * language. Eclipse itself resolves {@code %keys} in plugin.xml with the
	 * operating system language only, so Bella sets these texts itself.
	 */
	public static String plugin(String key) {
		String v = pluginTexts().getProperty(key);
		return v != null ? v : '!' + key + '!';
	}

	private static Properties pluginTexts() {
		Properties p = plugin;
		if (p == null) {
			p = new Properties();
			Bundle b = BellaPlugin.getDefault() == null ? null : BellaPlugin.getDefault().getBundle();
			ResourceBundle.Control control = ResourceBundle.Control
					.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
			java.util.List<Locale> candidates = control.getCandidateLocales("plugin", locale());
			// Most general first, so more specific files override.
			for (int i = candidates.size() - 1; i >= 0; i--) {
				String name = control.toBundleName("plugin", candidates.get(i)) + ".properties";
				URL url = b == null ? Messages.class.getResource("/" + name) : b.getEntry(name);
				if (url == null) {
					continue;
				}
				try (InputStream in = url.openStream()) {
					Properties layer = new Properties();
					layer.load(in); // ISO-8859-1 with \\u escapes, as written by generate.py
					p.putAll(layer);
				} catch (IOException e) {
					BellaPlugin.log("Cannot read " + name, e);
				}
			}
			plugin = p;
		}
		return p;
	}

	public static String fmt(String key, Object... args) {
		return new MessageFormat(get(key), locale()).format(args);
	}
}

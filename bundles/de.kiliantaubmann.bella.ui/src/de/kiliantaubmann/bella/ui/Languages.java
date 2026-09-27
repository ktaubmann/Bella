package de.kiliantaubmann.bella.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Languages Bella's UI is translated into, with their English names for prompts. */
public final class Languages {

	/** Tag → English name. Order is the order shown in the preferences. */
	public static final Map<String, String> SUPPORTED = new LinkedHashMap<>();

	static {
		SUPPORTED.put("en", "English");
		SUPPORTED.put("de", "German");
		SUPPORTED.put("fr", "French");
		SUPPORTED.put("es", "Spanish");
		SUPPORTED.put("it", "Italian");
		SUPPORTED.put("pt-BR", "Brazilian Portuguese");
		SUPPORTED.put("nl", "Dutch");
		SUPPORTED.put("pl", "Polish");
		SUPPORTED.put("cs", "Czech");
		SUPPORTED.put("tr", "Turkish");
		SUPPORTED.put("ru", "Russian");
		SUPPORTED.put("zh-CN", "Simplified Chinese");
		SUPPORTED.put("ja", "Japanese");
		SUPPORTED.put("ko", "Korean");
	}

	private Languages() {
	}

	/** English name of a tag, falling back to the locale's own English display name. */
	public static String englishName(String tag) {
		String name = SUPPORTED.get(tag);
		if (name != null) {
			return name;
		}
		Locale l = Locale.forLanguageTag(tag);
		String display = l.getDisplayLanguage(Locale.ENGLISH);
		return display.isEmpty() ? "English" : display;
	}

	/** Native name for menus, e.g. "Deutsch". */
	public static String nativeName(String tag) {
		Locale l = Locale.forLanguageTag(tag);
		String n = l.getDisplayName(l);
		return n.isEmpty() ? tag : Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	/** The UI language as a supported tag (closest match), for "answer like the UI". */
	public static String uiTag() {
		Locale l = Messages.locale();
		String full = l.toLanguageTag();
		if (SUPPORTED.containsKey(full)) {
			return full;
		}
		for (String tag : SUPPORTED.keySet()) {
			if (tag.startsWith(l.getLanguage())) {
				return tag;
			}
		}
		return "en";
	}
}

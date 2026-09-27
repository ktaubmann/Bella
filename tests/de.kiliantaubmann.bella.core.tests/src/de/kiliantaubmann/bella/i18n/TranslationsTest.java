package de.kiliantaubmann.bella.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Checks the UI bundle's translations: every language has exactly the keys
 * of English with the same placeholders, and every key used in Java code or
 * plugin.xml exists.
 */
class TranslationsTest {

	private static final Path UI = Path.of(System.getProperty("bella.repoRoot", "../.."), "bundles",
			"de.kiliantaubmann.bella.ui");
	private static final Path MESSAGES_DIR = UI.resolve("src/de/kiliantaubmann/bella/ui");
	private static final List<String> LANGS = List.of("de", "fr", "es", "it", "pt_BR", "nl", "pl", "cs", "tr", "ru",
			"zh_CN", "ja", "ko");

	private static Properties load(Path p) throws IOException {
		Properties props = new Properties();
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.ISO_8859_1)) {
			props.load(r);
		}
		return props;
	}

	private static Set<String> placeholders(String s) {
		Set<String> out = new TreeSet<>();
		Matcher m = Pattern.compile("\\{\\d}").matcher(s);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	private static void compare(Properties en, Properties other, String name) {
		assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(other.stringPropertyNames()),
				name + " must have the same keys as English");
		for (String k : en.stringPropertyNames()) {
			assertEquals(placeholders(en.getProperty(k)), placeholders(other.getProperty(k)), name + ": " + k);
			assertTrue(!other.getProperty(k).contains("'"), name + ": " + k + " uses an ASCII apostrophe");
		}
	}

	@Test
	void allLanguagesMatchEnglish() throws IOException {
		Properties enMessages = load(MESSAGES_DIR.resolve("messages.properties"));
		Properties enPlugin = load(UI.resolve("plugin.properties"));
		for (String lang : LANGS) {
			compare(enMessages, load(MESSAGES_DIR.resolve("messages_" + lang + ".properties")), "messages_" + lang);
			compare(enPlugin, load(UI.resolve("plugin_" + lang + ".properties")), "plugin_" + lang);
		}
	}

	@Test
	void everyUsedKeyExists() throws IOException {
		Properties messages = load(MESSAGES_DIR.resolve("messages.properties"));
		Properties plugin = load(UI.resolve("plugin.properties"));
		List<String> missing = new ArrayList<>();
		Pattern call = Pattern.compile("Messages\\.(?:get|fmt)\\(\"([^\"]+)\"\\s*[,)]");
		try (Stream<Path> files = Files.walk(UI.resolve("src"))) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = call.matcher(Files.readString(f));
				while (m.find()) {
					if (!messages.containsKey(m.group(1))) {
						missing.add(f.getFileName() + ": " + m.group(1));
					}
				}
			}
		}
		Matcher pm = Pattern.compile("\"%([\\w.-]+)\"").matcher(Files.readString(UI.resolve("plugin.xml")));
		while (pm.find()) {
			if (!plugin.containsKey(pm.group(1))) {
				missing.add("plugin.xml: %" + pm.group(1));
			}
		}
		assertTrue(missing.isEmpty(), "Missing keys: " + missing);
	}

	@Test
	void dynamicKeysExist() throws IOException {
		Properties messages = load(MESSAGES_DIR.resolve("messages.properties"));
		for (String prefix : List.of("chat.js.welcomeTitle", "chat.notice.refusal", "chat.notice.max_tokens",
				"chat.notice.max_tokens_tool", "chat.notice.max_rounds", "chat.tool.ok", "chat.tool.error",
				"chat.tool.denied", "chat.display.explain", "chat.display.refactor", "chat.display.unit_test",
				"generate.preset.4", "rewrite.preset.5", "implement.preset.3")) {
			assertTrue(messages.containsKey(prefix), prefix);
		}
	}
}

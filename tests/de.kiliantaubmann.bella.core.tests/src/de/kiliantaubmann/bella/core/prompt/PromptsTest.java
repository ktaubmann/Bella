package de.kiliantaubmann.bella.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptsTest {

	@Test
	void systemPromptCarriesLanguages() {
		String sys = new AbapPrompts("German", "English").chatSystem();
		assertTrue(sys.contains("Answer in German."));
		assertTrue(sys.contains("Write ABAP comments in English."));
		assertTrue(new AbapPrompts(null, null).chatSystem().contains("language the developer writes in"));
	}

	@Test
	void generationMarksCursor() {
		EditorContext ctx = new EditorContext("ZX", "PROG/P", null, "REPORT zx.\n\nWRITE 1.", "", 11);
		String user = new AbapPrompts(null, null).generateAtCursor(ctx, "loop").user();
		assertTrue(user.contains("REPORT zx.\n" + AbapPrompts.CURSOR + "\nWRITE 1."), user);
	}

	@Test
	void windowCutsLongSources() {
		String src = "x\n".repeat(20_000);
		String w = AbapPrompts.window(src, 20_000);
		assertTrue(w.length() < src.length());
		assertTrue(w.startsWith("* …"));
	}

	@Test
	void completionCleanerStripsFencesAndOverlap() {
		assertEquals("DATA(x) = 1.", CompletionCleaner.clean("```abap\nDATA(x) = 1.\n```", "", ""));
		assertEquals(" = 1.", CompletionCleaner.clean("  lv_a = 1.", "  lv_a", ""));
		assertEquals("IF x = 1.", CompletionCleaner.clean("IF x = 1.\nENDIF.", "", "\nENDIF."));
		assertEquals("", CompletionCleaner.clean("   \n", "", ""));
	}
}

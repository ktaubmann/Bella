package de.kiliantaubmann.bella.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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

	@Test
	void definitionsAreAddedToTheUserMessage() {
		Prompt p = new Prompt("sys", "Write code.\nReply with exactly one ```abap code block.");
		assertEquals(p, AbapPrompts.withDefinitions(p, " "));
		Prompt d = AbapPrompts.withDefinitions(p, "### MARA (TABL/DT)\n```abap\ndefine table mara {}\n```");
		assertEquals("sys", d.system());
		assertTrue(d.user().startsWith(p.user()));
		assertTrue(d.user().contains("<sap_definitions>\n### MARA (TABL/DT)"), d.user());
		assertTrue(d.user().strip().endsWith("Follow the reply format given above."), d.user());
		assertTrue(new AbapPrompts(null, null).chatSystem().contains("abap_lint"));
	}

	@Test
	void systemLineGoesIntoTheSystemPrompt() {
		Prompt p = new Prompt("sys", "user");
		assertEquals(p, AbapPrompts.withSystem(p, null));
		Prompt with = AbapPrompts.withSystem(p, "SAP_BASIS 750, on-premise");
		assertEquals("sys\nTarget system: SAP_BASIS 750, on-premise. Use only ABAP syntax and APIs available there.",
				with.system());
		assertEquals("user", with.user());
	}

	@Test
	void atcFixPromptQuotesTheLinesOfTheFindings() {
		EditorContext ctx = new EditorContext("ZCL_A", "CLAS/OC", "S4H",
				"REPORT z.\nSELECT * FROM mara INTO TABLE @DATA(lt).\n", "", 0);
		Prompt p = new AbapPrompts(null, null).fixAtcFindings(ctx,
				List.of("Warning line 2: Performance: SELECT *", "Info: no line"));
		assertTrue(p.user().contains("- Warning line 2: Performance: SELECT *\n"
				+ "  code: SELECT * FROM mara INTO TABLE @DATA(lt).\n- Info: no line\n"), p.user());
		assertTrue(p.user().contains("complete corrected source"), p.user());
		assertTrue(p.user().contains("as it is, including comments, formatting"), "continued lines are joined");
		assertFalse(p.user().contains("\t"), "no tabs from the source indentation");
		assertEquals(2, AbapPrompts.lineNumber("Error line 2: x"));
		assertEquals(0, AbapPrompts.lineNumber("Error: inline 2"));
	}

	@Test
	void transportReviewPromptNamesTheToolAndTheSections() {
		String p = new AbapPrompts(null, null).reviewTransport("DEVK900100", "S4H_100");
		assertTrue(p.startsWith("Review transport request DEVK900100 in system S4H_100 before it is released."), p);
		for (String part : List.of("adt_transport_review", "**What the transport does**", "**Security**", "AUTHORITY-CHECK",
				"**Completeness**", "**Verdict**", "Do not change, activate or release anything")) {
			assertTrue(p.contains(part), part);
		}
		assertTrue(new AbapPrompts(null, null).chatSystem().contains("adt_transport_review"));
		int verdict = p.indexOf("**Verdict**");
		int blocking = p.indexOf("**Blocking**");
		int shouldFix = p.indexOf("**Should fix**");
		int overview = p.indexOf("**Overview**");
		int purpose = p.indexOf("**What the transport does**");
		assertTrue(verdict >= 0 && verdict < blocking && blocking < shouldFix && shouldFix < overview
				&& overview < purpose, "verdict and top points come first");
		assertTrue(p.contains("at most 5 points"), p);
		assertTrue(p.contains("**Coverage**"), p);
	}

	@Test
	void cleanAbapRulesInChatButNotInCompletion() {
		String system = new AbapPrompts(null, null).chatSystem();
		assertTrue(system.contains("Clean ABAP"), system);
		assertTrue(system.contains("NEW instead of CREATE OBJECT"), system);
		assertTrue(system.contains("Project naming rules (if configured below) and the style of the existing code take"),
				system);
		assertFalse(AbapPrompts.completion("DATA lv", "", "ZREP").system().contains("Clean ABAP"));
	}
}

package de.kiliantaubmann.bella.ui.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.ui.editor.CodeActions;

/** The checks an editor action runs on its proposal before the diff preview. */
class EditorChecksTest {

	@Test
	void correctionGetsSyntaxErrorsAndStyleErrorsAndWarnings() {
		List<String> problems = EditorHandler.problems(List.of(
				new AbapLint.Finding(1, "naming", AbapLint.Severity.WARNING, "lv_x breaks the rules"),
				new AbapLint.Finding(2, "hint", AbapLint.Severity.INFO, "only a hint"),
				new AbapLint.Finding(3, "obsolete", AbapLint.Severity.ERROR, "MOVE is obsolete")),
				List.of("Error line 7: X is unknown (code: x = 1.)"));
		assertEquals(List.of("Syntax check: Error line 7: X is unknown (code: x = 1.)",
				"Style check: Line 1 [warning] naming: lv_x breaks the rules",
				"Style check: Line 3 [error] obsolete: MOVE is obsolete"), problems);
		assertTrue(EditorHandler.problems(List.of(new AbapLint.Finding(1, "hint", AbapLint.Severity.INFO, "x")),
				List.of()).isEmpty(), "hints alone need no correction");
	}

	@Test
	void editPutsTheCodeWhereTheActionStarted() {
		String src = "REPORT z.\n  WRITE 1.\n";
		int cursor = src.indexOf("WRITE 1.") + "WRITE 1.".length();
		CodeActions.Anchor atCursor = new CodeActions.Anchor(src, cursor, 0);
		assertEquals("REPORT z.\n  WRITE 1. WRITE 2.\n",
				CodeActions.after(atCursor, CodeActions.Target.CURSOR, " WRITE 2.").orElseThrow());
		int select = src.indexOf("WRITE 1.");
		CodeActions.Anchor selection = new CodeActions.Anchor(src, select, "WRITE 1.".length());
		assertEquals("REPORT z.\n  WRITE 3.\n",
				CodeActions.after(selection, CodeActions.Target.SELECTION, "WRITE 3.").orElseThrow());
		assertFalse(CodeActions.after(atCursor, CodeActions.Target.SELECTION, "x").isPresent(), "no selection");
		assertFalse(CodeActions.after(atCursor, CodeActions.Target.METHOD, "x").isPresent(), "no method");
		assertEquals("NEW\n", CodeActions.after(atCursor, CodeActions.Target.DOCUMENT, "NEW").orElseThrow());
	}

	@Test
	void editReplacesTheBodyOfTheMethodAtTheCursor() {
		String src = "CLASS lcl IMPLEMENTATION.\n  METHOD run.\n    old( ).\n  ENDMETHOD.\nENDCLASS.\n";
		CodeActions.Anchor inMethod = new CodeActions.Anchor(src, src.indexOf("old"), 0);
		String after = CodeActions.after(inMethod, CodeActions.Target.METHOD, "new( ).").orElseThrow();
		assertTrue(after.contains("new( )."), after);
		assertFalse(after.contains("old( )."), after);
		assertTrue(after.contains("  METHOD run.\n") && after.contains("  ENDMETHOD.\n"), after);
	}
}

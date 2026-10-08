package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AbapEditTest {

	@Test
	void replacesMethodBodyWithIndentation() {
		String src = "CLASS c IMPLEMENTATION.\n  METHOD m.\n    old = 1.\n  ENDMETHOD.\nENDCLASS.\n";
		AbapStructureScanner.Block b = AbapStructureScanner.routineAt(src, src.indexOf("old")).orElseThrow();
		AbapEdit.Replacement r = AbapEdit.replaceBody(src, b, "DATA x TYPE i.\nIF x > 0.\n  x = 1.\nENDIF.\n");
		assertEquals("CLASS c IMPLEMENTATION.\n  METHOD m.\n    DATA x TYPE i.\n    IF x > 0.\n      x = 1.\n    ENDIF.\n  ENDMETHOD.\nENDCLASS.\n",
				r.applyTo(src));
	}

	@Test
	void fillsEmptyMethod() {
		String src = "  METHOD m.\n  ENDMETHOD.";
		AbapStructureScanner.Block b = AbapStructureScanner.routineAt(src, 3).orElseThrow();
		assertEquals("  METHOD m.\n    rv = 1.\n  ENDMETHOD.", AbapEdit.replaceBody(src, b, "rv = 1.").applyTo(src));
	}

	@Test
	void oneLineMethodIsSplit() {
		String src = "METHOD m. x = 1. ENDMETHOD.";
		AbapStructureScanner.Block b = AbapStructureScanner.routineAt(src, 3).orElseThrow();
		assertEquals("METHOD m.\n  y = 2.\nENDMETHOD.", AbapEdit.replaceBody(src, b, "y = 2.").applyTo(src));
	}

	@Test
	void keepsFullLineCommentsInColumnOne() {
		assertEquals("    x = 1.\n* note\n    y = 2.", AbapEdit.indent("x = 1.\n* note\ny = 2.", "    "));
	}

	@Test
	void relocatesTheActionRangeAfterTyping() {
		String then = "METHOD a.\n  x = 1.\nENDMETHOD.\nMETHOD b.\n  y = 2.\nENDMETHOD.\n";
		int sel = then.indexOf("y = 2.");
		assertArrayEquals(new int[] { sel, 6 }, AbapEdit.relocate(then, then, sel, 6).orElseThrow());
		String now = "* new comment\n" + then;
		assertArrayEquals(new int[] { sel + 14, 6 }, AbapEdit.relocate(now, then, sel, 6).orElseThrow(),
				"text added above moves the range");
		int cursor = then.indexOf("ENDMETHOD.\nMETHOD b");
		assertArrayEquals(new int[] { cursor + 14, 0 }, AbapEdit.relocate(now, then, cursor, 0).orElseThrow());
		assertTrue(AbapEdit.relocate(then.replace("y = 2.", "y = 3."), then, sel, 6).isEmpty(),
				"the selected code itself changed");
		String twice = "  y = 2.\n" + then;
		assertArrayEquals(new int[] { sel + 9, 6 }, AbapEdit.relocate(twice, then, sel, 6).orElseThrow(),
				"the closest occurrence wins");
	}

	@Test
	void multiLineSelectionsBecomeWholeLines() {
		String src = "*&----*\n*& Report Z\nREPORT z.\nIF a = 1.\n  b = 2.\nENDIF.\n";
		// from column 1 of the first line (without its *) to before ENDIF on the last line
		int start = 1;
		int end = src.indexOf("ENDIF.") + 2;
		org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { 0, src.indexOf("ENDIF.") + 6 },
				AbapEdit.wholeLines(src, start, end - start));
		int b = src.indexOf("b = 2");
		org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { b, 1 }, AbapEdit.wholeLines(src, b, 1),
				"a part of one line stays");
		int ifLine = src.indexOf("IF a");
		int endLine = src.indexOf("ENDIF.");
		org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] { ifLine, endLine - ifLine },
				AbapEdit.wholeLines(src, ifLine, endLine - ifLine), "whole lines stay");
	}
}

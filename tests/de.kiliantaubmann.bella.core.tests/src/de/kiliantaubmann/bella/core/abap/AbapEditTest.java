package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

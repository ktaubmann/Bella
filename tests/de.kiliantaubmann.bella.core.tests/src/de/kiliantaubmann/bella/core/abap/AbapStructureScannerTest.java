package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Block;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner.Kind;

class AbapStructureScannerTest {

	static final String CLASS = """
			CLASS zcl_order DEFINITION PUBLIC FINAL CREATE PUBLIC.
			  PUBLIC SECTION.
			    METHODS get_total
			      IMPORTING iv_id TYPE i
			      RETURNING VALUE(rv_total) TYPE p.
			    CLASS-METHODS create.
			ENDCLASS.

			CLASS zcl_order IMPLEMENTATION.
			  METHOD get_total.
			    " comment with ENDMETHOD. inside
			    DATA(lv_text) = 'ENDMETHOD. in a literal'.
			    DATA(lv_tpl) = |Total { iv_id }. ENDMETHOD.|.
			* full line comment ENDMETHOD.
			    rv_total = 42.
			  ENDMETHOD.

			  METHOD create.
			  ENDMETHOD.
			ENDCLASS.
			""";

	@Test
	void findsMethodAroundCursorIgnoringCommentsAndLiterals() {
		int cursor = CLASS.indexOf("rv_total = 42");
		Block m = AbapStructureScanner.routineAt(CLASS, cursor).orElseThrow();
		assertEquals(Kind.METHOD, m.kind());
		assertEquals("GET_TOTAL", m.name());
		String body = m.body(CLASS);
		assertTrue(body.contains("rv_total = 42."));
		assertTrue(CLASS.substring(m.footerStart()).startsWith("ENDMETHOD."));
	}

	@Test
	void emptyMethodAndClassBlocks() {
		int cursor = CLASS.indexOf("METHOD create.") + 3;
		Block m = AbapStructureScanner.routineAt(CLASS, cursor).orElseThrow();
		assertEquals("CREATE", m.name());
		assertTrue(m.body(CLASS).isBlank());
		Block cls = AbapStructureScanner.classAt(CLASS, cursor).orElseThrow();
		assertEquals(Kind.CLASS_IMPLEMENTATION, cls.kind());
		assertEquals("ZCL_ORDER", cls.name());
	}

	@Test
	void findsDeclarations() {
		assertTrue(AbapStructureScanner.classDefinition(CLASS, "ZCL_ORDER").orElseThrow().startsWith("CLASS zcl_order DEFINITION"));
		String decl = AbapStructureScanner.methodDeclaration(CLASS, "zcl_order", "get_total").orElseThrow();
		assertTrue(decl.startsWith("METHODS get_total IMPORTING iv_id TYPE i"), decl);
		assertTrue(AbapStructureScanner.methodDeclaration(CLASS, "zcl_order", "create").isPresent());
	}

	@Test
	void noRoutineOutsideMethods() {
		assertTrue(AbapStructureScanner.routineAt(CLASS, 5).isEmpty());
	}

	@Test
	void formsAndDeferredClasses() {
		String prog = """
				REPORT zdemo.
				CLASS lcl_x DEFINITION DEFERRED.
				START-OF-SELECTION.
				  PERFORM run.
				FORM run.
				  WRITE 'hi'.
				ENDFORM.
				""";
		List<Block> blocks = AbapStructureScanner.blocks(prog);
		assertEquals(1, blocks.size());
		assertEquals(Kind.FORM, blocks.get(0).kind());
		assertEquals("RUN", blocks.get(0).name());
	}

	@Test
	void statementsSplitAtPeriodsOnly() {
		List<AbapStructureScanner.Statement> st = AbapStructureScanner.statements("DATA x TYPE string VALUE 'a.b'. x = `c.d`.");
		assertEquals(2, st.size());
		assertEquals(List.of("DATA", "X"), st.get(0).words(2));
	}
}

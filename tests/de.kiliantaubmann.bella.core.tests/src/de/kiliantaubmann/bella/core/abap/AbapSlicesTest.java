package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AbapSlicesTest {

	static final String CLASS = """
			CLASS zcl_order DEFINITION PUBLIC.
			  PUBLIC SECTION.
			    INTERFACES zif_save.
			    METHODS get_items RETURNING VALUE(rt) TYPE string_table.
			ENDCLASS.

			CLASS zcl_order IMPLEMENTATION.
			  METHOD get_items.
			    rt = VALUE #( ( `a` ) ).
			  ENDMETHOD.

			  METHOD zif_save~save.
			    COMMIT WORK.
			  ENDMETHOD.
			ENDCLASS.
			""";

	@Test
	void methodWithDeclarationAndLine() {
		AbapSlices.Slice s = AbapSlices.method(CLASS, "ZCL_ORDER", "get_items").orElseThrow();
		assertEquals(8, s.firstLine());
		assertEquals("Declaration:\nMETHODS get_items RETURNING VALUE(rt) TYPE string_table.\n\nImplementation:\n"
				+ "  METHOD get_items.\n    rt = VALUE #( ( `a` ) ).\n  ENDMETHOD.", s.text());
	}

	@Test
	void interfaceMethodByShortName() {
		AbapSlices.Slice s = AbapSlices.method(CLASS, "ZCL_ORDER", "save").orElseThrow();
		assertEquals("  METHOD zif_save~save.\n    COMMIT WORK.\n  ENDMETHOD.", s.text());
		assertTrue(AbapSlices.method(CLASS, "ZCL_ORDER", "missing").isEmpty());
		assertEquals(List.of("GET_ITEMS", "ZIF_SAVE~SAVE"), AbapSlices.methodNames(CLASS));
	}

	@Test
	void grepShowsMatchesWithContextAndGaps() {
		String src = "a\nb\nSELECT x\nc\nd\ne\nf\nselect y\ng";
		assertEquals("2 matching lines:\n2: b\n3: SELECT x\n4: c\n…\n7: f\n8: select y\n9: g",
				AbapSlices.grep(src, "select", 1, 100));
		assertEquals("No line matches 'loop'.", AbapSlices.grep(src, "loop", 1, 100));
		assertEquals("1 matching line:\n1: a(\n2: b", AbapSlices.grep("a(\nb", "a(", 1, 100));
	}

	@Test
	void grepIsCut() {
		String result = AbapSlices.grep("x\nx\nx\nx", "x", 0, 2);
		assertTrue(result.startsWith("4 matching lines:\n1: x\n2: x\n… (cut after 2 lines"), result);
	}

	@Test
	void replacesOnlyOneMethodAndDropsFrame() {
		String updated = AbapEdit.replaceMethod(CLASS, "GET_ITEMS",
				"METHOD get_items.\n  rt = VALUE #( ( `b` ) ).\nENDMETHOD.").orElseThrow();
		assertTrue(updated.contains("  METHOD get_items.\n    rt = VALUE #( ( `b` ) ).\n  ENDMETHOD."), updated);
		assertTrue(updated.contains("  METHOD zif_save~save.\n    COMMIT WORK.\n  ENDMETHOD."), updated);
		assertEquals(CLASS.replace("`a`", "`b`"), updated);
		assertTrue(AbapEdit.replaceMethod(CLASS, "nope", "x = 1.").isEmpty());
	}
}

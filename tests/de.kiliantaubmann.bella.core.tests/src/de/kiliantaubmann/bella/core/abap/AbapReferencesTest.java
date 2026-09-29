package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.AbapReferences.Hint;
import de.kiliantaubmann.bella.core.abap.AbapReferences.Reference;

class AbapReferencesTest {

	private static List<String> names(List<Reference> refs) {
		return refs.stream().map(Reference::name).toList();
	}

	@Test
	void findsTablesTypesClassesAndFunctions() {
		List<Reference> refs = AbapReferences.extract("""
				CLASS zcl_orders DEFINITION PUBLIC INHERITING FROM zcl_base.
				  PUBLIC SECTION.
				    INTERFACES zif_orders.
				    TYPES ty_orders TYPE STANDARD TABLE OF vbak WITH EMPTY KEY.
				    METHODS read IMPORTING iv_kunnr TYPE kunnr RETURNING VALUE(rt) TYPE ty_orders.
				ENDCLASS.
				CLASS zcl_orders IMPLEMENTATION.
				  METHOD read.
				    DATA lv_date TYPE sy-datum.
				    DATA lt_items TYPE RANGE OF vbap-posnr.
				    SELECT vbeln, erdat FROM vbak INNER JOIN vbap ON vbap~vbeln = vbak~vbeln
				      WHERE kunnr = @iv_kunnr INTO TABLE @DATA(lt_rows).
				    DATA(lo_log) = NEW zcl_log( ).
				    zcl_util=>convert( ).
				    CALL FUNCTION 'BAPI_USER_GET_DETAIL' EXPORTING username = sy-uname.
				    DATA lr TYPE REF TO zif_other.
				  ENDMETHOD.
				ENDCLASS.
				""");
		List<String> n = names(refs);
		assertEquals(List.of("ZCL_BASE", "ZIF_ORDERS", "VBAK", "KUNNR", "VBAP", "ZCL_LOG", "ZCL_UTIL",
				"BAPI_USER_GET_DETAIL", "ZIF_OTHER"), n);
		assertEquals(Hint.CLASS, refs.get(0).hint());
		assertEquals(Hint.TYPE, refs.get(2).hint());
		assertEquals(Hint.FUNCTION, refs.get(n.indexOf("BAPI_USER_GET_DETAIL")).hint());
	}

	@Test
	void selectHintIsTable() {
		List<Reference> refs = AbapReferences.extract("SELECT SINGLE matnr FROM mara WHERE matnr = @lv INTO @DATA(x).");
		assertEquals(List.of(new Reference("MARA", Hint.TABLE)), refs);
	}

	@Test
	void ignoresLocalsBuiltinsCommentsAndLiterals() {
		List<Reference> refs = AbapReferences.extract("""
				* SELECT * FROM zcomment_table.
				CLASS lcl_helper DEFINITION.
				ENDCLASS.
				TYPES: BEGIN OF ty_line, a TYPE i, END OF ty_line.
				TYPES tt_lines TYPE STANDARD TABLE OF ty_line WITH EMPTY KEY.
				DATA lv_text TYPE string. " TYPE zin_comment
				DATA lv_flag TYPE abap_bool.
				DATA lo TYPE REF TO lcl_helper.
				DATA lt TYPE tt_lines.
				lv_text = 'TYPE zin_literal'.
				lv_text = |{ lcl_helper=>x } FROM zin_template|.
				SELECT * FROM @lt AS itab INTO TABLE @DATA(lt_copy).
				""");
		assertTrue(refs.isEmpty(), refs.toString());
	}

	@Test
	void namespacesAreKept() {
		assertEquals(List.of("/ABC/CL_X"), names(AbapReferences.extract("DATA lo TYPE REF TO /abc/cl_x.")));
	}

	@Test
	void instructionYieldsObjectNamesOnly() {
		assertEquals(List.of("MARA"), names(AbapReferences.fromInstruction("select mara and show")));
		assertEquals(List.of("VBAK", "KNA1"),
				names(AbapReferences.fromInstruction("Lies die ersten 10 Aufträge aus VBAK mit Kunden aus KNA1")));
		assertEquals(List.of("BAPI_USER_GET_DETAIL"),
				names(AbapReferences.fromInstruction("Call function 'BAPI_USER_GET_DETAIL' for the user")));
		assertTrue(AbapReferences.fromInstruction("").isEmpty());
		assertEquals(List.of(), names(AbapReferences.fromInstruction("TABLES entfernen")));
		assertEquals(List.of(), names(AbapReferences.fromInstruction("remove unit tests and rename the method")));
		assertTrue(AbapReferences.fromInstruction("a b c d e f g h i j k l m n o p q r s t u v w x y z aaa bbb ccc ddd eee fff ggg hhh iii jjj")
				.size() <= AbapReferences.MAX_INSTRUCTION_NAMES);
	}

	@Test
	void mergeKeepsFirstHint() {
		List<Reference> m = AbapReferences.merge(List.of(new Reference("MARA", Hint.ANY)),
				List.of(new Reference("MARA", Hint.TABLE), new Reference("MARC", Hint.TABLE)));
		assertEquals(List.of(new Reference("MARA", Hint.ANY), new Reference("MARC", Hint.TABLE)), m);
		assertFalse(m.isEmpty());
	}
}

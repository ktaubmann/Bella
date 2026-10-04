package de.kiliantaubmann.bella.core.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.conventions.NamingRules;

class NamingCheckTest {

	static final NamingRules RULES = NamingRules.parse(NamingRules.TEMPLATE).rules();

	static List<String> naming(String src) {
		return AbapLint.check(src, RULES).stream().filter(f -> f.rule().equals("naming")).map(AbapLint.Finding::format).toList();
	}

	@Test
	void checksDeclarationsByPlace() {
		String src = """
				CLASS zcl_order DEFINITION PUBLIC.
				  PUBLIC SECTION.
				    CONSTANTS lc_max TYPE i VALUE 10.
				    CLASS-DATA gv_count TYPE i.
				    DATA total TYPE p.
				    METHODS add IMPORTING iv_amount TYPE p amount2 TYPE p OPTIONAL
				                EXPORTING ev_sum TYPE p
				                RETURNING VALUE(result) TYPE p.
				    METHODS zif_x~run.
				ENDCLASS.
				CLASS zcl_order IMPLEMENTATION.
				  METHOD add.
				    DATA: lv_ok TYPE abap_bool,
				          counter TYPE i,
				          BEGIN OF ls_line,
				            field TYPE i,
				          END OF ls_line.
				    FIELD-SYMBOLS <row> TYPE any.
				    DATA(sum) = iv_amount + amount2.
				    LOOP AT mt_items ASSIGNING FIELD-SYMBOL(<ls_item>).
				    ENDLOOP.
				    WRITE 'DATA(not_a_name) = 1'.
				  ENDMETHOD.
				ENDCLASS.
				""";
		assertEquals(List.of(
				"Line 5 [warning] naming: attribute \"total\" should match mv_*, mt_*, ms_*, mo_*, mr_*",
				"Line 6 [warning] naming: importing \"amount2\" should match iv_*, it_*, is_*, io_*, ir_*",
				"Line 6 [warning] naming: returning \"result\" should match rv_*, rt_*, rs_*, ro_*, rr_*",
				"Line 13 [warning] naming: local data \"counter\" should match lv_*, lt_*, ls_*, lo_*, lr_*, lx_*",
				"Line 18 [warning] naming: field symbol \"<row>\" should match <lv_*>, <lt_*>, <ls_*>, <lo_*>, <fs_*>",
				"Line 19 [warning] naming: local data \"sum\" should match lv_*, lt_*, ls_*, lo_*, lr_*, lx_*"),
				naming(src));
	}

	@Test
	void localAndTestClassesTypesAndPrograms() {
		String src = """
				REPORT zsd_orders.
				TYPES ty_amount TYPE p.
				TYPES amounts TYPE STANDARD TABLE OF ty_amount WITH EMPTY KEY.
				DATA go_app TYPE REF TO lcl_app.
				DATA app2 TYPE REF TO lcl_app.
				CLASS lcl_app DEFINITION.
				ENDCLASS.
				CLASS helper DEFINITION.
				ENDCLASS.
				CLASS ltc_app DEFINITION FOR TESTING RISK LEVEL HARMLESS.
				ENDCLASS.
				CLASS test_app DEFINITION FOR TESTING.
				ENDCLASS.
				CLASS lcl_late DEFINITION DEFERRED.
				""";
		List<String> f = naming(src);
		assertEquals(List.of(
				"Line 3 [warning] naming: local type \"amounts\" should match ty_*, tt_*",
				"Line 5 [warning] naming: global data \"app2\" should match gv_*, gt_*, gs_*, go_*, gr_*",
				"Line 8 [warning] naming: local class \"helper\" should match lcl_*",
				"Line 12 [warning] naming: test class \"test_app\" should match ltc_*"), f);
	}

	@Test
	void noRulesNoFindingsAndEachNameOnce() {
		assertTrue(naming("METHOD m. DATA x TYPE i. ENDMETHOD.").isEmpty() == false);
		assertTrue(AbapLint.check("METHOD m. DATA x TYPE i. ENDMETHOD.").stream().noneMatch(f -> f.rule().equals("naming")));
		assertEquals(1, naming("METHOD m.\nDATA(x) = 1.\nx = 2.\nDATA(x) = 3.\nENDMETHOD.").size());
	}

	@Test
	void localFriendsAndCreatePublicAreNoClassDeclarations() {
		assertEquals(List.of(), naming("CLASS zcl_order DEFINITION LOCAL FRIENDS ltc_order."));
		assertEquals(List.of(), naming("CLASS lcl_x DEFINITION CREATE PUBLIC.\nENDCLASS."));
		assertEquals(1, naming("CLASS zcl_x DEFINITION CREATE PUBLIC FINAL.\nENDCLASS.").size(),
				"a local class named like a global one");
	}
}

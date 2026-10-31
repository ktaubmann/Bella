package de.kiliantaubmann.bella.core.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.lint.AbapLint.Finding;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;

class AbaplintRulesTest {

	private static List<String> rules(String source) {
		return AbapLint.check(source).stream().map(Finding::rule).toList();
	}

	private static boolean has(String source, String rule) {
		return rules(source).contains(rule);
	}

	private static String fixed(String source) {
		return AbapLintFix.fix(source, Target.of("758", false)).source();
	}

	@Test
	void securityAndErrors() {
		assertTrue(has("CALL TRANSACTION 'SU01'.", "call_transaction_authority_check"));
		assertEquals(List.of(), rules("CALL TRANSACTION 'SU01' WITH AUTHORITY-CHECK."));
		assertTrue(has("EXEC SQL.\nENDEXEC.", "dangerous_statement"));
		assertTrue(has("GENERATE SUBROUTINE POOL lt_code NAME lv_prog.", "dangerous_statement"));
		assertTrue(has("SELECT * FROM (lv_table) INTO TABLE @lt.", "dangerous_statement"));
		assertTrue(has("SELECT a FROM t WHERE (lv_where) INTO TABLE @lt.", "dangerous_statement"));
		assertEquals(List.of(), rules("SELECT a FROM t WHERE ( a = 1 OR b = 2 ) INTO TABLE @DATA(lt)."));
		assertTrue(has("CALL FUNCTION 'Z_X' DESTINATION lv_dest.", "rfc_error_handling"));
		assertTrue(rules("CALL FUNCTION 'Z_X' DESTINATION lv_dest EXCEPTIONS OTHERS = 1.\nIF sy-subrc <> 0.\n  x = 1.\n"
				+ "ENDIF.").isEmpty());
		assertEquals(List.of(), rules("CALL FUNCTION 'Z_X' DESTINATION lv_dest STARTING NEW TASK 'T'."));
		assertTrue(has("DELETE ztab FROM ls_row.", "ambiguous_statement"));
		assertTrue(has("MODIFY ztab FROM ls_row.", "ambiguous_statement"));
		assertEquals(List.of(), rules("DELETE ztab FROM @ls_row."));
		assertEquals(List.of(), rules("DELETE TABLE lt FROM ls_row."));
		assertEquals(List.of("identical_move"), rules("lv_a = lv_a."));
		assertEquals(List.of("invalid_table_index"), rules("x = lt[ 0 ]."));
		assertTrue(has("READ TABLE lt INTO ls INDEX 0.", "invalid_table_index"));
		assertEquals(List.of("sy_modification"), rules("sy-subrc = 4."));
		assertEquals(List.of(), rules("sy-tvar0 = lv_x."));
	}

	@Test
	void blocks() {
		assertEquals(List.of("when_others_last"),
				rules("CASE x.\n  WHEN OTHERS.\n    a = 1.\n  WHEN 1.\n    a = 2.\nENDCASE."));
		assertEquals(List.of(), rules("CASE x.\n  WHEN 1.\n    a = 2.\n  WHEN OTHERS.\n    a = 1.\nENDCASE."));
		assertEquals(List.of("try_without_catch"), rules("TRY.\n  x( ).\nENDTRY."));
		assertEquals(List.of("empty_structure"), rules("IF a = 1.\nENDIF."));
		assertEquals(List.of(), rules("IF a = 1.\n  \" nothing to do yet\nENDIF."));
		assertEquals(List.of("empty_structure"), rules("LOOP AT lt INTO DATA(ls).\nENDLOOP."));
		assertEquals(List.of(), rules("LOOP AT lt TRANSPORTING NO FIELDS WHERE a = 1.\nENDLOOP.\nIF sy-subrc = 0.\n"
				+ "  x = 1.\nENDIF."));
		assertEquals(List.of("catch_and_raise"),
				rules("TRY.\n  x( ).\nCATCH zcx_a INTO DATA(lx).\n  RAISE EXCEPTION lx.\nENDTRY."));
	}

	@Test
	void isAssignedInLoop() {
		String loop = "LOOP AT lt_keys INTO DATA(lv_key).\n  READ TABLE lt ASSIGNING <ls> WITH KEY k = lv_key.\n"
				+ "  IF <ls> IS ASSIGNED.\n    <ls>-x = 1.\n  ENDIF.\nENDLOOP.";
		assertTrue(has(loop, "is_assigned_in_loop"));
		assertTrue(!has(loop.replace("  READ", "  UNASSIGN <ls>.\n  READ"), "is_assigned_in_loop"));
		assertTrue(!has(loop.replace("LOOP AT lt_keys INTO DATA(lv_key).", "IF a = 1.").replace("ENDLOOP.", "ENDIF."),
				"is_assigned_in_loop"));
	}

	@Test
	void readability() {
		assertEquals(List.of("if_in_if"), rules("IF a = 1.\n  x = 1.\nELSE.\n  IF b = 1.\n    x = 2.\n  ENDIF.\nENDIF."));
		assertEquals(List.of("no_yoda_conditions"), rules("IF 0 = lv_count.\n  x = 1.\nENDIF."));
		assertEquals(List.of(), rules("IF lv_count = 0.\n  x = 1.\nENDIF."));
		assertEquals(List.of("no_chained_assignment"), rules("a = b = c."));
		assertEquals(List.of(), rules("a = xsdbool( b = c )."));
		assertEquals(List.of("unnecessary_loop_guard"),
				rules("IF lt IS NOT INITIAL.\n  LOOP AT lt INTO DATA(ls).\n    x = 1.\n  ENDLOOP.\nENDIF."));
		assertEquals(List.of("no_macros"), rules("DEFINE add.\n  &1 = &1 + 1.\nEND-OF-DEFINITION."));
		assertEquals(List.of("use_class_based_exceptions"), rules("METHODS m EXCEPTIONS failed."));
		assertEquals(List.of("mix_returning"),
				rules("METHODS m EXPORTING ev_a TYPE i RETURNING VALUE(rv_b) TYPE i."));
		assertEquals(List.of("add_test_attributes"),
				rules("CLASS ltc DEFINITION FOR TESTING.\n  PRIVATE SECTION.\n    METHODS run FOR TESTING.\nENDCLASS."));
		assertEquals(List.of(), rules("CLASS ltc DEFINITION FOR TESTING DURATION SHORT RISK LEVEL HARMLESS.\n"
				+ "  PRIVATE SECTION.\n    METHODS run FOR TESTING.\nENDCLASS."));
		// a test double has no test methods
		assertEquals(List.of(), rules("CLASS ltd_double DEFINITION FOR TESTING.\n  PUBLIC SECTION.\n    METHODS get.\n"
				+ "ENDCLASS."));
		assertTrue(has("CALL FUNCTION 'GUID_CREATE' IMPORTING ev_guid_32 = lv.", "function_module_recommendations"));
		assertTrue(AbapLint.check("CALL FUNCTION 'GUID_CREATE' IMPORTING ev_guid_32 = lv.").stream()
				.anyMatch(f -> f.message().contains("CL_SYSTEM_UUID")));
	}

	@Test
	void fixableForms() {
		assertEquals(List.of("prefer_is_not"), rules("IF NOT lv IS INITIAL.\n  x = 1.\nENDIF."));
		assertEquals(List.of("omit_receiving"), rules("lo->get( RECEIVING rv_x = lv )."));
		assertEquals(List.of(), rules("CALL METHOD lo->(lv_name) RECEIVING rv_x = lv."));
		assertEquals(List.of("exporting"), rules("lo->run( EXPORTING iv_a = 1 )."));
		assertEquals(List.of(), rules("lo->run( EXPORTING iv_a = 1 IMPORTING ev_b = lv )."));
		assertEquals(List.of("space_before_dot"), rules("x = 1 ."));
		List<Finding> layout = AbapLint.check("x = 1 .\ny = 2 .");
		assertEquals(1, layout.size());
		assertTrue(layout.get(0).message().contains("2 statements"), layout.get(0).message());
		assertEquals(List.of("empty_line_in_statement"), rules("x = a +\n\n    b."));
		assertEquals(List.of("max_one_statement"), rules("x = 1. y = 2."));
		assertEquals(List.of("use_bool_expression"), rules("IF a = 1.\n  lv_ok = abap_true.\nELSE.\n  lv_ok = abap_false.\nENDIF."));
		assertEquals(List.of("else_after_all_returns"),
				rules("METHOD m.\n  IF a = 1.\n    RETURN.\n  ELSE.\n    x = 1.\n  ENDIF.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  IF a = 1.\n    y = 1.\n  ELSE.\n    x = 1.\n  ENDIF.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  IF a = 1.\n    LOOP AT lt INTO DATA(ls).\n      RETURN.\n    ENDLOOP.\n"
				+ "  ELSE.\n    x = 1.\n  ENDIF.\nENDMETHOD."));
	}

	@Test
	void fixes() {
		assertEquals("IF lv IS NOT INITIAL AND lt_x NOT IN r_x.\n  x = 1.\nENDIF.",
				fixed("IF NOT lv IS INITIAL AND NOT lt_x IN r_x.\n  x = 1.\nENDIF."));
		assertEquals("lv = lo->get( iv_a = 1 ).", fixed("CALL METHOD lo->get EXPORTING iv_a = 1 RECEIVING rv_x = lv."));
		assertEquals("lv = lo->get( ).", fixed("lo->get( RECEIVING rv_x = lv )."));
		assertEquals("lo->run( iv_a = 'EXPORTING' ).", fixed("lo->run( EXPORTING iv_a = 'EXPORTING' )."));
		assertEquals("x = 1.", fixed("x = 1 ."));
		assertEquals("x = a +\n    b.", fixed("x = a +\n\n    b."));
		assertEquals("  x = 1.\n  y = 2.", fixed("  x = 1. y = 2."));
		assertEquals("b = 'X TO Y'.", fixed("MOVE 'X TO Y' TO b."));
		assertEquals("  lv_ok = xsdbool( a = 1 ).\nx = 1.",
				fixed("  IF a = 1.\n    lv_ok = abap_true.\n  ELSE.\n    lv_ok = abap_false.\n  ENDIF.\nx = 1."));
		assertEquals("lv_ok = xsdbool( NOT ( a = 1 ) ).",
				fixed("IF a = 1.\n  lv_ok = abap_false.\nELSE.\n  lv_ok = abap_true.\nENDIF."));
		assertEquals("METHOD m.\n  IF a = 1.\n    RETURN.\n  ENDIF.\n  x = 1.\n  y = 2.\nENDMETHOD.",
				fixed("METHOD m.\n  IF a = 1.\n    RETURN.\n  ELSE.\n    x = 1.\n    y = 2.\n  ENDIF.\nENDMETHOD."));
		// a fixed statement inside the moved ELSE branch is fixed as well
		assertEquals("IF a = 1.\n  RETURN.\nENDIF.\nb = a.", fixed("IF a = 1.\n  RETURN.\nELSE.\n  MOVE a TO b.\nENDIF."));
	}

	@Test
	void fixesLeaveUnclearCasesAlone() {
		for (String keep : List.of(
				"lo->get( EXPORTING iv_a = 1 IMPORTING ev_b = lv RECEIVING rv_x = lv2 ).",
				"IF a = 1. \" set the flag\n  lv_ok = abap_true.\nELSE.\n  lv_ok = abap_false.\nENDIF.",
				"IF a = 1.\n  RETURN.\nELSEIF b = 1.\n  RETURN.\nELSE.\n  x = 1.\nENDIF.",
				"x = 1. \" one\ny = 2.",
				"CREATE OBJECT lo_regex EXPORTING pattern = 'a' ##REGEX_POSIX.")) {
			assertEquals(keep, fixed(keep), keep);
		}
		// xsdbool( ) needs 7.40
		String flag = "IF a = 1.\n  lv_ok = abap_true.\nELSE.\n  lv_ok = abap_false.\nENDIF.";
		assertEquals(flag, AbapLintFix.fix(flag, Target.of("702", false)).source());
	}

	@Test
	void fixReportsLinesOfTheOriginal() {
		AbapLintFix.Result r = AbapLintFix.fix("IF a = 1.\n  lv_ok = abap_true.\nELSE.\n  lv_ok = abap_false.\nENDIF.\n"
				+ "MOVE a TO b.", Target.of("758", false));
		assertEquals("lv_ok = xsdbool( a = 1 ).\nb = a.", r.source());
		assertEquals(List.of("Line 1 use_bool_expression", "Line 6 obsolete_move"), r.applied());
	}
}

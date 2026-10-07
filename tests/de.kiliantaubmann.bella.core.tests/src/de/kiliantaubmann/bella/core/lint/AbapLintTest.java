package de.kiliantaubmann.bella.core.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.lint.AbapLint.Finding;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AbapLintTest {

	private static List<String> rules(String source) {
		return AbapLint.check(source).stream().map(Finding::rule).toList();
	}

	@Test
	void modernCodeIsClean() {
		String src = """
				METHOD read.
				  " SELECT * FROM mara in a comment is fine
				  SELECT matnr, mtart FROM mara WHERE mtart = @iv_type ORDER BY matnr
				    INTO TABLE @DATA(lt_mara) UP TO 10 ROWS.
				  LOOP AT lt_mara INTO DATA(ls_mara).
				    rv_count += 1.
				  ENDLOOP.
				  SELECT SINGLE maktx FROM makt WHERE matnr = @iv_matnr INTO @DATA(lv_text).
				  IF sy-subrc <> 0.
				    RAISE EXCEPTION NEW zcx_not_found( ).
				  ENDIF.
				  DATA(lo) = NEW zcl_log( ).
				  lo->add( |MOVE a TO b and SELECT * are only text here| ).
				  TRY.
				      lo->save( ).
				    CATCH zcx_log INTO DATA(lx).
				      MESSAGE lx TYPE 'E'.
				  ENDTRY.
				  IF line_exists( lt_mara[ matnr = iv_matnr ] ).
				  ENDIF.
				ENDMETHOD.
				""";
		assertEquals(List.of(), AbapLint.check(src));
	}

	@Test
	void obsoleteStatements() {
		assertEquals(List.of("obsolete_move"), rules("MOVE a TO b."));
		assertEquals(List.of(), rules("MOVE-CORRESPONDING a TO b."));
		assertEquals(List.of("obsolete_compute"), rules("COMPUTE x = a + b."));
		assertEquals(List.of("obsolete_arithmetic"), rules("ADD 1 TO x."));
		assertEquals(List.of("obsolete_arithmetic"), rules("SUBTRACT 1 FROM x."));
		assertEquals(List.of("call_method"), rules("CALL METHOD lo->run EXPORTING a = 1."));
		assertEquals(List.of(), rules("CALL METHOD lo->(lv_name)."));
		assertEquals(List.of("create_object"), rules("CREATE OBJECT lo."));
		assertEquals(List.of(), rules("CREATE OBJECT lo TYPE (lv_class)."));
		assertEquals(List.of("obsolete_refresh"), rules("REFRESH lt."));
		assertEquals(List.of("describe_lines"), rules("DESCRIBE TABLE lt LINES lv."));
		assertEquals(List.of("obsolete_ranges"), rules("RANGES r_matnr FOR mara-matnr."));
		assertEquals(List.of("obsolete_occurs"), rules("DATA lt TYPE mara OCCURS 0."));
		assertEquals(List.of("header_line"), rules("DATA lt TYPE TABLE OF mara WITH HEADER LINE."));
		assertEquals(List.of("obsolete_tables"), rules("TABLES mara."));
		assertEquals(List.of("form_routine", "form_routine"), rules("PERFORM x.\nFORM x.\nENDFORM."));
	}

	@Test
	void databaseAccess() {
		assertEquals(List.of("select_star"), rules("SELECT * FROM mara WHERE matnr IN @r INTO TABLE @DATA(lt)."));
		assertEquals(List.of("select_star"), rules("SELECT FROM mara FIELDS * WHERE matnr IN @r INTO TABLE @DATA(lt)."));
		assertEquals(List.of("select_endselect"),
				rules("SELECT matnr FROM mara WHERE matnr IN @r INTO @DATA(lv).\n  WRITE lv.\nENDSELECT."));
		assertEquals(List.of("select_in_loop"),
				rules("LOOP AT lt INTO DATA(ls).\n  SELECT matnr FROM mara WHERE matnr = @ls-matnr INTO TABLE @DATA(x).\nENDLOOP."));
		assertEquals(List.of(), rules("LOOP AT lt INTO DATA(ls).\nENDLOOP.\nSELECT matnr FROM mara WHERE matnr IN @r INTO TABLE @DATA(x)."));
		assertEquals(List.of("select_up_to_order"), rules("SELECT matnr FROM mara INTO TABLE @DATA(x) UP TO 5 ROWS."));
		assertEquals(List.of("select_single_subrc"),
				rules("SELECT SINGLE matnr FROM mara WHERE matnr = @lv INTO @DATA(ls).\nWRITE ls."));
		assertEquals(List.of(), rules("SELECT SINGLE @abap_true FROM mara WHERE matnr = @lv INTO @DATA(exists).\nIF exists = abap_true.\nENDIF."));
		assertEquals(List.of(), rules("SELECT SINGLE matnr FROM mara WHERE matnr = @lv INTO @DATA(ls).\nCHECK ls IS NOT INITIAL."));
	}

	@Test
	void selectLoopNestingIgnoresSingleAndAggregateSelects() {
		assertEquals(List.of("select_endselect", "select_in_loop"),
				rules("SELECT f FROM t WHERE a = @x INTO @DATA(lv).\n"
						+ "  SELECT SINGLE y FROM u WHERE b = @lv INTO @DATA(lv2).\n  IF sy-subrc = 0.\n  ENDIF.\nENDSELECT."));
		assertEquals(List.of("select_endselect", "select_in_loop"),
				rules("SELECT f FROM t WHERE a = @x INTO @DATA(lv).\n"
						+ "  SELECT MAX( y ) FROM u WHERE b = @lv INTO @DATA(lv2).\nENDSELECT."));
		assertTrue(AbapLint.aggregateOnly("SELECT COUNT( * ) FROM MARA INTO @DATA(N)"));
		assertFalse(AbapLint.aggregateOnly("SELECT MATKL, COUNT( * ) FROM MARA GROUP BY MATKL INTO TABLE @DATA(N)"));
	}

	@Test
	void performanceRules() {
		assertEquals(List.of("fae_empty_check"),
				rules("SELECT matnr FROM marc FOR ALL ENTRIES IN @lt WHERE matnr = @lt-matnr INTO TABLE @DATA(x)."));
		assertEquals(List.of(), rules("IF lt IS NOT INITIAL.\n"
				+ "SELECT matnr FROM marc FOR ALL ENTRIES IN @lt WHERE matnr = @lt-matnr INTO TABLE @DATA(x).\nENDIF."));
		assertEquals(List.of(), rules("CHECK lines( lt ) > 0.\n"
				+ "SELECT matnr FROM marc FOR ALL ENTRIES IN @lt WHERE matnr = @lt-matnr INTO TABLE @DATA(x)."));
		assertEquals(List.of("select_no_where"), rules("SELECT matnr FROM mara INTO TABLE @DATA(x)."));
		assertEquals(List.of(), rules("SELECT COUNT( * ) FROM mara INTO @DATA(n)."));
		assertEquals(List.of("commit_in_loop"), rules("LOOP AT lt INTO DATA(ls).\n  COMMIT WORK.\nENDLOOP."));
		assertEquals(List.of("rfc_in_loop"),
				rules("LOOP AT lt INTO DATA(ls).\n  CALL FUNCTION 'Z_X' DESTINATION lv_dest.\nENDLOOP."));
		assertEquals(List.of("nested_loop_where"),
				rules("LOOP AT lt INTO DATA(ls).\n  LOOP AT lt2 INTO DATA(ls2) WHERE k = ls-k.\n  ENDLOOP.\nENDLOOP."));
		assertEquals(List.of(), rules("LOOP AT lt INTO DATA(ls).\nENDLOOP.\nLOOP AT lt2 INTO DATA(ls2) WHERE k = 1.\nENDLOOP."));
	}

	@Test
	void cleanAbapRules() {
		assertEquals(List.of("boolean_literal"), rules("lv_flag = 'X'."));
		assertEquals(List.of("boolean_literal"), rules("IF lv_flag = space.\nENDIF."));
		assertEquals(List.of(), rules("lv_flag = abap_true."));
		assertEquals(List.of(), rules("lv_name = space."));
		assertEquals(List.of("concatenate"), rules("CONCATENATE a b INTO c."));
		assertEquals(List.of("too_many_importing"), rules("METHODS m IMPORTING a TYPE i b TYPE i c TYPE string d TYPE REF TO zcl_x."));
		assertEquals(List.of(), rules("METHODS m IMPORTING a TYPE i b TYPE i RETURNING VALUE(r) TYPE i."));
		assertEquals(4, AbapLint.importingCount("METHODS M IMPORTING A TYPE I B TYPE I C TYPE STRING D TYPE REF TO ZCL_X"));
	}

	@Test
	void errorHandlingAndDebugging() {
		assertEquals(List.of("catch_cx_root"), rules("TRY.\n x( ).\nCATCH cx_root INTO DATA(e).\n log( e ).\nENDTRY."));
		assertEquals(List.of("empty_catch"), rules("TRY.\n x( ).\nCATCH zcx_a.\nENDTRY."));
		assertEquals(List.of("break_point"), rules("BREAK-POINT."));
		assertEquals(List.of("break_point"), rules("BREAK kilian."));
		assertEquals(List.of("message_abort"), rules("MESSAGE 'Stop'(001) TYPE 'A'."));
		assertEquals(List.of("message_abort"), rules("MESSAGE x001(zmsg)."));
		assertEquals(List.of(), rules("MESSAGE TEXT-002 TYPE 'I'."));
		assertEquals(List.of("read_table"), rules("READ TABLE lt INTO ls WITH KEY matnr = lv."));
	}

	@Test
	void textsBelongInTheTextPool() {
		assertEquals(List.of("internal_screen_text"), rules("%_s_vbeln_%_app_%-text = TEXT-001."));
		assertEquals(List.of("internal_screen_text"), rules("%_p_test_%_app_%-text = 'Test run (no deletion)'."));
		assertEquals(List.of("text_literal"), rules("WRITE: / 'Deleted deliveries:', lv_count."));
		assertEquals(List.of("text_literal"), rules("WRITE / |{ lv_count } deliveries deleted|."));
		assertEquals(List.of("text_literal"), rules("MESSAGE 'No deliveries found' TYPE 'S'."));
		assertEquals(List.of("text_literal"), rules("MESSAGE |Delivery { lv_vbeln } locked| TYPE 'E'."));
		assertEquals(List.of("text_literal"), rules("SELECTION-SCREEN COMMENT 1(20) 'Delivery'."));
		assertEquals(List.of(), rules("WRITE: / TEXT-001, lv_count."));
		assertEquals(List.of(), rules("WRITE / 'Deleted deliveries:'(002)."));
		assertEquals(List.of(), rules("WRITE: / sy-uline, '|', '-', |{ lv_count }|."));
		assertEquals(List.of(), rules("MESSAGE e001(zsd) WITH lv_vbeln."));
		assertEquals(List.of(), rules("MESSAGE lx TYPE 'E'."));
		assertEquals(List.of(), rules("SELECTION-SCREEN BEGIN OF BLOCK b1 WITH FRAME TITLE TEXT-t01."));
		assertEquals(List.of(), rules("lv_text = 'Not shown here'."));
	}

	@Test
	void lineNumbersPointAtStatementStart() {
		List<Finding> f = AbapLint.check("REPORT z.\n\n* comment\nDATA x TYPE i.\n  MOVE 1\n    TO x.\n");
		assertEquals(1, f.size());
		assertEquals(5, f.get(0).line());
		assertTrue(f.get(0).format().startsWith("Line 5 [warning] obsolete_move: "), f.get(0).format());
	}

	@Test
	void toolReportsFindings() {
		LintToolProvider p = new LintToolProvider();
		assertEquals("abap_lint", p.listTools().get(0).name());
		ToolResult r = p.call("abap_lint", Json.parseObject("{\"source\":\"SELECT * FROM mara INTO TABLE @DATA(x).\"}"),
				CancelToken.NONE);
		assertFalse(r.isError());
		assertTrue(r.content().contains("select_star"), r.content());
		assertEquals("No findings.", p.call("abap_lint", Json.parseObject("{\"source\":\"x = 1.\"}"), CancelToken.NONE)
				.content());
		assertTrue(p.call("abap_lint", Json.parseObject("{}"), CancelToken.NONE).isError());
	}
}

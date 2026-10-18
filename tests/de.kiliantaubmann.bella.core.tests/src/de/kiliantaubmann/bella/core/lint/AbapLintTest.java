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
		assertEquals(List.of("prefer_corresponding"), rules("MOVE-CORRESPONDING a TO b."));
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
		assertEquals(List.of("read_table"), rules("READ TABLE lt INTO ls WITH KEY matnr = lv.\nIF sy-subrc = 0.\nENDIF."));
	}

	@Test
	void subrcAfterStatementsThatCanFail() {
		assertEquals(List.of("check_subrc"), rules("READ TABLE lt INTO ls INDEX 1.\nx = ls-a."));
		assertEquals(List.of(), rules("READ TABLE lt INTO ls INDEX 1.\nIF sy-subrc <> 0.\n RETURN.\nENDIF."));
		assertEquals(List.of(), rules("READ TABLE lt ASSIGNING <ls> INDEX 1.\nIF <ls> IS NOT ASSIGNED.\n RETURN.\nENDIF."));
		assertEquals(List.of(), rules("READ TABLE lt INTO ls INDEX 1. \"#EC CI_SUBRC\nx = ls-a."));
		assertEquals(List.of("check_subrc"), rules("ASSIGN (lv_name) TO <lv>.\n<lv> = 1."));
		assertEquals(List.of("check_subrc"), rules("ASSIGN COMPONENT 'A' OF STRUCTURE ls TO <lv>.\n<lv> = 1."));
		// a variable is always assigned
		assertEquals(List.of(), rules("ASSIGN ls-a TO <lv>.\n<lv> = 1."));
		assertEquals(List.of("check_subrc"), rules("AUTHORITY-CHECK OBJECT 'S_TCODE' ID 'TCD' FIELD 'SE38'.\nx = 1."));
		assertEquals(List.of("check_subrc"),
				rules("CALL FUNCTION 'Z_X'\n  EXCEPTIONS\n    not_found = 1\n    OTHERS    = 2.\nx = 1."));
		assertEquals(List.of(), rules("CALL FUNCTION 'Z_X'\n  EXPORTING\n    iv = 1.\nx = 1."));
		assertEquals(List.of(), rules("CALL FUNCTION 'Z_X' EXCEPTIONS OTHERS = 1.\nCASE sy-subrc.\nENDCASE."));
		assertEquals(List.of(), rules("FIND 'A' IN lv MATCH COUNT lv_n."));
	}

	@Test
	void databaseRules() {
		String loop = "LOOP AT lt INTO ls.\n%s\nENDLOOP.";
		assertEquals(List.of("db_operation_in_loop"), rules(String.format(loop, "UPDATE zt SET a = @ls-a WHERE k = @ls-k.")));
		assertEquals(List.of("db_operation_in_loop"), rules(String.format(loop, "INSERT zt FROM ls.")));
		assertEquals(List.of("db_operation_in_loop"), rules(String.format(loop, "DELETE FROM zt WHERE k = ls-k.")));
		assertEquals(List.of("db_operation_in_loop"), rules(String.format(loop, "MODIFY zt FROM @ls.")));
		// internal tables
		assertEquals(List.of(), rules(String.format(loop, "INSERT ls INTO TABLE lt2.")));
		assertEquals(List.of(), rules(String.format(loop, "MODIFY lt2 FROM ls INDEX 1.")));
		assertEquals(List.of(), rules(String.format(loop, "DELETE lt2 WHERE a = ls-a.")));
		assertEquals(List.of(), rules("UPDATE zt SET a = @lv WHERE k = @lv_k."));

		assertEquals(List.of("delete_index_in_loop"), rules(String.format(loop, "DELETE lt INDEX sy-tabix.")));
		assertEquals(List.of(), rules(String.format(loop, "DELETE lt2 INDEX sy-tabix.")));

		assertEquals(List.of("no_mandt_in_database_operations"),
				rules("SELECT a FROM zt WHERE mandt = @sy-mandt INTO TABLE @DATA(lt)."));
		assertEquals(List.of(), rules("SELECT a FROM zt USING CLIENT @lv WHERE mandt = @lv INTO TABLE @DATA(lt)."));
		assertEquals(List.of(), rules("SELECT a FROM zt WHERE b = @sy-mandt INTO TABLE @DATA(lt)."));

		assertEquals(List.of("strict_sql"), rules("SELECT a FROM zt INTO TABLE @DATA(lt) WHERE b = @lv."));
		assertEquals(List.of(), rules("SELECT a FROM zt WHERE b = @lv ORDER BY a INTO TABLE @DATA(lt) UP TO 5 ROWS."));
		// without @ the old syntax allows INTO anywhere
		assertEquals(List.of(), rules("SELECT a FROM zt INTO TABLE lt WHERE b = lv."));
		assertEquals(List.of(), AbapLint.check("SELECT a FROM zt INTO TABLE @DATA(lt) WHERE b = @lv.",
				de.kiliantaubmann.bella.core.conventions.NamingRules.NONE, AbapLint.Target.of("740", false)).stream()
				.filter(f -> f.rule().equals("strict_sql")).toList());
	}

	@Test
	void flowAndCasts() {
		assertEquals(List.of("exit_or_check"), rules("METHOD m.\n CHECK iv = 1.\n x = 1.\nENDMETHOD."));
		assertEquals(List.of("exit_or_check"), rules("METHOD m.\n EXIT.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n LOOP AT lt INTO DATA(ls).\n  CHECK ls-a = 1.\n  x( ls ).\n ENDLOOP.\nENDMETHOD."));
		// event blocks of reports
		assertEquals(List.of(), rules("START-OF-SELECTION.\n CHECK p_test = abap_false."));

		assertEquals(List.of("try_cast_empty_catch"),
				rules("TRY.\n lo_b ?= lo_a.\nCATCH cx_sy_move_cast_error.\nENDTRY."));
	}

	@Test
	void declarationsThatAreNotUsed() {
		assertEquals(List.of("prefer_returning_to_exporting"),
				rules("METHODS get IMPORTING iv TYPE i EXPORTING ev TYPE string."));
		assertEquals(List.of(), rules("METHODS get EXPORTING ev TYPE string ev2 TYPE i."));
		assertEquals(List.of(), rules("METHODS get EXPORTING ev TYPE any."));
		assertEquals(List.of(), rules("METHODS get EXPORTING ev TYPE STANDARD TABLE."));
		assertEquals(List.of(), rules("METHODS on_click FOR EVENT clicked OF zcl_b EXPORTING ev TYPE i."));
		assertEquals(List.of(), rules("METHODS get EXPORTING ev TYPE i CHANGING cv TYPE i."));

		assertEquals(List.of("unused_types"), rules("METHOD m.\n TYPES ty_a TYPE i.\n x = 1.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n TYPES ty_a TYPE i.\n DATA lv TYPE ty_a.\n x = lv.\nENDMETHOD."));
		assertEquals(List.of("unused_types"),
				rules("METHOD m.\n TYPES: BEGIN OF ty_s,\n a TYPE i,\n END OF ty_s.\n x = 1.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n TYPES: BEGIN OF ty_s,\n a TYPE i,\n END OF ty_s,\n"
				+ " ty_t TYPE HASHED TABLE OF ty_s WITH UNIQUE KEY a.\n DATA lt TYPE ty_t.\n x = lines( lt ).\nENDMETHOD."));

		String cls = """
				CLASS zcl_a DEFINITION PUBLIC.
				  PUBLIC SECTION.
				    METHODS run.
				  PRIVATE SECTION.
				    METHODS used.
				    METHODS: unused, handler FOR EVENT e OF zcl_b.
				ENDCLASS.
				CLASS zcl_a IMPLEMENTATION.
				  METHOD run.
				    used( ).
				    SET HANDLER handler FOR ALL INSTANCES.
				  ENDMETHOD.
				  METHOD used.
				  ENDMETHOD.
				  METHOD unused.
				    unused( ).
				  ENDMETHOD.
				  METHOD handler.
				  ENDMETHOD.
				ENDCLASS.
				""";
		List<Finding> f = AbapLint.check(cls).stream().filter(x -> x.rule().equals("unused_methods")).toList();
		assertEquals(1, f.size(), f.toString());
		assertTrue(f.get(0).message().contains("unused"), f.get(0).message());
		assertEquals(6, f.get(0).line());
		// without the implementation, or with friends, nothing can be told
		assertEquals(List.of(), rules(cls.substring(0, cls.indexOf("CLASS zcl_a IMPLEMENTATION"))));
		assertEquals(List.of(), AbapLint.check(cls.replace("DEFINITION PUBLIC.", "DEFINITION PUBLIC GLOBAL FRIENDS zcl_c."))
				.stream().filter(x -> x.rule().equals("unused_methods")).toList());
	}

	@Test
	void commentedOutCode() {
		assertEquals(List.of("commented_code"), rules("* lv_count = lines( lt ).\n* ENDIF.\nx = 1."));
		assertEquals(List.of("commented_code"), rules("x = 1.\n  \" CALL METHOD lo->run( ).\n"));
		// prose and documentation
		assertEquals(List.of(), rules("* Check the input and read the table.\nx = 1."));
		assertEquals(List.of(), rules("\"! Reads the order.\n\"#EC NEEDED\nx = 1."));
		assertEquals(List.of(), rules("*\"  IMPORTING\n*\"     VALUE(IV_ID) TYPE I\nx = 1."));
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

	@Test
	void unreachableCodeAndIdenticalConditions() {
		assertEquals(List.of("unreachable_code"), rules("METHOD m.\n  RETURN.\n  x = 1.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  IF a = 1.\n    RETURN.\n  ENDIF.\n  x = 1.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  TRY.\n      RAISE EXCEPTION NEW zcx_x( ).\n    CATCH zcx_x.\n"
				+ "      x = 1.\n  ENDTRY.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  RAISE EVENT changed.\n  x = 1.\nENDMETHOD."));
		assertEquals(List.of("identical_conditions"),
				rules("IF a = 1.\n  x = 1.\nELSEIF b = 2.\n  x = 2.\nELSEIF a = 1.\n  x = 3.\nENDIF."));
		assertEquals(List.of(), rules("IF a = 1.\n  IF a = 1.\n  ENDIF.\nELSEIF b = 1.\nENDIF."));
	}

	@Test
	void beginEndNames() {
		assertEquals(List.of(), rules("TYPES: BEGIN OF ty_a,\n  f TYPE i,\nEND OF ty_a."));
		assertEquals(List.of("begin_end_names"), rules("TYPES: BEGIN OF ty_a,\n  f TYPE i,\nEND OF ty_b."));
		assertEquals(List.of(), rules("SELECTION-SCREEN BEGIN OF BLOCK b1 WITH FRAME TITLE TEXT-001.\n"
				+ "SELECTION-SCREEN END OF BLOCK b1."));
		assertEquals(List.of("begin_end_names"), rules("SELECTION-SCREEN BEGIN OF BLOCK b1.\n"
				+ "SELECTION-SCREEN END OF BLOCK b2."));
		assertEquals(List.of(), rules("SELECTION-SCREEN BEGIN OF LINE.\nSELECTION-SCREEN END OF LINE."));
	}

	@Test
	void unusedVariables() {
		assertEquals(List.of("unused_variables"), rules("METHOD m.\n  DATA lv_unused TYPE i.\n  x = 1.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  DATA: lv_a TYPE i,\n        ls_b TYPE ty_b.\n"
				+ "  lv_a = ls_b-f.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  DATA lv_n TYPE i.\n  out->write( |{ lv_n } rows| ).\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  FIELD-SYMBOLS <ls_row> TYPE any.\n"
				+ "  LOOP AT lt ASSIGNING <ls_row>.\n  ENDLOOP.\nENDMETHOD."));
		assertEquals(List.of("unused_variables"), rules("METHOD m.\n  FIELD-SYMBOLS <ls_row> TYPE any.\n"
				+ "  x = 1.\nENDMETHOD."));
		// a name that only appears in a text literal is not a use
		assertEquals(List.of("unused_variables"), rules("METHOD m.\n  DATA lv_x TYPE i.\n"
				+ "  out->write( 'lv_x' ).\nENDMETHOD."));
		// structure components are used as ls_x-a, not on their own
		assertEquals(List.of(), rules("METHOD m.\n  DATA: BEGIN OF ls_x,\n          a TYPE i,\n          b TYPE i,\n"
				+ "        END OF ls_x.\n  ls_x-a = 1.\n  ls_x-b = 2.\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  DATA BEGIN OF ls_x.\n  DATA a TYPE i.\n  DATA END OF ls_x.\n"
				+ "  out->write( ls_x-a ).\nENDMETHOD."));
	}

	@Test
	void sizeAndNesting() {
		StringBuilder longMethod = new StringBuilder("METHOD m.\n");
		for (int i = 0; i < 111; i++) {
			longMethod.append("  x = x + 1.\n");
		}
		assertEquals(List.of("method_length"), rules(longMethod.append("ENDMETHOD.").toString()));
		StringBuilder deep = new StringBuilder("METHOD m.\n");
		for (int i = 0; i < 7; i++) {
			deep.append("IF a = ").append(i).append(".\n");
		}
		for (int i = 0; i < 7; i++) {
			deep.append("ENDIF.\n");
		}
		assertEquals(List.of("nesting"), rules(deep.append("ENDMETHOD.").toString()));
		StringBuilder complex = new StringBuilder("METHOD m.\n");
		for (int i = 0; i < 13; i++) {
			complex.append("IF a = ").append(i).append(" OR b = 1.\nENDIF.\n");
		}
		assertEquals(List.of("cyclomatic_complexity"), rules(complex.append("ENDMETHOD.").toString()));
	}

	@Test
	void linesAndKeywordCase() {
		assertEquals(List.of("line_length"), rules("x = '" + "a".repeat(130) + "'."));
		assertEquals(List.of("whitespace_end"), rules("x = 1.  \ny = 2."));
		assertEquals(List.of("sequential_blank"), rules("x = 1.\n\n\n\n\ny = 2."));
		assertEquals(List.of("keyword_case"), rules("DATA a TYPE i.\nDATA b TYPE i.\nDATA c TYPE i.\n"
				+ "DATA d TYPE i.\nDATA e TYPE i.\ndata f type i.\na = b + c + d + e + f."));
		assertEquals(List.of(), rules("data a type i.\ndata b type i.\na = b."));
		// method calls and assignments start with names, not keywords
		assertEquals(List.of(), rules("DATA a TYPE i.\nDATA b TYPE i.\nDATA c TYPE i.\nDATA d TYPE i.\nDATA e TYPE i.\n"
				+ "display( ).\nresult-deletable = abap_true.\nlo_alv->display( ).\ncl_x=>run( ).\nb += 1."));
	}

	@Test
	void strictSqlAndObsoleteAdditions() {
		assertEquals(List.of("sql_escape_host_variables"),
				rules("SELECT SINGLE vbeln FROM likp WHERE vbeln = @lv INTO ls_likp.\nIF sy-subrc = 0.\nENDIF."));
		assertEquals(List.of(), rules("SELECT SINGLE vbeln FROM likp WHERE vbeln = @lv INTO @DATA(ls).\n"
				+ "IF sy-subrc = 0.\nENDIF."));
		assertEquals(List.of("obsolete_statement"), rules("ON CHANGE OF x.\nENDON."));
		assertEquals(List.of("obsolete_statement"), rules("LOCAL x."));
	}

	private static List<String> rules(String source, AbapLint.Target target) {
		return AbapLint.check(source, null, target).stream().map(AbapLint.Finding::rule).toList();
	}

	@Test
	void releaseSyntax() {
		AbapLint.Target old = AbapLint.Target.of("702", false);
		String modern = "METHOD m.\n  DATA(lv_n) = lines( lt ).\n  out->write( lv_n ).\nENDMETHOD.";
		assertEquals(List.of("release_syntax"), rules(modern, old));
		assertTrue(AbapLint.check(modern, null, old).get(0).message().contains("this system has SAP_BASIS 7.02"));
		assertEquals(List.of(), rules(modern, AbapLint.Target.of("750", false)));
		assertEquals(List.of(), rules(modern, AbapLint.Target.UNKNOWN));
		assertEquals(List.of("release_syntax"), rules("METHOD m.\n  lo = NEW zcl_x( ).\nENDMETHOD.", old));
		assertEquals(List.of("release_syntax"), rules("METHOD m.\n  out->write( lt[ 1 ] ).\nENDMETHOD.", old));
		// NEW #( ) does not exist on 7.02, so CREATE OBJECT gets no hint there
		assertEquals(List.of(), rules("METHOD m.\n  CREATE OBJECT lo.\nENDMETHOD.", old));
		assertEquals(List.of("release_syntax"), rules("METHOD m.\n  RAISE EXCEPTION NEW zcx_x( ).\nENDMETHOD.",
				AbapLint.Target.of("750", false)));
		assertEquals(List.of("release_syntax"), rules("METHOD m.\n  FINAL(lv) = 1.\n  out->write( lv ).\nENDMETHOD.",
				AbapLint.Target.of("7.54", false)));
	}

	@Test
	void abapCloud() {
		AbapLint.Target cloud = new AbapLint.Target(0, true);
		List<AbapLint.Finding> f = AbapLint.check("REPORT zrep.\nSELECT matnr FROM mara INTO TABLE lt_mara.\n"
				+ "MOVE a TO b.\nWRITE 'x'(001).", null, cloud);
		assertTrue(f.stream().anyMatch(x -> x.rule().equals("cloud_types") && x.line() == 1), f.toString());
		assertTrue(f.stream().anyMatch(x -> x.rule().equals("strict_sql") && x.severity() == AbapLint.Severity.ERROR),
				f.toString());
		assertTrue(f.stream().anyMatch(x -> x.rule().equals("obsolete_move") && x.severity() == AbapLint.Severity.ERROR
				&& x.message().endsWith("Not allowed in ABAP Cloud.")), f.toString());
		assertTrue(f.stream().anyMatch(x -> x.rule().equals("cloud_types") && x.line() == 4), f.toString());
	}

	@Test
	void modernForms() {
		// no inline declaration hints: DATA( ) takes the type of the right-hand side, which only a parser knows
		assertEquals(List.of(), rules("METHOD m.\n  DATA lv_matnr TYPE matnr.\n  lv_matnr = condense( lv_raw ).\n"
				+ "  out->write( lv_matnr ).\nENDMETHOD."));
		assertEquals(List.of(), rules("METHOD m.\n  DATA lo_log TYPE REF TO zif_log.\n  lo_log = NEW zcl_file_log( ).\n"
				+ "  lo_log->add( ).\nENDMETHOD."));
		assertEquals(List.of("prefer_xsdbool"), rules("METHOD m.\n  out->write( boolc( a = b ) ).\nENDMETHOD."));
		assertEquals(List.of("prefer_raise_exception_new"), rules("METHOD m.\n  RAISE EXCEPTION TYPE zcx_x.\nENDMETHOD."));
		assertEquals(List.of("use_line_exists"), rules("METHOD m.\n  READ TABLE lt WITH KEY id = 1 TRANSPORTING NO FIELDS.\n"
				+ "  IF sy-subrc = 0.\n    RETURN.\n  ENDIF.\nENDMETHOD."));
	}

	@Test
	void cdsRules() {
		String legacy = "@AbapCatalog.sqlViewName: 'ZV_TRAVEL'\ndefine view ZI_Travel as select from ztravel\n"
				+ "  association [0..*] to ZI_Booking as Booking on $projection.Id = Booking.TravelId\n"
				+ "{ key id as Id, Booking }";
		assertEquals(List.of("cds_legacy_view", "cds_association_name"), rules(legacy));
		assertEquals(AbapLint.Severity.ERROR, AbapLint.check(legacy, null, new AbapLint.Target(0, true)).get(0).severity());
		// view entities do not exist before 7.55, so the hint would be wrong there
		assertEquals(List.of("cds_association_name"), rules(legacy, AbapLint.Target.of("750", false)));
		assertEquals(List.of(), rules("// travel\ndefine view entity ZI_Travel as select from ztravel\n"
				+ "  composition [0..*] of ZI_Booking as _Booking\n{ key id as Id, _Booking }"));
	}

	@Test
	void viewEntityWithLineBreakIsNoLegacyView() {
		assertEquals(List.of(), rules("define view  entity ZI_X as select from ztab { key id }"));
		assertEquals(List.of(), rules("define root view\n    entity ZI_X as select from ztab { key id }"));
	}

	@Test
	void onlyBasisReleasesCount() {
		assertEquals(750, AbapLint.Target.of("7.50", false).release());
		assertEquals(758, AbapLint.Target.of("758", false).release());
		assertEquals(816, AbapLint.Target.of("SAP_BASIS 816", false).release());
		// product releases say nothing about the ABAP syntax
		assertEquals(0, AbapLint.Target.of("2022", false).release());
		assertEquals(0, AbapLint.Target.of("S/4HANA 2022", false).release());
		assertEquals(0, AbapLint.Target.of("", false).release());
		// a support package or the text adt_list_systems shows follows the release
		assertEquals(740, AbapLint.Target.of("7.40 SP05", false).release());
		assertEquals(750, AbapLint.Target.of("750 SP12", false).release());
		assertEquals(758, AbapLint.Target.of("SAP_BASIS 758, on-premise", false).release());
		assertEquals(0, AbapLint.Target.of("7500", false).release());
	}
}

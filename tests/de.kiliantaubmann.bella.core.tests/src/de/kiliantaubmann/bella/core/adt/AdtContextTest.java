package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.AbapReferences.Hint;
import de.kiliantaubmann.bella.core.abap.AbapReferences.Reference;
import de.kiliantaubmann.bella.core.util.CancelToken;

class AdtContextTest {

	static final String SEARCH = "GET /sap/bc/adt/repository/informationsystem/search";

	static String hits(String... uriTypeNameDesc) {
		StringBuilder sb = new StringBuilder("<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">");
		for (int i = 0; i + 3 < uriTypeNameDesc.length + 1; i += 4) {
			sb.append("<adtcore:objectReference adtcore:uri=\"").append(uriTypeNameDesc[i]).append("\" adtcore:type=\"")
					.append(uriTypeNameDesc[i + 1]).append("\" adtcore:name=\"").append(uriTypeNameDesc[i + 2])
					.append("\" adtcore:description=\"").append(uriTypeNameDesc[i + 3]).append("\"/>");
		}
		return sb.append("</adtcore:objectReferences>").toString();
	}

	/** A small SAP system: a table, a data element, a class, a function module. */
	static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.route(SEARCH, r -> {
			String q = r.path().replaceAll(".*query=([^&]*).*", "$1").toUpperCase();
			return FakeAdt.ok(switch (q) {
			case "MARA" -> hits("/sap/bc/adt/packages/mara", "DEVC/K", "MARA", "not a table",
					"/sap/bc/adt/ddic/tables/mara", "TABL/DT", "MARA", "General Material Data");
			case "MATNR" -> hits("/sap/bc/adt/ddic/dataelements/matnr", "DTEL/DE", "MATNR", "Material Number");
			case "ZCL_LOG" -> hits("/sap/bc/adt/oo/classes/zcl_log", "CLAS/OC", "ZCL_LOG", "Log",
					"/sap/bc/adt/oo/classes/zcl_log_old", "CLAS/OC", "ZCL_LOG_OLD", "Old log");
			case "Z_GET" -> hits("/sap/bc/adt/functions/groups/zfg/fmodules/z_get", "FUGR/FF", "Z_GET", "Get");
			default -> hits();
			});
		});
		adt.route("GET /sap/bc/adt/ddic/tables/mara/source/main", r -> new AdtResponse(200, "text/plain",
				"define table mara {\n  key mandt : mandt not null;\n  key matnr : matnr not null;\n  mtart : mtart;\n}"));
		adt.route("GET /sap/bc/adt/ddic/dataelements/matnr", r -> FakeAdt.ok("""
				<blue:wbobj xmlns:blue="http://www.sap.com/wbobj/dictionary/dtel" xmlns:adtcore="http://www.sap.com/adt/core"
				  adtcore:name="MATNR" adtcore:description="Material Number" adtcore:changedBy="DEV">
				  <atom:link xmlns:atom="http://www.w3.org/2005/Atom" href="x" rel="y"/>
				  <dtel:dataElement xmlns:dtel="http://www.sap.com/adt/dictionary/dataelements">
				    <dtel:typeKind>domain</dtel:typeKind>
				    <dtel:typeName>MATNR</dtel:typeName>
				    <dtel:dataType>CHAR</dtel:dataType>
				    <dtel:dataTypeLength>000040</dtel:dataTypeLength>
				    <dtel:shortFieldLabel/>
				  </dtel:dataElement>
				</blue:wbobj>"""));
		adt.route("GET /sap/bc/adt/oo/classes/zcl_log/source/main", r -> new AdtResponse(200, "text/plain", """
				CLASS zcl_log DEFINITION PUBLIC FINAL CREATE PUBLIC.
				  PUBLIC SECTION.
				    METHODS add IMPORTING iv_text TYPE string.
				  PROTECTED SECTION.
				    DATA mt_secret TYPE string_table.
				  PRIVATE SECTION.
				ENDCLASS.
				CLASS zcl_log IMPLEMENTATION.
				  METHOD add.
				  ENDMETHOD.
				ENDCLASS."""));
		adt.route("GET /sap/bc/adt/functions/groups/zfg/fmodules/z_get/source/main", r -> new AdtResponse(200,
				"text/plain", """
						FUNCTION z_get
						  IMPORTING
						    VALUE(iv_id) TYPE i
						  EXPORTING
						    VALUE(ev_name) TYPE string.
						*"----------------------------------
						*" local comment
						*"----------------------------------
						  SELECT SINGLE name FROM ztab WHERE id = @iv_id INTO @ev_name.
						ENDFUNCTION."""));
		return adt;
	}

	@Test
	void buildsDefinitionsPerType() throws Exception {
		AdtContext.Result r = AdtContext.build(new AdtClient(system().stateless("x")),
				List.of(new Reference("MARA", Hint.TABLE), new Reference("MATNR", Hint.TYPE),
						new Reference("ZCL_LOG", Hint.CLASS), new Reference("Z_GET", Hint.FUNCTION),
						new Reference("ZNOPE", Hint.ANY)),
				AdtContext.Limits.DEFAULT, CancelToken.NONE);
		assertEquals(List.of("MARA", "MATNR", "ZCL_LOG", "Z_GET"), r.used());
		assertEquals(List.of("ZNOPE"), r.notFound());
		String t = r.text();
		assertTrue(t.contains("### MARA (TABL/DT – General Material Data)\n```abap\ndefine table mara"), t);
		assertTrue(t.contains("key matnr : matnr not null;"), t);
		// data element: XML summary without links and change data
		assertTrue(t.contains("### MATNR (DTEL/DE – Material Number)\n```text"), t);
		assertTrue(t.contains("dataType: CHAR"), t);
		assertTrue(t.contains("dataTypeLength: 000040"), t);
		assertFalse(t.contains("changedBy"), t);
		assertFalse(t.contains("href"), t);
		// class: public section only
		assertTrue(t.contains("METHODS add IMPORTING iv_text TYPE string."), t);
		assertFalse(t.contains("mt_secret"), t);
		assertFalse(t.contains("IMPLEMENTATION"), t);
		// function module: signature only
		assertTrue(t.contains("VALUE(ev_name) TYPE string."), t);
		assertTrue(t.contains("*\" local comment"), t);
		assertFalse(t.contains("ztab"), t);
		assertTrue(t.endsWith("Not found: ZNOPE\n"), t);
	}

	@Test
	void respectsObjectLimitAndTimeout() throws Exception {
		List<Reference> refs = List.of(new Reference("MARA", Hint.TABLE), new Reference("MATNR", Hint.TYPE),
				new Reference("ZCL_LOG", Hint.CLASS));
		AdtContext.Result one = AdtContext.build(new AdtClient(system().stateless("x")), refs,
				new AdtContext.Limits(1, 8_000, 40_000), CancelToken.NONE);
		assertEquals(List.of("MARA"), one.used());
		assertEquals(List.of("MATNR", "ZCL_LOG"), one.skipped());

		FakeAdt adt = system();
		AdtContext.Result none = AdtContext.build(new AdtClient(adt.stateless("x")), refs,
				new AdtContext.Limits(12, 0, 40_000), CancelToken.NONE);
		assertTrue(none.isEmpty());
		assertEquals(3, none.skipped().size());
		assertTrue(adt.log.isEmpty(), adt.log.toString());
	}

	@Test
	void truncatesToCharacterBudget() throws Exception {
		AdtContext.Result r = AdtContext.build(new AdtClient(system().stateless("x")),
				List.of(new Reference("ZCL_LOG", Hint.CLASS), new Reference("MARA", Hint.TABLE)),
				new AdtContext.Limits(12, 8_000, 250), CancelToken.NONE);
		assertEquals(List.of("ZCL_LOG"), r.used());
		assertEquals(List.of("MARA"), r.skipped());
	}

	@Test
	void candidatesPutInstructionFirst() {
		List<Reference> c = AdtContext.candidates("DATA x TYPE matnr.", "select mara");
		assertEquals(List.of("MARA", "MATNR"), c.stream().map(Reference::name).toList());
	}

	@Test
	void connectionErrorsAreNotReportedAsMissing() throws Exception {
		AdtTransport broken = (r, c) -> {
			throw new java.io.IOException("connection to partner broken");
		};
		AdtContext.Result r = AdtContext.build(new AdtClient(broken), List.of(new Reference("MARA", Hint.TABLE)),
				AdtContext.Limits.DEFAULT, CancelToken.NONE);
		assertTrue(r.isEmpty());
		assertEquals(List.of(), r.notFound());
		assertEquals(List.of("MARA: connection to partner broken"), r.failed());
	}
}

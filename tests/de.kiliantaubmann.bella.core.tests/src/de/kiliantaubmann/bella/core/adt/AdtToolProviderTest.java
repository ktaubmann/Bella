package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.SchemaCheck;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtToolProviderTest {

	private static FakeAdt twoSystems() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.systems.add(new AdtSystem("qa", "Q4H_200", "Q4H", "200", "DEV", false));
		return adt;
	}

	@Test
	void schemasAreValidObjects() {
		for (ToolSpec t : new AdtToolProvider(twoSystems(), () -> null).listTools()) {
			assertEquals("object", t.inputSchema().get("type").getAsString(), t.name());
			assertTrue(t.name().startsWith("adt_"));
		}
	}

	@Test
	void readsSourceFromDefaultSystem() {
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/oo/classes/zcl_a/source/main",
				r -> new AdtResponse(200, "text/plain", "CLASS zcl_a DEFINITION."));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = p.call("adt_read_source", Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("CLASS zcl_a DEFINITION.", r.content());
	}

	@Test
	void resolvesUnknownTypeBySearch() {
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/repository/informationsystem/search", r -> FakeAdt.ok(
						"<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference adtcore:uri=\"/sap/bc/adt/programs/programs/zrep\" adtcore:type=\"PROG/P\" adtcore:name=\"ZREP\"/></adtcore:objectReferences>"))
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main", r -> new AdtResponse(200, "text/plain", "REPORT zrep."));
		ToolResult r = new AdtToolProvider(adt, () -> null).call("adt_read_source",
				Json.parseObject("{\"name\":\"zrep\"}"), CancelToken.NONE);
		assertEquals("REPORT zrep.", r.content());
	}

	@Test
	void refusesSystemThatIsNotLoggedOn() {
		ToolResult r = new AdtToolProvider(twoSystems(), () -> "dev").call("adt_read_source",
				Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"system\":\"Q4H\"}"), CancelToken.NONE);
		assertTrue(r.isError());
		assertTrue(r.content().contains("Not logged on"));
	}

	@Test
	void activationReportsErrors() {
		FakeAdt adt = twoSystems().route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(
				"<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\"><msg type=\"E\" href=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=3,1\"><shortText><txt>Syntax error</txt></shortText></msg></chkl:messages>"));
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_activate",
				Json.parseObject("{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}"), CancelToken.NONE);
		assertTrue(r.isError());
		assertTrue(r.content().contains("Error line 3: Syntax error"), r.content());
	}

	@Test
	void writeClosesStatefulSession() {
		FakeAdt adt = twoSystems()
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolSpec write = p.listTools().stream().filter(t -> t.name().equals("adt_write_source")).findFirst().orElseThrow();
		var input = Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"source\":\"CLASS zcl_a…\"}");
		assertEquals(null, SchemaCheck.validate(write.inputSchema(), input));
		ToolResult r = p.call("adt_write_source", input, CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals(0, adt.openSessions);
		assertTrue(r.content().contains("Not activated"));
	}

	@Test
	void readsDdicDefinitions() {
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/ddic/tables/mara/source/main",
						r -> new AdtResponse(200, "text/plain", "define table mara { key matnr : matnr; }"))
				.route("GET /sap/bc/adt/ddic/dataelements/matnr", r -> FakeAdt.ok(
						"<dtel:dataElement xmlns:dtel=\"http://www.sap.com/adt/dictionary/dataelements\"><dtel:dataType>CHAR</dtel:dataType></dtel:dataElement>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("define table mara { key matnr : matnr; }", p.call("adt_read_source",
				Json.parseObject("{\"name\":\"MARA\",\"type\":\"TABL/DT\"}"), CancelToken.NONE).content());
		assertEquals("dataElement\n  dataType: CHAR", p.call("adt_read_source",
				Json.parseObject("{\"name\":\"MATNR\",\"type\":\"DTEL\"}"), CancelToken.NONE).content());
	}

	@Test
	void contextFromObjectAndSource() {
		FakeAdt adt = AdtContextTest.system();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.route("GET /sap/bc/adt/programs/programs/zrep/source/main", r -> new AdtResponse(200, "text/plain",
				"REPORT zrep.\nSELECT SINGLE * FROM mara INTO @DATA(ls).\nNEW zcl_log( )->add( `x` )."));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolSpec spec = p.listTools().stream().filter(t -> t.name().equals("adt_context")).findFirst().orElseThrow();
		assertEquals(ToolSpec.Kind.READ, spec.kind());
		var byName = Json.parseObject("{\"name\":\"ZREP\",\"type\":\"PROG\"}");
		assertEquals(null, SchemaCheck.validate(spec.inputSchema(), byName));
		ToolResult r = p.call("adt_context", byName, CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("### MARA (TABL/DT"), r.content());
		assertTrue(r.content().contains("### ZCL_LOG (CLAS/OC"), r.content());
		assertFalse(r.content().contains("### ZREP"), r.content());

		ToolResult bySource = p.call("adt_context",
				Json.parseObject("{\"source\":\"DATA lv TYPE matnr.\",\"names\":[\"z_get\"]}"), CancelToken.NONE);
		assertTrue(bySource.content().startsWith("### Z_GET (FUGR/FF"), bySource.content());
		assertTrue(bySource.content().contains("### MATNR (DTEL/DE"), bySource.content());

		ToolResult nothing = p.call("adt_context", Json.parseObject("{\"source\":\"WRITE 'x'.\"}"), CancelToken.NONE);
		assertEquals("No referenced repository objects found.", nothing.content());
	}

	@Test
	void contextReportsConnectionErrorsAsError() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.route("GET ", r -> {
			throw new java.io.UncheckedIOException(new java.io.IOException("connection to partner broken"));
		});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = p.call("adt_context", Json.parseObject("{\"names\":[\"MARA\"]}"), CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("connection to partner broken"), r.content());
		assertFalse(r.content().contains("exist"), r.content());

		FakeAdt ok = AdtContextTest.system();
		ok.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		ToolResult missing = new AdtToolProvider(ok, () -> "dev").call("adt_context",
				Json.parseObject("{\"names\":[\"ZNOPE\"]}"), CancelToken.NONE);
		assertEquals("None of these objects exist in the system: ZNOPE", missing.content());
	}
}

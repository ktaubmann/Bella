package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	private static final String CLASS_SRC = "CLASS zcl_a DEFINITION PUBLIC.\n  PUBLIC SECTION.\n    METHODS run.\nENDCLASS.\n"
			+ "CLASS zcl_a IMPLEMENTATION.\n  METHOD run.\n    WRITE 'x'.\n  ENDMETHOD.\n  METHOD other.\n  ENDMETHOD.\nENDCLASS.\n";

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void readsOneMethodOrMatchingLines() {
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/oo/classes/zcl_a/source/main",
				r -> new AdtResponse(200, "text/plain", CLASS_SRC));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult m = call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"method\":\"run\"}");
		assertEquals("Lines from 6:\nDeclaration:\nMETHODS run.\n\nImplementation:\n  METHOD run.\n    WRITE 'x'.\n  ENDMETHOD.",
				m.content());
		ToolResult missing = call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"method\":\"nope\"}");
		assertTrue(missing.isError());
		assertTrue(missing.content().contains("Implemented methods: RUN, OTHER"), missing.content());
		ToolResult g = call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"grep\":\"write\"}");
		assertTrue(g.content().startsWith("1 matching line:\n5: CLASS zcl_a IMPLEMENTATION."), g.content());
	}

	@Test
	void repeatedReadIsRevalidatedWithEtagAndWriteDropsTheCache() {
		List<String> ifNoneMatch = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> {
					String tag = r.headers().get("If-None-Match");
					ifNoneMatch.add(String.valueOf(tag));
					return "\"v1\"".equals(tag) ? new AdtResponse(304, "", "")
							: new AdtResponse(200, "text/plain", CLASS_SRC, Map.of("ETag", "\"v1\""));
				})
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String json = "{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}";
		assertEquals(CLASS_SRC, call(p, "adt_read_source", json).content());
		assertEquals(CLASS_SRC, call(p, "adt_read_source", json).content());
		assertFalse(call(p, "adt_write_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"source\":\"x\"}").isError());
		call(p, "adt_read_source", json);
		assertEquals(List.of("null", "\"v1\"", "null"), ifNoneMatch);
	}

	@Test
	void autoVersionNotesUnactivatedChanges() {
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main?version=active",
						r -> new AdtResponse(200, "text/plain", "active"))
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", "inactive"))
				.route("GET /sap/bc/adt/activation/inactiveobjects", r -> FakeAdt.ok(
						"<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<ioc:entry><ioc:object><ioc:ref adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a\" adtcore:name=\"ZCL_A\"/></ioc:object>"
								+ "<ioc:transport><ioc:ref adtcore:uri=\"/sap/bc/adt/cts/transportrequests/K900001\" adtcore:name=\"K900001\"/></ioc:transport></ioc:entry>"
								+ "</ioc:inactiveObjects>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult auto = call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}");
		assertTrue(auto.content().startsWith("Note: ZCL_A has saved changes that are not activated yet"), auto.content());
		assertTrue(auto.content().endsWith("\n\ninactive"), auto.content());
		assertEquals("active", call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"version\":\"active\"}").content());
		assertTrue(call(p, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"version\":\"x\"}").isError());
		assertEquals(List.of("ZCL_A"), adtInactive(adt));
	}

	private static List<String> adtInactive(FakeAdt adt) {
		try {
			return new AdtClient(adt.stateless("dev")).inactiveObjects(CancelToken.NONE);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void writesOnlyTheGivenMethod() {
		List<String> written = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", CLASS_SRC))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> {
					written.add(r.body());
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_write_source",
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"method\":\"run\",\"source\":\"WRITE 'y'.\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Saved method RUN of ZCL_A"), r.content());
		assertEquals(List.of(CLASS_SRC.replace("WRITE 'x'.", "WRITE 'y'.")), written);
		assertTrue(call(p, "adt_write_source",
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"method\":\"nope\",\"source\":\"x\"}").isError());
	}

	@Test
	void packagePatterns() {
		List<String> p = AdtToolProvider.packagePatterns(" $tmp, Z*;y*  /ABC/* ");
		assertEquals(List.of("$TMP", "Z*", "Y*", "/ABC/*"), p);
		assertTrue(AdtToolProvider.packageAllowed("$TMP", p));
		assertTrue(AdtToolProvider.packageAllowed("zsales", p));
		assertTrue(AdtToolProvider.packageAllowed("/ABC/CORE", p));
		assertFalse(AdtToolProvider.packageAllowed("SAPLMARA", p));
		assertFalse(AdtToolProvider.packageAllowed("$TMPX", p));
		assertTrue(AdtToolProvider.packagePatterns("  ").isEmpty());
	}

	@Test
	void writesOnlyToAllowedPackages() {
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/oo/classes/zcl_a", r -> FakeAdt.ok(
						"<class:abapClass xmlns:class=\"http://www.sap.com/adt/oo/classes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<adtcore:packageRef adtcore:name=\"ZSALES\"/></class:abapClass>"))
				.route("GET /sap/bc/adt/oo/classes/cl_sap", r -> FakeAdt.ok(
						"<class:abapClass xmlns:class=\"http://www.sap.com/adt/oo/classes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<adtcore:packageRef adtcore:name=\"SABP\"/></class:abapClass>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "$TMP, Z*");
		assertTrue(p.refuse("adt_write_source", Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"source\":\"x\"}"),
				CancelToken.NONE).isEmpty());
		String refused = p.refuse("adt_write_source",
				Json.parseObject("{\"name\":\"CL_SAP\",\"type\":\"CLAS\",\"source\":\"x\"}"), CancelToken.NONE).orElseThrow();
		assertTrue(refused.startsWith("CL_SAP is in package SABP"), refused);
		assertTrue(p.refuse("adt_activate", Json.parseObject(
				"{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"},{\"name\":\"CL_SAP\",\"type\":\"CLAS\"}]}"),
				CancelToken.NONE).isPresent());
		assertTrue(p.refuse("adt_create_object", Json.parseObject(
				"{\"name\":\"ZCL_B\",\"type\":\"CLAS\",\"description\":\"d\",\"package\":\"$tmp\"}"), CancelToken.NONE).isEmpty());
		assertTrue(p.refuse("adt_create_object", Json.parseObject(
				"{\"name\":\"ZCL_B\",\"type\":\"CLAS\",\"description\":\"d\",\"package\":\"SABP\"}"), CancelToken.NONE).isPresent());
		assertTrue(p.refuse("adt_read_source", Json.parseObject("{\"name\":\"CL_SAP\"}"), CancelToken.NONE).isEmpty());
		assertTrue(new AdtToolProvider(adt, () -> "dev", () -> "").refuse("adt_write_source",
				Json.parseObject("{\"name\":\"CL_SAP\",\"type\":\"CLAS\",\"source\":\"x\"}"), CancelToken.NONE).isEmpty());
		String unknown = p.refuse("adt_write_source",
				Json.parseObject("{\"name\":\"ZREP\",\"type\":\"PROG\",\"source\":\"x\"}"), CancelToken.NONE).orElseThrow();
		assertTrue(unknown.startsWith("Could not check the package"), unknown);
	}
}

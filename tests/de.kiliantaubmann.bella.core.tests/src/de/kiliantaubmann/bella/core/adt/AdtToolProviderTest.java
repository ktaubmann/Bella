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
	void contextReportsLostConnection() {
		FakeAdt adt = twoSystems();
		adt.down = new AdtConnectionException("dev", "connection broken");
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_context",
				Json.parseObject("{\"names\":[\"ZCL_DEMO_A\",\"ZCL_DEMO_B\"]}"), CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("Connection to SAP system dev lost"), r.content());
		assertFalse(r.content().contains("exist"), r.content());
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
		// the write reads the source once more (from the cache) for the syntax check before saving
		assertEquals(List.of("null", "\"v1\"", "\"v1\"", "null"), ifNoneMatch);
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

	static final String COMPONENTS = "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"><atom:entry><atom:id>SAP_BASIS</atom:id>"
			+ "<atom:title>758;SAPK-75802INSAPBASIS;0002;SAP Basis Component</atom:title></atom:entry>"
			+ "<atom:entry><atom:id>SAP_ABA</atom:id><atom:title>75I;x;0002;Cross-Application</atom:title></atom:entry></atom:feed>";

	@Test
	void listsSystemsWithRelease() {
		AdtSystemInfo.clear();
		int[] calls = { 0 };
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/system/components", r -> {
			calls[0]++;
			return FakeAdt.ok(COMPONENTS);
		});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String text = call(p, "adt_list_systems", "{}").content();
		assertTrue(text.contains("S4H_100 [destination dev] logged on, SAP_BASIS 758, on-premise"), text);
		assertTrue(text.contains("Q4H_200 [destination qa] NOT logged on\n"), text);
		call(p, "adt_list_systems", "{}");
		assertEquals(1, calls[0], "release is read once");
	}

	@Test
	void parsesComponentsInactiveListsTransportCheckAndDumps() throws Exception {
		assertEquals("SAP_BASIS 758, on-premise", AdtClient.parseComponents(COMPONENTS).describe());
		assertTrue(AdtClient.parseComponents(COMPONENTS.replace("SAP_ABA", "SAP_CLOUD")).cloud());
		assertEquals(List.of("ZCL_ARC1_TEST", "ZARC1_TEST_REPORT"), AdtClient.parseInactiveObjects(
				"<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">"
						+ "<adtcore:objectReference adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_arc1_test\" adtcore:name=\"ZCL_ARC1_TEST\"/>"
						+ "<adtcore:objectReference adtcore:uri=\"/sap/bc/adt/programs/programs/zarc1_test_report\" adtcore:name=\"ZARC1_TEST_REPORT\"/>"
						+ "</adtcore:objectReferences>"));

		AdtClient.TransportCheck t = AdtClient.parseTransportCheck(TRANSPORT_CHECK);
		assertEquals("ZSALES", t.packageName());
		assertFalse(t.local());
		assertTrue(t.recordingRequired());
		assertEquals("DEVK900099", t.lockedIn());
		assertEquals(List.of("DEVK900101 First candidate (DEVELOPER)", "DEVK900102 Second candidate (DEVELOPER)"),
				t.candidates());

		List<AdtClient.Dump> dumps = AdtClient.parseDumps(DUMPS);
		assertEquals(2, dumps.size());
		assertEquals(new AdtClient.Dump("20260328201914vhcala4hci_A4H_00%20%20%20DEVELOPER%20001%2019",
				"2026-03-28T20:19:14Z", "DEVELOPER", "STRING_OFFSET_TOO_LARGE", "SAPLSUSR_CERTRULE"), dumps.get(0));
		assertEquals("20260327150000vhcala4hci_A4H_00%20%20%20ADMIN%20001%2005", dumps.get(1).id());
		assertEquals("COMPUTE_INT_ZERODIVIDE", dumps.get(1).error());
		assertEquals("SAPMTEST", dumps.get(1).program());
	}

	static final String TRANSPORT_CHECK = "<asx:abap version=\"1.0\" xmlns:asx=\"http://www.sap.com/abapxml\"><asx:values><DATA>"
			+ "<OPERATION/><DEVCLASS>ZSALES</DEVCLASS><KORRFLAG>X</KORRFLAG><DLVUNIT>HOME</DLVUNIT><RECORDING>X</RECORDING><MESSAGES/>"
			+ "<REQUESTS><CTS_REQUEST><REQ_HEADER><TRKORR>DEVK900101</TRKORR><AS4USER>DEVELOPER</AS4USER><AS4TEXT>First candidate</AS4TEXT></REQ_HEADER></CTS_REQUEST>"
			+ "<CTS_REQUEST><REQ_HEADER><TRKORR>DEVK900102</TRKORR><AS4USER>DEVELOPER</AS4USER><AS4TEXT>Second candidate</AS4TEXT></REQ_HEADER></CTS_REQUEST></REQUESTS>"
			+ "<LOCKS><CTS_OBJECT_LOCK><LOCK_HOLDER><REQ_HEADER><TRKORR>DEVK900099</TRKORR><AS4USER>DEVELOPER</AS4USER></REQ_HEADER>"
			+ "<TASK_HEADERS><CTS_TASK_HEADER><TRKORR>DEVK900100</TRKORR></CTS_TASK_HEADER></TASK_HEADERS></LOCK_HOLDER></CTS_OBJECT_LOCK></LOCKS>"
			+ "</DATA></asx:values></asx:abap>";

	static final String DUMPS = "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"><atom:author><atom:name>SAP AG</atom:name></atom:author>"
			+ "<atom:link href=\"/sap/bc/adt/runtime/dumps\" rel=\"self\"/>"
			+ "<atom:entry><atom:author><atom:name>DEVELOPER</atom:name></atom:author>"
			+ "<atom:category term=\"STRING_OFFSET_TOO_LARGE\" label=\"Laufzeitfehler\"/><atom:category term=\"SAPLSUSR_CERTRULE\" label=\"Beendetes ABAP-Programm\"/>"
			+ "<atom:id>/sap/bc/adt/vit/runtime/dumps/20260328201914vhcala4hci_A4H_00%20%20%20DEVELOPER%20001%2019</atom:id>"
			+ "<atom:published>2026-03-28T20:19:14Z</atom:published></atom:entry>"
			+ "<atom:entry><atom:author><atom:name>ADMIN</atom:name></atom:author>"
			+ "<atom:category term=\"COMPUTE_INT_ZERODIVIDE\" label=\"ABAP runtime error\"/><atom:category term=\"SAPMTEST\" label=\"Terminated ABAP program\"/>"
			+ "<atom:id>/sap/bc/adt/vit/runtime/dumps/x</atom:id>"
			+ "<atom:link href=\"adt://A4H/sap/bc/adt/runtime/dump/20260327150000vhcala4hci_A4H_00%20%20%20ADMIN%20001%2005\" rel=\"self\"/>"
			+ "<atom:published>2026-03-27T15:00:00Z</atom:published></atom:entry></atom:feed>";

	@Test
	void transportInfoAndShortDumps() {
		List<String> dumpQueries = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("POST /sap/bc/adt/cts/transportchecks", r -> {
					assertTrue(r.body().contains("<DEVCLASS>ZSALES</DEVCLASS><URI>/sap/bc/adt/oo/classes/zcl_a</URI><OPERATION></OPERATION>"),
							r.body());
					return FakeAdt.ok(TRANSPORT_CHECK);
				})
				.route("GET /sap/bc/adt/oo/classes/zcl_a", r -> FakeAdt.ok(
						"<class:abapClass xmlns:class=\"http://www.sap.com/adt/oo/classes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<adtcore:packageRef adtcore:name=\"ZSALES\"/></class:abapClass>"))
				.route("GET /sap/bc/adt/runtime/dumps", r -> {
					dumpQueries.add(r.path());
					return FakeAdt.ok(DUMPS);
				})
				.route("GET /sap/bc/adt/runtime/dump/abc%20d/formatted", r -> new AdtResponse(200, "text/plain", "Runtime error x"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String info = call(p, "adt_transports", "{\"action\":\"for_object\",\"name\":\"ZCL_A\",\"type\":\"CLAS\"}").content();
		assertTrue(info.startsWith("ZCL_A in package ZSALES: changes are recorded in a transport request.\n"
				+ "Already locked in request DEVK900099; use it.\nOpen requests that fit:\n- DEVK900101 First candidate (DEVELOPER)"), info);
		assertTrue(call(p, "adt_transports", "{\"action\":\"for_object\",\"name\":\"ZNEW\",\"create\":true,\"package\":\"ZSALES\"}").isError(),
				"type is needed for a new object");

		String list = call(p, "adt_diagnose", "{\"action\":\"short_dumps\"}").content();
		assertTrue(list.startsWith("2026-03-28T20:19:14Z  STRING_OFFSET_TOO_LARGE in SAPLSUSR_CERTRULE (DEVELOPER)  id: "), list);
		call(p, "adt_diagnose", "{\"action\":\"short_dumps\",\"user\":\"*\",\"max_results\":99}");
		assertEquals(List.of("/sap/bc/adt/runtime/dumps?$top=10&$query=and%28equals%28user%2CDEV%29%29",
				"/sap/bc/adt/runtime/dumps?$top=50"), dumpQueries);
		assertEquals("Runtime error x", call(p, "adt_diagnose", "{\"action\":\"short_dumps\",\"id\":\"abc d\"}").content());
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

	@Test
	void inactiveListIsReusedAcrossReadsAndDroppedAfterAWrite() {
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", CLASS_SRC))
				.route("GET /sap/bc/adt/activation/inactiveobjects", r -> FakeAdt.ok(
						"<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\"/>"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String json = "{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}";
		call(p, "adt_read_source", json);
		call(p, "adt_read_source", json);
		assertEquals(1, adt.log.stream().filter(l -> l.contains("inactiveobjects")).count());
		call(p, "adt_write_source", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"source\":\"x\"}");
		call(p, "adt_read_source", json);
		assertEquals(2, adt.log.stream().filter(l -> l.contains("inactiveobjects")).count());
	}

	@Test
	void syntaxCheckUsesTheActiveVersionWhenNothingIsPending() {
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/activation/inactiveobjects", r -> FakeAdt.ok(
						"<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\"/>"))
				.route("POST /sap/bc/adt/checkruns", r -> {
					bodies.add(r.body());
					return FakeAdt.ok("<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"/>");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("No syntax errors.", call(p, "adt_syntax_check", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}").content());
		assertTrue(bodies.get(0).contains("chkrun:version=\"active\""), bodies.get(0));
		call(p, "adt_syntax_check", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"source\":\"CLASS zcl_a.\"}");
		assertTrue(bodies.get(1).contains("chkrun:version=\"inactive\"><chkrun:artifacts>"), bodies.get(1));
	}

	@Test
	void tableContentsBuildTheSelectAndFormatATable() {
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = twoSystems().route("POST /sap/bc/adt/datapreview/freestyle?rowNumber=10", r -> {
			bodies.add(r.body());
			return FakeAdt.ok(AdtClientTest.TABLE_XML);
		});
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_table_contents", Json.parseObject(
				"{\"table\":\"t000\",\"columns\":\"mandt, mtext\",\"where\":\"mandt <> '999'\",\"max_rows\":10}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals(List.of("SELECT mandt, mtext FROM T000 WHERE mandt <> '999'"), bodies);
		assertTrue(r.content().startsWith("2 of 3 rows from"), r.content());
		assertTrue(r.content().contains("| MANDT | MTEXT |"), r.content());
		assertTrue(r.content().contains("| 100 | Dev \\| Test |"), r.content());
	}

	@Test
	void tableContentsNeedATableName() {
		ToolResult r = new AdtToolProvider(twoSystems(), () -> "dev").call("adt_table_contents",
				Json.parseObject("{\"table\":\"t000 WHERE 1 = 1\"}"), CancelToken.NONE);
		assertTrue(r.isError());
	}

	@Test
	void activationRunsUnitTestsWhenAsked() {
		List<String> testBodies = new ArrayList<>();
		FakeAdt adt = twoSystems().route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/abapunit/testruns", r -> {
					testBodies.add(r.body());
					return FakeAdt.ok("""
							<aunit:runResult xmlns:aunit="http://www.sap.com/adt/aunit" xmlns:adtcore="http://www.sap.com/adt/core">
							 <program adtcore:name="ZCL_A"><testClasses><testClass adtcore:name="LTC_A"><testMethods>
							  <testMethod adtcore:name="OK"/></testMethods></testClass></testClasses></program>
							</aunit:runResult>""");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult plain = p.call("adt_activate",
				Json.parseObject("{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}"), CancelToken.NONE);
		assertFalse(plain.content().contains("ABAP Unit"), plain.content());
		assertTrue(testBodies.isEmpty());
		ToolResult tested = p.call("adt_activate", Json.parseObject(
				"{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"},{\"name\":\"ZIF_A\",\"type\":\"INTF\"}],\"run_unit_tests\":true}"),
				CancelToken.NONE);
		assertFalse(tested.isError(), tested.content());
		assertTrue(tested.content().contains("ABAP Unit:\n1 of 1 test methods passed."), tested.content());
		assertEquals(1, testBodies.size());
		assertTrue(testBodies.get(0).contains("/sap/bc/adt/oo/classes/zcl_a"), testBodies.get(0));
		assertFalse(testBodies.get(0).contains("zif_a"), testBodies.get(0));
	}

	@Test
	void includeMustBeAKnownClassInclude() {
		FakeAdt adt = twoSystems();
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_read_source", Json.parseObject(
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"include\":\"../../zcl_b/source/main\"}"), CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("Unknown class include"), r.content());
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("zcl_b")), adt.log.toString());
	}

	@Test
	void columnsAreOnlyColumnNames() {
		ToolResult r = new AdtToolProvider(twoSystems(), () -> "dev").call("adt_table_contents", Json.parseObject(
				"{\"table\":\"t000\",\"columns\":\"mandt FROM usr02 UNION SELECT bname\"}"), CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("'columns' takes column names"), r.content());
	}

	@Test
	void readsAndWritesTextElements() {
		List<AdtRequest> puts = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/textelements/programs/zrep/source/selections",
						r -> new AdtResponse(200, "text/plain", "S_VBELN=Delivery\n"))
				.route("GET /sap/bc/adt/textelements/programs/zrep/source/headings", r -> new AdtResponse(200, "text/plain", ""))
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR>DEVK900001</CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/textelements/programs/zrep/source/selections", r -> {
					puts.add(r);
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("S_VBELN=Delivery\n",
				call(p, "adt_text_elements", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"part\":\"selections\"}").content());
		assertEquals("No headings maintained for ZREP.",
				call(p, "adt_text_elements", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"part\":\"headings\"}").content());

		ToolSpec write = p.listTools().stream().filter(t -> t.name().equals("adt_write_text_elements")).findFirst()
				.orElseThrow();
		assertEquals(ToolSpec.Kind.WRITE, write.kind());
		var input = Json.parseObject("{\"name\":\"ZREP\",\"type\":\"PROG\",\"part\":\"selections\","
				+ "\"texts\":\"S_VBELN=Delivery\\nP_TEST=Test run (no deletion)\"}");
		assertEquals(null, SchemaCheck.validate(write.inputSchema(), input));
		ToolResult r = p.call("adt_write_text_elements", input, CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the selections of ZREP in S4H_100 (transport DEVK900001). Text elements are active at once.",
				r.content());
		assertEquals(1, puts.size());
		AdtRequest put = puts.get(0);
		assertEquals("/sap/bc/adt/textelements/programs/zrep/source/selections?lockHandle=H&corrNr=DEVK900001",
				put.path());
		assertEquals("application/vnd.sap.adt.textelements.selections.v1", put.contentType());
		assertEquals("application/vnd.sap.adt.textelements.selections.v1", put.headers().get("Accept"));
		assertEquals("S_VBELN=Delivery\nP_TEST=Test run (no deletion)", put.body());
		assertEquals(0, adt.openSessions);
		assertTrue(adt.log.get(adt.log.size() - 1).startsWith("S POST /sap/bc/adt/textelements/programs/zrep?_action=UNLOCK"),
				adt.log.toString());

		ToolResult badLine = call(p, "adt_write_text_elements",
				"{\"name\":\"ZREP\",\"type\":\"PROG\",\"part\":\"selections\",\"texts\":\"%_S_VBELN_%_APP_%-TEXT = 'x'\"}");
		assertTrue(badLine.isError() && badLine.content().contains("NAME=Text"), badLine.content());
		ToolResult classSelections = call(p, "adt_write_text_elements",
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"part\":\"selections\",\"texts\":\"P_A=x\"}");
		assertTrue(classSelections.isError() && classSelections.content().contains("Classes only have text symbols"),
				classSelections.content());
		assertTrue(call(p, "adt_text_elements", "{\"name\":\"ZIF_A\",\"type\":\"INTF\",\"part\":\"symbols\"}")
				.isError());
		assertEquals(1, puts.size());
	}

	@Test
	void textElementWritesKeepToAllowedPackages() {
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/programs/programs/rsabc", r -> FakeAdt.ok(
				"<program:abapProgram xmlns:program=\"http://www.sap.com/adt/programs/programs\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
						+ "<adtcore:packageRef adtcore:name=\"SABP\"/></program:abapProgram>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "$TMP, Z*");
		String refused = p.refuse("adt_write_text_elements", Json.parseObject(
				"{\"name\":\"RSABC\",\"type\":\"PROG\",\"part\":\"symbols\",\"texts\":\"001=x\"}"), CancelToken.NONE)
				.orElseThrow();
		assertTrue(refused.startsWith("RSABC is in package SABP"), refused);
	}

	@Test
	void writeReportsStyleAndSyntaxFindings() {
		FakeAdt adt = twoSystems()
				.route("POST /sap/bc/adt/programs/programs/zrep?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/programs/programs/zrep/source/main", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/programs/programs/zrep?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/checkruns", r -> FakeAdt.ok(
						"<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"><chkrun:checkReport><chkrun:checkMessageList>"
								+ "<chkrun:checkMessage chkrun:uri=\"/sap/bc/adt/programs/programs/zrep/source/main#start=4,0\" chkrun:type=\"W\" chkrun:shortText=\"Unused variable\"/>"
								+ "</chkrun:checkMessageList></chkrun:checkReport></chkrun:checkRunReports>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_write_source", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"source\":"
				+ "\"REPORT zrep.\\nINITIALIZATION.\\n  %_p_test_%_app_%-text = 'Test run'.\\nWRITE 'Done'.\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Saved ZREP in S4H_100. Not activated yet."), r.content());
		assertTrue(r.content().contains("Line 3 [error] internal_screen_text"), r.content());
		assertTrue(r.content().contains("Line 4 [warning] text_literal"), r.content());
		assertTrue(r.content().contains("Syntax check of the saved version:\nWarning line 4: Unused variable"),
				r.content());
		assertTrue(adt.log.stream().anyMatch(l -> l.startsWith("POST /sap/bc/adt/checkruns")), adt.log.toString());
	}

	@Test
	void activationRunsAtcOnlyWhenAsked() {
		FakeAdt adt = twoSystems()
				.route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs", r -> FakeAdt.ok(""))
				.route("GET /sap/bc/adt/atc/worklists/W1", r -> FakeAdt.ok(
						"<atcworklist:worklist xmlns:atcworklist=\"http://www.sap.com/adt/atc/worklist\" xmlns:atcfinding=\"http://www.sap.com/adt/atc/finding\">"
								+ "<atcfinding:finding atcfinding:location=\"/sap/bc/adt/programs/programs/zrep/source/main#start=12,2\" "
								+ "atcfinding:priority=\"2\" atcfinding:checkTitle=\"Extended Program Check\" "
								+ "atcfinding:messageTitle=\"Char. strings w/o text elements\"/></atcworklist:worklist>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "", d -> "ZVARIANT");
		ToolResult r = call(p, "adt_activate", "{\"objects\":[{\"name\":\"ZREP\",\"type\":\"PROG\"}],\"run_atc\":true}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("ATC findings:\nPriority 2 line 12: Extended Program Check: Char. strings w/o text "
				+ "elements\nFix priority 1 and 2 findings now"), r.content());
		assertTrue(adt.log.contains("POST /sap/bc/adt/atc/worklists?checkVariant=ZVARIANT"), adt.log.toString());

		int before = adt.log.size();
		ToolResult off = call(p, "adt_activate", "{\"objects\":[{\"name\":\"ZREP\",\"type\":\"PROG\"}]}");
		assertFalse(off.content().contains("ATC"), off.content());
		assertTrue(adt.log.subList(before, adt.log.size()).stream().noneMatch(l -> l.contains("/atc/")));
	}

	static final String TEXT_SEARCH = "<tsr:textSearchResult xmlns:tsr=\"http://www.sap.com/adt/ris/textSearch\" "
			+ "xmlns:adtcore=\"http://www.sap.com/adt/core\">"
			+ "<tsr:textSearchObject adtcore:uri=\"/sap/bc/adt/ris/proxy?content=objectName%3AZCL_SALES%2CobjectType%3ACLAS\">"
			+ "<tsr:adtMainObject adtcore:type=\"CLAS/OC\" adtcore:name=\"ZCL_SALES\"/>"
			+ "<tsr:textLine adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_sales/source/main%23start%3D12%2C4\">"
			+ "<tsr:content>    lv_x = <b>'DELIVERY_BLOCK'</b>.</tsr:content></tsr:textLine></tsr:textSearchObject>"
			+ "<tsr:textSearchObject adtcore:uri=\"/sap/bc/adt/packages/zsd\"/></tsr:textSearchResult>";

	@Test
	void searchesInSources() throws Exception {
		List<AdtClient.SourceHit> hits = AdtClient.parseSourceHits(TEXT_SEARCH);
		assertEquals(List.of(new AdtClient.SourceHit("ZCL_SALES", "CLAS/OC",
				"/sap/bc/adt/ris/proxy?content=objectName%3AZCL_SALES%2CobjectType%3ACLAS",
				List.of(new AdtClient.SourceLine(12, "lv_x = 'DELIVERY_BLOCK'.")))), hits);

		List<String> paths = new ArrayList<>();
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/repository/informationsystem/textsearch", r -> {
			paths.add(r.path());
			return FakeAdt.ok(TEXT_SEARCH);
		});
		ToolResult r = new AdtToolProvider(adt, () -> null).call("adt_search_objects", Json.parseObject(
				"{\"query\":\"DELIVERY_BLOCK\",\"search_in\":\"source\",\"package\":\"zsd\",\"type\":\"CLAS/OC\"}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("ZCL_SALES (CLAS/OC)\n  12: lv_x = 'DELIVERY_BLOCK'.\n", r.content());
		assertTrue(paths.get(0).contains("searchString=DELIVERY_BLOCK&searchFromIndex=1&searchToIndex=50"), paths.get(0));
		assertTrue(paths.get(0).endsWith("&objectType=CLAS&packageName=ZSD"), paths.get(0));

		ToolResult unsupported = new AdtToolProvider(twoSystems(), () -> null).call("adt_search_objects",
				Json.parseObject("{\"query\":\"x\",\"search_in\":\"source\"}"), CancelToken.NONE);
		assertTrue(unsupported.isError() && unsupported.content().contains("no source code search"),
				unsupported.content());
	}

	private static String checkMessages(String... errors) {
		StringBuilder sb = new StringBuilder("<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\">"
				+ "<chkrun:checkReport><chkrun:checkMessageList>");
		for (String e : errors) {
			sb.append("<chkrun:checkMessage chkrun:uri=\"/sap/bc/adt/programs/programs/zrep/source/main#start=2,0\" "
					+ "chkrun:type=\"E\" chkrun:shortText=\"").append(e).append("\"/>");
		}
		return sb.append("</chkrun:checkMessageList></chkrun:checkReport></chkrun:checkRunReports>").toString();
	}

	@Test
	void writeIsCheckedBeforeSaving() {
		List<String> checked = new ArrayList<>();
		List<String> saved = new ArrayList<>();
		FakeAdt adt = twoSystems()
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main",
						r -> new AdtResponse(200, "text/plain", "REPORT zrep.\nWRITE x."))
				.route("POST /sap/bc/adt/checkruns", r -> {
					String source = new String(java.util.Base64.getDecoder().decode(
							r.body().replaceAll("(?s).*<chkrun:content>(.*)</chkrun:content>.*", "$1")),
							java.nio.charset.StandardCharsets.UTF_8);
					checked.add(source);
					// the old source has one error, the new one with "y" a second one
					return FakeAdt.ok(source.contains("y") ? checkMessages("Field X is unknown", "Field Y is unknown")
							: source.contains("x") ? checkMessages("Field X is unknown") : checkMessages());
				})
				.route("POST /sap/bc/adt/programs/programs/zrep?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/programs/programs/zrep/source/main", r -> {
					saved.add(r.body());
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/programs/programs/zrep?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");

		ToolResult refused = call(p, "adt_write_source",
				"{\"name\":\"ZREP\",\"type\":\"PROG\",\"source\":\"REPORT zrep.\\nWRITE: x, y.\"}");
		assertTrue(refused.isError() && refused.content().startsWith("Not saved: the new source adds syntax errors:\n"
				+ "Error line 2: Field Y is unknown"), refused.content());
		assertTrue(saved.isEmpty());
		assertEquals(2, checked.size(), "new source, then the old one for comparison");

		ToolResult allowed = call(p, "adt_write_source",
				"{\"name\":\"ZREP\",\"type\":\"PROG\",\"allow_errors\":true,\"source\":\"REPORT zrep.\\nWRITE: x, y.\"}");
		assertFalse(allowed.isError(), allowed.content());
		assertEquals(1, saved.size());

		checked.clear();
		ToolResult clean = call(p, "adt_write_source",
				"{\"name\":\"ZREP\",\"type\":\"PROG\",\"source\":\"REPORT zrep.\\nWRITE 'ok'(001).\"}");
		assertFalse(clean.isError(), clean.content());
		assertTrue(clean.content().contains("Syntax check: no errors."), clean.content());
		assertEquals(1, checked.size(), "a clean change costs one check, also standing in for the check after saving");
	}

	@Test
	void transportsWithoutNameAskForIt() {
		AdtToolProvider p = new AdtToolProvider(twoSystems(), () -> "dev");
		for (String action : List.of("for_object", "history")) {
			ToolResult r = call(p, "adt_transports", "{\"action\":\"" + action + "\"}");
			assertTrue(r.isError() && r.content().contains("Give 'name'"), r.content());
		}
	}

	@Test
	void nestedSearchHitsCountOnce() throws Exception {
		String xml = "<tsr:textSearchResult xmlns:tsr=\"http://www.sap.com/adt/ris/textSearch\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\">"
				+ "<tsr:textSearchObject adtcore:uri=\"/sap/bc/adt/ris/proxy?content=objectName%3AZCL_A\">"
				+ "<tsr:adtMainObject adtcore:type=\"CLAS/OC\" adtcore:name=\"ZCL_A\"/>"
				+ "<tsr:textSearchObject adtcore:uri=\"/sap/bc/adt/ris/proxy?content=objectName%3AZCL_A%2Cinclude\">"
				+ "<tsr:adtMainObject adtcore:type=\"CLAS/OC\" adtcore:name=\"ZCL_A\"/>"
				+ "<tsr:textLine adtcore:uri=\"/x%23start%3D3%2C0\"><tsr:content>a</tsr:content></tsr:textLine>"
				+ "<tsr:textLine adtcore:uri=\"/x%23start%3D9%2C0\"><tsr:content>b</tsr:content></tsr:textLine>"
				+ "</tsr:textSearchObject></tsr:textSearchObject></tsr:textSearchResult>";
		List<AdtClient.SourceHit> hits = AdtClient.parseSourceHits(xml);
		assertEquals(1, hits.size(), hits.toString());
		assertEquals(2, hits.get(0).lines().size());
	}

	@Test
	void unsupportedSearchIsRecognizedByItsMessageKey() {
		String sadt = "<exc:exception xmlns:exc=\"http://www.sap.com/abapxml/types/communicationframework\">"
				+ "<message lang=\"EN\">%s</message><properties><entry key=\"T100KEY-ID\">SADT_REST</entry>"
				+ "<entry key=\"T100KEY-NO\">%s</entry></properties></exc:exception>";
		FakeAdt adt = twoSystems().route("GET /sap/bc/adt/repository/informationsystem/textsearch",
				r -> new AdtResponse(400, "application/xml", String.format(sadt, "Not supported", "020")));
		ToolResult r = call(new AdtToolProvider(adt, () -> "dev"), "adt_search_objects",
				"{\"query\":\"x\",\"search_in\":\"source\"}");
		assertTrue(r.content().contains("does not support source code search"), r.content());

		// another SADT_REST message that only mentions 2020 somewhere is no "unsupported"
		FakeAdt denied = twoSystems().route("GET /sap/bc/adt/repository/informationsystem/textsearch",
				r2 -> new AdtResponse(403, "application/xml", String.format(sadt, "No authorization since 2020", "102")));
		ToolResult d = call(new AdtToolProvider(denied, () -> "dev"), "adt_search_objects",
				"{\"query\":\"x\",\"search_in\":\"source\"}");
		assertTrue(d.content().contains("No authorization for source code search"), d.content());
	}

	@Test
	void systemWithoutComponentsIsNotAskedOnEveryWrite() {
		AdtSystemInfo.clear();
		FakeAdt adt = twoSystems();
		AdtClient c = new AdtClient(adt.stateless("dev"));
		assertTrue(AdtSystemInfo.of("dev", c, CancelToken.NONE).isEmpty());
		assertTrue(AdtSystemInfo.of("dev", c, CancelToken.NONE).isEmpty());
		assertEquals(1, adt.log.stream().filter(l -> l.contains("/system/components")).count(), adt.log.toString());
		AdtSystemInfo.clear();
	}
}

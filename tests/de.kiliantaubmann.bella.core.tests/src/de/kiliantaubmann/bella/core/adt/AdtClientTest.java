package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.util.CancelToken;

class AdtClientTest {

	@Test
	void buildsObjectUris() {
		assertEquals("/sap/bc/adt/oo/classes/zcl_x", AdtObjectRef.uriFor("ZCL_X", "CLAS/OC"));
		assertEquals("/sap/bc/adt/oo/classes/%2fabc%2fcl_x", AdtObjectRef.uriFor("/ABC/CL_X", "CLAS"));
		assertEquals("/sap/bc/adt/programs/programs/zr", AdtObjectRef.uriFor("ZR", "prog"));
		assertEquals(null, AdtObjectRef.uriFor("X", "TABL"));
		assertEquals("/sap/bc/adt/oo/classes/zcl_x", AdtObjectRef.objectUri("/sap/bc/adt/oo/classes/zcl_x/source/main#start=1,0"));
		assertEquals("/sap/bc/adt/oo/classes/zcl_x/includes/testclasses", AdtObjectRef.sourceUri("/sap/bc/adt/oo/classes/zcl_x", "TestClasses"));
		// class and group includes belong to their owner; a program include is an object of its own
		assertEquals("/sap/bc/adt/oo/classes/zcl_x", AdtObjectRef.objectUri("/sap/bc/adt/oo/classes/zcl_x/includes/testclasses"));
		assertEquals("/sap/bc/adt/functions/groups/zfg", AdtObjectRef.objectUri("/sap/bc/adt/functions/groups/zfg/includes/lzfgtop"));
		assertEquals("/sap/bc/adt/programs/includes/zincl", AdtObjectRef.objectUri("/sap/bc/adt/programs/includes/zincl/source/main"));
		assertEquals("/sap/bc/adt/programs/includes/zincl", AdtObjectRef.objectUri("/sap/bc/adt/programs/includes/zincl"));
	}

	@Test
	void readsTheMediaTypeA415Offers() {
		assertEquals("application/vnd.sap.adt.functions.fmodules.v3+xml", AdtClient.supportedMediaType(
				"<message>Unsupported Media Type. Supported Media Types: application/vnd.sap.adt.functions.fmodules.v3+xml</message>"));
		assertEquals(null, AdtClient.supportedMediaType("Unsupported Media Type"));
		assertEquals(null, AdtClient.supportedMediaType(null));
	}

	@Test
	void applyingAQuickfixNeedsTheExactPath() {
		String proposal = "/sap/bc/adt/quickfixes/proposals/providers/refactoring/quickfixes/declare_local_variable";
		assertTrue(AdtRequest.post(proposal, "application/xml", "<x/>", "application/xml").exactPath());
		// every proposal apply() accepts, not only those below proposals/
		assertTrue(AdtRequest.post("/sap/bc/adt/quickfixes/abap/declare_local", "application/xml", "<x/>",
				"application/xml").exactPath());
		assertFalse(AdtRequest.post("/sap/bc/adt/quickfixes/evaluation?uri=u", "application/*", "s", "application/*")
				.exactPath());
		assertFalse(AdtRequest.get(proposal, "application/xml").exactPath());
	}

	@Test
	void quickfixRecognisesTheServerErrorPage() {
		assertTrue(AdtQuickfix.isErrorPage("<!DOCTYPE html>\n<html><head><title>Application Server Error</title>"));
		assertTrue(AdtQuickfix.isErrorPage("  <html>"));
		assertFalse(AdtQuickfix.isErrorPage("<?xml version=\"1.0\"?><exc:exception/>"));
		assertFalse(AdtQuickfix.isErrorPage(null));
	}

	@Test
	void launchpadCatalogIdAlsoFromItsEntityUrl() {
		assertEquals("/UI2/CATALOG_ALL", AdtToolProvider.catalogId(
				"https://host:443/sap/opu/odata/UI2/PAGE_BUILDER_CUST/Catalogs('%2FUI2%2FCATALOG_ALL')"));
		assertEquals("ZCAT", AdtToolProvider.catalogId(" ZCAT "));
		assertEquals("X-SAP-UI2-CATALOGPAGE:ZCAT", AdtToolProvider.catalogId("X-SAP-UI2-CATALOGPAGE:ZCAT"));
	}

	@Test
	void parsesSearchResults() throws Exception {
		List<AdtObjectRef> refs = AdtClient.parseObjectReferences("""
				<adtcore:objectReferences xmlns:adtcore="http://www.sap.com/adt/core">
				  <adtcore:objectReference adtcore:uri="/sap/bc/adt/oo/classes/zcl_a" adtcore:type="CLAS/OC" adtcore:name="ZCL_A" adtcore:packageName="ZPKG" adtcore:description="Orders"/>
				</adtcore:objectReferences>""");
		assertEquals(1, refs.size());
		assertEquals("ZCL_A", refs.get(0).name());
		assertEquals("ZPKG", refs.get(0).packageName());
	}

	@Test
	void parsesCheckMessagesWithLines() throws Exception {
		List<AdtClient.Message> msgs = AdtClient.parseCheckMessages("""
				<chkrun:checkRunReports xmlns:chkrun="http://www.sap.com/adt/checkrun">
				 <chkrun:checkReport><chkrun:checkMessageList>
				  <chkrun:checkMessage chkrun:uri="/sap/bc/adt/oo/classes/zcl_a/source/main#start=12,4" chkrun:type="E" chkrun:shortText="Field X is unknown."/>
				 </chkrun:checkMessageList></chkrun:checkReport>
				</chkrun:checkRunReports>""");
		assertEquals("Error line 12: Field X is unknown.", msgs.get(0).format());
	}

	@Test
	void summarizesUnitResults() throws Exception {
		String s = AdtClient.summarizeUnitResult("""
				<aunit:runResult xmlns:aunit="http://www.sap.com/adt/aunit" xmlns:adtcore="http://www.sap.com/adt/core">
				 <program adtcore:name="ZCL_A"><testClasses>
				  <testClass adtcore:name="LTC_A"><testMethods>
				   <testMethod adtcore:name="OK"/>
				   <testMethod adtcore:name="FAILS"><alerts><alert kind="failedAssertion" severity="critical"><title>Expected 1, got 2</title></alert></alerts></testMethod>
				  </testMethods></testClass>
				 </testClasses></program>
				</aunit:runResult>""");
		assertTrue(s.startsWith("1 of 2 test methods passed."), s);
		assertTrue(s.contains("FAILED LTC_A->FAILS"));
		assertTrue(s.contains("Expected 1, got 2"));
	}

	@Test
	void writeLocksPutsAndAlwaysUnlocks() throws Exception {
		FakeAdt adt = new FakeAdt()
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<asx:abap><asx:values><DATA><LOCK_HANDLE>H1</LOCK_HANDLE><CORRNR>K900001</CORRNR><IS_LOCAL></IS_LOCAL></DATA></asx:values></asx:abap>"))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(500, "text/plain", "dump"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""));
		try (AdtTransport.Session s = adt.stateful("D")) {
			assertThrows(AdtException.class, () -> AdtClient.writeSource(s, "/sap/bc/adt/oo/classes/zcl_a", null,
					"CLASS zcl_a…", null, CancelToken.NONE));
		}
		assertEquals(List.of(
				"S POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK&accessMode=MODIFY",
				"S PUT /sap/bc/adt/oo/classes/zcl_a/source/main?lockHandle=H1&corrNr=K900001",
				"S POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK&lockHandle=H1"), adt.log);
	}

	@Test
	void nonLocalObjectWithoutTransportIsRefused() throws Exception {
		FakeAdt adt = new FakeAdt()
				.route("POST /sap/bc/adt/programs/programs/zr?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/programs/programs/zr?_action=UNLOCK", r -> FakeAdt.ok(""));
		try (AdtTransport.Session s = adt.stateful("D")) {
			AdtException e = assertThrows(AdtException.class, () -> AdtClient.writeSource(s,
					"/sap/bc/adt/programs/programs/zr", null, "REPORT zr.", null, CancelToken.NONE));
			assertTrue(e.getMessage().contains("transport"));
		}
		assertTrue(adt.log.get(adt.log.size() - 1).contains("UNLOCK"));
	}

	@Test
	void adtErrorMessagesAreExtracted() {
		String msg = AdtErrors.message(new AdtResponse(403, "application/xml",
				"<exc:exception xmlns:exc=\"http://www.sap.com/abapxml/types/communicationframework\"><message lang=\"EN\">No authorization</message></exc:exception>"));
		assertEquals("HTTP 403: No authorization", msg);
	}

	@Test
	void mapsDdicTypesToUris() {
		assertEquals("/sap/bc/adt/ddic/tables/mara", AdtObjectRef.uriFor("MARA", "TABL/DT"));
		assertEquals("/sap/bc/adt/ddic/structures/bapiret2", AdtObjectRef.uriFor("BAPIRET2", "TABL/DS"));
		assertEquals("/sap/bc/adt/ddic/dataelements/matnr", AdtObjectRef.uriFor("MATNR", "DTEL"));
		assertEquals("/sap/bc/adt/messageclass/zmsg", AdtObjectRef.uriFor("ZMSG", "MSAG/N"));
		assertEquals(null, AdtObjectRef.uriFor("Z_FM", "FUGR/FF"));
		assertEquals("FUGR/FF", AdtClient.searchType("func"));
		assertEquals("TABL/DS", AdtClient.searchType("structure"));
	}

	@Test
	void readDefinitionFallsBackToXmlSummary() throws Exception {
		FakeAdt adt = new FakeAdt().route("GET /sap/bc/adt/ddic/tables/t000", r -> FakeAdt.ok(
				"<tabl:table xmlns:tabl=\"http://www.sap.com/adt/ddic/tables\" tabl:name=\"T000\" tabl:changedAt=\"2020\"><tabl:field tabl:name=\"MANDT\" tabl:type=\"MANDT\"/></tabl:table>"));
		// source/main answers 404 on older releases (no route): the XML description is summarized
		adt.routes.put("GET /sap/bc/adt/ddic/tables/t000/source/main", r -> new AdtResponse(404, "text/plain", "no"));
		adt.routes.put("GET /sap/bc/adt/ddic/tables/t000", adt.routes.remove("GET /sap/bc/adt/ddic/tables/t000"));
		String d = new AdtClient(adt.stateless("D")).readDefinition(
				new AdtObjectRef("/sap/bc/adt/ddic/tables/t000", "T000", "TABL/DT", "", ""), CancelToken.NONE);
		assertEquals("table name=T000\n  field name=MANDT, type=MANDT", d);
	}

	@Test
	void readDefinitionReportsUnsupportedRelease() {
		FakeAdt adt = new FakeAdt();
		AdtException e = assertThrows(AdtException.class, () -> new AdtClient(adt.stateless("D")).readDefinition(
				new AdtObjectRef("/sap/bc/adt/ddic/tabletypes/ztt", "ZTT", "TTYP/DA", "", ""), CancelToken.NONE));
		assertTrue(e.getMessage().contains("cannot be read through ADT"), e.getMessage());
		// XML-only types do not try source/main
		assertEquals(List.of("GET /sap/bc/adt/ddic/tabletypes/ztt"), adt.log);
	}

	@Test
	void requestsAreLoggedWithStatusAndSapMessage() throws Exception {
		FakeAdt adt = new FakeAdt().route("GET /sap/bc/adt/oo/classes/zcl_a/source/main",
				r -> new AdtResponse(200, "text/plain", "CLASS zcl_a DEFINITION."));
		adt.route("GET /sap/bc/adt/oo/classes/zcl_b", r -> new AdtResponse(403, "application/xml",
				"<exc:exception xmlns:exc=\"http://www.sap.com/abapxml/types/communicationframework\"><message lang=\"EN\">No authorization</message></exc:exception>"));
		AdtClient c = new AdtClient(adt.stateless("D"));
		try (de.kiliantaubmann.bella.core.testutil.LogRecorder log = de.kiliantaubmann.bella.core.testutil.LogRecorder
				.start(de.kiliantaubmann.bella.core.util.Log.Level.INFO)) {
			c.readSource("/sap/bc/adt/oo/classes/zcl_a", null, CancelToken.NONE);
			assertThrows(AdtException.class, () -> c.readSource("/sap/bc/adt/oo/classes/zcl_b", null, CancelToken.NONE));
			assertTrue(log.lines.get(0).matches("INFO \\[adt\\] GET /sap/bc/adt/oo/classes/zcl_a/source/main -> 200 \\(\\d+ ms\\)"),
					log.lines.get(0));
			assertTrue(log.lines.get(1).startsWith("WARN [adt] GET /sap/bc/adt/oo/classes/zcl_b/source/main -> 403"),
					log.lines.get(1));
			assertTrue(log.lines.get(1).endsWith("HTTP 403: No authorization"), log.lines.get(1));
		}
	}

	@Test
	void missingClassIncludeIsExplained() throws Exception {
		FakeAdt adt = new FakeAdt().route("GET /sap/bc/adt/oo/classes/cl_x/includes/testclasses",
				r -> new AdtResponse(404, "application/xml",
						"<exc:exception xmlns:exc=\"http://www.sap.com/abapxml/types/communicationframework\"><message lang=\"EN\">CL_X does not have any inactive version</message></exc:exception>"));
		AdtException e = assertThrows(AdtException.class, () -> new AdtClient(adt.stateless("D"))
				.readSource("/sap/bc/adt/oo/classes/cl_x", "testclasses", CancelToken.NONE));
		assertEquals(404, e.status());
		assertTrue(e.getMessage().contains("no local test classes"), e.getMessage());
		// with an explicit version SAP's message is literal and passed on unchanged
		e = assertThrows(AdtException.class, () -> new AdtClient(adt.stateless("D"))
				.readSource("/sap/bc/adt/oo/classes/cl_x", "testclasses", "inactive", CancelToken.NONE));
		assertEquals("HTTP 404: CL_X does not have any inactive version", e.getMessage());
	}

	@Test
	void parsesResponsesAboveTheJdkEntitySizeLimit() throws Exception {
		// Java 24+ rejects documents over 100,000 characters under secure processing by default
		String xml = "<r>" + "x".repeat(250_000) + "</r>";
		assertEquals(250_000, AdtXml.parse(xml).getDocumentElement().getTextContent().length());
	}

	@Test
	void onlyStatefulSessionsAreLabelled() throws Exception {
		FakeAdt adt = new FakeAdt().route("GET /sap/bc/adt/programs/programs/zr_demo/source/main",
				r -> new AdtResponse(200, "text/plain", "REPORT zr_demo."));
		assertFalse(adt.stateless("D").isStateful());
		try (de.kiliantaubmann.bella.core.testutil.LogRecorder log = de.kiliantaubmann.bella.core.testutil.LogRecorder
				.start(de.kiliantaubmann.bella.core.util.Log.Level.INFO); AdtTransport.Session s = adt.stateful("D")) {
			assertTrue(s.isStateful());
			new AdtClient(adt.stateless("D")).readSource("/sap/bc/adt/programs/programs/zr_demo", null, CancelToken.NONE);
			AdtClient.exchange(s, AdtRequest.get("/sap/bc/adt/programs/programs/zr_demo/source/main", "text/plain"),
					CancelToken.NONE);
			assertTrue(log.lines.get(0).startsWith("INFO [adt] GET /sap"), log.all());
			assertTrue(log.lines.get(1).startsWith("INFO [adt] [stateful] GET /sap"), log.all());
		}
	}

	private static String atcFinding(String prio, int line, String title) {
		return "<atcfinding:finding atcfinding:location=\"/sap/bc/adt/programs/programs/zrep/source/main#start=" + line
				+ ",0\" atcfinding:priority=\"" + prio + "\" atcfinding:checkTitle=\"C\" atcfinding:messageTitle=\""
				+ title + "\"/>";
	}

	@Test
	void atcFindingsAreSortedByPriorityThenLine() throws Exception {
		String xml = "<atcworklist:worklist xmlns:atcworklist=\"http://www.sap.com/adt/atc/worklist\""
				+ " xmlns:atcfinding=\"http://www.sap.com/adt/atc/finding\">" + atcFinding("3", 1, "info")
				+ atcFinding("2", 9, "warn late") + atcFinding("1", 20, "error") + atcFinding("2", 4, "warn early")
				+ "</atcworklist:worklist>";
		List<AdtClient.Message> m = AdtClient.parseAtcFindings(xml);
		assertEquals(List.of("C: error", "C: warn early", "C: warn late", "C: info"),
				m.stream().map(AdtClient.Message::text).toList());
		assertEquals(List.of("Error", "Warning", "Warning", "Info"),
				m.stream().map(AdtClient.Message::severity).toList());
	}

	@Test
	void failedUnlockDoesNotHideASuccessfulWrite() throws Exception {
		AdtTransport.Session session = new AdtTransport.Session() {
			@Override
			public AdtResponse send(AdtRequest r, CancelToken c) throws java.io.IOException {
				if (r.path().contains("_action=LOCK")) {
					return new AdtResponse(200, "application/xml",
							"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>");
				}
				if (r.path().contains("_action=UNLOCK")) {
					throw new java.io.IOException("connection broken");
				}
				return new AdtResponse(200, "text/plain", "");
			}

			@Override
			public void close() {
			}
		};
		assertEquals("", AdtClient.writeSource(session, "/sap/bc/adt/programs/programs/zrep", null, "REPORT zrep.",
				null, CancelToken.NONE));
	}

	static final String TABLE_XML = """
			<dataPreview:tableData xmlns:dataPreview="http://www.sap.com/adt/dataPreview">
			 <dataPreview:totalRows>3</dataPreview:totalRows>
			 <dataPreview:isHanaAnalyticalView>false</dataPreview:isHanaAnalyticalView>
			 <dataPreview:columns>
			  <dataPreview:metadata dataPreview:name="MANDT" dataPreview:type="C" dataPreview:description="Client"/>
			  <dataPreview:dataSet><dataPreview:data>000</dataPreview:data><dataPreview:data>100</dataPreview:data></dataPreview:dataSet>
			 </dataPreview:columns>
			 <dataPreview:columns>
			  <dataPreview:metadata dataPreview:name="MTEXT" dataPreview:type="C" dataPreview:description="Name"/>
			  <dataPreview:dataSet><dataPreview:data>SAP AG</dataPreview:data><dataPreview:data>Dev | Test</dataPreview:data></dataPreview:dataSet>
			 </dataPreview:columns>
			</dataPreview:tableData>""";

	@Test
	void parsesTableContentsColumnByColumn() throws Exception {
		AdtClient.TableData d = AdtClient.parseTableData(TABLE_XML);
		assertEquals(List.of("MANDT", "MTEXT"), d.columns());
		assertEquals(List.of(List.of("000", "SAP AG"), List.of("100", "Dev | Test")), d.rows());
		assertEquals(3, d.totalRows());
	}

	@Test
	void tableContentsSendOnlyOneSelect() throws Exception {
		List<String> bodies = new java.util.ArrayList<>();
		FakeAdt adt = new FakeAdt().route("POST /sap/bc/adt/datapreview/freestyle?rowNumber=5", r -> {
			bodies.add(r.body());
			return FakeAdt.ok(TABLE_XML);
		});
		AdtClient c = new AdtClient(adt.stateless("D"));
		assertEquals(2, c.tableContents("SELECT * FROM t000.", 5, CancelToken.NONE).rows().size());
		assertEquals(List.of("SELECT * FROM t000"), bodies);
		assertThrows(AdtException.class, () -> c.tableContents("DELETE FROM t000", 5, CancelToken.NONE));
		assertThrows(AdtException.class,
				() -> c.tableContents("SELECT * FROM t000; DELETE FROM t000", 5, CancelToken.NONE));
		assertEquals(1, bodies.size());
		c.tableContents("SELECT * FROM t000 WHERE mtext LIKE '%;%'", 5, CancelToken.NONE);
		assertEquals(2, bodies.size(), "a ';' inside a literal is fine");
	}

	@Test
	void messagesNameTheClassInclude() {
		assertEquals("Warning line 7 in include testclasses: x", new AdtClient.Message("Warning", "x",
				"/sap/bc/adt/oo/classes/zcl_a/includes/testclasses#start=7,1", 7).format());
		assertEquals("Error line 3: y",
				new AdtClient.Message("Error", "y", "/sap/bc/adt/oo/classes/zcl_a/source/main#start=3,1", 3).format());
	}

	@Test
	void logLeavesOutTheBase64SourceOfASyntaxCheck() {
		String body = "<chkrun:artifact><chkrun:content>IiBMaXN0cyBvdXRib3Vu\nZCBkZWxpdmVyaWVz</chkrun:content></chkrun:artifact>";
		assertEquals("<chkrun:artifact><chkrun:content>[source, 37 characters base64]</chkrun:content></chkrun:artifact>",
				AdtClient.withoutBase64(body));
		assertEquals("SELECT * FROM mara", AdtClient.withoutBase64("SELECT * FROM mara"));
	}
}

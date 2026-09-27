package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class TransportReviewTest {

	static String obj(String pgmid, String type, String name, String wbtype) {
		return "<tm:abap_object tm:pgmid=\"" + pgmid + "\" tm:type=\"" + type + "\" tm:name=\"" + name + "\" tm:wbtype=\""
				+ wbtype + "\" tm:obj_info=\"x\"/>";
	}

	static final String PAD = " ".repeat(30 - "ZCL_CALC".length());

	/** An open request: its task changes a method and the local implementations of ZCL_CALC, a report and a table. */
	static final String REQUEST = "<tm:root xmlns:tm=\"http://www.sap.com/cts/adt/tm\">"
			+ "<tm:request tm:number=\"DEVK900100\" tm:owner=\"DEV\" tm:desc=\"Calculator\" tm:type=\"K\" tm:status=\"D\" tm:target=\"QAS\">"
			+ obj("CORR", "RELE", "DEVK900101 20260101 DEV", "")
			+ "<tm:task tm:number=\"DEVK900101\" tm:parent=\"DEVK900100\" tm:owner=\"DEV\" tm:desc=\"Calculator\" tm:status=\"D\">"
			+ obj("LIMU", "METH", "ZCL_CALC" + PAD + "ADD", "CLAS/OM")
			+ obj("LIMU", "CINC", "ZCL_CALC======================CCIMP", "CLAS/I")
			+ obj("LIMU", "REPS", "ZREP", "PROG/P")
			+ obj("R3TR", "TABL", "ZTAB", "TABL/DT")
			+ "</tm:task></tm:request></tm:root>";

	static String feed(String... entries) {
		return "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
				+ String.join("", entries) + "</atom:feed>";
	}

	static String entry(String base, String number, String time, String transport) {
		return "<atom:entry><atom:author><atom:name>DEV</atom:name></atom:author><atom:content type=\"text/plain\" src=\""
				+ base + "/1/" + number + "/content\"/><atom:id>" + number + "</atom:id>"
				+ (transport == null ? "" : "<atom:link adtcore:name=\"" + transport
						+ "\" href=\"/sap/bc/adt/cts/transportrequests/" + transport
						+ "\" rel=\"http://www.sap.com/adt/relations/transport/request\"/>")
				+ "<atom:updated>" + time + "</atom:updated></atom:entry>";
	}

	static final String CALC = "/sap/bc/adt/oo/classes/zcl_calc";
	static final String CALC_MAIN = CALC + "/includes/main/versions";
	static final String OLD = "CLASS zcl_calc IMPLEMENTATION.\n  METHOD add.\n    rv = a + b.\n  ENDMETHOD.\nENDCLASS.";
	static final String NEW = "CLASS zcl_calc IMPLEMENTATION.\n  METHOD add.\n    rv = a + b.\n    NEW zcl_helper( )->log( rv ).\n  ENDMETHOD.\nENDCLASS.";

	static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.route("GET /sap/bc/adt/cts/transportrequests/DEVK900100", r -> FakeAdt.ok(REQUEST))
				.route("GET /sap/bc/adt/cts/transportrequests?", r -> FakeAdt.ok(REQUEST))
				.route("GET " + CALC_MAIN + "/1/00000/content", r -> new AdtResponse(200, "text/plain", NEW))
				.route("GET " + CALC_MAIN + "/1/00001/content", r -> new AdtResponse(200, "text/plain", OLD))
				.route("GET " + CALC_MAIN, r -> FakeAdt.ok(feed(entry(CALC_MAIN, "00000", "2026-02-01T10:00:00Z", "DEVK900100"),
						entry(CALC_MAIN, "00001", "2026-01-01T10:00:00Z", "DEVK900001"))))
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main/versions/1/00001/content",
						r -> new AdtResponse(200, "text/plain", "REPORT zrep.\nWRITE 'hi'."))
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main/versions", r -> FakeAdt.ok(feed(
						entry("/sap/bc/adt/programs/programs/zrep/source/main/versions", "00001", "2026-02-01T10:00:00Z", "DEVK900100"))))
				.route("GET /sap/bc/adt/repository/informationsystem/search", r -> {
					if (r.path().contains("query=ZTAB")) {
						return FakeAdt.ok(ref("/sap/bc/adt/ddic/tables/ztab", "ZTAB", "TABL/DT"));
					}
					if (r.path().contains("query=ZCL_HELPER")) {
						return FakeAdt.ok(ref("/sap/bc/adt/oo/classes/zcl_helper", "ZCL_HELPER", "CLAS/OC"));
					}
					return FakeAdt.ok("<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"/>");
				})
				.route("GET /sap/bc/adt/ddic/tables/ztab/source/main", r -> new AdtResponse(200, "text/plain", "define table ztab { key id : abap.int4; }"))
				.route("GET /sap/bc/adt/oo/classes/zcl_helper/transports", r -> FakeAdt.ok(
						"<asx:abap xmlns:asx=\"http://www.sap.com/abapxml\"><asx:values><DATA><CORRNR>DEVK900777</CORRNR></DATA></asx:values></asx:abap>"))
				.route("POST /sap/bc/adt/checkruns", r -> FakeAdt.ok(
						"<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"><chkrun:checkMessage chkrun:uri=\""
								+ CALC + "/source/main#start=4,1\" chkrun:type=\"W\" chkrun:shortText=\"Variable never used\"/></chkrun:checkRunReports>"))
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs", r -> FakeAdt.ok(""))
				.route("GET /sap/bc/adt/atc/worklists/W1", r -> FakeAdt.ok(
						"<atcworklist:worklist xmlns:atcworklist=\"http://www.sap.com/adt/atc/worklist\" xmlns:atcfinding=\"http://www.sap.com/adt/atc/finding\">"
								+ "<atcfinding:finding atcfinding:location=\"/sap/bc/adt/programs/programs/zrep/source/main#start=2,0\" atcfinding:priority=\"2\""
								+ " atcfinding:checkTitle=\"Security\" atcfinding:messageTitle=\"Missing authority check\"/></atcworklist:worklist>"))
				.route("POST /sap/bc/adt/abapunit/testruns", r -> FakeAdt.ok("<aunit:runResult xmlns:aunit=\"http://www.sap.com/adt/aunit\"/>"))
				.route("GET /sap/bc/adt/activation/inactiveobjects", r -> FakeAdt.ok(
						"<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<ioc:entry><ioc:object><ioc:ref adtcore:uri=\"/sap/bc/adt/programs/programs/zrep\" adtcore:name=\"ZREP\"/></ioc:object></ioc:entry>"
								+ "</ioc:inactiveObjects>"));
		return adt;
	}

	static String ref(String uri, String name, String type) {
		return "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference adtcore:uri=\""
				+ uri + "\" adtcore:name=\"" + name + "\" adtcore:type=\"" + type + "\"/></adtcore:objectReferences>";
	}

	@Test
	void parsesRequestTasksAndEntries() throws Exception {
		AdtTransportRequest tr = AdtTransportRequest.parse(REQUEST).get(0);
		assertEquals("DEVK900100", tr.id());
		assertEquals(List.of("DEVK900100", "DEVK900101"), List.copyOf(tr.ids()));
		assertEquals(4, tr.entries().size(), "the release comment on the request is left out");
		assertFalse(tr.released());
		assertEquals(1, tr.requestObjects().size());
	}

	@Test
	void rollsUpClassComponentsAndPrograms() throws Exception {
		List<TransportReview.Item> items = TransportReview.rollup(AdtTransportRequest.parse(REQUEST).get(0).entries());
		assertEquals(List.of("CLAS ZCL_CALC", "PROG ZREP", "TABL ZTAB"),
				items.stream().map(i -> i.type() + " " + i.name()).toList());
		assertEquals(List.of("main", "implementations"), TransportReview.classIncludes(items.get(0)));
		assertEquals(List.of("ADD"), TransportReview.methods(items.get(0)));
		AdtTransportRequest.Entry thirtyChars = new AdtTransportRequest.Entry("LIMU", "CINC",
				"ZCL_A_NAME_OF_THIRTY_CHARACTERCCAU", "CLAS/I", "", "T");
		assertEquals("ZCL_A_NAME_OF_THIRTY_CHARACTER", TransportReview.classOwner(thirtyChars));
		assertEquals(List.of("testclasses"), TransportReview.classIncludes(
				new TransportReview.Item("CLAS", "X", "", new java.util.ArrayList<>(List.of(thirtyChars)))));
	}

	@Test
	void dossierShowsChangesChecksAndCompleteness() {
		AdtToolProvider p = new AdtToolProvider(system(), () -> "dev");
		ToolResult r = p.call("adt_transport_review", Json.parseObject("{\"request\":\"devk900100\"}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		String d = r.content();
		assertTrue(d.startsWith("# Transport request DEVK900100 – Calculator\nOwner DEV, modifiable (not released), type Workbench, target QAS\n"
				+ "Tasks: DEVK900101 (DEV, Calculator)\n"), d);
		assertTrue(d.contains("- CLAS ZCL_CALC – x (methods ADD)\n"), d);
		assertTrue(d.contains("### CLAS ZCL_CALC: changed, +1 −0 lines\n```diff\n--- version 00001\n+++ version 00000\n"), d);
		assertTrue(d.contains("+    NEW zcl_helper( )->log( rv ).\n"), d);
		assertTrue(d.contains("### CLAS ZCL_CALC (implementations): no version history in the system"), d);
		assertTrue(d.contains("### PROG ZREP: created by this request, +2 −0 lines"), d);
		assertTrue(d.contains("### TABL ZTAB: current definition (no version history for this type)\n```\ndefine table ztab"), d);
		assertTrue(d.contains("### Syntax check\n- ZCL_CALC Warning line 4: Variable never used\n"), d);
		assertTrue(d.contains("### ATC\n- ZREP Warning line 2: Security: Missing authority check\n"), d);
		assertTrue(d.contains("### ABAP Unit\nNo test methods were executed."), d);
		assertTrue(d.contains("- Not activated (the request would transport the active version only): ZREP\n"), d);
		assertTrue(d.contains("- ZCL_HELPER (CLAS/OC) is used and has unreleased changes in request DEVK900777"), d);
	}

	@Test
	void checksPickTheVersionAndAcceptTheUnitResultType() {
		FakeAdt adt = system();
		List<String> bodies = new java.util.ArrayList<>();
		List<String> accepts = new java.util.ArrayList<>();
		adt.route("POST /sap/bc/adt/checkruns", r -> {
			bodies.add(r.body());
			return FakeAdt.ok("<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"/>");
		});
		adt.route("POST /sap/bc/adt/abapunit/testruns", r -> {
			accepts.add(r.headers().get("Accept"));
			return FakeAdt.ok("<aunit:runResult xmlns:aunit=\"http://www.sap.com/adt/aunit\"/>");
		});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertFalse(p.call("adt_transport_review", Json.parseObject("{\"request\":\"devk900100\"}"), CancelToken.NONE).isError());
		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0).contains("adtcore:uri=\"" + CALC + "\" chkrun:version=\"active\""),
				"only saved, not activated objects have an inactive version: " + bodies.get(0));
		assertTrue(bodies.get(0).contains("adtcore:uri=\"/sap/bc/adt/programs/programs/zrep\" chkrun:version=\"inactive\""),
				bodies.get(0));
		assertTrue(accepts.get(0).startsWith("application/vnd.sap.adt.abapunit.testruns.result.v2+xml"), accepts.get(0));
	}

	@Test
	void atcUsesTheVariantSetForTheSystem() {
		FakeAdt adt = system();
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "", d -> d.equals("dev") ? "z_no_remote" : "");
		assertFalse(p.call("adt_transport_review", Json.parseObject("{\"request\":\"devk900100\"}"), CancelToken.NONE).isError());
		assertTrue(adt.log.contains("POST /sap/bc/adt/atc/worklists?checkVariant=Z_NO_REMOTE"), adt.log.toString());
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("atc/customizing")), "no need to ask for the system default");

		FakeAdt plain = system();
		new AdtToolProvider(plain, () -> "dev").call("adt_transport_review",
				Json.parseObject("{\"request\":\"devk900100\"}"), CancelToken.NONE);
		assertTrue(plain.log.contains("GET /sap/bc/adt/atc/customizing"), plain.log.toString());
	}

	@Test
	void failedUnitRunHasOneHeading() {
		FakeAdt adt = system();
		adt.route("POST /sap/bc/adt/abapunit/testruns", r -> new AdtResponse(406, "text/plain", "not acceptable"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String d = p.call("adt_transport_review", Json.parseObject("{\"request\":\"devk900100\"}"), CancelToken.NONE)
				.content();
		assertTrue(d.contains("### ABAP Unit\nNot possible: "), d);
		assertEquals(d.indexOf("### ABAP Unit"), d.lastIndexOf("### ABAP Unit"), d);
	}

	@Test
	void oneObjectLimitsAndErrors() {
		FakeAdt adt = system();
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String one = p.call("adt_transport_review", Json.parseObject("{\"request\":\"DEVK900100\",\"object\":\"zrep\"}"),
				CancelToken.NONE).content();
		assertTrue(one.contains("### PROG ZREP: created"), one);
		assertFalse(one.contains("ZCL_CALC:"), one);
		assertFalse(one.contains("## Checks"), "a single object skips the checks");
		assertTrue(adt.log.stream().noneMatch(l -> l.startsWith("POST /sap/bc/adt/atc")));

		String missing = p.call("adt_transport_review", Json.parseObject("{\"request\":\"DEVK900100\",\"object\":\"nope\"}"),
				CancelToken.NONE).content();
		assertEquals("Request DEVK900100 contains no object NOPE.", missing);
		assertTrue(p.call("adt_transport_review", Json.parseObject("{\"request\":\"DEVK999999\"}"), CancelToken.NONE).isError());

		String list = p.call("adt_transports", Json.parseObject("{}"), CancelToken.NONE).content();
		assertEquals("DEVK900100  Calculator  (DEV, target QAS, 1 tasks, 4 objects)\n", list);
		assertTrue(adt.log.contains("GET /sap/bc/adt/cts/transportrequests?user=DEV&target=true&requestType=KWT&requestStatus=D"),
				adt.log.toString());
	}

	@Test
	void diffsAreCutAtTheLimit() throws Exception {
		AdtClient c = new AdtClient(system().stateless("dev"));
		AdtTransportRequest tr = c.transport("DEVK900100", CancelToken.NONE).orElseThrow();
		String d = TransportReview.build(c, tr, null, false, new TransportReview.Limits(1, 60, 60_000, 5), CancelToken.NONE);
		assertTrue(d.contains("… (diff cut; adt_transport_review with object=ZCL_CALC shows all of it)"), d);
		assertTrue(d.contains("Not shown (limit reached; call adt_transport_review with 'object' for one of them): ZREP, ZTAB"), d);
	}

	@Test
	void messagesGoToTheObjectWithTheLongestMatchingUri() {
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		names.put("/sap/bc/adt/oo/classes/zcl_ab", "ZCL_AB");
		names.put("/sap/bc/adt/oo/classes/zcl_a", "ZCL_A");
		String out = TransportReview.messages(List.of(
				new AdtClient.Message("Error", "x", "/sap/bc/adt/oo/classes/zcl_ab/source/main#start=5,0", 5),
				new AdtClient.Message("Error", "y", "/sap/bc/adt/oo/classes/zcl_a/source/main#start=2,0", 2)), names);
		assertEquals("- ZCL_AB Error line 5: x\n- ZCL_A Error line 2: y\n", out);
	}

	@Test
	void entriesMergeTasksAndRequestAndStatusN() {
		AdtTransportRequest.Entry a = new AdtTransportRequest.Entry("R3TR", "CLAS", "ZCL_A", "", "", "T1");
		AdtTransportRequest.Entry b = new AdtTransportRequest.Entry("R3TR", "PROG", "ZREP", "", "", "");
		AdtTransportRequest.Entry aAgain = new AdtTransportRequest.Entry("R3TR", "CLAS", "ZCL_A", "", "", "");
		AdtTransportRequest tr = new AdtTransportRequest("R1", "", "", "N", "K", "",
				List.of(new AdtTransportRequest.Task("T1", "", "", "D", List.of(a))), List.of(b, aAgain));
		assertEquals(List.of("ZCL_A", "ZREP"), tr.entries().stream().map(AdtTransportRequest.Entry::name).toList());
		assertTrue(tr.released(), "N = released with import protection");
	}
}

package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** SAP behaviour that ARC-1 verified on real systems and Bella handles the same way. */
class AdtReleaseQuirksTest {

	private static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt;
	}

	private static ToolResult call(FakeAdt adt, String tool, String json) {
		return new AdtToolProvider(adt, () -> "dev").call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	private static String preaudit(String... refs) {
		StringBuilder sb = new StringBuilder("<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\">");
		for (int i = 0; i < refs.length; i += 3) {
			sb.append("<ioc:entry><ioc:object><ioc:ref adtcore:uri=\"").append(refs[i]).append("\" adtcore:type=\"")
					.append(refs[i + 1]).append("\" adtcore:name=\"").append(refs[i + 2])
					.append("\"/></ioc:object><ioc:transport/></ioc:entry>");
		}
		return sb.append("</ioc:inactiveObjects>").toString();
	}

	@Test
	void preauditWithOtherObjectsIsNotReportedAsActivated() {
		List<String> posts = new ArrayList<>();
		FakeAdt adt = system().route("POST /sap/bc/adt/activation", r -> {
			posts.add(r.path());
			return FakeAdt.ok(preaudit("/sap/bc/adt/oo/classes/zcl_a", "CLAS/OC", "ZCL_A",
					"/sap/bc/adt/oo/interfaces/zif_a", "INTF/OI", "ZIF_A"));
		});
		ToolResult r = call(adt, "adt_activate", "{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}");
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("Nothing was activated") && r.content().contains("ZIF_A (INTF/OI)"), r.content());
		assertFalse(r.content().contains("ZCL_A (CLAS/OC)"), r.content());
		// the other objects are not activated behind the developer's back
		assertEquals(1, posts.size(), posts.toString());
	}

	@Test
	void preauditOfTheGivenObjectsIsConfirmed() {
		List<String> posts = new ArrayList<>();
		FakeAdt adt = system().route("POST /sap/bc/adt/activation", r -> {
			posts.add(r.path());
			return FakeAdt.ok(r.path().contains("preauditRequested=true")
					? preaudit("/sap/bc/adt/oo/classes/zcl_a", "CLAS/OC", "ZCL_A")
					: "");
		});
		ToolResult r = call(adt, "adt_activate", "{\"objects\":[{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Activated ZCL_A"), r.content());
		assertEquals(List.of("/sap/bc/adt/activation?method=activate&preauditRequested=true",
				"/sap/bc/adt/activation?method=activate&preauditRequested=false"), posts);
	}

	@Test
	void ordinaryActivationResultIsNoPreaudit() throws Exception {
		assertEquals(List.of(), AdtClient.parsePreaudit(""));
		assertEquals(List.of(), AdtClient.parsePreaudit(
				"<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\"/>"));
	}

	@Test
	void batchFailingWithEd064AloneIsActivatedOneByOne() {
		String ed064 = "<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\"><msg type=\"E\"><shortText>"
				+ "<txt>No next/previous object found (ED064)</txt></shortText></msg></chkl:messages>";
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = system().route("POST /sap/bc/adt/activation", r -> {
			bodies.add(r.body());
			return FakeAdt.ok(r.body().contains("zcl_a") && r.body().contains("zif_a") ? ed064 : "");
		});
		ToolResult r = call(adt, "adt_activate",
				"{\"objects\":[{\"name\":\"ZIF_A\",\"type\":\"INTF\"},{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}");
		assertFalse(r.isError(), r.content());
		assertEquals(3, bodies.size());
		assertTrue(bodies.get(1).contains("zif_a") && !bodies.get(1).contains("zcl_a"), bodies.get(1));
		assertTrue(bodies.get(2).contains("zcl_a") && !bodies.get(2).contains("zif_a"), bodies.get(2));
	}

	@Test
	void realActivationErrorsAreNotRetried() {
		String errors = "<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\">"
				+ "<msg type=\"E\"><shortText><txt>No next/previous object found (ED064)</txt></shortText></msg>"
				+ "<msg type=\"E\"><shortText><txt>Syntax error</txt></shortText></msg></chkl:messages>";
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = system().route("POST /sap/bc/adt/activation", r -> {
			bodies.add(r.body());
			return FakeAdt.ok(errors);
		});
		ToolResult r = call(adt, "adt_activate",
				"{\"objects\":[{\"name\":\"ZIF_A\",\"type\":\"INTF\"},{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}]}");
		assertTrue(r.isError(), r.content());
		assertEquals(1, bodies.size());
	}

	private static final String WORKLIST = "<atcworklist:worklist xmlns:atcworklist=\"http://www.sap.com/adt/atc/worklist\" "
			+ "xmlns:atcfinding=\"http://www.sap.com/adt/atc/finding\"><atcfinding:finding "
			+ "atcfinding:location=\"/sap/bc/adt/programs/programs/zrep/source/main#start=3,0\" atcfinding:priority=\"1\" "
			+ "atcfinding:checkTitle=\"Check\" atcfinding:messageTitle=\"Finding\"/></atcworklist:worklist>";

	@Test
	void atcRunIsAwaitedUntilCompleted() throws Exception {
		List<String> states = new ArrayList<>(List.of("Running", "Completed"));
		FakeAdt adt = system()
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs?worklistId=W1&clientWait=false", r -> new AdtResponse(201,
						"application/xml", "", Map.of("Location", "https://host:443/sap/bc/adt/atc/runs/R1")))
				.route("GET /sap/bc/adt/atc/runs/R1", r -> FakeAdt.ok(
						"<atc:run xmlns:atc=\"http://www.sap.com/adt/atc\" status=\"" + states.remove(0) + "\"/>"))
				.route("GET /sap/bc/adt/atc/worklists/W1", r -> FakeAdt.ok(WORKLIST));
		List<AdtClient.Message> msgs = new AdtClient(adt.stateless("dev"))
				.atcCheck("/sap/bc/adt/programs/programs/zrep", "ZV", CancelToken.NONE);
		assertEquals(1, msgs.size());
		assertEquals(2, adt.log.stream().filter(l -> l.equals("GET /sap/bc/adt/atc/runs/R1")).count(), adt.log.toString());
		assertTrue(adt.log.indexOf("GET /sap/bc/adt/atc/worklists/W1?includeExemptedFindings=false")
				> adt.log.lastIndexOf("GET /sap/bc/adt/atc/runs/R1"), adt.log.toString());
	}

	@Test
	void failedAtcRunIsAnError() {
		FakeAdt adt = system()
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs", r -> new AdtResponse(201, "application/xml", "",
						Map.of("Location", "/sap/bc/adt/atc/runs/R1")))
				.route("GET /sap/bc/adt/atc/runs/R1", r -> FakeAdt.ok(
						"<atc:run xmlns:atc=\"http://www.sap.com/adt/atc\" status=\"Failed\"/>"));
		AdtException e = assertThrows(AdtException.class, () -> new AdtClient(adt.stateless("dev"))
				.atcCheck("/sap/bc/adt/programs/programs/zrep", "ZV", CancelToken.NONE));
		assertTrue(e.getMessage().contains("status Failed"), e.getMessage());
	}

	@Test
	void atcRunWithoutLocationReadsTheWorklistDirectly() throws Exception {
		FakeAdt adt = system()
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs", r -> FakeAdt.ok(""))
				.route("GET /sap/bc/adt/atc/worklists/W1", r -> FakeAdt.ok(WORKLIST));
		assertEquals(1, new AdtClient(adt.stateless("dev"))
				.atcCheck("/sap/bc/adt/programs/programs/zrep", "ZV", CancelToken.NONE).size());
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("/atc/runs/")), adt.log.toString());
	}

	@Test
	void atcRunStatusPathOnlyForRunsOfThisSystem() {
		assertEquals("/sap/bc/adt/atc/runs/R1", AdtClient.atcRunStatusPath("/sap/bc/adt/atc/runs/R1"));
		assertEquals("/sap/bc/adt/atc/runs/R1", AdtClient.atcRunStatusPath("http://h:50000/sap/bc/adt/atc/runs/R1?x=1"));
		assertNull(AdtClient.atcRunStatusPath(null));
		assertNull(AdtClient.atcRunStatusPath("/sap/bc/adt/atc/runs/"));
		assertNull(AdtClient.atcRunStatusPath("/sap/bc/adt/atc/runs/R1/../x"));
		assertNull(AdtClient.atcRunStatusPath("/sap/bc/adt/other/R1"));
	}

	@Test
	void versionFeedsKeepTheirPath() {
		assertTrue(AdtRequest.get("/sap/bc/adt/oo/classes/zcl_a/includes/definitions/versions", "application/atom+xml")
				.exactPath());
		assertTrue(AdtRequest.get("/sap/bc/adt/programs/programs/zrep/source/main/versions", "x").exactPath());
		assertFalse(AdtRequest.get("/sap/bc/adt/programs/programs/zrep/source/main/versions/19700101/00000/content", "x")
				.exactPath());
		assertFalse(AdtRequest.post("/sap/bc/adt/programs/programs/zrep/source/main/versions", "x", "", "x").exactPath());
	}

	@Test
	void searchInPackageDropsObjectsOfOtherPackages() {
		List<AdtObjectRef> hits = List.of(new AdtObjectRef("/a", "ZA", "CLAS/OC", "ZPKG", ""),
				new AdtObjectRef("/b", "ZB", "CLAS/OC", "ZOTHER", ""), new AdtObjectRef("/c", "ZC", "CLAS/OC", "", ""));
		assertEquals(List.of("ZA"), AdtClient.inPackage(hits, "zpkg").stream().map(AdtObjectRef::name).toList());
		assertEquals(List.of("ZA", "ZC"), AdtClient.inPackage(List.of(hits.get(0), hits.get(2)), "ZPKG").stream()
				.map(AdtObjectRef::name).toList());
		assertEquals(hits, AdtClient.inPackage(hits, null));
	}

	@Test
	void packageRunIgnoresObjectsOfOtherPackages() {
		List<String> runs = new ArrayList<>();
		String ref = "<adtcore:objectReference adtcore:uri=\"%s\" adtcore:type=\"%s\" adtcore:name=\"%s\" "
				+ "adtcore:packageName=\"%s\"/>";
		// a release without the packageName filter answers with objects of any package
		FakeAdt adt = system().route("GET /sap/bc/adt/packages/zold", r -> FakeAdt.ok("<pak:package/>"))
				.route(AdtContextTest.SEARCH, r -> FakeAdt.ok("<adtcore:objectReferences xmlns:adtcore=\""
						+ "http://www.sap.com/adt/core\">"
						+ String.format(ref, "/sap/bc/adt/oo/classes/zcl_a", "CLAS/OC", "ZCL_A", "ZOLD")
						+ String.format(ref, "/sap/bc/adt/oo/classes/cl_x", "CLAS/OC", "CL_X", "SX")
						+ String.format(ref, "/sap/bc/adt/packages/sy", "DEVC/K", "SY", "SX")
						+ "</adtcore:objectReferences>"))
				.route("POST /sap/bc/adt/abapunit/testruns", r -> {
					runs.add(r.body());
					return FakeAdt.ok("<aunit:runResult xmlns:aunit=\"http://www.sap.com/adt/aunit\"/>");
				});
		ToolResult r = call(adt, "adt_run_unit_tests", "{\"package\":\"ZOLD\",\"include_subpackages\":true}");
		assertFalse(r.isError(), r.content());
		assertEquals(1, runs.size(), runs.toString());
		assertTrue(runs.get(0).contains("zcl_a") && !runs.get(0).contains("cl_x"), runs.get(0));
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("packageName=SY")), adt.log.toString());
	}

	@Test
	void sqlLinesFitSapsLimitAndUseCrlf() throws Exception {
		assertEquals("SELECT *\r\nFROM t000", AdtClient.fitSqlLines("SELECT *\nFROM t000"));
		StringBuilder in = new StringBuilder("SELECT * FROM t000 WHERE mandt IN (");
		for (int i = 0; i < 80; i++) {
			in.append(i == 0 ? "" : ", ").append("'").append(String.format("%03d", i)).append("'");
		}
		String sql = in.append(")").toString();
		String fitted = AdtClient.fitSqlLines(sql);
		for (String line : fitted.split("\r\n")) {
			assertTrue(line.length() <= AdtClient.SQL_LINE_MAX, line);
		}
		assertEquals(sql, fitted.replace("\r\n", " "));
		// a literal must not be broken
		String literal = "SELECT * FROM t000 WHERE mtext = '" + "x ".repeat(200) + "'";
		assertThrows(AdtException.class, () -> AdtClient.fitSqlLines(literal));
	}

	@Test
	void tableContentsCutsTheExtraRow() throws Exception {
		FakeAdt adt = system().route("POST /sap/bc/adt/datapreview/freestyle", r -> FakeAdt.ok(
				"<dataPreview:tableData xmlns:dataPreview=\"http://www.sap.com/adt/dataPreview\"><dataPreview:totalRows>5"
						+ "</dataPreview:totalRows><dataPreview:columns><dataPreview:metadata dataPreview:name=\"MANDT\"/>"
						+ "<dataPreview:dataSet><dataPreview:data>000</dataPreview:data><dataPreview:data>001"
						+ "</dataPreview:data><dataPreview:data>066</dataPreview:data></dataPreview:dataSet>"
						+ "</dataPreview:columns></dataPreview:tableData>"));
		AdtClient.TableData d = new AdtClient(adt.stateless("dev")).tableContents("SELECT mandt FROM t000", 2,
				CancelToken.NONE);
		assertEquals(2, d.rows().size());
	}

	@Test
	void legacyMediaTypesOnlyAfterAMissingContentHandler() {
		AdtResponse missing = new AdtResponse(400, "application/xml", "<exc:exception xmlns:exc=\"x\"><type id=\""
				+ "ExceptionContentHandlerNotFound\"/></exc:exception>");
		AdtRequest get = AdtRequest.get("/sap/bc/adt/oo/classes/zcl_a", "application/*");
		assertEquals("application/vnd.sap.adt.oo.classes+xml",
				AdtClient.legacyMediaTypeRetry(get, missing).headers().get("Accept"));
		AdtRequest create = AdtRequest.post("/sap/bc/adt/programs/programs?corrNr=K1", "application/*", "<x/>",
				"application/*");
		AdtRequest retry = AdtClient.legacyMediaTypeRetry(create, missing);
		assertEquals("application/vnd.sap.adt.programs.programs+xml", retry.contentType());
		assertEquals(create.path(), retry.path());
		assertNull(AdtClient.legacyMediaTypeRetry(get, new AdtResponse(400, "application/xml", "<other/>")));
		assertNull(AdtClient.legacyMediaTypeRetry(AdtRequest.get("/sap/bc/adt/oo/classes/zcl_a/source/main",
				"application/*"), missing));
	}

	@Test
	void lostLockHandleIsExplained() {
		String m = AdtErrors.message(new AdtResponse(423, "application/xml",
				"<exc:exception xmlns:exc=\"x\"><message>Invalid lock handle</message></exc:exception>"));
		assertTrue(m.contains("SAP_BASIS 7.51"), m);
		assertFalse(AdtErrors.message(new AdtResponse(423, "application/xml",
				"<exc:exception xmlns:exc=\"x\"><message>Locked by user X</message></exc:exception>"))
				.contains("7.51"));
	}
}

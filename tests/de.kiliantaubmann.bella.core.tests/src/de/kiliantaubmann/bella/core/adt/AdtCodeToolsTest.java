package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.ClassSurgeryTest;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtCodeToolsTest {

	private static FakeAdt adt() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt;
	}

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	private static String table(String[] names, String[][] rows) {
		StringBuilder sb = new StringBuilder("<dataPreview:tableData xmlns:dataPreview=\"http://www.sap.com/adt/dataPreview\">"
				+ "<dataPreview:totalRows>" + rows.length + "</dataPreview:totalRows>");
		for (int c = 0; c < names.length; c++) {
			sb.append("<dataPreview:columns><dataPreview:metadata dataPreview:name=\"").append(names[c])
					.append("\"/><dataPreview:dataSet>");
			for (String[] row : rows) {
				sb.append("<dataPreview:data>").append(row[c]).append("</dataPreview:data>");
			}
			sb.append("</dataPreview:dataSet></dataPreview:columns>");
		}
		return sb.append("</dataPreview:tableData>").toString();
	}

	@Test
	void apiStateAndVersions() {
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/apireleases/%2Fsap%2Fbc%2Fadt%2Foo%2Fclasses%2Fcl_abap_random", r -> FakeAdt.ok(
						"<ars:apiRelease xmlns:ars=\"http://www.sap.com/adt/ars\"><ars:c1Release ars:contract=\"C1\" "
								+ "ars:useInSAPCloudPlatform=\"true\" ars:useInKeyUserApps=\"false\"><ars:status ars:state=\"RELEASED\" "
								+ "ars:stateDescription=\"Released\"/></ars:c1Release><ars:c2Release ars:contract=\"C2\">"
								+ "<ars:status ars:state=\"DEPRECATED\" ars:stateDescription=\"Deprecated\"/><ars:successors>"
								+ "<ars:successor ars:name=\"CL_ABAP_RANDOM_NEW\"/></ars:successors></ars:c2Release></ars:apiRelease>"))
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main/versions/20261001100000/00002/content",
						r -> new AdtResponse(200, "text/plain", "CLASS zcl_a old source"))
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main/versions", r -> FakeAdt.ok(
						"<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"><atom:entry><atom:id>00002</atom:id>"
								+ "<atom:updated>2026-10-01T10:00:00Z</atom:updated><atom:author><atom:name>DEV</atom:name></atom:author>"
								+ "<atom:content src=\"/sap/bc/adt/oo/classes/zcl_a/source/main/versions/20261001100000/00002/content\"/>"
								+ "</atom:entry></atom:feed>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult state = call(p, "adt_object_info", "{\"name\":\"CL_ABAP_RANDOM\",\"type\":\"CLAS\",\"action\":\"api_state\"}");
		assertEquals("Release state of CL_ABAP_RANDOM:\n- C1: Released (ABAP Cloud)\n- C2: Deprecated; successor "
				+ "CL_ABAP_RANDOM_NEW\n", state.content());
		ToolResult versions = call(p, "adt_object_info", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"versions\"}");
		assertFalse(versions.isError(), versions.content());
		assertTrue(versions.content().contains("00002"), versions.content());
		assertEquals("CLASS zcl_a old source", call(p, "adt_object_info",
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"version_source\",\"version\":\"00002\"}").content());
		assertTrue(call(p, "adt_object_info", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"x\"}").isError());
	}

	private static String version(String number, String time, String transport) {
		return "<atom:entry><atom:author><atom:name>DEV</atom:name></atom:author><atom:content type=\"text/plain\" "
				+ "src=\"/sap/bc/adt/programs/programs/zrep/source/main/versions/1/" + number + "/content\"/><atom:id>"
				+ number + "</atom:id>" + (transport == null ? "" : "<atom:link adtcore:name=\"" + transport
						+ "\" href=\"/sap/bc/adt/cts/transportrequests/" + transport
						+ "\" rel=\"http://www.sap.com/adt/relations/transport/request\"/>")
				+ "<atom:updated>" + time + "</atom:updated></atom:entry>";
	}

	@Test
	void transportHistoryFromVersions() {
		FakeAdt adt = adt().route("GET /sap/bc/adt/programs/programs/zrep/source/main/versions", r -> FakeAdt.ok(
				"<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
						+ version("00003", "2026-06-25T10:00:00Z", "DEVK900200")
						+ version("00002", "2026-06-24T10:00:00Z", "DEVK900200")
						+ version("00000", "2026-06-23T12:00:00Z", null)
						+ version("00001", "2026-06-23T09:00:00Z", "DEVK900100") + "</atom:feed>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_transports", "{\"action\":\"history\",\"name\":\"ZREP\",\"type\":\"PROG\"}");
		assertEquals("Transport requests of ZREP, newest first:\n"
				+ "DEVK900200  2026-06-24T10:00:00Z – 2026-06-25T10:00:00Z  DEV  (2 versions)\n"
				+ "DEVK900100  2026-06-23T09:00:00Z  DEV  (1 version)\n"
				+ "1 version without a transport request (e.g. the active or a local one).\n", r.content());
		assertEquals(r.content(), call(p, "adt_transports",
				"{\"action\":\"history\",\"name\":\"ZREP\",\"type\":\"PROG\",\"include\":\"  main \"}").content());
		assertTrue(call(p, "adt_transports", "{\"action\":\"x\"}").isError());
	}

	@Test
	void metadataExtensionsHaveADirectUri() {
		assertEquals("/sap/bc/adt/ddic/ddlx/sources/zc_travel_mde", AdtObjectRef.uriFor("ZC_TRAVEL_MDE", "DDLX"));
		assertEquals("/sap/bc/adt/acm/dcl/sources/zi_travel_dcl", AdtObjectRef.uriFor("ZI_TRAVEL_DCL", "DCLS/DL"));
	}

	@Test
	void navigation() {
		List<String> sql = new ArrayList<>();
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", "src"))
				.route("POST /sap/bc/adt/navigation/target", r -> FakeAdt.ok(
						"<adtcore:objectReference xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:uri="
								+ "\"/sap/bc/adt/oo/classes/zcl_b/source/main#start=30,16\" adtcore:type=\"CLAS/OM\" adtcore:name=\"GET\"/>"))
				.route("POST /sap/bc/adt/datapreview/freestyle", r -> {
					sql.add(r.body());
					return FakeAdt.ok(r.body().contains("refclsname = 'ZCL_A'")
							? table(new String[] { "CLSNAME" }, new String[][] { { "ZCL_A_SUB" } })
							: table(new String[] { "CLSNAME", "REFCLSNAME", "RELTYPE" },
									new String[][] { { "ZCL_A", "ZCL_BASE", "2" }, { "ZCL_A", "ZIF_X", "1" } }));
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult def = call(p, "adt_navigate", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"definition\",\"line\":3,\"column\":8}");
		assertEquals("Defined in GET (CLAS/OM) line 30: /sap/bc/adt/oo/classes/zcl_b/source/main#start=30,16",
				def.content());
		assertTrue(adt.log.stream().anyMatch(l -> l.contains("%23start%3D3%2C8&filter=definition")), adt.log.toString());
		assertEquals("ZCL_A: superclass ZCL_BASE; interfaces ZIF_X; subclasses ZCL_A_SUB.",
				call(p, "adt_navigate", "{\"name\":\"zcl_a\",\"action\":\"hierarchy\"}").content());
		assertTrue(call(p, "adt_navigate", "{\"name\":\"x' OR '1\",\"action\":\"hierarchy\"}").isError());
		assertEquals(2, sql.size());
		assertTrue(call(p, "adt_navigate", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"completion\"}").isError());
	}

	@Test
	void editCodeWritesAndRefusesNewSyntaxErrors() {
		List<String> written = new ArrayList<>();
		boolean[] broken = { false };
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", ClassSurgeryTest.CLASS))
				.route("POST /sap/bc/adt/checkruns", r -> FakeAdt.ok(broken[0] && r.body().contains("chkrun:content")
						&& !r.body().contains(java.util.Base64.getEncoder().encodeToString(
								ClassSurgeryTest.CLASS.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
								? "<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"><chkrun:checkMessage "
										+ "chkrun:uri=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=3,1\" chkrun:type=\"E\" "
										+ "chkrun:shortText=\"Type X is unknown\"/></chkrun:checkRunReports>"
								: "<chkrun:checkRunReports xmlns:chkrun=\"http://www.sap.com/adt/checkrun\"/>"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT /sap/bc/adt/oo/classes/zcl_a/source/main", r -> {
					written.add(r.body());
					return FakeAdt.ok("");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult ok = call(p, "adt_edit_code", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"add_method\","
				+ "\"source\":\"METHODS count RETURNING VALUE(rv) TYPE i.\"}");
		assertFalse(ok.isError(), ok.content());
		assertTrue(ok.content().startsWith("Saved add_method in ZCL_A in S4H_100. Not activated yet."), ok.content());
		assertTrue(written.get(0).contains("METHOD count.\n  ENDMETHOD."), written.get(0));

		broken[0] = true;
		ToolResult refused = call(p, "adt_edit_code", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"add_method\","
				+ "\"source\":\"METHODS other RETURNING VALUE(rv) TYPE x.\"}");
		assertTrue(refused.isError(), refused.content());
		assertTrue(refused.content().contains("Type X is unknown"), refused.content());
		assertEquals(1, written.size());
		ToolResult surgery = call(p, "adt_edit_code", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"action\":\"delete_method\","
				+ "\"method\":\"nope\"}");
		assertTrue(surgery.isError() && surgery.content().contains("NOPE is not declared"), surgery.content());
	}

	@Test
	void deleteChecksWhereUsedFirst() {
		List<String> deletes = new ArrayList<>();
		FakeAdt adt = adt()
				.route("POST /sap/bc/adt/repository/informationsystem/usageReferences", r -> FakeAdt.ok(
						"<usageReferences:usageReferenceResult xmlns:usageReferences=\"http://www.sap.com/adt/ris/usageReferences\" "
								+ "xmlns:adtcore=\"http://www.sap.com/adt/core\"><usageReferences:referencedObjects>"
								+ "<usageReferences:referencedObject uri=\"/sap/bc/adt/programs/programs/zrep\"><usageReferences:adtObject "
								+ "adtcore:name=\"ZREP\" adtcore:type=\"PROG/P\"/></usageReferences:referencedObject>"
								+ "</usageReferences:referencedObjects></usageReferences:usageReferenceResult>"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/oo/classes/zcl_a?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("DELETE /sap/bc/adt/oo/classes/zcl_a", r -> {
					deletes.add(r.path());
					return FakeAdt.ok("");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult used = call(p, "adt_delete_object", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\"}");
		assertTrue(used.isError() && used.content().startsWith("ZCL_A is used by 1 objects, e.g. ZREP."), used.content());
		assertTrue(deletes.isEmpty());
		ToolResult forced = call(p, "adt_delete_object", "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"force\":true}");
		assertEquals("Deleted ZCL_A in S4H_100.", forced.content());
		assertEquals(List.of("/sap/bc/adt/oo/classes/zcl_a?lockHandle=H"), deletes);
	}
}

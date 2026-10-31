package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** CDS test cases and SQL, inactive objects, components, application logs, launchpad writes and TADIR lookups. */
class AdtArcPortTest {

	private static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt;
	}

	private static ToolResult call(FakeAdt adt, String tool, String json) {
		return new AdtToolProvider(adt, () -> "dev").call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void cdsTestCasesAndSql() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/aunit/dbtestdoubles/cds/testcases?ddlsourceName=I_CURRENCY", r -> FakeAdt.ok(
						"<cdstc:root xmlns:cdstc=\"http://www.sap.com/adt/aunit/cds\"><cdstc:cds>I_CURRENCY</cdstc:cds>"
								+ "<cdstc:testCases><cdstc:testCase><cdstc:title>Whole view</cdstc:title><cdstc:testMethod>"
								+ "test_view</cdstc:testMethod><cdstc:description>Checks all rows</cdstc:description>"
								+ "<cdstc:semanticType>VIEW</cdstc:semanticType></cdstc:testCase><cdstc:testCase>"
								+ "<cdstc:testMethod>test_decimals</cdstc:testMethod><cdstc:semanticType>CAST"
								+ "</cdstc:semanticType><cdstc:calculatedField>Decimals</cdstc:calculatedField>"
								+ "</cdstc:testCase></cdstc:testCases></cdstc:root>"))
				.route("POST /sap/bc/adt/ddic/ddl/createstatements/I_CURRENCY", r -> FakeAdt.ok(
						"<ddl:createStatements xmlns:ddl=\"http://www.sap.com/adt/ddic/ddl\"><ddl:source ddl:name=\"I_CURRENCY\"/>"
								+ "<ddl:createStatement name=\"ICURRENCY\" type=\"VIEW\" state=\"active\"><ddl:statement>CREATE "
								+ "VIEW \"ICURRENCY\" AS SELECT 1</ddl:statement></ddl:createStatement></ddl:createStatements>"));
		ToolResult tc = call(adt, "adt_diagnose", "{\"action\":\"cds_testcases\",\"name\":\"i_currency\"}");
		assertEquals("SAP's suggested test cases for I_CURRENCY (2; methods for a test class with the CDS Test Double "
				+ "Framework):\n- test_view – Whole view [VIEW]\n  Checks all rows\n- test_decimals [CAST], field Decimals\n",
				tc.content());
		assertEquals("-- ICURRENCY (VIEW), active\nCREATE VIEW \"ICURRENCY\" AS SELECT 1",
				call(adt, "adt_diagnose", "{\"action\":\"cds_sql\",\"name\":\"I_CURRENCY\"}").content());
		assertTrue(call(system(), "adt_diagnose", "{\"action\":\"cds_testcases\",\"name\":\"I_CURRENCY\"}").content()
				.contains("SAP_BASIS 8.16"));
		assertTrue(call(adt, "adt_diagnose", "{\"action\":\"cds_sql\"}").isError());
	}

	@Test
	void inactiveObjectsAndComponents() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/activation/inactiveobjects", r -> FakeAdt.ok(
						"<ioc:inactiveObjects xmlns:ioc=\"http://www.sap.com/abapxml/inactiveCtsObjects\" "
								+ "xmlns:adtcore=\"http://www.sap.com/adt/core\"><ioc:entry><ioc:object ioc:user=\"DEVELOPER\" "
								+ "ioc:deleted=\"false\"><ioc:ref adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a\" "
								+ "adtcore:type=\"CLAS/OC\" adtcore:name=\"ZCL_A\" adtcore:description=\"Orders\"/></ioc:object>"
								+ "<ioc:transport><ioc:ref adtcore:uri=\"/sap/bc/adt/cts/transportrequests/S4HK900001\" "
								+ "adtcore:name=\"S4HK900001\"/></ioc:transport></ioc:entry></ioc:inactiveObjects>"))
				.route("GET /sap/bc/adt/system/components", r -> FakeAdt.ok(
						"<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\"><atom:entry><atom:id>SAP_BASIS</atom:id>"
								+ "<atom:title>758;SAPK-75802INSAPBASIS;0002;SAP Basis Component</atom:title></atom:entry>"
								+ "</atom:feed>"));
		assertEquals("Inactive objects (1; saved, not activated):\n- ZCL_A (CLAS/OC) – Orders, user DEVELOPER, request "
				+ "S4HK900001", call(adt, "adt_diagnose", "{\"action\":\"inactive_objects\"}").content());
		assertEquals("Installed software components (release, support package):\n- SAP_BASIS 758, SAPK-75802INSAPBASIS "
				+ "0002 – SAP Basis Component\n", call(adt, "adt_diagnose", "{\"action\":\"components\"}").content());
	}

	@Test
	void applicationLog() {
		String handle = "ABCDEFGHIJKLMNOPQRSTU'";
		List<String> paths = new ArrayList<>();
		FakeAdt adt = system().route("GET " + AdtAppLog.SERVICE, r -> {
			paths.add(r.path());
			if (r.path().contains("ApplicationLogMessageSet")) {
				return FakeAdt.ok("{\"d\":{\"results\":[{\"LogHandle\":\"" + handle + "\",\"MessageNumber\":\"000001\","
						+ "\"Type\":\"E\",\"Text\":\"Posting failed\",\"Timestamp\":\"20260101120000\",\"HasLongText\":true,"
						+ "\"HasDetailText\":false},{\"LogHandle\":\"" + handle + "\",\"MessageNumber\":\"000002\",\"Type\":"
						+ "\"I\",\"Text\":\"Next\",\"Timestamp\":\"20260101120001\",\"HasLongText\":false,\"HasDetailText\":"
						+ "false}]}}");
			}
			return FakeAdt.ok("{\"d\":{\"results\":[{\"LogNumber\":\"00000000000000000042\",\"LogHandle\":\"" + handle
					+ "\",\"Object\":\"ZSALES\",\"SubObject\":\"POST\",\"ExternalNumber\":\"\",\"Severity\":\"E\","
					+ "\"CreatedAt\":\"20260101120000\"}]}}");
		});
		ToolResult r = call(adt, "adt_table_contents", "{\"action\":\"application_log\",\"log_number\":"
				+ "\"00000000000000000042\",\"max_rows\":1}");
		assertFalse(r.isError(), r.content());
		assertEquals("Application log 00000000000000000042: object ZSALES / POST, severity E, created 20260101120000\n"
				+ "000001 E 20260101120000: Posting failed [long text in SLG1]\nMore messages: read on with 'offset' 1.\n"
				+ "Pages are read one by one; the log can change in between.", r.content());
		assertTrue(paths.get(1).contains("ApplicationLogOverviewSet('ABCDEFGHIJKLMNOPQRSTU%27%27')/"), paths.get(1));
		assertTrue(call(adt, "adt_table_contents", "{\"action\":\"application_log\",\"log_number\":\"42\"}").isError());
	}

	@Test
	void applicationLogRefusesUnexpectedAnswers() {
		FakeAdt adt = system().route("GET " + AdtAppLog.SERVICE, r -> FakeAdt.ok("{\"d\":{\"results\":[{\"LogNumber\":"
				+ "\"00000000000000000099\",\"LogHandle\":\"x\"}]}}"));
		ToolResult r = call(adt, "adt_table_contents", "{\"action\":\"application_log\",\"log_number\":"
				+ "\"00000000000000000042\"}");
		assertTrue(r.isError() && r.content().contains("answered unexpectedly"), r.content());
	}

	@Test
	void launchpadWritesWithToken() {
		List<AdtRequest> sent = new ArrayList<>();
		FakeAdt adt = system().route("GET " + AdtFlp.SERVICE + "/", r -> new AdtResponse(200, "application/xml", "",
				Map.of("x-csrf-token", "TOKEN"))).route("POST " + AdtFlp.SERVICE, r -> {
					sent.add(r);
					return new AdtResponse(201, "application/json", "{\"d\":{\"instanceId\":\"00O2\"}}");
				}).route("DELETE " + AdtFlp.SERVICE, r -> {
					sent.add(r);
					return new AdtResponse(204, "application/json", "");
				});
		ToolResult tile = call(adt, "adt_flp_manage", "{\"action\":\"create_tile\",\"catalog\":\"X-SAP-UI2-CATALOGPAGE:"
				+ "ZSALES\",\"title\":\"Orders\",\"semantic_object\":\"SalesOrder\",\"semantic_action\":\"display\","
				+ "\"tile_id\":\"t1\"}");
		assertFalse(tile.isError(), tile.content());
		assertTrue(tile.content().contains("HTTP 201"), tile.content());
		AdtRequest post = sent.get(0);
		assertEquals("TOKEN", post.headers().get("x-csrf-token"));
		JsonObject body = Json.parseObject(post.body());
		assertEquals("X-SAP-UI2-CATALOGPAGE:ZSALES", Json.str(body, "pageId"));
		assertEquals(AdtFlp.LAUNCHER, Json.str(body, "chipId"));
		assertEquals("{\"tileConfiguration\":\"{\\\"id\\\":\\\"t1\\\",\\\"display_title_text\\\":\\\"Orders\\\","
				+ "\\\"semantic_object\\\":\\\"SalesOrder\\\",\\\"semantic_action\\\":\\\"display\\\"}\"}",
				Json.str(body, "configuration"));
		call(adt, "adt_flp_manage", "{\"action\":\"add_tile_to_group\",\"catalog\":\"ZSALES\",\"group\":\"ZG\","
				+ "\"tile_instance_id\":\"00O2\"}");
		assertEquals("X-SAP-UI2-PAGE:X-SAP-UI2-CATALOGPAGE:ZSALES:00O2", Json.str(Json.parseObject(sent.get(1).body()),
				"chipId"));
		call(adt, "adt_flp_manage", "{\"action\":\"delete_catalog\",\"catalog\":\"X-SAP-UI2-CATALOGPAGE:Z O'K\"}");
		assertEquals(AdtFlp.SERVICE + "/Catalogs('X-SAP-UI2-CATALOGPAGE%3AZ%20O%27%27K')", sent.get(2).path());
		assertTrue(call(adt, "adt_flp_manage", "{\"action\":\"create_group\",\"group\":\"ZG\"}").content()
				.contains("Give 'title'"));
		assertEquals(3, sent.size());
		// asks even in Automode
		assertEquals(ToolPolicy.Decision.CONFIRM, new ToolPolicy(List.of(), ChatMode.AUTO).decide(ToolSpec.of(
				"adt_flp_manage", "", new JsonObject(), null, ToolSpec.Kind.WRITE), new JsonObject()));
	}

	@Test
	void tadirLookupNamesGhosts() {
		List<String> sqls = new ArrayList<>();
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/repository/informationsystem/search", r -> FakeAdt.ok(r.path().contains("ZCL_A")
						? "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference "
								+ "adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a\" adtcore:type=\"CLAS/OC\" adtcore:name=\"ZCL_A\" "
								+ "adtcore:packageName=\"$TMP\"/></adtcore:objectReferences>"
						: "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"/>"))
				.route("POST /sap/bc/adt/datapreview/freestyle", r -> {
					sqls.add(r.body());
					Map<String, List<String>> cols = new LinkedHashMap<>();
					cols.put("PGMID", List.of("R3TR", "R3TR"));
					cols.put("OBJECT", List.of("CLAS", "PROG"));
					cols.put("OBJ_NAME", List.of("ZCL_A", "ZGHOST"));
					cols.put("DEVCLASS", List.of("$TMP", "$TMP"));
					cols.put("AUTHOR", List.of("DEVELOPER", "DEVELOPER"));
					cols.put("SRCSYSTEM", List.of("S4H", "S4H"));
					cols.put("DELFLAG", List.of("", ""));
					return FakeAdt.ok(AdtStoredTextTest.table(cols));
				});
		ToolResult r = call(adt, "adt_search_objects", "{\"query\":\"zcl_a, zghost\",\"search_in\":\"tadir\"}");
		assertTrue(r.content().startsWith("ZCL_A:\n  ADT: CLAS package $TMP\n  TADIR: R3TR CLAS package $TMP"), r.content());
		assertTrue(r.content().contains("ZGHOST:\n  ADT: not found\n  TADIR: R3TR PROG package $TMP, author DEVELOPER, "
				+ "source system S4H\n  Only in TADIR"), r.content());
		assertEquals("SELECT pgmid, object, obj_name, devclass, author, srcsystem, delflag FROM tadir WHERE obj_name IN "
				+ "('ZCL_A', 'ZGHOST')", sqls.get(0));
		assertTrue(call(adt, "adt_search_objects", "{\"query\":\"ZCL_*\",\"search_in\":\"tadir\"}").isError());
	}
}

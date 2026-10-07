package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** Diagnostics, transports, packages, Git, RAP and UI5: request paths, bodies and parsers. */
class AdtOpsTest {

	private static FakeAdt adt() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt;
	}

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	private static String feed(String... entries) {
		return "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\">" + String.join("", entries) + "</atom:feed>";
	}

	private static final String TM = "xmlns:tm=\"http://www.sap.com/cts/adt/tm\"";

	// ---- diagnostics -------------------------------------------------------------

	@Test
	void diagnoseReads() {
		List<String> sql = new ArrayList<>();
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/runtime/systemmessages", r -> FakeAdt.ok(feed("<atom:entry><atom:title>Downtime"
						+ "</atom:title><atom:updated>2026-10-07T08:00:00Z</atom:updated><atom:author><atom:name>ADMIN"
						+ "</atom:name></atom:author><atom:summary>From 18:00\n until 20:00</atom:summary><atom:id>1</atom:id>"
						+ "</atom:entry>")))
				.route("GET /sap/bc/adt/runtime/traces/abaptraces/T1/statements", r -> FakeAdt.ok(
						"<trc:statements xmlns:trc=\"http://www.sap.com/adt/runtime/traces/abaptraces\"><trc:statement "
								+ "trc:id=\"1\" trc:description=\"SELECT ZTAB\" trc:hitCount=\"40\"/></trc:statements>"))
				.route("POST /sap/bc/adt/datapreview/freestyle", r -> {
					sql.add(r.body());
					return FakeAdt.ok("<dataPreview:tableData xmlns:dataPreview=\"http://www.sap.com/adt/dataPreview\">"
							+ "<dataPreview:totalRows>0</dataPreview:totalRows></dataPreview:tableData>");
				})
				.route("GET /sap/bc/adt/atc/variants?name=Z*", r -> FakeAdt.ok(
						"<nameditem:namedItemList xmlns:nameditem=\"http://www.sap.com/adt/nameditem\"><nameditem:namedItem>"
								+ "<nameditem:name>ZCLEAN</nameditem:name><nameditem:description>Clean code</nameditem:description>"
								+ "</nameditem:namedItem><nameditem:namedItem><nameditem:name>ZCLEAN</nameditem:name>"
								+ "</nameditem:namedItem></nameditem:namedItemList>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");

		assertEquals("- Downtime | 2026-10-07T08:00:00Z | ADMIN | From 18:00 until 20:00 | id 1\n",
				call(p, "adt_diagnose", "{\"action\":\"system_messages\"}").content());
		assertEquals("statement: description=SELECT ZTAB hitCount=40 id=1\n", call(p, "adt_diagnose",
				"{\"action\":\"traces\",\"id\":\"/sap/bc/adt/runtime/traces/abaptraces/T1\",\"part\":\"statements\"}")
				.content());
		assertTrue(call(p, "adt_diagnose", "{\"action\":\"traces\",\"id\":\"T1/../x\"}").isError());
		assertTrue(call(p, "adt_diagnose", "{\"action\":\"traces\",\"id\":\"T1\",\"part\":\"x\"}").isError());

		ToolResult auth = call(p, "adt_diagnose", "{\"action\":\"authorization_trace\",\"user\":\"dev\","
				+ "\"auth_object\":\"S_TCODE\",\"only_failures\":true}");
		assertTrue(auth.content().startsWith("No authorization trace entries match."), auth.content());
		assertTrue(sql.get(0).endsWith("FROM suauthvaltrc WHERE username = 'DEV' AND object = 'S_TCODE' AND rc <> 0 "
				+ "ORDER BY firstcall DESCENDING"), sql.get(0));
		assertTrue(call(p, "adt_diagnose", "{\"action\":\"authorization_trace\",\"user\":\"x' OR '1\"}").isError());
		assertEquals(1, sql.size());

		assertEquals("ATC check variants:\n- ZCLEAN  Clean code\n",
				call(p, "adt_diagnose", "{\"action\":\"atc_variants\",\"filter\":\"Z*\"}").content());
		ToolResult missing = call(p, "adt_diagnose", "{\"action\":\"gateway_errors\"}");
		assertTrue(missing.isError() && missing.content().contains("Not available on this system (/sap/bc/adt/gw/errorlog)"),
				missing.content());
		assertTrue(call(p, "adt_diagnose", "{\"action\":\"gateway_errors\",\"id\":\"../x\"}").isError());
	}

	@Test
	void traceControl() {
		List<AdtRequest> requests = new ArrayList<>();
		boolean[] location = { true };
		FakeAdt adt = adt()
				.route("POST /sap/bc/adt/runtime/traces/abaptraces/parameters", r -> {
					requests.add(r);
					return new AdtResponse(201, "application/xml", "", location[0]
							? Map.of("Location", "/sap/bc/adt/runtime/traces/abaptraces/parameters/P1")
							: Map.of());
				})
				.route("POST /sap/bc/adt/runtime/traces/abaptraces/requests", r -> {
					requests.add(r);
					return FakeAdt.ok(feed("<atom:entry><atom:title>Bella trace</atom:title><atom:id>R1</atom:id></atom:entry>"));
				})
				.route("DELETE /sap/bc/adt/runtime/traces/abaptraces/requests/R1", r -> FakeAdt.ok(""))
				.route("GET /sap/bc/adt/st05/trace/state", r -> FakeAdt.ok("<ts:traceState xmlns:ts=\"http://www.sap.com/adt/"
						+ "perf/trace/state\"><ts:sqlOn>false</ts:sqlOn><ts:traceUser/></ts:traceState>"))
				.route("PUT /sap/bc/adt/st05/trace/state", r -> {
					requests.add(r);
					return FakeAdt.ok("");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");

		ToolResult armed = call(p, "adt_trace_control", "{\"action\":\"trace_start\",\"process_type\":\"dialog\"}");
		assertFalse(armed.isError(), armed.content());
		assertTrue(armed.content().startsWith("Trace armed for DEV:\n- Bella trace | id R1\n"), armed.content());
		assertTrue(requests.get(0).body().contains("<trc:sqlTrace value=\"true\"/>"), requests.get(0).body());
		String query = requests.get(1).path();
		assertTrue(query.contains("traceUser=DEV&traceClient=100"), query);
		assertTrue(query.contains("processType=%2Fsap%2Fbc%2Fadt%2Fruntime%2Ftraces%2Fabaptraces%2Fprocesstypes%2Fdialog"),
				query);
		assertTrue(query.contains("objecttypes%2Ftransaction"), query);
		assertTrue(query.endsWith("parametersId=%2Fsap%2Fbc%2Fadt%2Fruntime%2Ftraces%2Fabaptraces%2Fparameters%2FP1"), query);
		assertTrue(call(p, "adt_trace_control", "{\"action\":\"trace_start\",\"process_type\":\"x\"}").isError());
		location[0] = false;
		assertTrue(call(p, "adt_trace_control", "{\"action\":\"trace_start\"}").content().contains("no id"));

		assertEquals("Trace request cancelled.", call(p, "adt_trace_control", "{\"action\":\"trace_cancel\",\"id\":\"R1\"}")
				.content());
		assertTrue(call(p, "adt_trace_control", "{\"action\":\"trace_cancel\",\"id\":\"../../x\"}").isError());
		assertTrue(call(p, "adt_trace_control", "{\"action\":\"trace_cancel\",\"id\":\"/sap/bc/adt/oo/classes/x\"}").isError());
		assertTrue(call(p, "adt_trace_control", "{\"action\":\"trace_cancel\",\"id\":\"R1%2F..%2Fx\"}").isError());

		assertTrue(call(p, "adt_trace_control", "{\"action\":\"set_sql_trace\"}").isError());
		assertEquals("SQL trace (ST05) on for DEV2.", call(p, "adt_trace_control",
				"{\"action\":\"set_sql_trace\",\"on\":true,\"user\":\"dev2\"}").content());
		String state = requests.get(requests.size() - 1).body();
		assertTrue(state.contains("<ts:sqlOn>true</ts:sqlOn><ts:traceUser>DEV2</ts:traceUser>"), state);
	}

	// ---- transports and packages ---------------------------------------------------

	@Test
	void transportCreate() {
		List<AdtRequest> posts = new ArrayList<>();
		FakeAdt adt = adt()
				.route("POST /sap/bc/adt/cts/transports", r -> {
					posts.add(r);
					return new AdtResponse(200, "text/plain", "/com.sap.cts/object_record/DEVK900200");
				})
				.route("POST /sap/bc/adt/cts/transportrequests", r -> {
					posts.add(r);
					return FakeAdt.ok("<tm:root " + TM + "><tm:request tm:number=\"DEVK900201\"/></tm:root>");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");

		assertEquals("Created transport request DEVK900200 in S4H_100.", call(p, "adt_transport_manage",
				"{\"action\":\"create\",\"description\":\"Fix & test\",\"package\":\"zbella\",\"transport_layer\":\"ZDEV\"}")
				.content());
		assertEquals("POST /sap/bc/adt/cts/transports?transportLayer=ZDEV", adt.log.get(0));
		assertTrue(posts.get(0).body().contains("<DEVCLASS>ZBELLA</DEVCLASS>"), posts.get(0).body());
		assertTrue(posts.get(0).body().contains("<REQUEST_TEXT>Fix &amp; test</REQUEST_TEXT>"), posts.get(0).body());
		assertTrue(posts.get(0).contentType().endsWith("dataname=com.sap.adt.CreateCorrectionRequest"));

		assertEquals("Created transport request DEVK900201 in S4H_100.", call(p, "adt_transport_manage",
				"{\"action\":\"create\",\"description\":\"To QAS\",\"target\":\"qas\"}").content());
		assertTrue(posts.get(1).body().contains("tm:useraction=\"newrequest\""), posts.get(1).body());
		assertTrue(posts.get(1).body().contains("tm:target=\"QAS\""), posts.get(1).body());

		assertTrue(call(p, "adt_transport_manage", "{\"action\":\"create\"}").isError());
		ToolResult release = call(p, "adt_transport_manage", "{\"action\":\"release\",\"request\":\"DEVK900200\"}");
		assertTrue(release.isError() && release.content().contains("releasing is not possible"), release.content());
	}

	@Test
	void transportReassignDeleteAndRemoveObject() {
		String request = "<tm:root " + TM + "><tm:request tm:number=\"DEVK900100\" tm:owner=\"DEV\" tm:desc=\"Calc\" "
				+ "tm:type=\"K\" tm:status=\"D\"><tm:task tm:number=\"DEVK900101\" tm:parent=\"DEVK900100\" tm:owner=\"DEV\" "
				+ "tm:desc=\"Calc\" tm:status=\"D\"><tm:abap_object tm:pgmid=\"R3TR\" tm:type=\"PROG\" tm:name=\"ZREP\" "
				+ "tm:position=\"000002\" tm:obj_desc=\"Report\"/></tm:task><tm:task tm:number=\"DEVK900102\" "
				+ "tm:parent=\"DEVK900100\" tm:owner=\"OLD\" tm:desc=\"Calc\" tm:status=\"R\"/></tm:request></tm:root>";
		List<AdtRequest> puts = new ArrayList<>();
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/cts/transportrequests/DEVK900100", r -> FakeAdt.ok(request))
				.route("PUT /sap/bc/adt/cts/transportrequests/", r -> {
					puts.add(r);
					return FakeAdt.ok("");
				})
				.route("DELETE /sap/bc/adt/cts/transportrequests/", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");

		assertEquals("DEVK900100 now belongs to OTHER, with its open tasks.", call(p, "adt_transport_manage",
				"{\"action\":\"reassign\",\"request\":\"devk900100\",\"owner\":\"other\",\"with_tasks\":true}").content());
		// the released task keeps its owner; the request goes last
		assertEquals(List.of("/sap/bc/adt/cts/transportrequests/DEVK900101", "/sap/bc/adt/cts/transportrequests/DEVK900100"),
				puts.stream().map(AdtRequest::path).toList());
		assertTrue(puts.get(1).body().contains("tm:targetuser=\"OTHER\" tm:useraction=\"changeowner\""), puts.get(1).body());
		assertTrue(call(p, "adt_transport_manage", "{\"action\":\"reassign\",\"request\":\"DEVK900100\",\"owner\":\"a b\"}")
				.isError());
		assertTrue(call(p, "adt_transport_manage", "{\"action\":\"reassign\",\"request\":\"foo\",\"owner\":\"X\"}").isError());

		adt.log.clear();
		assertEquals("Deleted DEVK900100 and its tasks.", call(p, "adt_transport_manage",
				"{\"action\":\"delete\",\"request\":\"DEVK900100\",\"with_tasks\":true}").content());
		assertEquals(List.of("DELETE /sap/bc/adt/cts/transportrequests/DEVK900101",
				"DELETE /sap/bc/adt/cts/transportrequests/DEVK900100"),
				adt.log.stream().filter(l -> l.startsWith("DELETE")).toList());

		puts.clear();
		assertEquals("Removed R3TR PROG ZREP from task DEVK900101.", call(p, "adt_transport_manage",
				"{\"action\":\"remove_object\",\"request\":\"DEVK900100\",\"pgmid\":\"r3tr\",\"object_type\":\"prog\","
						+ "\"object_name\":\"zrep\"}").content());
		String body = puts.get(0).body();
		assertTrue(body.contains("tm:number=\"DEVK900101\" tm:useraction=\"removeobject\""), body);
		assertTrue(body.contains("tm:position=\"000002\""), body);
		ToolResult absent = call(p, "adt_transport_manage", "{\"action\":\"remove_object\",\"request\":\"DEVK900100\","
				+ "\"pgmid\":\"R3TR\",\"object_type\":\"CLAS\",\"object_name\":\"ZCL_X\"}");
		assertTrue(absent.isError() && absent.content().contains("is not in a task of DEVK900100"), absent.content());
	}

	@Test
	void transportValues() {
		FakeAdt adt = adt().route("GET /sap/bc/adt/packages/valuehelps/transportlayers", r -> FakeAdt.ok(
				"<nameditem:namedItemList xmlns:nameditem=\"http://www.sap.com/adt/nameditem\"><nameditem:namedItem>"
						+ "<nameditem:name>ZDEV</nameditem:name><nameditem:description>Development</nameditem:description>"
						+ "<nameditem:data>QAS</nameditem:data></nameditem:namedItem></nameditem:namedItemList>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("Transport layers:\n- ZDEV  Development  → QAS\n",
				call(p, "adt_list_transports", "{\"values\":\"layers\"}").content());
	}

	@Test
	void packageCreateAndDelete() {
		List<AdtRequest> posts = new ArrayList<>();
		FakeAdt adt = adt()
				.route("POST /sap/bc/adt/packages?", r -> {
					posts.add(r);
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/packages/zbella_new?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR>DEVK900100</CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/packages/zbella_new?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("DELETE /sap/bc/adt/packages/zbella_new", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "ZBELLA*");

		assertEquals("Created package ZBELLA_NEW in S4H_100.", call(p, "adt_package_manage", "{\"action\":\"create\","
				+ "\"name\":\"zbella_new\",\"description\":\"New\",\"super_package\":\"zbella\",\"software_component\":\"home\","
				+ "\"transport_layer\":\"zdev\",\"transport\":\"DEVK900100\"}").content());
		assertEquals("POST /sap/bc/adt/packages?corrNr=DEVK900100", adt.log.get(0));
		String xml = posts.get(0).body();
		assertTrue(xml.contains("adtcore:name=\"ZBELLA_NEW\" adtcore:type=\"DEVC/K\""), xml);
		assertTrue(xml.contains("adtcore:responsible=\"DEV\""), xml);
		assertTrue(xml.contains("<pak:attributes pak:packageType=\"development\" pak:recordChanges=\"true\"/>"), xml);
		assertTrue(xml.contains("<pak:superPackage adtcore:name=\"ZBELLA\"/>"), xml);
		assertTrue(xml.contains("<pak:softwareComponent pak:name=\"HOME\"/>"), xml);
		assertTrue(xml.contains("<pak:transportLayer pak:name=\"ZDEV\"/>"), xml);
		assertTrue(AdtManage.packageXml("$Z", "Local", "$TMP", null, null, null, null)
				.contains("pak:recordChanges=\"false\""));

		ToolResult outside = call(p, "adt_package_manage", "{\"action\":\"create\",\"name\":\"ZOTHER\",\"description\":\"x\"}");
		assertTrue(outside.isError(), outside.content());
		assertTrue(call(p, "adt_package_manage", "{\"action\":\"create\",\"description\":\"x\"}").isError());
		assertEquals(1, posts.size());

		assertEquals("Deleted package ZBELLA_NEW.", call(p, "adt_package_manage",
				"{\"action\":\"delete\",\"name\":\"ZBELLA_NEW\"}").content());
		assertTrue(adt.log.contains("S DELETE /sap/bc/adt/packages/zbella_new?lockHandle=H&corrNr=DEVK900100"),
				adt.log.toString());
		assertEquals(0, adt.openSessions);
	}

	// ---- Git -----------------------------------------------------------------------

	private static final String REPOS = "<abapgitrepo:repositories xmlns:abapgitrepo=\"http://www.sap.com/adt/abapgit/"
			+ "repositories\" xmlns:atom=\"http://www.w3.org/2005/Atom\"><abapgitrepo:repository>"
			+ "<abapgitrepo:key>000000000001</abapgitrepo:key><abapgitrepo:package>ZBELLA</abapgitrepo:package>"
			+ "<abapgitrepo:url>https://github.com/example/bella</abapgitrepo:url>"
			+ "<abapgitrepo:branchName>refs/heads/main</abapgitrepo:branchName>"
			+ "<atom:link href=\"/sap/bc/adt/abapgit/repos/000000000001/stage\" rel=\"http://www.sap.com/adt/abapgit/"
			+ "relations/stage\" type=\"stage_link\"/>"
			+ "<atom:link href=\"/sap/bc/adt/abapgit/repos/000000000001/push\" rel=\"http://www.sap.com/adt/abapgit/"
			+ "relations/push\"/></abapgitrepo:repository><abapgitrepo:repository>"
			+ "<abapgitrepo:key>000000000002</abapgitrepo:key><abapgitrepo:package>ZOTHER</abapgitrepo:package>"
			+ "<abapgitrepo:url>https://github.com/example/other</abapgitrepo:url>"
			+ "<atom:link href=\"https://host/sap/bc/other/push\" rel=\"http://www.sap.com/adt/abapgit/relations/push\"/>"
			+ "</abapgitrepo:repository></abapgitrepo:repositories>";

	private static final String STAGE = "<abapgitstaging:abapgitstaging xmlns:abapgitstaging=\"http://www.sap.com/adt/"
			+ "abapgit/staging\" xmlns:adtcore=\"http://www.sap.com/adt/core\"><abapgitstaging:unstaged_objects>"
			+ "<abapgitstaging:abapgitobject adtcore:name=\"ZCL_A\" adtcore:type=\"CLAS/OC\" adtcore:uri=\"/sap/bc/adt/oo/"
			+ "classes/zcl_a\" abapgitstaging:wbkey=\"CLAS\"><abapgitstaging:abapgitfile abapgitstaging:name=\"zcl_a.clas.abap\" "
			+ "abapgitstaging:path=\"/src/\" abapgitstaging:localState=\"M\"/></abapgitstaging:abapgitobject>"
			+ "<abapgitstaging:abapgitobject adtcore:name=\"ZIF_B\" adtcore:type=\"INTF/OI\" abapgitstaging:wbkey=\"INTF\">"
			+ "<abapgitstaging:abapgitfile abapgitstaging:name=\"zif_b.intf.abap\" abapgitstaging:path=\"/src/\" "
			+ "abapgitstaging:localState=\"A\"/></abapgitstaging:abapgitobject></abapgitstaging:unstaged_objects>"
			+ "<abapgitstaging:staged_objects/><abapgitstaging:ignored_objects><abapgitstaging:abapgitobject "
			+ "adtcore:name=\"meta\"/></abapgitstaging:ignored_objects></abapgitstaging:abapgitstaging>";

	@Test
	void gitReadsAndGcts() {
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/abapgit/repos", r -> FakeAdt.ok(REPOS))
				.route("GET /sap/bc/cts_abapvcs/repository", r -> new AdtResponse(200, "application/json",
						"{\"result\":[{\"rid\":\"bella\",\"status\":\"READY\"}]}"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("- ZBELLA  https://github.com/example/bella  refs/heads/main  key 000000000001\n"
				+ "- ZOTHER  https://github.com/example/other  key 000000000002\n",
				call(p, "adt_git", "{\"action\":\"repos\"}").content());
		ToolResult gcts = call(p, "adt_git", "{\"provider\":\"gcts\",\"action\":\"repos\"}");
		assertTrue(gcts.content().contains("\"rid\": \"bella\""), gcts.content());
		assertTrue(call(p, "adt_git", "{\"provider\":\"gcts\",\"action\":\"branches\"}").isError());
		ToolResult noGcts = call(p, "adt_git", "{\"provider\":\"gcts\",\"action\":\"system\"}");
		assertTrue(noGcts.isError() && noGcts.content().contains("gCTS is not available"), noGcts.content());
	}

	@Test
	void gitWrites() {
		List<AdtRequest> posts = new ArrayList<>();
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/abapgit/repos/000000000001/stage", r -> FakeAdt.ok(STAGE))
				.route("GET /sap/bc/adt/abapgit/repos", r -> FakeAdt.ok(REPOS))
				.route("POST /sap/bc/adt/abapgit/repos", r -> {
					posts.add(r);
					return FakeAdt.ok(r.path().endsWith("/push") ? ""
							: "<abapObjects:abapObjects xmlns:abapObjects=\"http://www.sap.com/adt/abapgit/abapObjects\">"
									+ "<abapObjects:abapObject><abapObjects:type>CLAS</abapObjects:type><abapObjects:name>ZCL_A"
									+ "</abapObjects:name><abapObjects:msgType>S</abapObjects:msgType></abapObjects:abapObject>"
									+ "<abapObjects:abapObject><abapObjects:type>INTF</abapObjects:type><abapObjects:name>ZIF_B"
									+ "</abapObjects:name><abapObjects:msgType>E</abapObjects:msgType><abapObjects:msgText>"
									+ "Not activated</abapObjects:msgText></abapObjects:abapObject></abapObjects:abapObjects>");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "ZBELLA*");

		ToolResult pulled = call(p, "adt_git_write", "{\"action\":\"pull\",\"repo\":\"zbella\",\"transport\":\"DEVK900100\"}");
		assertEquals("Pulled https://github.com/example/bella into ZBELLA. 2 objects processed.\nErrors:\n"
				+ "- INTF ZIF_B: Not activated\n", pulled.content());
		assertEquals("/sap/bc/adt/abapgit/repos/000000000001/pull", posts.get(0).path());
		assertTrue(posts.get(0).body().contains("<abapgitrepo:transportRequest>DEVK900100</abapgitrepo:transportRequest>"));
		// outside the allowed packages
		assertTrue(call(p, "adt_git_write", "{\"action\":\"pull\",\"repo\":\"000000000002\"}").isError());
		// credentials never go through the model
		assertTrue(call(p, "adt_git_write", "{\"action\":\"clone\",\"url\":\"https://me:secret@github.com/x\","
				+ "\"package\":\"ZBELLA_X\"}").isError());
		assertTrue(call(p, "adt_git_write", "{\"action\":\"clone\",\"url\":\"https://github.com/x\",\"package\":\"ZOTHER\"}")
				.isError());
		assertEquals(1, posts.size());

		ToolResult pushed = call(p, "adt_git_write", "{\"action\":\"push\",\"repo\":\"ZBELLA\",\"comment\":\"Add <A>\","
				+ "\"objects\":\"zcl_a\",\"author_name\":\"Dev\",\"author_email\":\"dev@example.com\"}");
		assertEquals("Pushed the changes of ZBELLA to https://github.com/example/bella.", pushed.content());
		String payload = posts.get(1).body();
		assertEquals("/sap/bc/adt/abapgit/repos/000000000001/push", posts.get(1).path());
		assertTrue(payload.contains("<abapgitstaging:staged_objects>\n    <abapgitstaging:abapgitobject adtcore:name=\"ZCL_A\" "
				+ "adtcore:type=\"CLAS/OC\" adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a\" abapgitstaging:wbkey=\"CLAS\">"),
				payload);
		assertTrue(payload.contains("abapgitstaging:name=\"zcl_a.clas.abap\" abapgitstaging:path=\"/src/\" "
				+ "abapgitstaging:localState=\"M\"/>"), payload);
		assertFalse(payload.contains("ZIF_B"), payload);
		assertTrue(payload.contains("abapgitstaging:comment=\"Add &lt;A&gt;\""), payload);
		assertTrue(payload.contains("<abapgitstaging:author abapgitstaging:name=\"Dev\" "
				+ "abapgitstaging:email=\"dev@example.com\"/>"), payload);
		ToolResult nothing = call(p, "adt_git_write", "{\"action\":\"push\",\"repo\":\"ZBELLA\",\"comment\":\"x\","
				+ "\"objects\":\"ZCL_NONE\"}");
		assertTrue(nothing.isError() && nothing.content().contains("Nothing to push"), nothing.content());
		assertTrue(call(p, "adt_git_write", "{\"action\":\"push\",\"repo\":\"ZBELLA\"}").isError());
	}

	@Test
	void abapGitLinksStayBelowItsResources() throws Exception {
		AdtGit.Repo bad = new AdtGit.Repo("2", "Z", "u", "", List.of(new String[][] {
				{ "http://www.sap.com/adt/abapgit/relations/push", "https://host/sap/bc/other/push", "" } }));
		assertTrue(org.junit.jupiter.api.Assertions.assertThrows(AdtException.class, () -> bad.link("push")).getMessage()
				.contains("Unexpected abapGit link"));
		AdtGit.Repo ok = new AdtGit.Repo("1", "Z", "u", "", List.of(new String[][] {
				{ "http://www.sap.com/adt/abapgit/relations/check", "https://host/sap/bc/adt/abapgit/repos/1/checks", "" } }));
		assertEquals("/sap/bc/adt/abapgit/repos/1/checks", ok.link("check"));
	}

	// ---- RAP -----------------------------------------------------------------------

	static final String BDEF = """
			managed implementation in class zbp_i_travel unique;
			strict ( 2 );

			define behavior for ZI_TRAVEL alias Travel
			persistent table ztravel
			lock master
			authorization master ( instance )
			{
			  create;
			  update ( features : instance );
			  action ( features : instance ) acceptTravel result [1] $self;
			  internal action recalcTotal;
			  determination setStatus on modify { create; }
			  validation validateDates on save { field BeginDate; }
			  association _Booking { create; }
			}

			define behavior for ZI_BOOKING
			persistent table zbooking
			lock dependent by _Travel
			{
			  update;
			}
			""";

	@Test
	void rapHandlers() {
		var handlers = AdtRap.handlers(BDEF);
		assertEquals(List.of("Travel", "BOOKING"), List.copyOf(handlers.keySet()));
		assertEquals(List.of(
				"METHODS accepttravel FOR MODIFY IMPORTING keys FOR ACTION Travel~acceptTravel RESULT result.",
				"METHODS recalctotal FOR MODIFY IMPORTING keys FOR ACTION Travel~recalcTotal.",
				"METHODS setstatus FOR DETERMINE ON MODIFY IMPORTING keys FOR Travel~setStatus.",
				"METHODS validatedates FOR VALIDATE ON SAVE IMPORTING keys FOR Travel~validateDates.",
				"METHODS get_instance_features FOR INSTANCE FEATURES IMPORTING keys REQUEST requested_features FOR Travel "
						+ "RESULT result.",
				"METHODS get_instance_authorizations FOR INSTANCE AUTHORIZATION IMPORTING keys REQUEST "
						+ "requested_authorizations FOR Travel RESULT result."),
				handlers.get("Travel").stream().map(AdtRap.Handler::signature).toList());
		assertTrue(handlers.get("BOOKING").isEmpty());

		List<String> added = new ArrayList<>();
		String include = AdtRap.scaffold("", handlers, added);
		assertTrue(include.startsWith("CLASS lhc_travel DEFINITION INHERITING FROM cl_abap_behavior_handler.\n"
				+ "  PRIVATE SECTION.\n    METHODS accepttravel"), include);
		assertTrue(include.contains("  METHOD validatedates.\n  ENDMETHOD.\n"), include);
		assertTrue(include.endsWith("ENDCLASS.\n"), include);
		assertEquals(6, added.size());
		assertFalse(include.contains("lhc_booking"), include);

		// a second run adds nothing; a new validation is added to the existing class
		List<String> again = new ArrayList<>();
		assertEquals(include, AdtRap.scaffold(include, handlers, again));
		assertTrue(again.isEmpty());
		var more = AdtRap.handlers(BDEF.replace("  association", "  validation checkCustomer on save { create; }\n"
				+ "  association"));
		String extended = AdtRap.scaffold(include, more, again);
		assertEquals(List.of("lhc_travel->checkcustomer"), again);
		assertTrue(extended.contains("METHODS checkcustomer FOR VALIDATE ON SAVE IMPORTING keys FOR Travel~checkCustomer."),
				extended);
		assertTrue(extended.contains("METHOD checkcustomer."), extended);
	}

	@Test
	void rapGenerateHandlersTool() {
		List<String> written = new ArrayList<>();
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/oo/classes/zbp_i_travel/source/main", r -> new AdtResponse(200, "text/plain",
						"CLASS zbp_i_travel DEFINITION PUBLIC ABSTRACT FINAL FOR BEHAVIOR OF zi_travel.\nENDCLASS.\n"
								+ "CLASS zbp_i_travel IMPLEMENTATION.\nENDCLASS."))
				.route("GET /sap/bc/adt/bo/behaviordefinitions/zi_travel/source/main",
						r -> new AdtResponse(200, "text/plain", BDEF))
				.route("GET /sap/bc/adt/oo/classes/zbp_i_travel/includes/implementations",
						r -> new AdtResponse(200, "text/plain", "* local types\n"))
				.route("POST /sap/bc/adt/oo/classes/zbp_i_travel?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/oo/classes/zbp_i_travel?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT /sap/bc/adt/oo/classes/zbp_i_travel/includes/implementations", r -> {
					written.add(r.body());
					return FakeAdt.ok("");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult dry = call(p, "adt_rap", "{\"action\":\"generate_handlers\",\"name\":\"zbp_i_travel\",\"dry_run\":true}");
		assertTrue(dry.content().startsWith("Would add lhc_travel->accepttravel"), dry.content());
		assertTrue(written.isEmpty());
		ToolResult saved = call(p, "adt_rap", "{\"action\":\"generate_handlers\",\"name\":\"ZBP_I_TRAVEL\"}");
		assertFalse(saved.isError(), saved.content());
		assertTrue(saved.content().contains("to the local types of ZBP_I_TRAVEL. Not activated yet"), saved.content());
		assertTrue(written.get(0).startsWith("* local types\n\nCLASS lhc_travel DEFINITION"), written.get(0));
		assertTrue(call(p, "adt_rap", "{\"action\":\"generate_handlers\"}").isError());
	}

	@Test
	void publishServiceBinding() {
		List<AdtRequest> posts = new ArrayList<>();
		String[] severity = { "OK" };
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/businessservices/bindings/zui_travel_o4", r -> FakeAdt.ok(
						"<srvb:serviceBinding xmlns:srvb=\"http://www.sap.com/adt/ddic/ServiceBindings\">"
								+ "<srvb:binding srvb:type=\"ODATA\" srvb:version=\"V4\" srvb:category=\"0\"/>"
								+ "<srvb:services><srvb:content srvb:version=\"0002\"/></srvb:services></srvb:serviceBinding>"))
				.route("POST /sap/bc/adt/businessservices/", r -> {
					posts.add(r);
					return FakeAdt.ok("<asx:abap xmlns:asx=\"http://www.sap.com/abapxml\"><asx:values><DATA><SEVERITY>"
							+ severity[0] + "</SEVERITY><SHORT_TEXT>Service ZUI_TRAVEL_O4 published</SHORT_TEXT>"
							+ "<LONG_TEXT/></DATA></asx:values></asx:abap>");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("Service ZUI_TRAVEL_O4 published", call(p, "adt_rap",
				"{\"action\":\"publish_srvb\",\"name\":\"zui_travel_o4\"}").content());
		assertEquals("/sap/bc/adt/businessservices/odatav4/publishjobs?servicename=ZUI_TRAVEL_O4&serviceversion=0002",
				posts.get(0).path());
		assertTrue(posts.get(0).body().contains("adtcore:name=\"ZUI_TRAVEL_O4\""));
		severity[0] = "ERROR";
		assertTrue(call(p, "adt_rap", "{\"action\":\"unpublish_srvb\",\"name\":\"zui_travel_o4\"}").isError());
		assertTrue(posts.get(1).path().startsWith("/sap/bc/adt/businessservices/odatav4/unpublishjobs?"));
		assertEquals("V2", AdtRap.bindingVersions("<x/>")[0]);
	}

	// ---- UI5 -----------------------------------------------------------------------

	@Test
	void ui5Reads() {
		FakeAdt adt = adt()
				.route("GET /sap/bc/adt/filestore/ui5-bsp/objects?maxResults=200&name=Z*", r -> FakeAdt.ok(feed(
						"<atom:entry><atom:title>ZAPP</atom:title><atom:summary>Travel app</atom:summary></atom:entry>")))
				.route("GET /sap/bc/adt/filestore/ui5-bsp/objects/ZAPP%2Fwebapp%2Fmanifest.json/content",
						r -> new AdtResponse(200, "application/json", "{\"sap.app\":{}}"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		assertEquals("- ZAPP | Travel app\n", call(p, "adt_ui5", "{\"action\":\"apps\",\"filter\":\"Z*\"}").content());
		assertEquals("{\"sap.app\":{}}", call(p, "adt_ui5",
				"{\"action\":\"file\",\"app\":\"zapp\",\"path\":\"/webapp/manifest.json\"}").content());
		assertTrue(call(p, "adt_ui5", "{\"action\":\"file\",\"app\":\"zapp\",\"path\":\"../x\"}").isError());
		assertTrue(call(p, "adt_ui5", "{\"action\":\"files\"}").isError());
		assertTrue(call(p, "adt_ui5", "{\"action\":\"flp_tiles\"}").isError());
	}
}

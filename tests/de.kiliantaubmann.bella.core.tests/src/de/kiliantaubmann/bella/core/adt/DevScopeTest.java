package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class DevScopeTest {

	private static String described(String pkg) {
		return "<adtcore:object xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:packageRef adtcore:name=\""
				+ pkg + "\"/></adtcore:object>";
	}

	private static String request(String number, String owner, String status) {
		return "<tm:root xmlns:tm=\"http://www.sap.com/cts/adt/tm\"><tm:request tm:number=\"" + number
				+ "\" tm:owner=\"" + owner + "\" tm:desc=\"x\" tm:type=\"K\" tm:status=\"" + status
				+ "\"/></tm:root>";
	}

	private static String hit(String uri, String type, String name, String pkg) {
		return "<adtcore:objectReference adtcore:uri=\"" + uri + "\" adtcore:type=\"" + type + "\" adtcore:name=\""
				+ name + "\" adtcore:packageName=\"" + pkg + "\" adtcore:description=\"d\"/>";
	}

	/** ZCL_OWN lives in ZSD_DELIV, ZCL_OTHER in ZMM_OTHER; DEV owns DEVK900001, OTHER DEVK900002. */
	private static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.route("GET /sap/bc/adt/oo/classes/zcl_own/source/main",
				r -> new AdtResponse(200, "text/plain", "CLASS zcl_own DEFINITION PUBLIC. ENDCLASS."))
				.route("GET /sap/bc/adt/oo/classes/zcl_own", r -> FakeAdt.ok(described("ZSD_DELIV")))
				.route("GET /sap/bc/adt/oo/classes/zcl_other", r -> FakeAdt.ok(described("ZMM_OTHER")))
				.route("GET /sap/bc/adt/packages/zsd_deliv", r -> FakeAdt.ok(described("ZSD")))
				.route("GET /sap/bc/adt/packages/zmm_other", r -> FakeAdt.ok(described("ZMM")))
				.route("GET /sap/bc/adt/cts/transportrequests/DEVK900001",
						r -> FakeAdt.ok(request("DEVK900001", "DEV", "D")))
				.route("GET /sap/bc/adt/cts/transportrequests/DEVK900002",
						r -> FakeAdt.ok(request("DEVK900002", "OTHER", "D")))
				.route(AdtContextTest.SEARCH, r -> {
					StringBuilder sb = new StringBuilder(
							"<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">");
					if (r.path().contains("query=ZCL_O") || r.path().contains("query=ZCL_OWN")) {
						sb.append(hit("/sap/bc/adt/oo/classes/zcl_own", "CLAS/OC", "ZCL_OWN", "ZSD_DELIV"));
					}
					if (r.path().contains("query=ZCL_O") || r.path().contains("query=ZCL_OTHER")) {
						sb.append(hit("/sap/bc/adt/oo/classes/zcl_other", "CLAS/OC", "ZCL_OTHER", "ZMM_OTHER"));
					}
					return FakeAdt.ok(sb.append("</adtcore:objectReferences>").toString());
				});
		return adt;
	}

	private static AdtToolProvider provider(FakeAdt adt, DevScope scope) {
		return new AdtToolProvider(adt, () -> "dev", () -> "", d -> "", () -> NamingRules.NONE, scope);
	}

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	private static Optional<String> refuse(AdtToolProvider p, String tool, JsonObject in) {
		return p.refuse(tool, in, CancelToken.NONE);
	}

	private static Optional<String> refuse(AdtToolProvider p, String tool, String json) {
		return refuse(p, tool, Json.parseObject(json));
	}

	@Test
	void withoutAPackageCustomerObjectsAreOffLimits() {
		AdtToolProvider p = provider(system(), new DevScope());
		Optional<String> read = refuse(p, "adt_read_source", "{\"name\":\"ZCL_OWN\",\"type\":\"CLAS\"}");
		assertTrue(read.isPresent() && read.get().contains("Ask the developer which package"), read.toString());
		assertEquals(Optional.empty(), refuse(p, "adt_read_source", "{\"name\":\"MARA\",\"type\":\"TABL\"}"));
		Optional<String> write = refuse(p, "adt_write_source", "{\"name\":\"ZCL_OWN\",\"type\":\"CLAS\",\"source\":\"x\"}");
		assertTrue(write.isPresent() && write.get().startsWith("No development package is set"), write.toString());
		assertTrue(call(p, "adt_dev_package", "{}").content().contains("No development package yet"));
	}

	@Test
	void customerObjectsOfOtherPackagesAreIgnored() {
		AdtToolProvider p = provider(system(), new DevScope());
		ToolResult unknown = call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"ZNOPE\"}");
		assertTrue(unknown.isError() && unknown.content().contains("does not exist"), unknown.content());
		ToolResult set = call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"zsd_deliv\"}");
		assertFalse(set.isError(), set.content());
		assertTrue(set.content().contains("Development package ZSD_DELIV (named by the developer)"), set.content());

		assertEquals(Optional.empty(), refuse(p, "adt_read_source", "{\"name\":\"ZCL_OWN\",\"type\":\"CLAS\"}"));
		Optional<String> other = refuse(p, "adt_read_source", "{\"name\":\"ZCL_OTHER\",\"type\":\"CLAS\"}");
		assertTrue(other.isPresent() && other.get().contains("ZCL_OTHER belongs to package ZMM_OTHER, outside the "
				+ "development package ZSD_DELIV"), other.toString());

		ToolResult search = call(p, "adt_search_objects", "{\"query\":\"ZCL_O*\"}");
		assertTrue(search.content().contains("ZCL_OWN (CLAS/OC)"), search.content());
		assertFalse(search.content().contains("ZCL_OTHER (CLAS/OC)"), search.content());
		assertTrue(search.content().contains("ignored (their names are taken): ZCL_OTHER"), search.content());

		ToolResult context = call(p, "adt_context", "{\"names\":[\"ZCL_OTHER\"]}");
		assertTrue(context.content().contains(AdtContext.IGNORED + "ZCL_OTHER"), context.content());
	}

	@Test
	void writesUseOnlyTheRequestTheDeveloperChose() {
		DevScope scope = new DevScope();
		AdtToolProvider p = provider(system(), scope);
		call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"ZSD_DELIV\"}");
		String write = "{\"name\":\"ZCL_OWN\",\"type\":\"CLAS\",\"source\":\"x\"}";
		Optional<String> none = refuse(p, "adt_write_source", write);
		assertTrue(none.isPresent() && none.get().contains("has not named a transport request"), none.toString());

		ToolResult foreign = call(p, "adt_dev_package", "{\"action\":\"set\",\"transport\":\"DEVK900002\"}");
		assertTrue(foreign.isError() && foreign.content().contains("belongs to OTHER"), foreign.content());
		ToolResult own = call(p, "adt_dev_package", "{\"action\":\"set\",\"transport\":\"devk900001\"}");
		assertFalse(own.isError(), own.content());
		assertEquals("DEVK900001", scope.transport("dev"));

		JsonObject in = Json.parseObject(write);
		assertEquals(Optional.empty(), refuse(p, "adt_write_source", in));
		assertEquals("DEVK900001", Json.str(in, "transport"));
		Optional<String> other = refuse(p, "adt_write_source",
				"{\"name\":\"ZCL_OWN\",\"type\":\"CLAS\",\"source\":\"x\",\"transport\":\"DEVK900003\"}");
		assertTrue(other.isPresent() && other.get().contains("chose transport request DEVK900001"), other.toString());
		Optional<String> outside = refuse(p, "adt_write_source", "{\"name\":\"ZCL_OTHER\",\"type\":\"CLAS\",\"source\":\"x\"}");
		assertTrue(outside.isPresent() && outside.get().contains("changes only objects of the development package"),
				outside.toString());

		// another package drops the request
		call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"ZMM_OTHER\"}");
		assertEquals(null, scope.transport("dev"));
	}

	@Test
	void freeSqlReadsOnlyTablesOfTheDevelopmentPackage() {
		AdtToolProvider p = provider(system(), new DevScope());
		call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"ZSD_DELIV\"}");
		Optional<String> other = refuse(p, "adt_table_contents",
				"{\"sql\":\"SELECT * FROM mara INNER JOIN zcl_other ON mara~matnr = zcl_other~matnr\"}");
		assertTrue(other.isPresent() && other.get().contains("ZCL_OTHER belongs to package ZMM_OTHER"), other.toString());
		assertEquals(Optional.empty(), refuse(p, "adt_table_contents", "{\"sql\":\"SELECT * FROM mara\"}"));
	}

	@Test
	void aNewPackageBecomesTheDevelopmentPackage() {
		DevScope scope = new DevScope();
		FakeAdt adt = system();
		adt.route("POST /sap/bc/adt/packages", r -> FakeAdt.ok(""));
		AdtToolProvider p = provider(adt, scope);
		String create = "{\"action\":\"create\",\"name\":\"ZNEW\",\"description\":\"d\",\"transport\":\"%s\"}";
		Optional<String> foreign = refuse(p, "adt_package_manage", create.formatted("DEVK900002"));
		assertTrue(foreign.isPresent() && foreign.get().contains("belongs to OTHER"), foreign.toString());
		assertEquals(Optional.empty(), refuse(p, "adt_package_manage", create.formatted("DEVK900001")));
		ToolResult created = call(p, "adt_package_manage", create.formatted("DEVK900001"));
		assertFalse(created.isError(), created.content());
		assertEquals("ZNEW", scope.developerPackage("dev"));
		assertEquals("DEVK900001", scope.transport("dev"));
	}

	@Test
	void laterEditorObjectsKeepPackageAndTransport() {
		DevScope scope = new DevScope();
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_own", "ZCL_OWN", "CLAS/OC"));
		scope.transport("dev", "DEVK900123");
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_own/includes/testclasses",
				"ZCL_OWN", "CLAS/OC"));
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_next", "ZCL_NEXT", "CLAS/OC"));
		assertEquals("ZCL_OWN", scope.editorObject().name());
		assertEquals("DEVK900123", scope.transport("dev"));
		scope.reset();
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_next", "ZCL_NEXT", "CLAS/OC"));
		assertEquals("ZCL_NEXT", scope.editorObject().name());
	}

	@Test
	void theEditorObjectSetsThePackage() {
		DevScope scope = new DevScope();
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_own", "ZCL_OWN", "CLAS/OC"));
		AdtToolProvider p = provider(system(), scope);
		ToolResult get = call(p, "adt_dev_package", "{}");
		assertTrue(get.content().contains("Development package ZSD_DELIV (of ZCL_OWN in the editor)"), get.content());
		ToolResult change = call(p, "adt_dev_package", "{\"action\":\"set\",\"package\":\"ZMM_OTHER\"}");
		assertTrue(change.isError() && change.content().contains("stays so"), change.content());

		Optional<String> elsewhere = refuse(p, "adt_create_object",
				"{\"name\":\"ZCL_NEW\",\"type\":\"CLAS\",\"description\":\"d\",\"package\":\"ZMM_OTHER\"}");
		assertTrue(elsewhere.isPresent() && elsewhere.get().contains("only in the development package ZSD_DELIV"),
				elsewhere.toString());
		JsonObject create = Json.parseObject("{\"name\":\"ZCL_NEW\",\"type\":\"CLAS\",\"description\":\"d\"}");
		refuse(p, "adt_create_object", create);
		assertEquals("ZSD_DELIV", Json.str(create, "package"));

		scope.reset();
		assertTrue(call(p, "adt_dev_package", "{}").content().contains("No development package yet"));
	}

	@Test
	void settingThePackageAlwaysAsksTheDeveloper() {
		ToolSpec spec = provider(system(), new DevScope()).listTools().stream()
				.filter(t -> t.name().equals("adt_dev_package")).findFirst().orElseThrow();
		ToolPolicy auto = new ToolPolicy(List.of(), ChatMode.AUTO);
		assertEquals(ToolPolicy.Decision.CONFIRM, auto.decide(spec, Json.parseObject("{\"action\":\"set\"}")));
		assertEquals(ToolPolicy.Decision.AUTO, auto.decide(spec, Json.parseObject("{}")));
		// without a scope the tool does not exist
		assertTrue(new AdtToolProvider(system(), () -> "dev").listTools().stream()
				.noneMatch(t -> t.name().equals("adt_dev_package")));
	}

	@Test
	void listenersHearEveryChange() {
		DevScope scope = new DevScope();
		AtomicInteger changes = new AtomicInteger();
		Runnable listener = changes::incrementAndGet;
		scope.addListener(listener);
		scope.developerPackage("dev", "zsd_deliv");
		scope.transport("dev", "DEVK900001");
		scope.transport("qas", "QASK900001"); // another system: nothing changes
		assertEquals(2, changes.get());
		assertEquals("dev", scope.destination());
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_own", "ZCL_OWN", "CLAS/OC"));
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_next", "ZCL_NEXT", "CLAS/OC"));
		assertEquals(3, changes.get());
		scope.reset();
		assertEquals(4, changes.get());
		assertEquals(null, scope.destination());
		scope.removeListener(listener);
		scope.developerPackage("dev", "ZSD_DELIV");
		assertEquals(4, changes.get());
	}

	@Test
	void theChatWindowChoosesWithTheChecksOfTheTool() throws Exception {
		DevScope scope = new DevScope();
		FakeAdt adt = system();
		adt.route("GET /sap/bc/adt/cts/transportrequests?", r -> FakeAdt.ok(request("DEVK900001", "DEV", "D")));
		AdtToolProvider p = provider(adt, scope);
		AdtToolProvider.Scope none = p.scope(CancelToken.NONE);
		assertEquals(null, none.pkg());
		assertEquals(null, none.transport());

		Optional<String> unknown = p.choose("ZNOPE", null, CancelToken.NONE);
		assertTrue(unknown.isPresent() && unknown.get().contains("does not exist"), unknown.toString());
		// the hint for the model is left out for the developer
		assertFalse(unknown.get().contains("Ask the developer"), unknown.get());
		assertEquals(Optional.empty(), p.choose("zsd_deliv", null, CancelToken.NONE));
		Optional<String> foreign = p.choose(null, "DEVK900002", CancelToken.NONE);
		assertTrue(foreign.isPresent() && foreign.get().contains("belongs to OTHER"), foreign.toString());
		assertEquals(Optional.empty(), p.choose(null, "devk900001", CancelToken.NONE));

		AdtToolProvider.Scope chosen = p.scope(CancelToken.NONE);
		assertEquals("ZSD_DELIV", chosen.pkg());
		assertEquals("DEVK900001", chosen.transport());
		assertEquals(null, chosen.editorObject());
		assertFalse(chosen.local());
		assertEquals(List.of("DEVK900001"), p.openTransports(CancelToken.NONE).stream()
				.map(AdtTransportRequest::id).toList());
		assertTrue(adt.log.contains("GET /sap/bc/adt/cts/transportrequests?user=DEV&target=true&requestType=KWT"
				+ "&requestStatus=D"), adt.log.toString());

		p.packages("zsd", 50, CancelToken.NONE);
		assertTrue(adt.log.stream().anyMatch(l -> l.contains("query=ZSD*") && l.contains("objectType=DEVC%2FK")),
				adt.log.toString());
	}

	@Test
	void theChatWindowShowsTheEditorObjectsPackage() throws Exception {
		DevScope scope = new DevScope();
		scope.editorObject(new AdtEditorObject("dev", "/sap/bc/adt/oo/classes/zcl_own", "ZCL_OWN", "CLAS/OC"));
		AdtToolProvider p = provider(system(), scope);
		AdtToolProvider.Scope shown = p.scope(CancelToken.NONE);
		assertEquals("ZSD_DELIV", shown.pkg());
		assertEquals("ZCL_OWN", shown.editorObject());
		Optional<String> other = p.choose("ZMM_OTHER", null, CancelToken.NONE);
		assertTrue(other.isPresent() && other.get().contains("stays so"), other.toString());
	}
}

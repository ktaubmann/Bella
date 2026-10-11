package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class PackageRunsTest {

	private static String hit(String uri, String type, String name, String pkg) {
		return "<adtcore:objectReference adtcore:uri=\"" + uri + "\" adtcore:type=\"" + type + "\" adtcore:name=\""
				+ name + "\" adtcore:packageName=\"" + pkg + "\" adtcore:description=\"d\"/>";
	}

	private static String hits(String... refs) {
		return "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">" + String.join("", refs)
				+ "</adtcore:objectReferences>";
	}

	/**
	 * ZPKG holds ZCL_A, the include ZREP_TOP, the table ZTAB and the
	 * subpackage ZSUB; the search of ZPKG also lists ZCL_SUB of ZSUB, as some
	 * systems do.
	 */
	private static FakeAdt system(List<String> bodies) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		adt.route("GET /sap/bc/adt/packages/zpkg", r -> FakeAdt.ok("<pak:package/>"))
				.route("GET /sap/bc/adt/packages/zsub", r -> FakeAdt.ok("<pak:package/>"))
				.route(AdtContextTest.SEARCH, r -> {
					if (r.path().contains("packageName=ZSUB")) {
						return FakeAdt.ok(hits(hit("/sap/bc/adt/oo/classes/zcl_sub", "CLAS/OC", "ZCL_SUB", "ZSUB")));
					}
					if (r.path().contains("packageName=ZPKG")) {
						return FakeAdt.ok(hits(hit("/sap/bc/adt/oo/classes/zcl_a", "CLAS/OC", "ZCL_A", "ZPKG"),
								hit("/sap/bc/adt/programs/includes/zrep_top", "PROG/I", "ZREP_TOP", "ZPKG"),
								hit("/sap/bc/adt/ddic/tables/ztab", "TABL/DT", "ZTAB", "ZPKG"),
								hit("/sap/bc/adt/packages/zsub", "DEVC/K", "ZSUB", "ZPKG"),
								hit("/sap/bc/adt/oo/classes/zcl_sub", "CLAS/OC", "ZCL_SUB", "ZSUB")));
					}
					return FakeAdt.ok(hits());
				})
				.route("POST /sap/bc/adt/abapunit/testruns", r -> {
					bodies.add(r.body());
					return FakeAdt.ok("""
							<aunit:runResult xmlns:aunit="http://www.sap.com/adt/aunit" xmlns:adtcore="http://www.sap.com/adt/core">
							 <program adtcore:name="ZCL_A"><testClasses>
							  <testClass adtcore:name="LTC_A"><testMethods>
							   <testMethod adtcore:name="OK"/>
							   <testMethod adtcore:name="FAILS"><alerts><alert kind="failedAssertion" severity="critical"><title>Expected 1, got 2</title></alert></alerts></testMethod>
							  </testMethods></testClass>
							 </testClasses></program>
							</aunit:runResult>""");
				})
				.route("POST /sap/bc/adt/atc/worklists", r -> new AdtResponse(200, "text/plain", "W1"))
				.route("POST /sap/bc/adt/atc/runs", r -> {
					bodies.add(r.body());
					return FakeAdt.ok("");
				})
				.route("GET /sap/bc/adt/atc/worklists/W1", r -> FakeAdt.ok(
						"<atcworklist:worklist xmlns:atcworklist=\"http://www.sap.com/adt/atc/worklist\" xmlns:atcfinding=\"http://www.sap.com/adt/atc/finding\">"
								+ "<atcfinding:finding atcfinding:location=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=7,2\" "
								+ "atcfinding:priority=\"3\" atcfinding:checkTitle=\"Naming\" atcfinding:messageTitle=\"Prefix\"/>"
								+ "<atcfinding:finding atcfinding:location=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=12,2\" "
								+ "atcfinding:priority=\"1\" atcfinding:checkTitle=\"Security\" atcfinding:messageTitle=\"Missing AUTHORITY-CHECK\"/>"
								+ "</atcworklist:worklist>"));
		return adt;
	}

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void unitTestsRunOverThePackage() {
		List<String> bodies = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(system(bodies), () -> "dev");
		ToolResult r = call(p, "adt_run_unit_tests", "{\"package\":\"zpkg\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ABAP Unit over package ZPKG: 1 objects run; 1 of 2 test methods passed."),
				r.content());
		assertTrue(r.content().contains("FAILED ZCL_A: LTC_A->FAILS"), r.content());
		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0).contains("/sap/bc/adt/oo/classes/zcl_a"), bodies.get(0));
		// neither the include, the table nor the object of the subpackage
		assertFalse(bodies.get(0).contains("zrep_top"), bodies.get(0));
		assertFalse(bodies.get(0).contains("ztab"), bodies.get(0));
		assertFalse(bodies.get(0).contains("zcl_sub"), bodies.get(0));
	}

	@Test
	void subpackagesRunWhenAsked() {
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = system(bodies);
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_run_unit_tests", "{\"package\":\"ZPKG\",\"include_subpackages\":true}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ABAP Unit over package ZPKG and 1 subpackage: 2 objects run;"),
				r.content());
		assertTrue(bodies.get(0).contains("zcl_sub"), bodies.get(0));
		assertEquals(1, bodies.get(0).split("zcl_sub", -1).length - 1, "listed once: " + bodies.get(0));
		assertTrue(adt.log.stream().anyMatch(l -> l.contains("packageName=ZSUB")), adt.log.toString());
	}

	@Test
	void atcRunsOverThePackage() {
		List<String> bodies = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(system(bodies), () -> "dev", () -> "", d -> "ZVARIANT");
		ToolResult r = call(p, "adt_atc_check", "{\"package\":\"ZPKG\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ATC over package ZPKG: 2 objects checked; priority 1: 1, priority 2: 0, "
				+ "priority 3: 1.\nPriority 1 ZCL_A line 12: Security: Missing AUTHORITY-CHECK\n"
				+ "Priority 3 ZCL_A line 7: Naming: Prefix"), r.content());
		assertEquals(1, bodies.size());
		assertTrue(bodies.get(0).contains("maximumVerdicts=\"" + PackageRuns.ATC_VERDICTS + "\""), bodies.get(0));
		assertTrue(bodies.get(0).contains("/sap/bc/adt/oo/classes/zcl_a"), bodies.get(0));
		assertTrue(bodies.get(0).contains("/sap/bc/adt/atc/objects/R3TR/TABL/ZTAB"), bodies.get(0));
		assertFalse(bodies.get(0).contains("zrep_top"), bodies.get(0));
	}

	@Test
	void largePackagesRunInChunks() {
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = system(bodies);
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < PackageRuns.CHUNK + 5; i++) {
			many.append(hit("/sap/bc/adt/oo/classes/zcl_n" + i, "CLAS/OC", "ZCL_N" + i, "ZBIG"));
		}
		adt.routes.remove(AdtContextTest.SEARCH);
		adt.route("GET /sap/bc/adt/packages/zbig", r -> FakeAdt.ok("<pak:package/>"))
				.route(AdtContextTest.SEARCH, r -> FakeAdt.ok(hits(many.toString())));
		ToolResult r = call(new AdtToolProvider(adt, () -> "dev"), "adt_run_unit_tests", "{\"package\":\"ZBIG\"}");
		assertFalse(r.isError(), r.content());
		assertEquals(2, bodies.size());
		assertTrue(r.content().startsWith("ABAP Unit over package ZBIG: " + (PackageRuns.CHUNK + 5)
				+ " objects run; 2 of 4 test methods passed."), r.content());
	}

	@Test
	void unknownPackageIsAnError() {
		FakeAdt adt = system(new ArrayList<>());
		ToolResult r = call(new AdtToolProvider(adt, () -> "dev"), "adt_atc_check", "{\"package\":\"ZNOPE\"}");
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("Package ZNOPE does not exist"), r.content());
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("/atc/")), adt.log.toString());
	}

	@Test
	void eitherNameOrPackage() {
		AdtToolProvider p = new AdtToolProvider(system(new ArrayList<>()), () -> "dev");
		assertTrue(call(p, "adt_run_unit_tests", "{}").isError());
		ToolResult both = call(p, "adt_atc_check", "{\"name\":\"ZCL_A\",\"package\":\"ZPKG\"}");
		assertTrue(both.isError() && both.content().contains("either 'name'"), both.content());
	}

	@Test
	void onlyTheDevelopmentPackageRuns() {
		DevScope scope = new DevScope();
		scope.developerPackage("dev", "ZPKG");
		List<String> bodies = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(system(bodies), () -> "dev", () -> "", d -> "",
				() -> NamingRules.NONE, scope);
		ToolResult other = call(p, "adt_run_unit_tests", "{\"package\":\"ZOTHER\"}");
		assertTrue(other.isError() && other.content().contains("not the development package ZPKG"), other.content());
		// the objects of the subpackage are customer objects of another package
		ToolResult own = call(p, "adt_run_unit_tests", "{\"package\":\"ZPKG\",\"include_subpackages\":true}");
		assertFalse(own.isError(), own.content());
		assertFalse(bodies.get(0).contains("zcl_sub"), bodies.get(0));
		assertTrue(own.content().contains("Left out: 1 customer objects outside the development package."),
				own.content());
	}

	@Test
	void runnableTypes() {
		java.util.Set<String> unit = java.util.Set.of("CLAS", "PROG", "FUGR");
		assertTrue(PackageRuns.runnable("PROG/P", unit));
		assertTrue(PackageRuns.runnable("FUGR/F", unit));
		assertFalse(PackageRuns.runnable("PROG/I", unit));
		assertFalse(PackageRuns.runnable("FUGR/FF", unit));
		assertFalse(PackageRuns.runnable("INTF/OI", unit));
	}
}

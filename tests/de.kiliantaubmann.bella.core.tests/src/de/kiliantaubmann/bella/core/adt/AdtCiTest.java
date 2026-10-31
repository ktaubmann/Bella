package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtCiTest {

	private static final String JUNIT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites name=\"ZPKG\" tests=\"3\" "
			+ "failures=\"1\" errors=\"0\" skipped=\"1\"><testsuite name=\"ZCL_A\" tests=\"3\" failures=\"1\" errors=\"0\" "
			+ "skipped=\"1\"><testcase classname=\"ZCL_A.LTC_A\" name=\"OK\"/><testcase classname=\"ZCL_A.LTC_A\" "
			+ "name=\"BROKEN\"><failure message=\"Expected 1, got 2\">Expected 1, got 2</failure></testcase>"
			+ "<testcase classname=\"ZCL_A.LTC_A\" name=\"LATER\"><skipped message=\"risk level\"/></testcase>"
			+ "</testsuite></testsuites>";
	private static final String CHECKSTYLE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><checkstyle version=\"8.36\">"
			+ "<file name=\"ZPKG/ZCL_A.clas.abap\"><error line=\"12\" severity=\"warning\" message=\"SELECT * used\" "
			+ "source=\"CL_CI_TEST_SELECT\"/><error severity=\"info\" message=\"Comment missing\"/></file></checkstyle>";

	private static FakeAdt system(List<AdtRequest> posts) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("GET /sap/bc/adt/packages/ZPKG", r -> FakeAdt.ok("<pak:package/>"))
				.route("GET /sap/bc/adt/api/abapunit/runs/00000000", r -> FakeAdt.ok("<run/>"))
				.route("POST /sap/bc/adt/api/abapunit/runs", r -> {
					posts.add(r);
					return new AdtResponse(201, "application/xml", "", Map.of("Location",
							"/sap/bc/adt/api/abapunit/runs/R1"));
				})
				.route("GET /sap/bc/adt/api/abapunit/runs/R1?withLongPolling=true", r -> FakeAdt.ok(
						"<aunit:run xmlns:aunit=\"http://www.sap.com/adt/api/aunit\" xmlns:atom=\"http://www.w3.org/2005/Atom\">"
								+ "<aunit:progress status=\"FINISHED\" percentage=\"100\"/><atom:link "
								+ "href=\"/sap/bc/adt/api/abapunit/results/R1\" rel=\"http://www.sap.com/adt/relations/"
								+ "runs/run-result\" type=\"application/vnd.sap.adt.api.junit.run-result.v1+xml\"/></aunit:run>"))
				.route("GET /sap/bc/adt/api/abapunit/results/R1", r -> {
					assertEquals(AdtCi.JUNIT, r.headers().get("Accept"));
					return FakeAdt.ok(JUNIT);
				})
				.route("GET /sap/bc/adt/api/atc/runs/00000000", r -> FakeAdt.ok("<run/>"))
				.route("POST /sap/bc/adt/api/atc/runs?clientWait=false", r -> {
					posts.add(r);
					return new AdtResponse(201, "application/xml", "", Map.of("Location", "/sap/bc/adt/api/atc/runs/A1"));
				})
				.route("GET /sap/bc/adt/api/atc/runs/A1", r -> FakeAdt.ok("<atc:run xmlns:atc=\"http://www.sap.com/adt/atc\" "
						+ "xmlns:atom=\"http://www.w3.org/2005/Atom\" status=\"Completed\"><atom:link "
						+ "href=\"/sap/bc/adt/api/atc/results/A1\" rel=\"http://www.sap.com/adt/relations/atc/result\" "
						+ "type=\"application/vnd.sap.atc.checkstyle.v1+xml\"/></atc:run>"))
				.route("GET /sap/bc/adt/api/atc/results/A1", r -> FakeAdt.ok(CHECKSTYLE));
	}

	private static ToolResult call(FakeAdt adt, String tool, String json) {
		return new AdtToolProvider(adt, () -> "dev").call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void unitTestsOfAPackageAsJUnit() {
		List<AdtRequest> posts = new ArrayList<>();
		ToolResult r = call(system(posts), "adt_run_unit_tests",
				"{\"package\":\"zpkg\",\"include_subpackages\":true,\"format\":\"junit\",\"include_report\":true}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ABAP Unit through the CI API (harmless tests of package ZPKG and its "
				+ "subpackages): 3 tests, 1 failures, 0 errors, 1 skipped. Verdict: FAILED"), r.content());
		assertTrue(r.content().contains("- failure ZCL_A.LTC_A.BROKEN: Expected 1, got 2"), r.content());
		assertTrue(r.content().contains("- skipped ZCL_A.LTC_A.LATER: risk level"), r.content());
		assertTrue(r.content().contains("JUnit report:\n" + JUNIT), r.content());
		String body = posts.get(0).body();
		assertEquals(AdtCi.AUNIT_RUN, posts.get(0).contentType());
		assertTrue(body.contains("<aunit:riskLevel harmless=\"true\" dangerous=\"false\" critical=\"false\"/>"), body);
		assertTrue(body.contains("<osl:package name=\"ZPKG\" includeSubpackages=\"true\"/>"), body);
	}

	@Test
	void atcOfAPackageAsCheckstyle() {
		List<AdtRequest> posts = new ArrayList<>();
		FakeAdt adt = system(posts);
		ToolResult r = call(adt, "adt_atc_check", "{\"package\":\"ZPKG\",\"format\":\"checkstyle\",\"check_variant\":"
				+ "\"z_ci\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ATC through the CI API (package ZPKG, variant Z_CI): 2 findings, 0 errors, "
				+ "1 warnings, 1 infos. Verdict: PASSED (fails on error or higher)"), r.content());
		assertTrue(r.content().contains("- warning ZPKG/ZCL_A.clas.abap line 12: SELECT * used [CL_CI_TEST_SELECT]"),
				r.content());
		assertTrue(posts.get(0).body().contains("checkVariant=\"Z_CI\""), posts.get(0).body());
		ToolResult strict = call(adt, "adt_atc_check", "{\"package\":\"ZPKG\",\"format\":\"checkstyle\",\"check_variant\":"
				+ "\"Z_CI\",\"fail_on\":\"warning\"}");
		assertTrue(strict.content().contains("Verdict: FAILED (fails on warning or higher)"), strict.content());
		assertTrue(call(adt, "adt_atc_check", "{\"name\":\"ZCL_A\",\"format\":\"checkstyle\"}").content()
				.contains("give 'package'"));
		assertTrue(call(adt, "adt_atc_check", "{\"package\":\"ZPKG\",\"format\":\"junit\"}").content()
				.contains("'format' is checkstyle"));
	}

	@Test
	void missingApiOrPackageRunsNothing() {
		List<AdtRequest> posts = new ArrayList<>();
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		adt.route("GET /sap/bc/adt/packages/ZPKG", r -> FakeAdt.ok("<pak:package/>"));
		assertTrue(call(adt, "adt_run_unit_tests", "{\"package\":\"ZPKG\",\"format\":\"junit\"}").content()
				.contains("no ABAP Unit CI API"));
		assertTrue(call(adt, "adt_run_unit_tests", "{\"package\":\"ZNONE\",\"format\":\"junit\"}").content()
				.contains("There is no package ZNONE"));
		assertTrue(posts.isEmpty());
	}

	@Test
	void unreadableReportIsIncompleteNotPassed() {
		// replaces the report of system(): two counted tests, one test case
		FakeAdt adt = system(new ArrayList<>()).route("GET /sap/bc/adt/api/abapunit/results/R1", r -> FakeAdt.ok("<testsuites tests=\"2\" failures=\"0\" "
				+ "errors=\"0\" skipped=\"0\"><testsuite><testcase name=\"A\"/></testsuite></testsuites>"));
		ToolResult r = call(adt, "adt_run_unit_tests", "{\"package\":\"ZPKG\",\"format\":\"junit\"}");
		assertTrue(r.content().contains("INCOMPLETE, which never counts as passed"), r.content());
		assertTrue(r.content().contains("counters do not match"), r.content());
	}

	@Test
	void parsersRefuseUnexpectedReports() throws IOException {
		AdtCi.JUnit j = AdtCi.parseJUnit(JUNIT);
		assertEquals(3, j.tests());
		assertFalse(j.passed());
		assertThrows(IOException.class, () -> AdtCi.parseJUnit("<testsuite tests=\"0\"/>"));
		// errors and skipped may be missing on the root; the test cases then give them
		AdtCi.JUnit few = AdtCi.parseJUnit("<testsuites tests=\"2\" failures=\"1\"><testsuite><testcase name=\"a\"/>"
				+ "<testcase name=\"b\"><failure message=\"m\"/></testcase></testsuite></testsuites>");
		assertEquals(1, few.failures());
		assertEquals(0, few.skipped());
		assertThrows(IOException.class, () -> AdtCi.parseJUnit("<testsuites failures=\"0\"><testsuite/></testsuites>"));
		assertThrows(IOException.class, () -> AdtCi.parseJUnit("<testsuites tests=\"1\" errors=\"x\"><testsuite>"
				+ "<testcase name=\"a\"/></testsuite></testsuites>"));
		assertThrows(IOException.class, () -> AdtCi.parseCheckstyle("<checkstyle><file name=\"x\"><error "
				+ "severity=\"fatal\" message=\"m\"/></file></checkstyle>"));
		assertEquals(2, AdtCi.parseCheckstyle(CHECKSTYLE).size());
	}

	@Test
	void runLocationsStayOnTheSystem() {
		assertEquals("/sap/bc/adt/api/atc/runs/A1", AdtCi.canonical("https://host:443/sap/bc/adt/api/atc/runs/A1",
				AdtCi.ATC_RUNS + "/"));
		assertNull(AdtCi.canonical("/sap/bc/adt/api/atc/runs/../../../x", AdtCi.ATC_RUNS + "/"));
		assertNull(AdtCi.canonical("/sap/bc/adt/repository/x", AdtCi.ATC_RUNS + "/"));
		assertNull(AdtCi.canonical(AdtCi.ATC_RUNS + "/", AdtCi.ATC_RUNS + "/"));
	}
}

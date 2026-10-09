package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** adt_diagnose 'odata_request' and adt_http_send: url checks, sap-statistics, CSRF and output. */
class AdtHttpTest {

	private static FakeAdt adt() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt;
	}

	private static ToolResult call(AdtToolProvider p, String tool, String json) {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void urlsMustStayOnTheSystem() {
		assertEquals("/sap/opu/odata/sap/ZSRV/Items?$top=5",
				AdtHttp.checkUrl(" /sap/opu/odata/sap/ZSRV/Items?$top=5 ", true));
		assertEquals("/sap/opu/odata4/sap/zui/srvd/sap/z/0001/Items", AdtHttp.checkUrl(
				"/sap/opu/odata4/sap/zui/srvd/sap/z/0001/Items", true));
		assertEquals("/sap/bc/rest/zorders", AdtHttp.checkUrl("/sap/bc/rest/zorders", false));
		for (String bad : List.of("", "https://evil.example/sap/opu/odata/x", "//evil.example/sap/opu/odata/x",
				"sap/opu/odata/x", "/sap/opu/odata/../bc/adt/x", "/sap/opu/odata/%2e%2e/bc/adt", "/sap/opu/odata\\x",
				"/sap/opu/odata/x#frag", "/sap/opu/odata/x\ny", "/sap/opu/odata/x?a=1&sap-password=secret")) {
			assertThrows(IllegalArgumentException.class, () -> AdtHttp.checkUrl(bad, false), bad);
		}
		// odata_request reads OData only
		assertThrows(IllegalArgumentException.class, () -> AdtHttp.checkUrl("/sap/bc/rest/zorders", true));
		assertThrows(IllegalArgumentException.class, () -> AdtHttp.checkUrl("/sap/opu/odatax/y", true));
		// ADT and gCTS have their own tools, also when spelled differently
		for (String adt : List.of("/sap/bc/adt/oo/classes/zcl_x", "/SAP/BC/ADT", "/sap//bc/adt/x", "/sap/bc/%61dt/x",
				"/sap/bc/cts_abapvcs/repository")) {
			assertThrows(IllegalArgumentException.class, () -> AdtHttp.checkUrl(adt, false), adt);
		}
	}

	@Test
	void headersTheLogonSetsAreRefused() {
		assertEquals(Map.of("If-Match", "*"), AdtHttp.headers(Json.parseObject("{\"headers\":{\"If-Match\":\"*\"}}")));
		for (String h : List.of("Authorization", "cookie", "X-CSRF-Token", "Host")) {
			assertThrows(IllegalArgumentException.class,
					() -> AdtHttp.headers(Json.parseObject("{\"headers\":{\"" + h + "\":\"x\"}}")), h);
		}
		assertThrows(IllegalArgumentException.class,
				() -> AdtHttp.headers(Json.parseObject("{\"headers\":{\"X-A\":\"1\\r\\nCookie: y\"}}")));
		assertThrows(IllegalArgumentException.class, () -> AdtHttp.headers(Json.parseObject("{\"headers\":\"X-A: 1\"}")));
	}

	@Test
	void statisticsGiveTheVerdict() {
		Map<String, Double> m = AdtHttp.statistics("total=812,fw=10,app=700,gwtotal=800,gwhub=30,gwapp=760,gwappdb=600,x=y");
		assertEquals(600d, m.get("gwappdb"));
		assertFalse(m.containsKey("x"));
		assertTrue(AdtHttp.verdict(m).startsWith("DB-bound"));
		assertTrue(AdtHttp.verdict(AdtHttp.statistics("gwtotal=800,gwapp=700,gwappdb=50")).startsWith("ABAP-bound"));
		assertTrue(AdtHttp.verdict(AdtHttp.statistics("gwtotal=800,gwhub=500,gwapp=100")).startsWith("Gateway-framework"));
		assertTrue(AdtHttp.verdict(AdtHttp.statistics("gwtotal=800")).contains("does not split"));
		assertTrue(AdtHttp.verdict(Map.of()).startsWith("No Gateway timing"));
	}

	@Test
	void odataRequestReadsWithStatistics() {
		List<AdtRequest> sent = new ArrayList<>();
		FakeAdt adt = adt().route("GET /sap/opu/odata/sap/ZSRV/Items", r -> {
			sent.add(r);
			return new AdtResponse(200, "application/json", "{\"d\":{\"results\":[{\"Id\":\"1\"}]}}",
					Map.of("sap-statistics", "gwtotal=40,gwapp=30,gwappdb=25", "set-cookie", "SAP_SESSIONID=abc",
							"x-csrf-token", "tok", "DataServiceVersion", "2.0"));
		});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_diagnose", "{\"action\":\"odata_request\",\"url\":\"/sap/opu/odata/sap/ZSRV/Items?$top=1\","
				+ "\"headers\":{\"Accept-Language\":\"de\"}}");
		assertFalse(r.isError(), r.content());
		assertEquals("/sap/opu/odata/sap/ZSRV/Items?$top=1&sap-statistics=true", sent.get(0).path());
		assertEquals("application/json", sent.get(0).headers().get("Accept"));
		assertEquals("de", sent.get(0).headers().get("Accept-Language"));
		assertTrue(r.content().contains("HTTP 200"), r.content());
		assertTrue(r.content().contains("gwappdb=25"), r.content());
		assertTrue(r.content().contains("Verdict: DB-bound"), r.content());
		assertTrue(r.content().contains("dataserviceversion: 2.0"), r.content());
		assertTrue(r.content().contains("\"Id\": \"1\""), r.content());
		assertFalse(r.content().contains("SAP_SESSIONID"), r.content());
		assertFalse(r.content().contains("tok"), r.content());

		ToolResult refused = call(p, "adt_diagnose", "{\"action\":\"odata_request\",\"url\":\"/sap/bc/adt/discovery\"}");
		assertTrue(refused.isError());
		assertEquals(1, sent.size());
	}

	@Test
	void odataRequestShowsErrors() {
		FakeAdt adt = adt().route("GET /sap/opu/odata/sap/ZSRV/Nope", r -> new AdtResponse(404, "application/xml",
				"<error><code>/IWBEP/CM_MGW_RT/020</code><message>Resource not found for segment 'Nope'</message></error>"));
		ToolResult r = call(new AdtToolProvider(adt, () -> "dev"), "adt_diagnose",
				"{\"action\":\"odata_request\",\"url\":\"/sap/opu/odata/sap/ZSRV/Nope\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("HTTP 404"), r.content());
		assertTrue(r.content().contains("Resource not found for segment"), r.content());
	}

	@Test
	void longAnswersAreCut() {
		String big = "{\"d\":\"" + "x".repeat(AdtDiagnostics.MAX_CHARS + 100) + "\"}";
		String out = AdtHttp.describe(new AdtResponse(200, "application/json", big));
		assertTrue(out.contains("cut after"), out.substring(out.length() - 80));
		assertTrue(out.length() < AdtDiagnostics.MAX_CHARS + 200);
	}

	@Test
	void sendFetchesTheCsrfTokenInTheSameSession() {
		List<AdtRequest> sent = new ArrayList<>();
		FakeAdt adt = adt().route("GET /sap/opu/odata/sap/ZSRV/Items", r -> {
			sent.add(r);
			return new AdtResponse(200, "application/json", "{}", Map.of("x-csrf-token", "T0K3N"));
		}).route("POST /sap/opu/odata/sap/ZSRV/Items", r -> {
			sent.add(r);
			return new AdtResponse(201, "application/json", "{\"d\":{\"Id\":\"42\"}}",
					Map.of("location", "/sap/opu/odata/sap/ZSRV/Items('42')", "set-cookie", "SAP_SESSIONID=abc"));
		});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = call(p, "adt_http_send", "{\"method\":\"post\",\"url\":\"/sap/opu/odata/sap/ZSRV/Items?sap-client=100\","
				+ "\"body\":\"{\\\"Name\\\":\\\"A\\\"}\"}");
		assertFalse(r.isError(), r.content());
		assertEquals(List.of("S GET /sap/opu/odata/sap/ZSRV/Items", "S POST /sap/opu/odata/sap/ZSRV/Items?sap-client=100"),
				adt.log);
		assertEquals("Fetch", sent.get(0).headers().get("x-csrf-token"));
		assertEquals("T0K3N", sent.get(1).headers().get("x-csrf-token"));
		assertEquals("application/json", sent.get(1).contentType());
		assertEquals("{\"Name\":\"A\"}", sent.get(1).body());
		assertEquals(0, adt.openSessions);
		assertTrue(r.content().contains("HTTP 201"), r.content());
		assertTrue(r.content().contains("location: /sap/opu/odata/sap/ZSRV/Items('42')"), r.content());
		assertFalse(r.content().contains("T0K3N"), r.content());
		assertFalse(r.content().contains("SAP_SESSIONID"), r.content());
	}

	@Test
	void sendSaysWhenNoTokenCame() {
		FakeAdt adt = adt().route("GET /sap/bc/rest/zorders", r -> new AdtResponse(405, "text/plain", ""))
				.route("DELETE /sap/bc/rest/zorders/1", r -> new AdtResponse(204, "", ""));
		ToolResult r = call(new AdtToolProvider(adt, () -> "dev"), "adt_http_send",
				"{\"method\":\"DELETE\",\"url\":\"/sap/bc/rest/zorders/1\",\"headers\":{\"If-Match\":\"*\"}}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("No CSRF token"), r.content());
		assertTrue(r.content().contains("HTTP 204"), r.content());
	}

	@Test
	void sendRefusesBeforeOpeningASession() {
		FakeAdt adt = adt();
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		for (String json : List.of("{\"method\":\"GET\",\"url\":\"/sap/bc/rest/x\"}",
				"{\"method\":\"POST\",\"url\":\"/sap/bc/adt/activation\"}",
				"{\"method\":\"POST\",\"url\":\"http://other/x\"}",
				"{\"method\":\"POST\",\"url\":\"/sap/bc/rest/x\",\"headers\":{\"Authorization\":\"Basic x\"}}")) {
			ToolResult r = call(p, "adt_http_send", json);
			assertTrue(r.isError(), json);
		}
		assertTrue(adt.log.stream().noneMatch(l -> l.contains(" POST ")), adt.log.toString());
		assertEquals(0, adt.openSessions);
	}
}

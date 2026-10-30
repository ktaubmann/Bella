package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtTableSettingsTest {

	/** Shaped like SAP_BASIS 7.58 GET …/ddic/db/settings/{table} (ARC-1's fixture, neutral names). */
	static String settings(String version) {
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?><ts:tableSettings adtcore:name=\"ZTEST\" "
				+ "adtcore:type=\"TABL/DTT\" adtcore:version=\"" + version + "\" adtcore:description=\"Test\" "
				+ "xmlns:ts=\"http://www.sap.com/dictionary/table/settings\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
				+ "<adtcore:containerRef adtcore:uri=\"/sap/bc/adt/ddic/tables/ztest\" adtcore:type=\"TABL/DT\" "
				+ "adtcore:name=\"ZTEST\"/><ts:dataClassCategory>APPL0</ts:dataClassCategory><ts:sizeCategory>0"
				+ "</ts:sizeCategory><ts:buffering><ts:allowed>N</ts:allowed><ts:type/><ts:areaKeyFields>0"
				+ "</ts:areaKeyFields></ts:buffering><ts:storageType/><ts:sharingType/><ts:loadUnit/><ts:translation "
				+ "ts:value=\"\" ts:isVisible=\"false\" ts:isEditable=\"false\" ts:granularity=\"2\"/><ts:loggingEnabled>"
				+ "false</ts:loggingEnabled><ts:supportsLoggingAssessment>false</ts:supportsLoggingAssessment>"
				+ "</ts:tableSettings>";
	}

	private static final String URI = "/sap/bc/adt/ddic/db/settings/ztest";

	private static FakeAdt adt(String[] version, List<AdtRequest> puts) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("GET " + URI + "?version=inactive", r -> FakeAdt.ok(settings(version[0])))
				.route("POST " + URI + "?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST " + URI + "?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT " + URI, r -> {
					puts.add(r);
					return FakeAdt.ok("");
				});
	}

	@Test
	void readShowsTheSettings() {
		ToolResult r = new AdtToolProvider(adt(new String[] { "active" }, new ArrayList<>()), () -> "dev").call(
				"adt_read_source", Json.parseObject("{\"name\":\"ztest\",\"type\":\"TABL/DTT\"}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("\"data_class\": \"APPL0\""), r.content());
		assertTrue(r.content().contains("\"size_category\": 0"), r.content());
		assertTrue(r.content().contains("\"logging\": false"), r.content());
		assertTrue(r.content().contains("\"buffering_allowed\": \"N\""), r.content());
		assertTrue(r.content().contains("then activate type TABL/DTT."), r.content());
	}

	@Test
	void changeReplacesOnlyTheGivenFieldsUnderTheLock() {
		List<AdtRequest> puts = new ArrayList<>();
		FakeAdt adt = adt(new String[] { "active" }, puts);
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_write_metadata", Json.parseObject(
				"{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\",\"data_class\":\"appl1\",\"buffering_allowed\":\"X\","
						+ "\"buffering_type\":\"G\",\"buffering_key_fields\":1,\"logging\":true}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the technical settings of ZTEST in S4H_100. Not activated yet; activate them with type "
				+ "TABL/DTT.", r.content());
		// read in the locked session, so another user's pending change is not lost
		int lock = adt.log.indexOf("S POST " + URI + "?_action=LOCK&accessMode=MODIFY");
		assertTrue(lock >= 0 && adt.log.indexOf("S GET " + URI + "?version=inactive") > lock, adt.log.toString());
		String put = puts.get(0).body();
		assertEquals(settings("active").replace("APPL0", "APPL1").replace("<ts:allowed>N", "<ts:allowed>X")
				.replace("<ts:type/>", "<ts:type>G</ts:type>").replace("<ts:areaKeyFields>0", "<ts:areaKeyFields>1")
				.replace("<ts:loggingEnabled>false", "<ts:loggingEnabled>true"), put);
		assertEquals(AdtTableSettings.TYPE, puts.get(0).headers().get("Accept"));
	}

	@Test
	void invalidValuesAreRefusedBeforeSaving() {
		List<AdtRequest> puts = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt(new String[] { "active" }, puts), () -> "dev");
		String allowed = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\","
				+ "\"buffering_allowed\":\"Y\"}"), CancelToken.NONE).content();
		assertTrue(allowed.contains("buffering_allowed is N"), allowed);
		String size = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\","
				+ "\"size_category\":12}"), CancelToken.NONE).content();
		assertTrue(size.contains("size_category is 0 to 9"), size);
		String nothing = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\"}"),
				CancelToken.NONE).content();
		assertTrue(nothing.contains("Give at least one of"), nothing);
		assertTrue(puts.isEmpty());
	}

	@Test
	void newSettingsAreActivatedWithTheTable() {
		String[] version = { "new" };
		FakeAdt adt = adt(version, new ArrayList<>()).route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(""));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		String json = "{\"objects\":[{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\"}]}";
		ToolResult r = p.call("adt_activate", Json.parseObject(json), CancelToken.NONE);
		assertTrue(r.isError() && r.content().contains("Activate ZTEST with type TABL"), r.content());
		assertFalse(adt.log.stream().anyMatch(l -> l.contains("/activation")), adt.log.toString());

		// a cancelled activation that SAP reports without an error
		version[0] = "inactive";
		ToolResult still = p.call("adt_activate", Json.parseObject(json), CancelToken.NONE);
		assertTrue(still.isError() && still.content().contains("are still inactive after the activation"),
				still.content());
		version[0] = "active";
		assertFalse(p.call("adt_activate", Json.parseObject(json), CancelToken.NONE).isError());
	}

	@Test
	void packageIsTheTables() {
		FakeAdt adt = adt(new String[] { "active" }, new ArrayList<>()).route("GET /sap/bc/adt/ddic/tables/ztest",
				r -> FakeAdt.ok("<x xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:packageRef "
						+ "adtcore:name=\"ZOTHER\"/></x>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "ZPKG");
		String refused = p.refuse("adt_write_metadata", Json.parseObject("{\"name\":\"ZTEST\",\"type\":\"TABL/DTT\","
				+ "\"data_class\":\"APPL1\"}"), CancelToken.NONE).orElseThrow();
		assertTrue(refused.contains("ZTEST is in package ZOTHER"), refused);
	}
}

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

class AdtServerDrivenTest {

	private static final String LOCKED = "<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>";

	/** A system with one object of the type at {@code collection}, recording POSTs and PUTs. */
	private static FakeAdt adt(String collection, String name, String meta, String source, List<AdtRequest> writes) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		String uri = collection + "/" + name;
		return adt.route("POST " + uri + "?_action=LOCK", r -> FakeAdt.ok(LOCKED))
				.route("POST " + uri + "?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT " + uri + "/source/main", r -> {
					writes.add(r);
					return FakeAdt.ok("");
				})
				.route("GET " + uri + "/source/main", r -> new AdtResponse(200, "application/json", source))
				.route("GET " + uri, r -> FakeAdt.ok(meta))
				.route("POST " + collection, r -> {
					writes.add(r);
					return new AdtResponse(201, "application/xml", "");
				});
	}

	private static String meta(String type, String name, String pkg) {
		return "<blue:blueSource xmlns:blue=\"http://www.sap.com/wbobj/blue\" xmlns:adtcore=\"http://www.sap.com/adt/core\" "
				+ "adtcore:name=\"" + name + "\" adtcore:type=\"" + type + "\" adtcore:description=\"Test\" "
				+ "adtcore:version=\"active\"><adtcore:packageRef adtcore:name=\"" + pkg + "\"/></blue:blueSource>";
	}

	@Test
	void createPostsTheMetadataThenTheSource() {
		List<AdtRequest> writes = new ArrayList<>();
		FakeAdt adt = adt("/sap/bc/adt/ddic/drty/sources", "ZBELLA_AMOUNT", "", "", writes);
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"zbella_amount\",\"type\":\"DRTY\",\"description\":\"Amount\",\"package\":\"$tmp\","
						+ "\"source\":\"define type zbella_amount : abap.dec(15,2)\"}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Created ZBELLA_AMOUNT (DRTY, CDS type (simple type or enumeration)) in package $TMP on S4H_100. "
				+ "Source written. Not activated yet.", r.content());
		AdtRequest post = writes.get(0);
		assertEquals("application/vnd.sap.adt.blues.v1+xml", post.contentType());
		assertTrue(post.body().contains("<blue:blueSource xmlns:blue=\"http://www.sap.com/wbobj/blue\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:type=\"DRTY/STY\" adtcore:name=\"ZBELLA_AMOUNT\""),
				post.body());
		assertFalse(post.body().contains("masterLanguage"), post.body());
		assertEquals("text/plain; charset=utf-8", writes.get(1).contentType());
		assertEquals("define type zbella_amount : abap.dec(15,2)", writes.get(1).body());
		assertEquals(0, adt.openSessions);
	}

	@Test
	void jsonTypesAreCheckedBeforeCreating() {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt("/sap/bc/adt/businessservices/evtbevb", "ZB", "", "", writes),
				() -> "dev");
		String r = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZB\",\"type\":\"EVTB/EVB\","
				+ "\"description\":\"x\",\"package\":\"$TMP\",\"source\":\"{not json\"}"), CancelToken.NONE).content();
		assertTrue(r.contains("takes its source as AFF JSON"), r);
		String job = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZB_JOB\",\"type\":\"SAJC\","
				+ "\"description\":\"x\",\"package\":\"$TMP\",\"source\":\"{}\"}"), CancelToken.NONE).content();
		assertTrue(job.contains("needs 'source' with generalInformation.className"), job);
		String log = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZBELLA_LOG_WITH_A_LONG_NAME\","
				+ "\"type\":\"APLO\",\"description\":\"x\",\"package\":\"$TMP\"}"), CancelToken.NONE).content();
		assertTrue(log.contains("at most 20 characters"), log);
		assertTrue(writes.isEmpty());
	}

	@Test
	void jobCatalogsNameTheirClassInTheCreate() throws Exception {
		String xml = AdtServerDriven.createXml(AdtServerDriven.type("SAJC"), "zb_job", "Jobs", "$TMP",
				"{\"generalInformation\":{\"className\":\"ZCL_B_JOB\"}}");
		assertTrue(xml.contains("<blue:additionalCreationProperties><adtcore:content adtcore:type=\"application/vnd.sap.adt."
				+ "objecttype.new.content.additional.v1+json\">{&quot;className&quot;:&quot;ZCL_B_JOB&quot;}"
				+ "</adtcore:content></blue:additionalCreationProperties>"), xml);
		AdtXml.parse(xml);
	}

	@Test
	void missingTypeIsExplained() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"ZB\",\"type\":\"COTA\",\"description\":\"x\",\"package\":\"$TMP\"}"), CancelToken.NONE);
		assertTrue(r.isError() && r.content().contains("8.16") && r.content().contains("Nothing was created"),
				r.content());
	}

	@Test
	void readShowsHeaderAndSource() {
		FakeAdt adt = adt("/sap/bc/adt/businessservices/evtbevb", "ZB", meta("EVTB/EVB", "ZB", "ZPKG"),
				"{\"formatVersion\":\"1\",\"header\":{\"description\":\"Test\"}}", new ArrayList<>());
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_read_source",
				Json.parseObject("{\"name\":\"zb\",\"type\":\"EVTB\"}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("EVTB ZB (RAP event binding): Test, package ZPKG, active."), r.content());
		assertTrue(r.content().contains("\"formatVersion\": \"1\""), r.content());
		// the package needs the type's own media type
		assertTrue(adt.log.contains("GET /sap/bc/adt/businessservices/evtbevb/ZB"), adt.log.toString());
	}

	@Test
	void writeSendsJsonAndTheGuardReadsThePackage() {
		List<AdtRequest> writes = new ArrayList<>();
		FakeAdt adt = adt("/sap/bc/adt/applicationlog/objects", "ZB_LOG", meta("APLO/TYP", "ZB_LOG", "ZOTHER"), "{}",
				writes);
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_write_source", Json.parseObject(
				"{\"name\":\"ZB_LOG\",\"type\":\"APLO\",\"source\":\"{\\\"header\\\":{}}\"}"), CancelToken.NONE);
		assertEquals("Saved ZB_LOG (APLO) in S4H_100. Active at once.", r.content());
		assertEquals("application/json", writes.get(0).contentType());
		String refused = new AdtToolProvider(adt, () -> "dev", () -> "ZPKG").refuse("adt_write_source",
				Json.parseObject("{\"name\":\"ZB_LOG\",\"type\":\"APLO\",\"source\":\"{}\"}"), CancelToken.NONE)
				.orElseThrow();
		assertTrue(refused.contains("ZB_LOG is in package ZOTHER"), refused);
	}
}

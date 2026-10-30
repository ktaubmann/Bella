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

class AdtLockObjectTest {

	/** Shaped like a SAP_BASIS 8.16 GET of a lock object (ARC-1's fixture, neutral names). */
	static final String STORED = "<?xml version=\"1.0\" encoding=\"utf-8\"?><enqu:lockobject adtcore:masterLanguage=\"EN\" "
			+ "adtcore:name=\"EZORDER\" adtcore:type=\"ENQU/DL\" adtcore:description=\"Orders &amp; items\" "
			+ "xmlns:enqu=\"http://www.sap.com/adt/ddic/enqu\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
			+ "<adtcore:packageRef adtcore:name=\"ZPKG\"/><enqu:content><enqu:allowRFC>false</enqu:allowRFC>"
			+ "<enqu:primaryTable><enqu:tableName>ZORDERS</enqu:tableName><enqu:lockMode>E</enqu:lockMode></enqu:primaryTable>"
			+ "<enqu:secondaryTables><enqu:secondaryTable><enqu:tableName>ZITEMS</enqu:tableName><enqu:lockMode>S"
			+ "</enqu:lockMode></enqu:secondaryTable></enqu:secondaryTables><enqu:lockParameters>"
			+ "<enqu:lockParameter><enqu:parameterWanted>true</enqu:parameterWanted><enqu:parameterName>ORDER_ID"
			+ "</enqu:parameterName><enqu:tableName>ZORDERS</enqu:tableName><enqu:fieldName>ORDER_ID</enqu:fieldName>"
			+ "</enqu:lockParameter><enqu:lockParameter><enqu:parameterWanted>false</enqu:parameterWanted>"
			+ "<enqu:parameterName>ITEM</enqu:parameterName><enqu:tableName>ZITEMS</enqu:tableName><enqu:fieldName>ITEM"
			+ "</enqu:fieldName></enqu:lockParameter></enqu:lockParameters><enqu:lockModules><enqu:lockModule "
			+ "adtcore:type=\"FUGR/FF\" adtcore:name=\"ENQUEUE_EZORDER\"/></enqu:lockModules></enqu:content>"
			+ "</enqu:lockobject>";

	private static final String LOCKED = "<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>";
	private static final String URI = "/sap/bc/adt/ddic/lockobjects/sources";

	private static FakeAdt adt(List<AdtRequest> writes) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("GET " + URI + "/ezorder", r -> FakeAdt.ok(STORED))
				.route("POST " + URI + "/ezorder?_action=LOCK", r -> FakeAdt.ok(LOCKED))
				.route("POST " + URI + "/ezorder?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT " + URI + "/ezorder", r -> {
					writes.add(r);
					return FakeAdt.ok("");
				})
				.route("POST " + URI, r -> {
					writes.add(r);
					return new AdtResponse(201, "application/xml", "");
				});
	}

	@Test
	void createSendsTheTablesWithThePost() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(adt(writes), () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"ezorder\",\"type\":\"ENQU\",\"description\":\"Orders\",\"package\":\"$TMP\","
						+ "\"tables\":[{\"table\":\"zorders\",\"lock_mode\":\"E\"},{\"table\":\"zitems\",\"lock_mode\":\"\"}]}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Created EZORDER (ENQU) in package $TMP on S4H_100. Not activated yet.", r.content());
		assertEquals(1, writes.size(), "no PUT after the create");
		AdtRequest post = writes.get(0);
		assertEquals(AdtLockObject.TYPE, post.contentType());
		assertTrue(post.body().startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<enqu:lockobject "), post.body());
		assertTrue(post.body().contains("<enqu:primaryTable><enqu:tableName>ZORDERS</enqu:tableName><enqu:lockMode>E"),
				post.body());
		assertTrue(post.body().contains("<enqu:secondaryTable><enqu:tableName>ZITEMS</enqu:tableName><enqu:lockMode>"
				+ "</enqu:lockMode>"), post.body());
		// SAP derives the parameters from the keys
		assertTrue(post.body().contains("<enqu:lockParameters></enqu:lockParameters>"), post.body());
	}

	@Test
	void createChecksTheInputFirst() {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt(writes), () -> "dev");
		String head = "{\"name\":\"EZORDER\",\"type\":\"ENQU\",\"description\":\"x\",\"package\":\"$TMP\"";
		assertTrue(p.call("adt_create_object", Json.parseObject(head + "}"), CancelToken.NONE).content()
				.contains("Give 'tables'"));
		String optimistic = p.call("adt_create_object", Json.parseObject(head
				+ ",\"tables\":[{\"table\":\"ZORDERS\",\"lock_mode\":\"O\"}]}"), CancelToken.NONE).content();
		assertTrue(optimistic.contains("O (optimistic) is a mode of the ENQUEUE_ call"), optimistic);
		String noMode = p.call("adt_create_object", Json.parseObject(head + ",\"tables\":[{\"table\":\"ZORDERS\"}]}"),
				CancelToken.NONE).content();
		assertTrue(noMode.contains("lock_mode is E"), noMode);
		assertTrue(writes.isEmpty());
	}

	@Test
	void changeKeepsWhatIsNotGiven() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(adt(writes), () -> "dev").call("adt_write_metadata",
				Json.parseObject("{\"name\":\"EZORDER\",\"type\":\"ENQU\",\"allow_rfc\":true}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the lock object EZORDER in S4H_100. Not activated yet.", r.content());
		String put = writes.get(0).body();
		assertTrue(put.contains("<enqu:allowRFC>true</enqu:allowRFC>"), put);
		assertTrue(put.contains("<enqu:lockMode>S</enqu:lockMode>"), put);
		assertTrue(put.contains("<enqu:parameterWanted>false</enqu:parameterWanted><enqu:parameterName>ITEM"), put);
		assertTrue(put.contains("adtcore:description=\"Orders &amp; items\""), put);
		assertFalse(put.contains("lockModule"), put);
		assertEquals(AdtLockObject.TYPE, writes.get(0).headers().get("Accept"));
	}

	@Test
	void changedTablesNeedTheirParameters() {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt(writes), () -> "dev");
		String tables = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"EZORDER\",\"type\":\"ENQU\","
				+ "\"tables\":[{\"table\":\"ZORDERS\",\"lock_mode\":\"E\"}]}"), CancelToken.NONE).content();
		assertTrue(tables.contains("Changing the tables needs 'lock_parameters'"), tables);
		String empty = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"EZORDER\",\"type\":\"ENQU\","
				+ "\"lock_parameters\":[]}"), CancelToken.NONE).content();
		assertTrue(empty.contains("'lock_parameters': [] is refused"), empty);
		// the lock mode of a table may change without the parameters
		ToolResult mode = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"EZORDER\",\"type\":\"ENQU\","
				+ "\"tables\":[{\"table\":\"ZORDERS\",\"lock_mode\":\"X\"},{\"table\":\"ZITEMS\",\"lock_mode\":\"S\"}]}"),
				CancelToken.NONE);
		assertFalse(mode.isError(), mode.content());
		assertEquals(1, writes.size());
	}

	@Test
	void readShowsTheWritableForm() throws Exception {
		String out = AdtLockObject.describe(AdtLockObject.parse(STORED));
		assertTrue(out.contains("\"tables\": [\n    {\n      \"table\": \"ZORDERS\",\n      \"lock_mode\": \"E\""), out);
		assertTrue(out.contains("\"wanted\": false"), out);
		assertTrue(out.contains("\"ENQUEUE_EZORDER\""), out);
		AdtLockObject.Stored s = AdtLockObject.parse(STORED);
		assertEquals(s.definition(), AdtLockObject.parse(AdtLockObject.xml(s.name(), s.description(), s.pkg(), "EN",
				null, s.definition())).definition());
	}
}

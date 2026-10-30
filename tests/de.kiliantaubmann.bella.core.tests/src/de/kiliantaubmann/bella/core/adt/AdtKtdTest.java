package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtKtdTest {

	private static String b64(String s) {
		return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	/** An envelope with the view's own node and one field; {@code view} and {@code field} are base64 or empty. */
	static String envelope(String view, String field) {
		return "<?xml version=\"1.0\" encoding=\"utf-8\"?><sktd:docu adtcore:name=\"ZI_ORDER\" adtcore:type=\"SKTD/TYP\" "
				+ "xmlns:sktd=\"http://www.sap.com/wbobj/texts/sktd\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
				+ "<adtcore:packageRef adtcore:name=\"ZPKG\"/><sktd:element sktd:longTextObligation=\"optional\">"
				+ "<sktd:id>ZI_ORDER</sktd:id>" + (view.isEmpty() ? "<sktd:text/>" : "<sktd:text>" + view + "</sktd:text>")
				+ "</sktd:element><sktd:element sktd:longTextObligation=\"optional\"><sktd:id>ZI_ORDER.OrderId</sktd:id>"
				+ (field.isEmpty() ? "<sktd:text/>" : "<sktd:text>" + field + "</sktd:text>")
				+ "</sktd:element></sktd:docu>";
	}

	private static final String URI = "/sap/bc/adt/documentation/ktd/documents";

	private static FakeAdt adt(String stored, List<AdtRequest> writes) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("GET " + URI + "/zi_order", r -> FakeAdt.ok(stored))
				.route("POST " + URI + "/zi_order?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST " + URI + "/zi_order?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT " + URI + "/zi_order", r -> {
					writes.add(r);
					return FakeAdt.ok("");
				})
				.route("POST " + URI, r -> {
					writes.add(r);
					return new AdtResponse(201, "application/xml", "");
				});
	}

	@Test
	void createReferencesTheDocumentedObjectAndWritesTheMarkdown() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(adt(envelope("", ""), writes), () -> "dev").call("adt_create_object",
				Json.parseObject("{\"name\":\"zi_order\",\"type\":\"SKTD\",\"description\":\"x\",\"package\":\"$TMP\","
						+ "\"documents_type\":\"DDLS\",\"source\":\"# Orders\\nOne row per order.\"}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Created ZI_ORDER (SKTD) in package $TMP on S4H_100. Documentation written. Not activated yet.",
				r.content());
		String post = writes.get(0).body();
		assertEquals(AdtKtd.TYPE, writes.get(0).contentType());
		assertTrue(post.contains("<sktd:refObject adtcore:name=\"ZI_ORDER\" adtcore:type=\"DDLS/DF\" "
				+ "adtcore:uri=\"/sap/bc/adt/ddic/ddl/sources/zi_order\"/>"), post);
		assertFalse(post.contains("adtcore:description"), post);
		// the object's own node, nothing else changed
		assertEquals(envelope(b64("# Orders\nOne row per order."), ""), writes.get(1).body());
	}

	@Test
	void classesAreRefusedBeforeCreating() {
		List<AdtRequest> writes = new ArrayList<>();
		String r = new AdtToolProvider(adt(envelope("", ""), writes), () -> "dev").call("adt_create_object",
				Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"KTD\",\"description\":\"x\",\"package\":\"$TMP\","
						+ "\"documents_type\":\"CLAS\"}"),
				CancelToken.NONE).content();
		assertTrue(r.contains("ABAP Doc"), r);
		assertTrue(writes.isEmpty());
	}

	@Test
	void severalNodesAreAddressedByHeading() throws Exception {
		String two = envelope(b64("View"), b64("Field"));
		// as adt_read_source shows them
		String read = AdtClient.ktdText(two);
		assertTrue(read.contains("## ZI_ORDER.OrderId"), read);
		String changed = AdtKtd.rewrite(two, "## ZI_ORDER\n\nView, new\n\n## zi_order.orderid\n\nField, new\n");
		assertEquals(envelope(b64("View, new"), b64("Field, new")), changed);
		// a heading that names no node is Markdown
		assertEquals(envelope(b64("View"), b64("## Details\nmore")),
				AdtKtd.rewrite(two, "## ZI_ORDER.OrderId\n## Details\nmore"));
	}

	@Test
	void unclearTargetsAreRefused() {
		String two = envelope(b64("View"), b64("Field"));
		AdtException plain = assertThrows(AdtException.class, () -> AdtKtd.rewrite(two, "Just text"));
		assertTrue(plain.getMessage().contains("documents 2 nodes"), plain.getMessage());
		AdtException before = assertThrows(AdtException.class,
				() -> AdtKtd.rewrite(two, "Intro\n## ZI_ORDER\nView"));
		assertTrue(before.getMessage().contains("Text before the first"), before.getMessage());
		assertThrows(AdtException.class, () -> AdtKtd.rewrite(two, "  "));
	}

	@Test
	void writeSourceSavesTheDocumentation() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(adt(envelope(b64("Old"), ""), writes), () -> "dev").call("adt_write_source",
				Json.parseObject("{\"name\":\"ZI_ORDER\",\"type\":\"SKTD\",\"source\":\"New\"}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the knowledge transfer document ZI_ORDER in S4H_100. Not activated yet.", r.content());
		assertEquals(envelope(b64("New"), ""), writes.get(0).body());
		assertEquals(AdtKtd.TYPE, writes.get(0).headers().get("Accept"));
	}
}

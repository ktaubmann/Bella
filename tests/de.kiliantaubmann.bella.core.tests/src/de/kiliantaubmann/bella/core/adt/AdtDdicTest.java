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

class AdtDdicTest {

	private static AdtDdic.CreateRequest create(String type, String name, String json) throws AdtException {
		return AdtDdic.create(type, name, "Desc", "ZPKG", "DEVK900001", "de", "DEVELOPER", Json.parseObject(json));
	}

	@Test
	void createRequestsPerType() throws AdtException {
		AdtDdic.CreateRequest fugr = create("FUGR", "ZSD_DELIVERY", "{}");
		assertEquals("/sap/bc/adt/functions/groups", fugr.collection());
		assertEquals(AdtDdic.FUNCTION_GROUP_TYPE, fugr.contentType());
		assertEquals("?corrNr=DEVK900001", fugr.query());
		assertTrue(fugr.body().contains("adtcore:type=\"FUGR/F\""), fugr.body());
		assertTrue(fugr.body().contains("adtcore:language=\"DE\""), fugr.body());

		AdtDdic.CreateRequest func = create("FUNC", "Z_SD_DELETE", "{\"group\":\"zsd_delivery\"}");
		assertEquals("/sap/bc/adt/functions/groups/zsd_delivery/fmodules", func.collection());
		assertEquals("/sap/bc/adt/functions/groups/zsd_delivery/fmodules/z_sd_delete", func.objectUri());
		assertEquals(AdtDdic.FUNCTION_MODULE_TYPE, func.contentType());
		assertTrue(func.body().contains("<adtcore:containerRef adtcore:name=\"ZSD_DELIVERY\" adtcore:type=\"FUGR/F\" "
				+ "adtcore:uri=\"/sap/bc/adt/functions/groups/zsd_delivery\"/>"), func.body());
		assertFalse(func.body().contains("packageRef"), func.body());
		assertThrows(AdtException.class, () -> create("FUNC", "Z_X", "{}"));

		AdtDdic.CreateRequest table = create("TABL", "ZSD_LOG", "{}");
		assertEquals("/sap/bc/adt/ddic/tables", table.collection());
		assertEquals("?corrNr=DEVK900001&_package=ZPKG", table.query());
		assertTrue(table.body().contains("<blue:blueSource") && table.body().contains("adtcore:type=\"TABL/DT\""));
		assertEquals("/sap/bc/adt/ddic/structures", create("STRU", "ZSD_S", "{}").collection());

		assertEquals("/sap/bc/adt/ddic/ddl/sources", create("DDLS", "ZI_X", "{}").collection());
		assertEquals(AdtDdic.BDEF_TYPE, create("BDEF", "ZI_X", "{}").contentType());
		assertEquals("/sap/bc/adt/ddic/ddlx/sources", create("DDLX", "ZC_X", "{}").collection());
		assertTrue(create("SRVD", "ZUI_X", "{}").body().contains("srvd:srvdSourceType=\"S\""));
		assertThrows(AdtException.class, () -> create("SRVB", "ZUI_X_O4", "{}"));
		String srvb = create("SRVB", "ZUI_X_O4", "{\"service_definition\":\"zui_x\",\"binding_type\":\"ODATA V4 UI\"}")
				.body();
		assertTrue(srvb.contains("srvb:category=\"0\" srvb:type=\"ODATA\" srvb:version=\"V4\""), srvb);
		assertTrue(srvb.contains("<srvb:serviceDefinition adtcore:name=\"ZUI_X\"/>"), srvb);
		assertThrows(AdtException.class, () -> create("VIEW", "ZV", "{}"));
		// person responsible only when it fits ADT's 12 characters
		assertFalse(AdtDdic.create("PROG", "ZX", "d", "$TMP", null, null, "first.last@example.com", null).body()
				.contains("responsible"));
	}

	@Test
	void dataElementsAndDomains() throws Exception {
		AdtDdic.CreateRequest dtel = create("DTEL", "ZSD_VBELN",
				"{\"domain\":\"vbeln\",\"short_label\":\"Delivery\",\"long_label\":\"Delivery number\"}");
		assertEquals(AdtDdic.DATAELEMENT_TYPE, dtel.contentType());
		assertTrue(dtel.body().contains("<dtel:typeKind>domain</dtel:typeKind>"), dtel.body());
		assertTrue(dtel.body().contains("<dtel:typeName>VBELN</dtel:typeName>"), dtel.body());
		assertTrue(dtel.body().contains("<dtel:shortFieldLabel>Delivery</dtel:shortFieldLabel>"
				+ "\n    <dtel:shortFieldLength>08</dtel:shortFieldLength>"), dtel.body());
		assertTrue(dtel.body().contains("<dtel:longFieldMaxLength>40</dtel:longFieldMaxLength>"), dtel.body());

		// changing one label keeps the other fields
		Map<String, String> current = AdtDdic.parseDataElement(dtel.body());
		assertEquals("VBELN", current.get("typeName"));
		Map<String, String> changed = AdtDdic.dataElementFields(Json.parseObject("{\"medium_label\":\"Lieferung\"}"),
				current);
		assertEquals("VBELN", changed.get("typeName"));
		assertEquals("Delivery", changed.get("shortFieldLabel"));
		assertEquals("Lieferung", changed.get("mediumFieldLabel"));
		assertEquals("09", changed.get("mediumFieldLength"));

		AdtDdic.CreateRequest doma = create("DOMA", "ZSD_STATUS", "{\"data_type\":\"char\",\"length\":1,"
				+ "\"fixed_values\":[{\"low\":\"A\",\"text\":\"Open\"},{\"low\":\"C\",\"text\":\"Closed\"}]}");
		assertTrue(doma.body().contains("<doma:datatype>CHAR</doma:datatype>"), doma.body());
		assertTrue(doma.body().contains("<doma:length>000001</doma:length>"), doma.body());
		assertTrue(doma.body().contains("<doma:position>0002</doma:position><doma:low>C</doma:low>"), doma.body());
		assertThrows(AdtException.class, () -> create("DOMA", "ZSD_X", "{}"));
		AdtDdic.Domain parsed = AdtDdic.parseDomain(doma.body());
		assertEquals("CHAR", parsed.dataType());
		assertEquals(2, parsed.fixedValues().size());
		AdtDdic.Domain lower = AdtDdic.domainFields(Json.parseObject("{\"lowercase\":true}"), parsed);
		assertTrue(lower.lowercase());
		assertEquals("000001", lower.length());
		assertEquals(2, lower.fixedValues().size());

		String ttyp = create("TTYP", "ZSD_T_VBELN", "{\"row_type\":\"vbeln\"}").body();
		assertTrue(ttyp.contains("<ttyp:typeKind>dictionaryType</ttyp:typeKind><ttyp:typeName>VBELN</ttyp:typeName>"),
				ttyp);
		assertTrue(create("TTYP", "ZSD_T_S", "{\"row_type\":\"string\"}").body()
				.contains("<ttyp:typeKind>predefinedAbapType</ttyp:typeKind>"));
	}

	@Test
	void messageClasses() throws Exception {
		List<AdtDdic.Message> messages = AdtDdic.messages(Json.parseObject(
				"{\"messages\":[{\"number\":\"1\",\"text\":\"Delivery & locked\"},{\"number\":2,\"text\":\"Done\"}]}"));
		assertEquals(List.of(new AdtDdic.Message("001", "Delivery & locked"), new AdtDdic.Message("002", "Done")),
				messages);
		String xml = AdtDdic.messageClassXml("ZSD", "Deliveries", "ZPKG", "DE", messages);
		assertTrue(xml.contains("adtcore:language=\"DE\" adtcore:masterLanguage=\"DE\""), xml);
		assertTrue(xml.contains("mc:msgno=\"001\" mc:msgtext=\"Delivery &amp; locked\""), xml);
		AdtDdic.MessageClass mc = AdtDdic.parseMessageClass(xml);
		assertEquals("ZPKG", mc.pkg());
		assertEquals(messages, mc.messages());
		assertEquals(List.of(new AdtDdic.Message("001", "Delivery & locked"), new AdtDdic.Message("003", "New")),
				AdtDdic.mergeMessages(mc.messages(), List.of(new AdtDdic.Message("003", "New")), List.of("2")));
		assertThrows(AdtException.class, () -> AdtDdic.messages(Json.parseObject(
				"{\"messages\":[{\"number\":\"1000\",\"text\":\"x\"}]}")));
	}

	@Test
	void changedMessageKeepsItsLongTextFlags() throws Exception {
		String xml = AdtDdic.messageClassXml("ZSD", "Deliveries", "ZPKG", "DE",
				List.of(new AdtDdic.Message("001", "Old", false, true)));
		List<AdtDdic.Message> current = AdtDdic.parseMessageClass(xml).messages();
		assertEquals(List.of(new AdtDdic.Message("001", "Old", false, true)), current);
		List<AdtDdic.Message> merged = AdtDdic.mergeMessages(current,
				List.of(new AdtDdic.Message("001", "Changed"), new AdtDdic.Message("005", "New")), List.of());
		assertEquals(List.of(new AdtDdic.Message("001", "Changed", false, true), new AdtDdic.Message("005", "New")),
				merged);
		String out = AdtDdic.messageClassXml("ZSD", "Deliveries", "ZPKG", "DE", merged);
		assertTrue(out.contains("mc:msgno=\"001\" mc:msgtext=\"Changed\" mc:selfexplainatory=\"false\" mc:documented=\"true\""),
				out);
	}

	@Test
	void tableTypeUpdateKeepsKeysAndAccessType() throws Exception {
		String current = "<?xml version=\"1.0\"?><ttyp:tableType xmlns:ttyp=\"http://www.sap.com/dictionary/tabletype\""
				+ " xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:description=\"Old\" adtcore:name=\"ZT\">"
				+ "<ttyp:rowType><ttyp:typeKind>dictionaryType</ttyp:typeKind><ttyp:typeName>ZSTR1</ttyp:typeName></ttyp:rowType>"
				+ "<ttyp:initialRowCount>00010</ttyp:initialRowCount><ttyp:accessType>sorted</ttyp:accessType>"
				+ "<ttyp:primaryKey><ttyp:definition>keyComponents</ttyp:definition><ttyp:kind>unique</ttyp:kind></ttyp:primaryKey>"
				+ "</ttyp:tableType>";
		String xml = AdtDdic.updateTableTypeXml(current, "New", "zstr2", null);
		assertTrue(xml.contains("<ttyp:typeName>ZSTR2</ttyp:typeName>"), xml);
		assertFalse(xml.contains("ZSTR1"), xml);
		assertTrue(xml.contains("<ttyp:accessType>sorted</ttyp:accessType>"), xml);
		assertTrue(xml.contains("<ttyp:kind>unique</ttyp:kind>"), xml);
		assertTrue(xml.contains("<ttyp:initialRowCount>00010</ttyp:initialRowCount>"), xml);
		assertTrue(xml.contains("adtcore:description=\"New\""), xml);
		assertFalse(xml.startsWith("<?xml"), xml);
	}

	@Test
	void functionModuleHelpers() throws AdtException {
		String meta = "<fmodule:abapFunctionModule xmlns:fmodule=\"x\" fmodule:processingType=\"normal\" adtcore:name=\"Z_X\"><a/></fmodule:abapFunctionModule>";
		String rfc = AdtDdic.withProcessingType(meta, "rfc", null);
		assertEquals("rfc", AdtDdic.processingType(rfc));
		assertEquals(1, rfc.split("processingType").length - 1, rfc);
		assertEquals("FUNCTION z_x\n  IMPORTING iv TYPE i.\nENDFUNCTION.",
				AdtDdic.stripParameterComments("FUNCTION z_x\n  IMPORTING iv TYPE i.\n*\"------\n*\"  IMPORTING\nENDFUNCTION."));
	}

	@Test
	void toolCreatesMessageClassWithMessages() {
		List<AdtRequest> puts = new ArrayList<>();
		FakeAdt adt = systems()
				.route("GET /sap/bc/adt/cts/transportrequests/DEVK900001", r -> FakeAdt.ok(TRANSPORT))
				.route("POST /sap/bc/adt/messageclass/zsd?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR>DEVK900001</CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/messageclass/zsd?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/messageclass", r -> new AdtResponse(201, "application/xml", ""))
				.route("PUT /sap/bc/adt/messageclass/zsd", r -> {
					puts.add(r);
					return FakeAdt.ok("");
				});
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult r = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZSD\",\"type\":\"MSAG\","
				+ "\"description\":\"Deliveries\",\"package\":\"zpkg\",\"transport\":\"DEVK900001\","
				+ "\"messages\":[{\"number\":\"001\",\"text\":\"Delivery & is locked\"}]}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Created ZSD (MSAG) in package ZPKG on S4H_100. 1 messages written."),
				r.content());
		assertTrue(adt.log.contains("POST /sap/bc/adt/messageclass?corrNr=DEVK900001"), adt.log.toString());
		assertEquals(1, puts.size());
		assertEquals(AdtDdic.MESSAGECLASS_TYPE, puts.get(0).contentType());
		assertTrue(puts.get(0).body().contains("mc:msgno=\"001\""), puts.get(0).body());
		assertTrue(puts.get(0).path().contains("lockHandle=H&corrNr=DEVK900001"), puts.get(0).path());

		ToolResult task = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZSD2\",\"type\":\"MSAG\","
				+ "\"description\":\"x\",\"package\":\"zpkg\",\"transport\":\"DEVK900002\"}"), CancelToken.NONE);
		assertTrue(task.isError() && task.content().contains("not a transport request"), task.content());
	}

	@Test
	void createSaysWhenTheObjectStaysEmpty() {
		FakeAdt adt = systems()
				.route("GET /sap/bc/adt/cts/transportrequests/DEVK900001", r -> FakeAdt.ok(TRANSPORT))
				.route("POST /sap/bc/adt/ddic/srvd/sources/zapi_x?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR>DEVK900001</CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/ddic/srvd/sources/zapi_x?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/ddic/srvd/sources", r -> new AdtResponse(201, "application/xml", ""))
				.route("PUT /sap/bc/adt/ddic/srvd/sources/zapi_x/source/main",
						r -> new AdtResponse(400, "text/plain", "Accept header missing"));
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"ZAPI_X\",\"type\":\"SRVD\",\"description\":\"x\",\"package\":\"zpkg\","
						+ "\"transport\":\"DEVK900001\",\"source\":\"define service ZAPI_X { expose ZR_X; }\"}"),
				CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("exists empty and inactive"), r.content());
		assertTrue(r.content().contains("adt_write_source"), r.content());
		assertEquals(0, adt.openSessions);
	}

	@Test
	void toolChangesMessagesKeepingTheOthers() {
		List<String> bodies = new ArrayList<>();
		FakeAdt adt = systems()
				.route("GET /sap/bc/adt/messageclass/zsd", r -> FakeAdt.ok(AdtDdic.messageClassXml("ZSD", "Deliveries",
						"ZPKG", "DE", List.of(new AdtDdic.Message("001", "Old"), new AdtDdic.Message("002", "Keep")))))
				.route("POST /sap/bc/adt/messageclass/zsd?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/messageclass/zsd", r -> {
					bodies.add(r.body());
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/messageclass/zsd?_action=UNLOCK", r -> FakeAdt.ok(""));
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_write_metadata", Json.parseObject(
				"{\"name\":\"ZSD\",\"type\":\"MSAG\",\"messages\":[{\"number\":\"1\",\"text\":\"New\"},"
						+ "{\"number\":\"3\",\"text\":\"Third\"}]}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the message class (3 messages) ZSD in S4H_100.", r.content());
		String body = bodies.get(0);
		assertTrue(body.contains("mc:msgno=\"001\" mc:msgtext=\"New\""), body);
		assertTrue(body.contains("mc:msgno=\"002\" mc:msgtext=\"Keep\""), body);
		assertTrue(body.contains("mc:msgno=\"003\" mc:msgtext=\"Third\""), body);
		assertTrue(body.contains("adtcore:language=\"DE\""), body);
	}

	@Test
	void toolCreatesFunctionModuleInItsGroup() {
		List<AdtRequest> requests = new ArrayList<>();
		FakeAdt adt = systems()
				.route("POST /sap/bc/adt/functions/groups/zsd/fmodules/z_sd_x?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST /sap/bc/adt/functions/groups/zsd/fmodules/z_sd_x?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/functions/groups/zsd/fmodules", r -> {
					requests.add(r);
					return new AdtResponse(201, "application/xml", "");
				})
				.route("PUT /sap/bc/adt/functions/groups/zsd/fmodules/z_sd_x/source/main", r -> {
					requests.add(r);
					return FakeAdt.ok("");
				})
				.route("GET /sap/bc/adt/functions/groups/zsd", r -> FakeAdt.ok(
						"<group:abapFunctionGroup xmlns:group=\"g\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
								+ "<adtcore:packageRef adtcore:name=\"SABP\"/></group:abapFunctionGroup>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "Z*");
		String json = "{\"name\":\"Z_SD_X\",\"type\":\"FUNC\",\"group\":\"ZSD\",\"description\":\"Delete\","
				+ "\"source\":\"FUNCTION z_sd_x.\\n*\\\"---\\nENDFUNCTION.\"}";
		String refused = p.refuse("adt_create_object", Json.parseObject(json), CancelToken.NONE).orElseThrow();
		assertTrue(refused.startsWith("Z_SD_X is in package SABP"), refused);
		ToolResult r = p.call("adt_create_object", Json.parseObject(json), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Created Z_SD_X (FUNC) in function group ZSD on S4H_100. Not activated yet."),
				r.content());
		assertEquals(AdtDdic.FUNCTION_MODULE_TYPE, requests.get(0).contentType());
		assertEquals("FUNCTION z_sd_x.\nENDFUNCTION.", requests.get(1).body());
	}

	private static FakeAdt systems() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "DE"));
		return adt;
	}

	private static final String TRANSPORT = "<tm:root xmlns:tm=\"http://www.sap.com/cts/adt/tm\">"
			+ "<tm:request tm:number=\"DEVK900001\" tm:desc=\"Deliveries\" tm:owner=\"DEVELOPER\" tm:status=\"D\">"
			+ "<tm:task tm:number=\"DEVK900002\" tm:owner=\"DEVELOPER\" tm:status=\"D\"/></tm:request></tm:root>";
}

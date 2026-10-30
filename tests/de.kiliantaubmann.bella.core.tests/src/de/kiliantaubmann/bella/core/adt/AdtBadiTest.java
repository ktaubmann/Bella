package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtBadiTest {

	/** Shaped like SAP_BASIS 8.16 GET …/enhsxsb/{spot} (synthetic, neutral names, as in ARC-1's fixture). */
	static final String SPOT = "<?xml version=\"1.0\" encoding=\"utf-8\"?><enhs:objectData adtcore:name=\"ES_MY_SPOT\" "
			+ "adtcore:type=\"ENHS/XSB\" xmlns:enhs=\"http://www.sap.com/adt/enhancements/enhs\" "
			+ "xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:packageRef adtcore:name=\"ZSPOT_PKG\"/>"
			+ "<enhs:contentSpecific><enhs:badiTechnology><enhs:badiDefinitions>"
			+ "<enhs:badiDefinition enhs:name=\"BADI_MY_FILTERED\" enhs:shorttext=\"Filtered\" enhs:singleUse=\"false\">"
			+ "<enhs:interface adtcore:uri=\"/sap/bc/adt/oo/interfaces/if_my_filtered\" adtcore:type=\"INTF/OI\" "
			+ "adtcore:name=\"IF_MY_FILTERED\"/><enhs:filters><enhs:filter enhs:filterName=\"COUNTRY\" "
			+ "enhs:filterType=\"C\" enhs:shorttext=\"Country\"><enhs:filterCheck xsi:type=\"enhcore:DictionaryCheck\" "
			+ "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
			+ "xmlns:enhcore=\"http://www.sap.com/abapsource/enhancementscore\"><enhcore:checkObject "
			+ "adtcore:uri=\"/sap/bc/adt/ddic/domains/land1\" adtcore:type=\"DOMA/DD\" adtcore:name=\"LAND1\"/>"
			+ "</enhs:filterCheck></enhs:filter><enhs:filter enhs:filterName=\"GENERIC_FILTER\" enhs:filterType=\"S\"/>"
			+ "</enhs:filters></enhs:badiDefinition>"
			+ "<enhs:badiDefinition enhs:name=\"BADI_MY_PLAIN\" enhs:shorttext=\"Plain\" enhs:singleUse=\"true\">"
			+ "<enhs:interface adtcore:name=\"IF_MY_PLAIN\"/></enhs:badiDefinition>"
			+ "</enhs:badiDefinitions></enhs:badiTechnology></enhs:contentSpecific></enhs:objectData>";

	/** A stored implementation with a filter and attributes Bella does not model. */
	static final String STORED = "<?xml version=\"1.0\" encoding=\"utf-8\"?><enho:objectData adtcore:masterLanguage=\"EN\" "
			+ "adtcore:abapLanguageVersion=\"cloudDevelopment\" adtcore:name=\"ZMY_ENH\" adtcore:type=\"ENHO/XHB\" "
			+ "adtcore:description=\"Mine\" xmlns:enho=\"http://www.sap.com/adt/enhancements/enho\" "
			+ "xmlns:adtcore=\"http://www.sap.com/adt/core\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
			+ "<adtcore:packageRef adtcore:name=\"ZPKG\"/><enho:contentCommon enho:toolType=\"BADI_IMPL\"><enho:usages>"
			+ "<enhcore:referencedObject xmlns:enhcore=\"http://www.sap.com/abapsource/enhancementscore\">"
			+ "<enhcore:objectReference adtcore:type=\"ENHS/XSB\" adtcore:name=\"ES_MY_SPOT\"/></enhcore:referencedObject>"
			+ "</enho:usages></enho:contentCommon><enho:contentSpecific><enho:badiTechnology><enho:badiImplementations>"
			+ "<enho:badiImplementation enho:name=\"ZMY_IMPL_A\" enho:shortText=\"A\" enho:example=\"true\" "
			+ "enho:default=\"false\" enho:active=\"true\" enho:customizingLock=\"X\"><enho:enhancementSpot "
			+ "adtcore:name=\"ES_MY_SPOT\"/><enho:badiDefinition adtcore:name=\"BADI_MY_FILTERED\"/>"
			+ "<enho:implementingClass adtcore:name=\"ZCL_MY_A\"/><enho:filterTree>"
			+ "<enho:filterToken xsi:type=\"enho:Or\"><enho:filterToken xsi:type=\"enho:Filter\" enho:filterName=\"COUNTRY\" "
			+ "enho:comparator=\"=\" enho:value=\"DE\"/><enho:filterToken xsi:type=\"enho:Filter\" "
			+ "enho:filterName=\"COUNTRY\" enho:comparator=\"=\" enho:value=\"AT\"/></enho:filterToken>"
			+ "<enho:filterProperty enho:filterName=\"COUNTRY\" enho:filterType=\"C\"/></enho:filterTree>"
			+ "</enho:badiImplementation>"
			+ "<enho:badiImplementation enho:name=\"ZMY_IMPL_B\" enho:shortText=\"B\" enho:example=\"false\" "
			+ "enho:default=\"false\" enho:active=\"false\"><enho:badiDefinition adtcore:name=\"BADI_MY_PLAIN\"/>"
			+ "<enho:implementingClass adtcore:name=\"ZCL_MY_B\"/></enho:badiImplementation>"
			+ "</enho:badiImplementations></enho:badiTechnology></enho:contentSpecific></enho:objectData>";

	private static final String LOCKED = "<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>";

	private static FakeAdt systems() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt;
	}

	/** The system with the spot, the lock of ZMY_ENH and the bodies of every POST and PUT. */
	private static FakeAdt enhancement(List<AdtRequest> writes) {
		return systems()
				.route("GET /sap/bc/adt/enhancements/enhsxsb/es_my_spot", r -> FakeAdt.ok(SPOT))
				.route("GET /sap/bc/adt/enhancements/enhoxhb/zmy_enh", r -> FakeAdt.ok(STORED))
				.route("POST /sap/bc/adt/enhancements/enhoxhb/zmy_enh?_action=LOCK", r -> FakeAdt.ok(LOCKED))
				.route("POST /sap/bc/adt/enhancements/enhoxhb/zmy_enh?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/enhancements/enhoxhb", r -> {
					writes.add(r);
					return new AdtResponse(201, "application/xml", "");
				})
				.route("PUT /sap/bc/adt/enhancements/enhoxhb/zmy_enh", r -> {
					writes.add(r);
					return FakeAdt.ok("");
				});
	}

	@Test
	void createPostsTheContainerThenWritesTheImplementations() {
		List<AdtRequest> writes = new ArrayList<>();
		FakeAdt adt = enhancement(writes);
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"description\":\"Mine\",\"package\":\"$tmp\","
						+ "\"enhancement_spot\":\"es_my_spot\",\"badi_implementations\":[{\"name\":\"zmy_impl\","
						+ "\"badi\":\"badi_my_filtered\",\"implementing_class\":\"zcl_my\","
						+ "\"filter\":\"country = 'DE' or country = AT\",\"active\":false}]}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Created ZMY_ENH (ENHO) in package $TMP on S4H_100. 1 BAdI implementations written. "
				+ "Not activated yet.", r.content());
		assertEquals(2, writes.size());
		AdtRequest post = writes.get(0);
		assertEquals(AdtBadi.ENHO_TYPE, post.contentType());
		// the container: spot, no implementations (they fail in the POST in transportable packages)
		assertTrue(post.body().contains("<enhcore:objectReference adtcore:uri=\"/sap/bc/adt/enhancements/enhsxsb/"
				+ "es_my_spot\" adtcore:type=\"ENHS/XSB\" adtcore:name=\"ES_MY_SPOT\"/>"), post.body());
		assertFalse(post.body().contains("<enho:badiImplementation "), post.body());
		String put = writes.get(1).body();
		assertTrue(writes.get(1).path().contains("lockHandle=H"), writes.get(1).path());
		assertTrue(put.contains("enho:name=\"ZMY_IMPL\" enho:shortText=\"\" enho:example=\"false\" "
				+ "enho:default=\"false\" enho:active=\"false\">"), put);
		assertTrue(put.contains("adtcore:type=\"ENHS/XB\" adtcore:name=\"BADI_MY_FILTERED\""), put);
		assertTrue(put.contains("adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_my\" adtcore:type=\"CLAS/OC\""), put);
		assertTrue(put.contains("<enho:filterToken xsi:type=\"enho:Or\"><enho:filterToken xsi:type=\"enho:Filter\" "
				+ "enho:filterName=\"COUNTRY\" enho:comparator=\"=\" enho:value=\"DE\""), put);
		// the declaration's DDIC check is copied from the spot
		assertTrue(put.contains("<enho:filterProperty enho:filterName=\"COUNTRY\" enho:filterType=\"C\">"
				+ "<enho:filterCheck xsi:type=\"enhcore:DictionaryCheck\""), put);
		assertTrue(put.contains("</enho:filterCheck></enho:filterProperty>"), put);
		assertEquals(0, adt.openSessions);
	}

	@Test
	void createChecksAgainstTheSpotBeforeAnything() {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(enhancement(writes), () -> "dev");
		String head = "{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"description\":\"x\",\"package\":\"$TMP\","
				+ "\"enhancement_spot\":\"es_my_spot\",\"badi_implementations\":[{\"name\":\"zmy_impl\",";
		ToolResult unknownBadi = p.call("adt_create_object", Json.parseObject(head
				+ "\"badi\":\"badi_other\",\"implementing_class\":\"zcl_my\"}]}"), CancelToken.NONE);
		assertTrue(unknownBadi.content().contains("BAdI BADI_OTHER is not defined in enhancement spot ES_MY_SPOT "
				+ "(defined: BADI_MY_FILTERED, BADI_MY_PLAIN)"), unknownBadi.content());
		ToolResult undeclared = p.call("adt_create_object", Json.parseObject(head
				+ "\"badi\":\"badi_my_filtered\",\"implementing_class\":\"zcl_my\",\"filter\":\"LAND = 'DE'\"}]}"),
				CancelToken.NONE);
		assertTrue(undeclared.content().contains("declares no filter LAND (declared: COUNTRY, GENERIC_FILTER)"),
				undeclared.content());
		ToolResult noClass = p.call("adt_create_object", Json.parseObject(head + "\"badi\":\"badi_my_plain\"}]}"),
				CancelToken.NONE);
		assertTrue(noClass.content().contains("needs 'badi' and 'implementing_class'"), noClass.content());
		ToolResult noSpot = p.call("adt_create_object", Json.parseObject(
				"{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"description\":\"x\",\"package\":\"$TMP\"}"), CancelToken.NONE);
		assertTrue(noSpot.content().contains("Give 'enhancement_spot'"), noSpot.content());
		assertTrue(writes.isEmpty(), "nothing created");
	}

	@Test
	void customerImplementationOfAnInternalSpotIsRefused() {
		List<AdtRequest> writes = new ArrayList<>();
		FakeAdt adt = enhancement(writes).route("GET /sap/bc/adt/enhancements/enhsxsb/es_internal",
				r -> FakeAdt.ok(SPOT.replace("ES_MY_SPOT", "ES_INTERNAL").replace("<enhs:contentSpecific>",
						"<enhs:contentCommon enhs:internal=\"true\"/><enhs:contentSpecific>")));
		ToolResult r = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"description\":\"x\",\"package\":\"$TMP\","
						+ "\"enhancement_spot\":\"es_internal\"}"),
				CancelToken.NONE);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("SAP-internal"), r.content());
		assertTrue(writes.isEmpty(), adt.log.toString());
	}

	@Test
	void unknownSpotIsNamed() {
		ToolResult r = new AdtToolProvider(enhancement(new ArrayList<>()), () -> "dev").call("adt_create_object",
				Json.parseObject("{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"description\":\"x\",\"package\":\"$TMP\","
						+ "\"enhancement_spot\":\"es_gone\"}"),
				CancelToken.NONE);
		assertTrue(r.content().contains("There is no enhancement spot ES_GONE"), r.content());
	}

	@Test
	void changeKeepsWhatItDoesNotTouch() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(enhancement(writes), () -> "dev").call("adt_write_metadata",
				Json.parseObject("{\"name\":\"zmy_enh\",\"type\":\"ENHO\",\"badi_implementations\":[{\"name\":"
						+ "\"zmy_impl_a\",\"short_text\":\"New & better\"},{\"name\":\"zmy_impl_c\",\"badi\":"
						+ "\"badi_my_plain\",\"implementing_class\":\"zcl_my_c\"}]}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Saved the BAdI implementations of ZMY_ENH (ZMY_IMPL_A, ZMY_IMPL_B, ZMY_IMPL_C) in S4H_100. "
				+ "Not activated yet.", r.content());
		String put = writes.get(0).body();
		assertTrue(put.contains("enho:name=\"ZMY_IMPL_A\" enho:shortText=\"New &amp; better\" enho:example=\"true\" "
				+ "enho:default=\"false\" enho:active=\"true\" enho:customizingLock=\"X\">"), put);
		// the stored tree is re-sent as SAP serialised it
		assertTrue(put.contains("<enho:filterTree><enho:filterToken xsi:type=\"enho:Or\">"), put);
		assertTrue(put.contains("enho:name=\"ZMY_IMPL_B\" enho:shortText=\"B\" enho:example=\"false\" "
				+ "enho:default=\"false\" enho:active=\"false\""), put);
		assertTrue(put.contains("enho:name=\"ZMY_IMPL_C\""), put);
		assertTrue(put.contains("adtcore:abapLanguageVersion=\"cloudDevelopment\""), put);
		assertTrue(put.contains("adtcore:description=\"Mine\""), put);
		assertFalse(put.contains("adtcore:responsible"), put);
	}

	@Test
	void changeRemovesAndReplacesFilters() throws Exception {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(enhancement(writes), () -> "dev");
		ToolResult r = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\","
				+ "\"badi_implementations\":[{\"name\":\"ZMY_IMPL_A\",\"filter\":\"COUNTRY = 'BE' AND GENERIC_FILTER CP "
				+ "'X*'\"}],\"remove_implementations\":[\"zmy_impl_b\"]}"), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		AdtBadi.Stored after = AdtBadi.parse(writes.get(0).body());
		assertEquals(List.of("ZMY_IMPL_A"), after.entries().stream().map(AdtBadi.Entry::name).toList());
		assertEquals("COUNTRY = 'BE' AND GENERIC_FILTER CP 'X*'", after.filters().get("ZMY_IMPL_A"));

		p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\","
				+ "\"badi_implementations\":[{\"name\":\"ZMY_IMPL_A\",\"filter\":\"\"}]}"), CancelToken.NONE);
		assertFalse(writes.get(1).body().contains("filterTree"), writes.get(1).body());

		ToolResult otherBadi = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\","
				+ "\"badi_implementations\":[{\"name\":\"ZMY_IMPL_A\",\"badi\":\"BADI_MY_PLAIN\"}]}"), CancelToken.NONE);
		assertTrue(otherBadi.content().contains("belongs to BAdI BADI_MY_FILTERED"), otherBadi.content());
		ToolResult spot = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\","
				+ "\"enhancement_spot\":\"ES_OTHER\"}"), CancelToken.NONE);
		assertTrue(spot.content().contains("cannot change"), spot.content());
		ToolResult missing = p.call("adt_write_metadata", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\","
				+ "\"remove_implementations\":[\"ZMY_NONE\"]}"), CancelToken.NONE);
		assertTrue(missing.content().contains("has no BAdI implementation ZMY_NONE"), missing.content());
		assertEquals(2, writes.size());
	}

	@Test
	void readShowsImplementationsAndSpots() {
		FakeAdt adt = enhancement(new ArrayList<>())
				.route("GET /sap/bc/adt/repository/informationsystem/search", r -> FakeAdt.ok(r.path().contains("ENHS")
						? "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference "
								+ "adtcore:uri=\"/sap/bc/adt/enhancements/enhsxsb/es_my_spot\" adtcore:type=\"ENHS/XSB\" "
								+ "adtcore:name=\"ES_MY_SPOT\"/></adtcore:objectReferences>"
						: "<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:objectReference "
								+ "adtcore:uri=\"/sap/bc/adt/enhancements/enhoxhb/zmy_enh\" adtcore:type=\"ENHO/XHB\" "
								+ "adtcore:name=\"ZMY_ENH\"/></adtcore:objectReferences>"));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult impl = p.call("adt_read_source", Json.parseObject("{\"name\":\"ZMY_ENH\",\"type\":\"ENHO\"}"),
				CancelToken.NONE);
		assertFalse(impl.isError(), impl.content());
		assertTrue(impl.content().contains("\"enhancement_spot\": \"ES_MY_SPOT\""), impl.content());
		assertTrue(impl.content().contains("\"filter\": \"COUNTRY = 'DE' OR COUNTRY = 'AT'\""), impl.content());
		assertTrue(impl.content().contains("\"implementing_class\": \"ZCL_MY_B\""), impl.content());
		ToolResult spot = p.call("adt_read_source", Json.parseObject("{\"name\":\"ES_MY_SPOT\",\"type\":\"ENHS\"}"),
				CancelToken.NONE);
		assertTrue(spot.content().contains("- BADI_MY_FILTERED (Filtered): interface IF_MY_FILTERED, multiple use, "
				+ "filters COUNTRY (type C, DOMA/DD LAND1), GENERIC_FILTER (type S)"), spot.content());
		assertTrue(spot.content().contains("- BADI_MY_PLAIN (Plain): interface IF_MY_PLAIN, single use"),
				spot.content());
	}

	@Test
	void filterConditions() throws Exception {
		assertEquals("(COUNTRY = 'DE' OR COUNTRY = 'AT') AND MODEL CP 'X*'",
				AdtBadi.render(AdtBadi.parseFilter("(country = 'DE' OR country = AT) and model cp 'X*'")));
		assertEquals("A = 'it''s' OR (B <> '1' AND C >= '2')",
				AdtBadi.render(AdtBadi.parseFilter("A = 'it''s' OR (B <> 1 AND C >= 2)")));
		assertThrows(AdtException.class, () -> AdtBadi.parseFilter("A = <>"));
		assertThrows(AdtException.class, () -> AdtBadi.parseFilter("A = 'x"));
		assertThrows(AdtException.class, () -> AdtBadi.parseFilter("(A = 1"));
		assertThrows(AdtException.class, () -> AdtBadi.parseFilter("A = 1 B = 2"));
		assertThrows(AdtException.class, () -> AdtBadi.parseFilter("(".repeat(40) + "A = 1" + ")".repeat(40)));
		AdtBadi.BadiDef badi = AdtBadi.parseSpot(SPOT).badis().get("BADI_MY_FILTERED");
		// SAP answers 400 to an OR of different filters inside an AND
		AdtException mixed = assertThrows(AdtException.class, () -> AdtBadi.filterTree(
				AdtBadi.parseFilter("(COUNTRY = 'DE' OR GENERIC_FILTER = 'X') AND COUNTRY <> 'US'"), "Z", badi));
		assertTrue(mixed.getMessage().contains("OR of AND groups"), mixed.getMessage());
		AdtBadi.filterTree(AdtBadi.parseFilter("(COUNTRY = 'DE' OR COUNTRY = 'AT') AND GENERIC_FILTER = 'X'"), "Z", badi);
	}

	@Test
	void writtenDocumentReadsBack() throws Exception {
		AdtBadi.Stored stored = AdtBadi.parse(STORED);
		assertEquals("ES_MY_SPOT", stored.spot());
		assertEquals("BADI_IMPL", stored.technology());
		String xml = AdtBadi.xml("ZMY_ENH", "Mine", "ZPKG", "EN", null, stored.languageVersion(), stored.spot(),
				stored.entries());
		AdtBadi.Stored again = AdtBadi.parse(xml);
		assertEquals(stored.entries(), again.entries());
		assertEquals(stored.filters(), again.filters());
	}
}

package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** Diff of versions, CDS impact, table structure, batch create and the further readable types. */
class AdtRelationsTest {

	private static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt;
	}

	private static ToolResult call(FakeAdt adt, String tool, String json) {
		return new AdtToolProvider(adt, () -> "dev").call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	private static AdtResponse text(String body) {
		return new AdtResponse(200, "text/plain", body);
	}

	private static String usages(String... refs) {
		return "<usageReferences:usageReferenceResult xmlns:usageReferences=\"http://www.sap.com/adt/ris/usageReferences\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\"><usageReferences:referencedObjects>"
				+ String.join("", refs) + "</usageReferences:referencedObjects></usageReferences:usageReferenceResult>";
	}

	private static String used(String uri, String type, String name, String pkg, String usage) {
		return "<usageReferences:referencedObject uri=\"" + uri + "\" isResult=\"true\" canHaveChildren=\"false\""
				+ (usage == null ? "" : " usageInformation=\"" + usage + "\"") + "><usageReferences:adtObject adtcore:name=\""
				+ name + "\" adtcore:type=\"" + type + "\"><adtcore:packageRef adtcore:name=\"" + pkg
				+ "\"/></usageReferences:adtObject></usageReferences:referencedObject>";
	}

	// ---- diff ------------------------------------------------------------------

	@Test
	void diffShowsTheChangesNotActivatedYet() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main?version=active",
						r -> text("REPORT zrep.\nWRITE 'a'.\n"))
				.route("GET /sap/bc/adt/programs/programs/zrep/source/main?version=inactive",
						r -> text("REPORT zrep.\nWRITE 'b'.\n"));
		ToolResult r = call(adt, "adt_object_info", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"action\":\"diff\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ZREP, active → inactive: +1 −1 lines"), r.content());
		assertTrue(r.content().contains("-WRITE 'a'.\n+WRITE 'b'."), r.content());
	}

	@Test
	void diffWithoutChangesSaysSo() {
		FakeAdt adt = system().route("GET /sap/bc/adt/programs/programs/zrep/source/main",
				r -> text("REPORT zrep.\n"));
		ToolResult r = call(adt, "adt_object_info", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"action\":\"diff\"}");
		assertTrue(r.content().contains("no difference. There are no saved changes"), r.content());
	}

	@Test
	void diffBetweenVersionNumbers() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/oo/classes/zcl_calc/source/main/versions",
						r -> new AdtResponse(200, "application/atom+xml", AdtRevisionsTest.FEED))
				.route("GET /sap/bc/adt/oo/classes/zcl_calc/includes/main/versions/20260623112341/00001/content",
						r -> text("CLASS zcl_calc.\nold\n"))
				.route("GET /sap/bc/adt/oo/classes/zcl_calc/includes/main/versions/20260623112341/00002/content",
						r -> text("CLASS zcl_calc.\nnew\n"));
		ToolResult r = call(adt, "adt_object_info",
				"{\"name\":\"ZCL_CALC\",\"type\":\"CLAS\",\"action\":\"diff\",\"from\":\"00001\",\"to\":\"00002\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("ZCL_CALC, version 00001 → version 00002: +1 −1 lines"), r.content());
		ToolResult unknown = call(adt, "adt_object_info",
				"{\"name\":\"ZCL_CALC\",\"type\":\"CLAS\",\"action\":\"diff\",\"from\":\"00009\"}");
		assertTrue(unknown.isError() && unknown.content().contains("Unknown version '00009'"), unknown.content());
	}

	// ---- CDS impact --------------------------------------------------------------

	@Test
	void upstreamOfACdsView() {
		AdtRelations.Upstream up = AdtRelations.upstream("""
				@EndUserText.label: 'Travel' // from ZCOMMENT
				define root view entity ZI_TRAVEL as select from ztravel as t
				  inner join /dmo/agency as a on a.agency_id = t.agency_id
				  composition [0..*] of ZI_BOOKING as _Booking
				  association [0..1] to ZI_CUSTOMER as _Customer on $projection.customer_id = _Customer.id
				  association of many to one ZI_CURRENCY as _Currency on _Currency.c = t.c
				{ key t.id, _Booking, _Customer }
				/* join ZNOT_ME */""");
		assertEquals(Set.of("ZTRAVEL", "/DMO/AGENCY"), up.sources());
		assertEquals(Set.of("ZI_BOOKING"), up.compositions());
		assertEquals(Set.of("ZI_CUSTOMER", "ZI_CURRENCY"), up.associations());
		AdtRelations.Upstream projection = AdtRelations.upstream("""
				define root view entity ZC_TRAVEL provider contract transactional_query as projection on ZI_TRAVEL
				{ key id, _Booking : redirected to composition child ZC_BOOKING }""");
		assertEquals(Set.of("ZI_TRAVEL"), projection.sources());
		assertEquals(Set.of("ZC_BOOKING"), projection.associations());
	}

	@Test
	void impactGroupsTheUsers() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/ddic/ddl/sources/zi_travel/source/main",
						r -> text("define root view entity ZI_TRAVEL as select from ztravel { key id }"))
				.route("POST /sap/bc/adt/repository/informationsystem/usageReferences", r -> FakeAdt.ok(usages(
						used("/sap/bc/adt/ddic/ddl/sources/zc_travel", "DDLS/DF", "ZC_TRAVEL", "ZTRV", "gradeDirect"),
						used("/sap/bc/adt/bo/behaviordefinitions/zi_travel", "BDEF/BDO", "ZI_TRAVEL", "ZTRV", "gradeDirect"),
						used("/sap/bc/adt/bo/behaviordefinitions/zr_x", "BDEF/BDO", "ZR_X", "ZTRV", "gradeDirect"),
						used("/sap/bc/adt/businessservices/bindings/zui_travel", "SRVB/SVB", "ZUI_TRAVEL", "ZTRV",
								"gradeIndirect"),
						used("/sap/bc/adt/oo/classes/zcl_report", "CLAS/OC", "ZCL_REPORT", "ZTRV", "gradeDirect"))));
		ToolResult r = call(adt, "adt_navigate", "{\"name\":\"ZI_TRAVEL\",\"type\":\"DDLS\",\"action\":\"impact\"}");
		assertFalse(r.isError(), r.content());
		String c = r.content();
		assertTrue(c.contains("- Data sources (FROM, JOIN, projection): ZTRAVEL"), c);
		assertTrue(c.contains("Used by (where-used list): 4 objects, 4 direct"), c);
		assertTrue(c.contains("CDS views on top of it:\n- ZC_TRAVEL (DDLS/DF, ZTRV)"), c);
		assertTrue(c.contains("Behavior definitions:\n- ZI_TRAVEL (BDEF/BDO, ZTRV)\n- ZR_X"), c);
		assertTrue(c.contains("ABAP code"), c);
		assertTrue(c.contains("Not listed: 1 indirect uses"), c);
		assertFalse(c.contains("ZUI_TRAVEL"), c);

		ToolResult all = call(adt, "adt_navigate",
				"{\"name\":\"ZI_TRAVEL\",\"type\":\"DDLS\",\"action\":\"impact\",\"include_indirect\":true}");
		assertTrue(all.content().contains("Service bindings:\n- ZUI_TRAVEL (SRVB/SVB, ZTRV, indirect)"), all.content());

		ToolResult notCds = call(adt, "adt_navigate", "{\"name\":\"ZREP\",\"type\":\"PROG\",\"action\":\"impact\"}");
		assertTrue(notCds.isError(), notCds.content());
	}

	/** One entry as an on-premise system sends it: isResult false everywhere, canHaveChildren on views too. */
	private static String node(String uri, String parent, String type, String name, boolean children, String usage) {
		return "<usageReferences:referencedObject uri=\"" + uri + "\" parentUri=\"" + parent
				+ "\" isResult=\"false\" canHaveChildren=\"" + children + "\""
				+ (usage == null ? "" : " usageInformation=\"" + usage + "\"") + "><usageReferences:adtObject adtcore:name=\""
				+ name + "\"" + (type == null ? "" : " adtcore:type=\"" + type + "\"")
				+ "/></usageReferences:referencedObject>";
	}

	@Test
	void impactKeepsExpandableViewsAndBehaviors() {
		String pkg = "/sap/bc/adt/packages/ztrv";
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/ddic/ddl/sources/zr_travel/source/main",
						r -> text("define root view entity ZR_TRAVEL as select from ztravel { key id }"))
				.route("POST /sap/bc/adt/repository/informationsystem/usageReferences", r -> FakeAdt.ok(usages(
						node(pkg, "", "DEVC/K", "ZTRV", true, null),
						node("/sap/bc/adt/bo/behaviordefinitions/zr_travel", pkg, "BDEF/BDO", "ZR_TRAVEL", true,
								"gradeDirect,includeProductive"),
						node("/sap/bc/adt/ddic/ddl/sources/zc_travel", pkg, "DDLS/DF", "ZC_TRAVEL", true,
								"gradeDirect,includeProductive"),
						node("/sap/bc/adt/oo/classes/zbp_r_travel", pkg, "CLAS/OC", "ZBP_R_TRAVEL", false, null),
						node("/sap/bc/adt/oo/classes/zbp_r_travel/includes/implementations#start=1,0",
								"/sap/bc/adt/oo/classes/zbp_r_travel", null, "Local Class Implementation", true,
								"gradeComponent,includeProductive"))));
		ToolResult r = call(adt, "adt_navigate", "{\"name\":\"ZR_TRAVEL\",\"type\":\"DDLS\",\"action\":\"impact\"}");
		String c = r.content();
		assertTrue(c.contains("Used by (where-used list): 3 objects, 3 direct"), c);
		assertTrue(c.contains("CDS views on top of it:\n- ZC_TRAVEL (DDLS/DF)"), c);
		assertTrue(c.contains("Behavior definitions:\n- ZR_TRAVEL (BDEF/BDO)"), c);
		assertTrue(c.contains("- ZBP_R_TRAVEL (CLAS/OC)"), c);
		assertFalse(c.contains("Local Class Implementation"), c);
		assertFalse(c.contains("ZTRV"), c);
	}

	// ---- structure ---------------------------------------------------------------

	@Test
	void includesAreParsed() {
		List<AdtRelations.Include> inc = AdtRelations.includes("""
				@EndUserText.label : 'x'
				define table ztab {
				  key mandt : mandt not null;
				  include zs_admin;
				  addr : include zs_address;
				  // include zs_comment;
				  .INCLUDE ZS_CLASSIC
				}""");
		assertEquals(List.of(new AdtRelations.Include("ZS_ADMIN", ""), new AdtRelations.Include("ZS_ADDRESS", "addr"),
				new AdtRelations.Include("ZS_CLASSIC", "")), inc);
		assertTrue(AdtRelations.extendsType("extend type ZTAB with zz_ext { zz_a : abap.char(1); }", "ztab"));
		assertFalse(AdtRelations.extendsType("define structure zs_other { a : ztab; }", "ZTAB"));
	}

	@Test
	void structureListsIncludesAndAppends() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/ddic/tables/ztab/source/main",
						r -> text("define table ztab {\n key mandt : mandt not null;\n include zs_admin;\n}"))
				.route("GET /sap/bc/adt/ddic/structures/zs_admin/source/main",
						r -> text("define structure zs_admin {\n include zs_inner;\n}"))
				.route("GET /sap/bc/adt/ddic/structures/zs_inner/source/main",
						r -> text("define structure zs_inner {\n x : abap.char(1);\n}"))
				.route("GET /sap/bc/adt/ddic/structures/zz_append/source/main",
						r -> text("@AbapCatalog.enhancement.category : #NOT_EXTENSIBLE\nextend type ztab with zz_append {\n"
								+ " zz_a : abap.char(1);\n}"))
				.route("GET /sap/bc/adt/ddic/structures/zs_user/source/main",
						r -> text("define structure zs_user {\n t : ztab;\n}"))
				.route(AdtContextTest.SEARCH, r -> {
					String name = r.path().contains("ZS_INNER") ? "zs_inner" : "zs_admin";
					return FakeAdt.ok("<adtcore:objectReferences xmlns:adtcore=\"http://www.sap.com/adt/core\">"
							+ "<adtcore:objectReference adtcore:uri=\"/sap/bc/adt/ddic/structures/" + name
							+ "\" adtcore:type=\"TABL/DS\" adtcore:name=\"" + name.toUpperCase() + "\"/></adtcore:objectReferences>");
				})
				.route("POST /sap/bc/adt/repository/informationsystem/usageReferences", r -> FakeAdt.ok(usages(
						used("/sap/bc/adt/ddic/structures/zz_append", "TABL/DS", "ZZ_APPEND", "ZEXT", null),
						used("/sap/bc/adt/ddic/structures/zs_user", "TABL/DS", "ZS_USER", "ZEXT", null))));
		ToolResult r = call(adt, "adt_navigate", "{\"name\":\"ZTAB\",\"type\":\"TABL/DT\",\"action\":\"structure\"}");
		assertFalse(r.isError(), r.content());
		assertEquals("""
				ZTAB (TABL/DT)
				  include ZS_ADMIN
				    include ZS_INNER
				  append ZZ_APPEND (ZEXT)

				2 includes, 1 append structures.
				""", r.content());
	}

	// ---- batch create ----------------------------------------------------------

	@Test
	void batchCreatesInOrderAndActivatesTogether() {
		List<String> activations = new ArrayList<>();
		FakeAdt adt = system()
				.route("POST /sap/bc/adt/oo/interfaces", r -> new AdtResponse(201, "application/xml", ""))
				.route("POST /sap/bc/adt/oo/classes", r -> new AdtResponse(201, "application/xml", ""))
				.route("POST /sap/bc/adt/activation", r -> {
					activations.add(r.body());
					return FakeAdt.ok("");
				});
		String json = "{\"package\":\"$TMP\",\"objects\":[{\"name\":\"ZIF_A\",\"type\":\"INTF\",\"description\":\"a\"},"
				+ "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"description\":\"b\"}]}";
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev", () -> "$TMP, Z*");
		assertEquals(java.util.Optional.empty(), p.refuse("adt_create_object", Json.parseObject(json), CancelToken.NONE));
		ToolResult r = p.call("adt_create_object", Json.parseObject(json), CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith("Created ZIF_A (INTF) in package $TMP"), r.content());
		assertTrue(r.content().contains("Created ZCL_A (CLAS) in package $TMP"), r.content());
		assertTrue(r.content().contains("Activated ZIF_A, ZCL_A"), r.content());
		assertEquals(1, activations.size());
		assertTrue(activations.get(0).contains("zif_a") && activations.get(0).contains("zcl_a"), activations.get(0));
	}

	@Test
	void batchStopsAtTheFirstFailureAndIsCheckedBeforeAnything() {
		FakeAdt adt = system()
				.route("POST /sap/bc/adt/oo/interfaces", r -> new AdtResponse(201, "application/xml", ""))
				.route("POST /sap/bc/adt/oo/classes", r -> new AdtResponse(400, "text/plain", "Name taken"));
		String json = "{\"package\":\"$TMP\",\"objects\":[{\"name\":\"ZIF_A\",\"type\":\"INTF\",\"description\":\"a\"},"
				+ "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"description\":\"b\"},"
				+ "{\"name\":\"ZCL_B\",\"type\":\"CLAS\",\"description\":\"c\"}]}";
		ToolResult r = call(adt, "adt_create_object", json);
		assertTrue(r.isError(), r.content());
		assertTrue(r.content().contains("Stopped at ZCL_A"), r.content());
		assertTrue(r.content().contains("Not created: ZCL_B."), r.content());
		assertTrue(adt.log.stream().noneMatch(l -> l.contains("/activation")), adt.log.toString());

		AdtToolProvider p = new AdtToolProvider(system(), () -> "dev", () -> "Z*");
		String foreign = "{\"objects\":[{\"name\":\"ZIF_A\",\"type\":\"INTF\",\"description\":\"a\",\"package\":\"ZPKG\"},"
				+ "{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"description\":\"b\",\"package\":\"SAPPKG\"}]}";
		String refused = p.refuse("adt_create_object", Json.parseObject(foreign), CancelToken.NONE).orElseThrow();
		assertTrue(refused.startsWith("ZCL_A: ") && refused.endsWith("Nothing was created."), refused);
		assertTrue(call(system(), "adt_create_object", "{\"objects\":[],\"package\":\"$TMP\"}").isError());
		assertTrue(call(system(), "adt_create_object", "{\"package\":\"$TMP\"}").isError());
	}

	// ---- further readable types -------------------------------------------------

	@Test
	void classicViewsLockObjectsAndTransactionsHaveTheirUris() {
		assertEquals("/sap/bc/adt/vit/wb/object_type/viewdv/object_name/ZV_ORDERS",
				AdtObjectRef.uriFor("zv_orders", "VIEW"));
		assertEquals("/sap/bc/adt/vit/wb/object_type/trant/object_name/ZSD01", AdtObjectRef.uriFor("ZSD01", "TRAN"));
		assertEquals("/sap/bc/adt/ddic/lockobjects/sources/ezorder", AdtObjectRef.uriFor("EZORDER", "ENQU"));
		assertEquals("TRAN", AdtClient.searchType("transaction"));
		assertEquals("SKTD", AdtClient.searchType("KTD"));
		assertTrue(AdtClient.xmlOnly("TRAN/T"));

		FakeAdt adt = system().route("GET /sap/bc/adt/vit/wb/object_type/trant/object_name/ZSD01",
				r -> FakeAdt.ok("<adtcore:object xmlns:adtcore=\"http://www.sap.com/adt/core\" adtcore:name=\"ZSD01\" "
						+ "adtcore:description=\"Delivery monitor\"/>"));
		ToolResult r = call(adt, "adt_read_source", "{\"name\":\"ZSD01\",\"type\":\"TRAN\"}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().contains("Delivery monitor"), r.content());
	}

	@Test
	void knowledgeTransferDocumentsAreDecoded() {
		String md = "# Purpose\nComputes the **delivery** date.";
		String xml = "<sktd:docu xmlns:sktd=\"http://www.sap.com/wbobj/texts/sktd\"><sktd:element><sktd:id>ZCL_A</sktd:id>"
				+ "<sktd:text>" + Base64.getEncoder().encodeToString(md.getBytes(StandardCharsets.UTF_8))
				+ "</sktd:text></sktd:element></sktd:docu>";
		FakeAdt adt = system().route("GET /sap/bc/adt/documentation/ktd/documents/zcl_a", r -> FakeAdt.ok(xml));
		ToolResult r = call(adt, "adt_read_source", "{\"name\":\"ZCL_A\",\"type\":\"SKTD\"}");
		assertFalse(r.isError(), r.content());
		assertEquals(md, r.content());
		ToolResult none = call(system(), "adt_read_source", "{\"name\":\"ZCL_B\",\"type\":\"KTD\"}");
		assertTrue(none.isError() && none.content().contains("No knowledge transfer document"), none.content());
	}
}

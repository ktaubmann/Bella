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

class AdtTableIndexTest {

	private static final String INDEX = "/sap/bc/adt/ddic/db/indexes/zorders~001";
	private static final String TABLE = "/sap/bc/adt/ddic/tables/zorders";
	private static final String SOURCE = "{\"formatVersion\":\"1\",\"header\":{\"description\":\"Status\","
			+ "\"originalLanguage\":\"en\"},\"uniqueIndex\":false,\"indexFields\":[{\"fieldName\":\"CLIENT\"},"
			+ "{\"fieldName\":\"STATUS\"}]}";

	/** Index metadata shaped like ARC-1's 7.58 fixture. */
	static String meta(String version) {
		return "<blue:blueSource adtcore:name=\"ZORDERS~001\" adtcore:type=\"TABL/DTI\" adtcore:version=\"" + version
				+ "\" adtcore:description=\"Status\" xmlns:blue=\"http://www.sap.com/wbobj/blue\" "
				+ "xmlns:adtcore=\"http://www.sap.com/adt/core\"><adtcore:packageRef adtcore:name=\"ZPKG\"/></blue:blueSource>";
	}

	private static FakeAdt adt(String[] version, String activation, List<AdtRequest> writes) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("GET " + TABLE, r -> FakeAdt.ok("<x xmlns:adtcore=\"http://www.sap.com/adt/core\">"
				+ "<adtcore:packageRef adtcore:name=\"ZPKG\"/></x>"))
				.route("POST " + INDEX + "?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("POST " + INDEX + "?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("PUT " + INDEX + "/source/main", r -> {
					writes.add(r);
					return FakeAdt.ok("");
				})
				.route("GET " + INDEX + "/source/main?version=inactive",
						r -> new AdtResponse(200, "application/json", SOURCE))
				.route("GET " + INDEX + "?version=inactive", r -> FakeAdt.ok(meta(version[0])))
				.route("POST /sap/bc/adt/ddic/db/indexes", r -> {
					writes.add(r);
					return new AdtResponse(201, "application/xml", "");
				})
				.route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(activation));
	}

	@Test
	void createTakesThePackageOfTheTable() {
		List<AdtRequest> writes = new ArrayList<>();
		ToolResult r = new AdtToolProvider(adt(new String[] { "new" }, "", writes), () -> "dev").call(
				"adt_create_object", Json.parseObject("{\"name\":\"zorders~001\",\"type\":\"TABL/DTI\","
						+ "\"description\":\"Status\",\"source\":" + Json.GSON.toJson(SOURCE) + "}"),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals("Created ZORDERS~001 (TABL/DTI, secondary index of a table) in package ZPKG on S4H_100. Source "
				+ "written. Not activated yet.", r.content());
		String post = writes.get(0).body();
		assertTrue(post.contains("adtcore:type=\"TABL/DTI\" adtcore:name=\"ZORDERS~001\""), post);
		assertTrue(post.contains("<adtcore:packageRef adtcore:name=\"ZPKG\"/>\n  <adtcore:containerRef "
				+ "adtcore:name=\"ZORDERS\" adtcore:type=\"TABL/DT\" adtcore:uri=\"" + TABLE + "\"/>"), post);
		assertEquals("application/json", writes.get(1).contentType());
		assertEquals(SOURCE, writes.get(1).body());
	}

	@Test
	void anotherPackageOrABadNameIsRefused() {
		List<AdtRequest> writes = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt(new String[] { "new" }, "", writes), () -> "dev");
		String pkg = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZORDERS~001\",\"type\":\"INDEX\","
				+ "\"description\":\"x\",\"package\":\"$TMP\"}"), CancelToken.NONE).content();
		assertTrue(pkg.contains("belongs to the package of its table, ZPKG"), pkg);
		String name = p.call("adt_create_object", Json.parseObject("{\"name\":\"ZORDERS_001\",\"type\":\"TABL/DTI\","
				+ "\"description\":\"x\"}"), CancelToken.NONE).content();
		assertTrue(name.contains("An index is named TABLE~ID"), name);
		assertTrue(writes.isEmpty());
		assertThrows(AdtException.class, () -> AdtTableIndex.parse("ZORDERS~ABCD"));
		// the guard takes the table's package, also when 'package' is left out
		String refused = new AdtToolProvider(adt(new String[] { "new" }, "", writes), () -> "dev", () -> "$TMP")
				.refuse("adt_create_object", Json.parseObject("{\"name\":\"ZORDERS~001\",\"type\":\"TABL/DTI\","
						+ "\"description\":\"x\"}"), CancelToken.NONE)
				.orElseThrow();
		assertTrue(refused.contains("ZORDERS~001 is in package ZPKG"), refused);
	}

	@Test
	void readListAndActivation() {
		String[] version = { "inactive" };
		String warning = "<chkl:messages xmlns:chkl=\"http://www.sap.com/abapxml/checklist\"><msg type=\"W\" "
				+ "href=\"" + INDEX + "\"><shortText><txt>Index could not be created</txt></shortText></msg></chkl:messages>";
		String[] activation = { warning };
		FakeAdt adt = adt(version, "", new ArrayList<>())
				.route("POST /sap/bc/adt/repository/nodestructure", r -> FakeAdt.ok("<asx:abap "
						+ "xmlns:asx=\"http://www.sap.com/abapxml\"><asx:values><DATA><TREE_CONTENT>"
						+ "<SEU_ADT_REPOSITORY_OBJ_NODE><OBJECT_TYPE>TABL/DTI</OBJECT_TYPE><OBJECT_NAME>001</OBJECT_NAME>"
						+ "<TECH_NAME>ZORDERS   001</TECH_NAME><DESCRIPTION>Status</DESCRIPTION>"
						+ "</SEU_ADT_REPOSITORY_OBJ_NODE><SEU_ADT_REPOSITORY_OBJ_NODE><OBJECT_TYPE>TABL/DT</OBJECT_TYPE>"
						+ "<OBJECT_NAME>ZORDERS</OBJECT_NAME></SEU_ADT_REPOSITORY_OBJ_NODE></TREE_CONTENT></DATA>"
						+ "</asx:values></asx:abap>"))
				.route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(activation[0]));
		AdtToolProvider p = new AdtToolProvider(adt, () -> "dev");
		ToolResult read = p.call("adt_read_source", Json.parseObject("{\"name\":\"ZORDERS~001\",\"type\":\"TABL/DTI\"}"),
				CancelToken.NONE);
		assertTrue(read.content().startsWith("TABL/DTI ZORDERS~001 (secondary index of a table): Status, package ZPKG, "
				+ "inactive."), read.content());
		assertTrue(read.content().contains("\"fieldName\": \"STATUS\""), read.content());
		assertEquals("Secondary indexes of ZORDERS (read one with type TABL/DTI):\n- ZORDERS~001: Status",
				p.call("adt_read_source", Json.parseObject("{\"name\":\"zorders\",\"type\":\"TABL\",\"include\":"
						+ "\"indexes\"}"), CancelToken.NONE).content());

		String json = "{\"objects\":[{\"name\":\"ZORDERS~001\",\"type\":\"TABL/DTI\"}]}";
		version[0] = "active";
		ToolResult warned = p.call("adt_activate", Json.parseObject(json), CancelToken.NONE);
		assertTrue(warned.isError() && warned.content().contains("is not confirmed"), warned.content());
		activation[0] = "";
		version[0] = "inactive";
		ToolResult still = p.call("adt_activate", Json.parseObject(json), CancelToken.NONE);
		assertTrue(still.isError() && still.content().contains("still inactive"), still.content());
		version[0] = "active";
		assertFalse(p.call("adt_activate", Json.parseObject(json), CancelToken.NONE).isError());
	}
}

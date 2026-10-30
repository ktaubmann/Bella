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

class AdtSpecialObjectsTest {

	private static FakeAdt system() {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt;
	}

	private static ToolResult read(FakeAdt adt, String json) {
		return new AdtToolProvider(adt, () -> "dev").call("adt_read_source", Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void authorizationField() {
		// shaped like ARC-1's recorded fixture
		FakeAdt adt = system().route("GET /sap/bc/adt/aps/iam/auth/BUKRS", r -> {
			assertEquals(AdtSpecialObjects.AUTH_TYPE, r.headers().get("Accept"));
			return FakeAdt.ok("<auth:auth adtcore:name=\"BUKRS\" adtcore:type=\"AUTH\" adtcore:description=\"Company Code\" "
					+ "xmlns:auth=\"http://www.sap.com/iam/auth\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
					+ "<adtcore:packageRef adtcore:name=\"BF\"/><auth:content><auth:fieldName>BUKRS</auth:fieldName>"
					+ "<auth:rollName>BUKRS</auth:rollName><auth:checkTable>T001</auth:checkTable><auth:exitFB/>"
					+ "<auth:domname>BUKRS</auth:domname><auth:outputlen>000004</auth:outputlen><auth:convexit/>"
					+ "<auth:orglvlinfo>Field is not defined as Organizational level.</auth:orglvlinfo></auth:content>"
					+ "</auth:auth>");
		});
		ToolResult r = read(adt, "{\"name\":\"bukrs\",\"type\":\"AUTH\"}");
		assertEquals("Authorization field BUKRS (Company Code), package BF.\nData element: BUKRS\nDomain: BUKRS\n"
				+ "Check table: T001\nOutput length: 4\nOrganizational level: Field is not defined as Organizational level.",
				r.content());
	}

	@Test
	void featureToggle() {
		FakeAdt adt = system().route("GET /sap/bc/adt/sfw/featuretoggles/SFW_DEMO/states", r -> new AdtResponse(200,
				"application/json", "{\"STATES\":{\"NAME\":\"SFW_DEMO\",\"CLIENT_STATE\":\"off\",\"USER_STATE\":\"undefined\","
						+ "\"CLIENT_STATES\":[{\"CLIENT\":\"000\",\"DESCRIPTION\":\"SAP SE\",\"STATE\":\"off\"},"
						+ "{\"CLIENT\":\"100\",\"STATE\":\"on\"}],\"USER_STATES\":[{\"CLIENT\":\"100\",\"USER\":\"TESTER\","
						+ "\"STATE\":\"on\"}]}}"));
		ToolResult r = read(adt, "{\"name\":\"sfw_demo\",\"type\":\"FEATURE_TOGGLE\"}");
		assertEquals("Feature toggle SFW_DEMO: off in this client.\nPer client:\n- 000: off (SAP SE)\n- 100: on\n"
				+ "Per user:\n- TESTER in client 100: on", r.content());
	}

	private static String table(String[] columns, String[]... rows) {
		StringBuilder sb = new StringBuilder("<dataPreview:tableData xmlns:dataPreview=\"http://www.sap.com/adt/dataPreview\">"
				+ "<dataPreview:totalRows>" + rows.length + "</dataPreview:totalRows>");
		for (int c = 0; c < columns.length; c++) {
			sb.append("<dataPreview:columns><dataPreview:metadata dataPreview:name=\"").append(columns[c])
					.append("\"/><dataPreview:dataSet>");
			for (String[] row : rows) {
				sb.append("<dataPreview:data>").append(row[c]).append("</dataPreview:data>");
			}
			sb.append("</dataPreview:dataSet></dataPreview:columns>");
		}
		return sb.append("</dataPreview:tableData>").toString();
	}

	@Test
	void borObjectTypeAndMethod() {
		List<String> sql = new ArrayList<>();
		FakeAdt adt = system().route("POST /sap/bc/adt/datapreview/freestyle", r -> {
			sql.add(r.body());
			return FakeAdt.ok(r.body().contains("verb = 'RELEASE'")
					? table(new String[] { "PROGNAME", "FORMNAME" }, new String[] { "RBUS2012", "RELEASE" })
					: table(new String[] { "VERB", "PROGNAME", "FORMNAME", "DESCRIPT" },
							new String[] { "RELEASE", "RBUS2012", "RELEASE", "Release" },
							new String[] { "DISPLAY", "", "", "Display" }));
		}).route("GET /sap/bc/adt/programs/programs/rbus2012/source/main",
				r -> new AdtResponse(200, "text/plain", "FORM release."));
		ToolResult list = read(adt, "{\"name\":\"bus2012\",\"type\":\"SOBJ\"}");
		assertEquals("Methods of BOR object type BUS2012 (read one with 'method'):\n- RELEASE: Release (program RBUS2012, "
				+ "form RELEASE)\n- DISPLAY: Display", list.content());
		assertEquals("SELECT verb, progname, formname, descript FROM swotlv WHERE lobjtype = 'BUS2012'", sql.get(0));
		ToolResult method = read(adt, "{\"name\":\"BUS2012\",\"type\":\"BOR\",\"method\":\"release\"}");
		assertEquals("BOR method BUS2012.RELEASE: program RBUS2012, form RELEASE\n\nFORM release.", method.content());
		// no search: it would find maintenance objects (SOBJ/MO) instead
		assertFalse(adt.log.stream().anyMatch(l -> l.contains("informationsystem/search")), adt.log.toString());
		ToolResult injection = read(adt, "{\"name\":\"X' OR '1'='1\",\"type\":\"SOBJ\"}");
		assertTrue(injection.isError() && injection.content().contains("no BOR object type name"), injection.content());
		assertEquals(2, sql.size());
	}

	@Test
	void maintenanceObjectIsReadOnly() {
		FakeAdt adt = system()
				.route("GET /sap/bc/adt/bct/smbctyp/zbc_x/source/main", r -> new AdtResponse(200, "application/json",
						"{\"formatVersion\":\"1\"}"))
				.route("GET /sap/bc/adt/bct/smbctyp/zbc_x", r -> FakeAdt.ok("<blue:blueSource "
						+ "xmlns:blue=\"http://www.sap.com/wbobj/blue\" xmlns:adtcore=\"http://www.sap.com/adt/core\" "
						+ "adtcore:name=\"ZBC_X\" adtcore:type=\"SMBC/TYP\"/>"));
		ToolResult r = read(adt, "{\"name\":\"ZBC_X\",\"type\":\"SMBC\"}");
		assertTrue(r.content().startsWith("SMBC ZBC_X (business configuration maintenance object). Source (AFF JSON, read "
				+ "only in Bella):\n{"), r.content());
		ToolResult create = new AdtToolProvider(adt, () -> "dev").call("adt_create_object", Json.parseObject(
				"{\"name\":\"ZBC_Y\",\"type\":\"SMBC\",\"description\":\"x\",\"package\":\"$TMP\"}"), CancelToken.NONE);
		assertTrue(create.isError() && create.content().contains("Bella only reads SMBC"), create.content());
	}
}

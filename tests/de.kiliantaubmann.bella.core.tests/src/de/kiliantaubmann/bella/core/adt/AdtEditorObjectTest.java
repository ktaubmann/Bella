package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AdtEditorObjectTest {

	@Test
	void keepsNameAndTypeFromTheReference() {
		AdtEditorObject o = AdtEditorObject.of("X21", "/sap/bc/adt/programs/programs/zsd_r_e267_delete_obd",
				"zsd_r_e267_delete_obd", "PROG/P");
		assertEquals("ZSD_R_E267_DELETE_OBD", o.name());
		assertEquals("PROG/P", o.type());
	}

	@Test
	void classIncludeWithoutNameGetsTheClassFromTheUri() {
		AdtEditorObject o = AdtEditorObject.of("X21", "/sap/bc/adt/oo/classes/zcl_sd_e267_bus_logic/source/main", "",
				"CLAS/I");
		assertEquals("ZCL_SD_E267_BUS_LOGIC", o.name());
		assertEquals("CLAS/OC", o.type());
		assertEquals("/sap/bc/adt/oo/classes/zcl_sd_e267_bus_logic/source/main", o.uri());
	}

	@Test
	void testClassIncludeAndNamespace() {
		AdtEditorObject o = AdtEditorObject.of("X21", "/sap/bc/adt/oo/classes/%2fabc%2fcl_x/includes/testclasses",
				null, "CLAS/I");
		assertEquals("/ABC/CL_X", o.name());
		assertEquals("CLAS/OC", o.type());
	}

	@Test
	void nothingWithoutUri() {
		assertNull(AdtEditorObject.of("X21", null, "ZCL_X", "CLAS/OC"));
	}
}

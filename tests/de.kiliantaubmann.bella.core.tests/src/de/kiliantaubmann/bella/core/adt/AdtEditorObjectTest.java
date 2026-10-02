package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AdtEditorObjectTest {

	@Test
	void keepsNameAndTypeFromTheReference() {
		AdtEditorObject o = AdtEditorObject.of("DEV", "/sap/bc/adt/programs/programs/z_demo_report",
				"z_demo_report", "PROG/P");
		assertEquals("Z_DEMO_REPORT", o.name());
		assertEquals("PROG/P", o.type());
	}

	@Test
	void classIncludeWithoutNameGetsTheClassFromTheUri() {
		AdtEditorObject o = AdtEditorObject.of("DEV", "/sap/bc/adt/oo/classes/zcl_demo_logic/source/main", "",
				"CLAS/I");
		assertEquals("ZCL_DEMO_LOGIC", o.name());
		assertEquals("CLAS/OC", o.type());
		assertEquals("/sap/bc/adt/oo/classes/zcl_demo_logic/source/main", o.uri());
	}

	@Test
	void testClassIncludeAndNamespace() {
		AdtEditorObject o = AdtEditorObject.of("DEV", "/sap/bc/adt/oo/classes/%2fabc%2fcl_x/includes/testclasses",
				null, "CLAS/I");
		assertEquals("/ABC/CL_X", o.name());
		assertEquals("CLAS/OC", o.type());
	}

	@Test
	void nothingWithoutUri() {
		assertNull(AdtEditorObject.of("DEV", null, "ZCL_X", "CLAS/OC"));
	}
}

package de.kiliantaubmann.bella.core.abap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.Json;

class ObjectTargetTest {

	@Test
	void parsesAdtUris() {
		assertEquals(new ObjectTarget("ZCL_ORDER", "CLAS"),
				ObjectTarget.fromAdtUri("/sap/bc/adt/oo/classes/zcl_order/source/main#start=3,1").orElseThrow());
		assertEquals(new ObjectTarget("/ABC/CL_X", "CLAS"),
				ObjectTarget.fromAdtUri("/sap/bc/adt/oo/classes/%2fabc%2fcl_x").orElseThrow());
		assertEquals(new ObjectTarget("Z_FM", "FUNC"),
				ObjectTarget.fromAdtUri("/sap/bc/adt/functions/groups/zgrp/fmodules/z_fm/source/main").orElseThrow());
		assertTrue(ObjectTarget.fromAdtUri("/sap/bc/adt/discovery").isEmpty());
	}

	@Test
	void readsToolInputs() {
		assertEquals(new ObjectTarget("ZCL_A", "CLAS"),
				ObjectTarget.fromToolInput(Json.parseObject("{\"name\":\"zcl_a\",\"type\":\"CLAS/OC\"}")).orElseThrow());
		assertEquals(new ObjectTarget("ZREP", "PROG"),
				ObjectTarget.fromToolInput(Json.parseObject("{\"uri\":\"/sap/bc/adt/programs/programs/zrep\"}")).orElseThrow());
	}

	@Test
	void matchesWithOptionalType() {
		ObjectTarget t = new ObjectTarget("ZCL_A", null);
		assertTrue(t.matches("zcl_a", "CLAS/OC"));
		assertFalse(new ObjectTarget("ZCL_A", "PROG").matches("ZCL_A", "CLAS/OC"));
	}

	@Test
	void recognisesSourceWrites() {
		ToolSpec write = ToolSpec.of("mcp_arc1_SAPWrite", "", new JsonObject(), Capability.WRITE_SOURCE,
				ToolSpec.Kind.WRITE);
		assertTrue(ObjectTarget.isSourceWrite(write, Json.parseObject("{\"name\":\"ZX\",\"source\":\"x\"}")));
		assertFalse(ObjectTarget.isSourceWrite(write, Json.parseObject("{\"action\":\"delete\",\"name\":\"ZX\",\"source\":\"\"}")));
		assertFalse(ObjectTarget.isSourceWrite(write, Json.parseObject("{\"name\":\"ZX\"}")));
	}

	@Test
	void activationTargetsFromObjectsArrayOrSingleObject() {
		ToolSpec adt = ToolSpec.of("adt_activate", "", new JsonObject(), Capability.ACTIVATE, ToolSpec.Kind.WRITE);
		assertEquals(List.of(new ObjectTarget("ZCL_A", "CLAS"), new ObjectTarget("ZREP", null)),
				ObjectTarget.activationTargets(adt, Json.parseObject(
						"{\"objects\":[{\"name\":\"zcl_a\",\"type\":\"CLAS/OC\"},{\"name\":\"zrep\"}]}")));
		ToolSpec arc1 = ToolSpec.of("SAPActivate", "", new JsonObject(), Capability.ACTIVATE, ToolSpec.Kind.WRITE);
		assertEquals(List.of(new ObjectTarget("ZCL_B", null)),
				ObjectTarget.activationTargets(arc1, Json.parseObject("{\"name\":\"ZCL_B\"}")));
		ToolSpec read = ToolSpec.of("adt_read_source", "", new JsonObject(), null, ToolSpec.Kind.READ);
		assertTrue(ObjectTarget.activationTargets(read, Json.parseObject("{\"name\":\"ZCL_B\"}")).isEmpty());
	}
}

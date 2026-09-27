package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.ToolPolicy.Decision;
import de.kiliantaubmann.bella.core.util.Json;

class ToolPolicyTest {

	private static ToolSpec tool(String name, ToolSpec.Kind kind) {
		return ToolSpec.of(name, "", new JsonObject(), null, kind);
	}

	@Test
	void defaultsReadAutoWriteConfirm() {
		ToolPolicy p = ToolPolicy.defaults();
		assertEquals(Decision.AUTO, p.decide(tool("adt_read_source", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_write_source", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_activate", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.AUTO, p.decide(tool("mcp_arc1_SAPRead", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("mcp_arc1_SAPWrite", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("mcp_other_thing", ToolSpec.Kind.UNKNOWN), new JsonObject()));
	}

	@Test
	void transportReleaseIsAlwaysDenied() {
		ToolPolicy p = new ToolPolicy(List.of(new ToolPolicy.Rule("*", Decision.AUTO)));
		ToolSpec t = tool("mcp_arc1_SAPTransport", ToolSpec.Kind.WRITE);
		assertEquals(Decision.DENY, p.decide(t, Json.parseObject("{\"action\":\"release\",\"id\":\"K900001\"}")));
		assertEquals(Decision.AUTO, p.decide(t, Json.parseObject("{\"action\":\"list\"}")));
	}

	@Test
	void userRulesComeFirst() {
		ToolPolicy p = new ToolPolicy(ToolPolicy.parseRules("# comment\nadt_activate = auto\nadt_read* = DENY\nbroken line"));
		assertEquals(Decision.AUTO, p.decide(tool("adt_activate", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.DENY, p.decide(tool("adt_read_source", ToolSpec.Kind.READ), new JsonObject()));
	}
}

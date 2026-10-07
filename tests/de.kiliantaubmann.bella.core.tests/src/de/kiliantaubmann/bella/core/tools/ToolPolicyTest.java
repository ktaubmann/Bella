package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_write_text_elements", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.AUTO, p.decide(tool("adt_text_elements", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.DENY, p.withMode(ChatMode.PLAN)
				.decide(tool("adt_write_text_elements", ToolSpec.Kind.WRITE), new JsonObject()));
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

	@Test
	void tableContentsAskFirst() {
		ToolPolicy p = ToolPolicy.defaults();
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_table_contents", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("mcp_arc1_SAPQuery", ToolSpec.Kind.READ), new JsonObject()));
	}

	@Test
	void planModeRefusesEverythingThatIsNotReadOnly() {
		ToolPolicy p = new ToolPolicy(ToolPolicy.parseRules("adt_write_source=AUTO")).withMode(ChatMode.PLAN);
		ToolSpec write = tool("adt_write_source", ToolSpec.Kind.WRITE);
		assertEquals(Decision.DENY, p.decide(write, new JsonObject()));
		assertTrue(p.refusal(write).startsWith("Refused in plan mode"));
		assertEquals(Decision.DENY, p.decide(tool("mcp_other_thing", ToolSpec.Kind.UNKNOWN), new JsonObject()));
		assertEquals(Decision.AUTO, p.decide(tool("adt_read_source", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_table_contents", ToolSpec.Kind.READ), new JsonObject()));
	}

	@Test
	void autoModeRunsWhatWouldAskButKeepsRefusals() {
		ToolPolicy p = new ToolPolicy(ToolPolicy.parseRules("adt_create_object=DENY")).withMode(ChatMode.AUTO);
		assertEquals(ChatMode.AUTO, p.mode());
		assertEquals(Decision.AUTO, p.decide(tool("adt_write_source", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.AUTO, p.decide(tool("adt_activate", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.AUTO, p.decide(tool("adt_table_contents", ToolSpec.Kind.READ), new JsonObject()));
		assertEquals(Decision.DENY, p.decide(tool("adt_create_object", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.DENY, p.decide(tool("mcp_arc1_SAPTransport", ToolSpec.Kind.WRITE),
				Json.parseObject("{\"action\":\"release\"}")));
		assertTrue(p.refusal(tool("adt_create_object", ToolSpec.Kind.WRITE)).startsWith("Refused by Bella"));
	}

	@Test
	void eachModeLetsMoreRunWithoutAsking() {
		ToolSpec read = tool("adt_read_source", ToolSpec.Kind.READ);
		ToolSpec data = ToolSpec.of("adt_table_contents", "", new JsonObject(), Capability.TABLE_CONTENTS,
				ToolSpec.Kind.READ);
		ToolSpec write = tool("adt_write_source", ToolSpec.Kind.WRITE);
		ToolSpec activate = tool("adt_activate", ToolSpec.Kind.WRITE);
		ToolSpec unknown = tool("mcp_other_thing", ToolSpec.Kind.UNKNOWN);
		// mode: read, data, write, activate, unknown
		Object[][] expected = {
				{ ChatMode.PLAN, Decision.AUTO, Decision.CONFIRM, Decision.DENY, Decision.DENY, Decision.DENY },
				{ ChatMode.SUGGEST, Decision.AUTO, Decision.CONFIRM, Decision.CONFIRM, Decision.CONFIRM, Decision.CONFIRM },
				{ ChatMode.NORMAL, Decision.AUTO, Decision.CONFIRM, Decision.CONFIRM, Decision.CONFIRM, Decision.CONFIRM },
				{ ChatMode.READ_DATA, Decision.AUTO, Decision.AUTO, Decision.CONFIRM, Decision.CONFIRM, Decision.CONFIRM },
				{ ChatMode.ACTIVATE, Decision.AUTO, Decision.CONFIRM, Decision.AUTO, Decision.AUTO, Decision.CONFIRM },
				{ ChatMode.AUTO, Decision.AUTO, Decision.AUTO, Decision.AUTO, Decision.AUTO, Decision.AUTO } };
		for (Object[] row : expected) {
			ToolPolicy p = ToolPolicy.defaults().withMode((ChatMode) row[0]);
			ToolSpec[] tools = { read, data, write, activate, unknown };
			for (int i = 0; i < tools.length; i++) {
				assertEquals(row[i + 1], p.decide(tools[i], new JsonObject()), row[0] + " " + tools[i].name());
			}
		}
	}

	@Test
	void suggestModeKeepsWritesInTheEditor() {
		ToolPolicy p = ToolPolicy.defaults().withMode(ChatMode.SUGGEST);
		assertTrue(p.editorOnly(tool("adt_write_source", ToolSpec.Kind.WRITE)));
		assertTrue(p.editorOnly(tool("adt_activate", ToolSpec.Kind.WRITE)));
		assertFalse(p.editorOnly(tool("adt_read_source", ToolSpec.Kind.READ)));
		assertTrue(p.refusal(tool("adt_activate", ToolSpec.Kind.WRITE)).startsWith("Refused in suggest mode"));
		assertFalse(ToolPolicy.defaults().editorOnly(tool("adt_write_source", ToolSpec.Kind.WRITE)));
	}

	@Test
	void askRulesAskInEveryMode() {
		for (ChatMode mode : ChatMode.values()) {
			ToolPolicy p = ToolPolicy.defaults().withMode(mode);
			Decision expected = mode == ChatMode.PLAN ? Decision.DENY : Decision.CONFIRM;
			assertEquals(expected, p.decide(tool("adt_delete_object", ToolSpec.Kind.WRITE), new JsonObject()), mode.name());
			for (String ask : List.of("adt_settings_write", "adt_transport_manage", "adt_git_write", "adt_trace_control")) {
				assertEquals(expected, p.decide(tool(ask, ToolSpec.Kind.WRITE), new JsonObject()), mode + " " + ask);
			}
		}
		ToolPolicy auto = ToolPolicy.defaults().withMode(ChatMode.AUTO);
		// rules with an action only match that action
		assertEquals(Decision.CONFIRM, auto.decide(tool("adt_package_manage", ToolSpec.Kind.WRITE),
				Json.parseObject("{\"action\":\"delete\"}")));
		assertEquals(Decision.AUTO, auto.decide(tool("adt_package_manage", ToolSpec.Kind.WRITE),
				Json.parseObject("{\"action\":\"create\"}")));
		assertEquals(Decision.CONFIRM, auto.decide(tool("mcp_arc1_SAPTransport", ToolSpec.Kind.UNKNOWN),
				Json.parseObject("{\"action\":\"create\"}")));
		assertEquals(Decision.AUTO, auto.decide(tool("mcp_arc1_SAPTransport", ToolSpec.Kind.UNKNOWN),
				Json.parseObject("{\"action\":\"list\"}")));
		// the developer can loosen it on purpose
		assertEquals(Decision.AUTO, new ToolPolicy(ToolPolicy.parseRules("adt_delete_object=AUTO"))
				.decide(tool("adt_delete_object", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(List.of(new ToolPolicy.Rule("x:delete", Decision.ASK)), ToolPolicy.parseRules("x:delete=ASK"));
	}
}

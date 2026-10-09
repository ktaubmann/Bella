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
	void debuggerToolsAskFirst() {
		ToolPolicy p = ToolPolicy.defaults();
		ToolSpec context = ToolSpec.of("debug_context", "", new JsonObject(), Capability.DEBUG_STATE,
				ToolSpec.Kind.READ);
		ToolSpec step = tool("debug_step", ToolSpec.Kind.WRITE);
		// a breakpoint changes Eclipse, not the SAP system
		ToolSpec breakpoint = tool("debug_breakpoint", ToolSpec.Kind.READ);
		// variable values go to the model provider
		assertEquals(Decision.CONFIRM, p.decide(context, new JsonObject()));
		assertEquals(Decision.AUTO, p.withMode(ChatMode.READ_DATA).decide(context, new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(breakpoint, new JsonObject()));
		// resuming may commit: asks also in Automode
		assertEquals(Decision.CONFIRM, p.withMode(ChatMode.AUTO).decide(step, new JsonObject()));
		assertEquals(Decision.CONFIRM, p.withMode(ChatMode.PLAN).decide(breakpoint, new JsonObject()));
		assertEquals(Decision.CONFIRM, p.withMode(ChatMode.ACTIVATE).decide(breakpoint, new JsonObject()));
		assertFalse(p.withMode(ChatMode.SUGGEST).editorOnly(breakpoint));
		assertEquals(Decision.CONFIRM, p.withMode(ChatMode.PLAN).decide(context, new JsonObject()));
		assertEquals(Decision.DENY, p.withMode(ChatMode.PLAN).decide(step, new JsonObject()));
		assertTrue(p.withMode(ChatMode.SUGGEST).refusal(step).contains("stepping or resuming runs the program"));
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
		// the authorization trace reads table SUAUTHVALTRC: user names and authorization values
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"authorization_trace\"}")));
		assertEquals(Decision.AUTO, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"short_dumps\"}")));
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
			for (String ask : List.of("adt_format_settings", "adt_transport_manage", "adt_git_write", "adt_trace_control")) {
				assertEquals(expected, p.decide(tool(ask, ToolSpec.Kind.WRITE), new JsonObject()), mode + " " + ask);
			}
		}
		ToolPolicy auto = ToolPolicy.defaults().withMode(ChatMode.AUTO);
		// rules with an action only match that action
		assertEquals(Decision.CONFIRM, auto.decide(tool("adt_package_manage", ToolSpec.Kind.WRITE),
				Json.parseObject("{\"action\":\"delete\"}")));
		// the tools trim the action, so surrounding blanks must not slip past the rule
		assertEquals(Decision.CONFIRM, auto.decide(tool("adt_package_manage", ToolSpec.Kind.WRITE),
				Json.parseObject("{\"action\":\" delete \"}")));
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

	@Test
	void rulesForMergedToolsKeepTheirEffect() {
		ToolPolicy p = new ToolPolicy(ToolPolicy.parseRules("adt_short_dumps=DENY\nADT_SETTINGS_WRITE=AUTO\n"
				+ "adt_list_transports=ASK"));
		assertEquals(Decision.DENY, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"short_dumps\"}")));
		assertEquals(Decision.AUTO, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"system_messages\"}")));
		assertEquals(Decision.AUTO, p.decide(tool("adt_format_settings", ToolSpec.Kind.WRITE), new JsonObject()));
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_transports", ToolSpec.Kind.READ), new JsonObject()));
		// the old list tool covered listing only, not the check before writing (the old adt_transport_info)
		assertEquals(Decision.CONFIRM, p.decide(tool("adt_transports", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"layers\"}")));
		assertEquals(Decision.AUTO, p.decide(tool("adt_transports", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"for_object\"}")));
	}

	@Test
	void wildcardsForMergedToolsKeepTheirEffect() {
		ToolPolicy p = new ToolPolicy(ToolPolicy.parseRules("adt_short_dump*=DENY"));
		assertEquals(Decision.DENY, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"short_dumps\"}")));
		assertEquals(Decision.AUTO, p.decide(tool("adt_diagnose", ToolSpec.Kind.READ),
				Json.parseObject("{\"action\":\"traces\"}")));
	}
}

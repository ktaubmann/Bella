package de.kiliantaubmann.bella.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.mcp.McpSetupCommands.Shell;
import de.kiliantaubmann.bella.core.util.Json;

class McpSetupCommandsTest {

	private static final String URL = "http://127.0.0.1:47011/mcp";
	private static final String JSON = "{\"type\":\"http\",\"url\":\"http://127.0.0.1:47011/mcp\","
			+ "\"headers\":{\"Authorization\":\"Bearer ab12\"},\"timeout\":" + McpServer.TOOL_TIMEOUT_MILLIS + "}";

	@Test
	void claudeCodeGetsTheServerWithTokenAndTimeoutForAllProjects() {
		assertEquals("claude mcp add-json --scope user bella-adt '" + JSON + "'",
				McpSetupCommands.claudeCode(URL, "ab12", Shell.BASH));
		// PowerShell and cmd mangle quotes in arguments, so the JSON goes through standard input
		assertEquals("'" + JSON + "' | claude mcp add-json --scope user bella-adt -",
				McpSetupCommands.claudeCode(URL, "ab12", Shell.POWERSHELL));
		assertEquals("echo " + JSON + "| claude mcp add-json --scope user bella-adt -",
				McpSetupCommands.claudeCode(URL, "ab12", Shell.CMD));
	}

	@Test
	void copilotReadsTheServerFromAFile() {
		Path file = Path.of("/home/dev/state/mcp/bella-adt-copilot.json");
		assertEquals("copilot --additional-mcp-config '@" + file.toAbsolutePath() + "'",
				McpSetupCommands.copilot(file, Shell.BASH));
		// an unquoted @ starts a splat in PowerShell
		assertEquals("copilot --additional-mcp-config \"@" + file.toAbsolutePath() + "\"",
				McpSetupCommands.copilot(file, Shell.POWERSHELL));
		JsonObject content = Json.parseObject(McpSetupCommands.copilotFile(URL, "ab12"));
		JsonObject server = content.getAsJsonObject("mcpServers").getAsJsonObject("bella-adt");
		assertEquals(URL, Json.str(server, "url"));
		assertEquals("Bearer ab12", server.getAsJsonObject("headers").get("Authorization").getAsString());
		assertEquals("*", server.getAsJsonArray("tools").get(0).getAsString());
		assertEquals(McpServer.TOOL_TIMEOUT_MILLIS, server.get("timeout").getAsInt());
	}

	@Test
	void theCopilotConfigurationKeepsOtherServers() {
		String existing = "{\"mcpServers\":{\"github\":{\"type\":\"http\",\"url\":\"https://x\"},"
				+ "\"bella-adt\":{\"url\":\"http://127.0.0.1:1/mcp\"}},\"other\":true}";
		JsonObject updated = Json.parseObject(McpSetupCommands.withCopilotServer(existing, URL, "ab12"));
		assertTrue(updated.get("other").getAsBoolean());
		JsonObject servers = updated.getAsJsonObject("mcpServers");
		assertEquals("https://x", Json.str(servers.getAsJsonObject("github"), "url"));
		assertEquals(URL, Json.str(servers.getAsJsonObject("bella-adt"), "url"));
		assertEquals(URL, Json.str(Json.parseObject(McpSetupCommands.withCopilotServer(null, URL, "ab12"))
				.getAsJsonObject("mcpServers").getAsJsonObject("bella-adt"), "url"));
		assertThrows(IllegalArgumentException.class, () -> McpSetupCommands.withCopilotServer("[1]", URL, "t"));
		assertThrows(IllegalArgumentException.class, () -> McpSetupCommands.withCopilotServer("{nope", URL, "t"));
	}

	@Test
	void copilotHomeFollowsTheEnvironment() {
		assertEquals(Path.of("/opt/cp"), McpSetupCommands.copilotHome(Map.of("COPILOT_HOME", "/opt/cp")));
		assertTrue(McpSetupCommands.copilotHome(Map.of()).endsWith(".copilot"));
	}

	@Test
	void theTokenIsLongAndHex() {
		String t = McpServer.newToken();
		assertTrue(t.matches("[0-9a-f]{64}"), t);
	}
}

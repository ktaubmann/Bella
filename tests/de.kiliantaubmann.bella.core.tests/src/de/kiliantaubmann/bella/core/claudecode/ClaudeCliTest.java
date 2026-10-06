package de.kiliantaubmann.bella.core.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

class ClaudeCliTest {

	@Test
	void findsConfiguredPathOnlyIfItExists() {
		Path home = Path.of("/home/k");
		assertEquals(Optional.of(Path.of("/opt/claude")),
				ClaudeCli.find("/opt/claude", Map.of(), "Linux", home, p -> p.equals(Path.of("/opt/claude"))));
		assertEquals(Optional.empty(), ClaudeCli.find("/opt/claude", Map.of("PATH", "/usr/bin"), "Linux", home,
				p -> p.equals(Path.of("/usr/bin/claude"))));
	}

	@Test
	void searchesPathThenKnownLocations() {
		Path home = Path.of("/home/k");
		Set<Path> files = Set.of(Path.of("/usr/local/bin/claude"), Path.of("/home/k/.local/bin/claude"));
		assertEquals(Optional.of(Path.of("/usr/local/bin/claude")),
				ClaudeCli.find("", Map.of("PATH", "/usr/bin:/usr/local/bin"), "Linux", home, files::contains));
		// Eclipse started from the Dock: no npm directory on PATH
		assertEquals(Optional.of(Path.of("/home/k/.local/bin/claude")),
				ClaudeCli.find(null, Map.of("PATH", "/usr/bin"), "Mac OS X", home, files::contains));
		assertEquals(Optional.empty(), ClaudeCli.find(null, Map.of(), "Linux", home, p -> false));
	}

	@Test
	void findsWindowsNpmShim() {
		Path shim = Path.of("C:\\Users\\k\\AppData\\Roaming", "npm", "claude.cmd");
		assertEquals(Optional.of(shim), ClaudeCli.find("",
				Map.of("Path", "C:\\Windows", "APPDATA", "C:\\Users\\k\\AppData\\Roaming"), "Windows 11",
				Path.of("C:\\Users\\k"), shim::equals));
	}

	@Test
	void runsCmdShimsThroughCmdExe() {
		assertEquals(List.of("cmd.exe", "/c", "claude.cmd", "--version"),
				ClaudeCli.command(Path.of("claude.cmd"), List.of("--version")));
		assertEquals(List.of("/usr/bin/claude", "--version"),
				ClaudeCli.command(Path.of("/usr/bin/claude"), List.of("--version")));
	}

	@Test
	void sessionCommandLocksTheCliDown() {
		List<String> a = ClaudeCli.sessionArgs("opus", "high", Path.of("sys.md"), Path.of("mcp.json"));
		int tools = a.indexOf("--tools");
		assertEquals("", a.get(tools + 1));
		assertTrue(a.contains("--strict-mcp-config"));
		assertEquals("mcp.json", a.get(a.indexOf("--mcp-config") + 1));
		assertEquals("mcp__bella", a.get(a.indexOf("--allowedTools") + 1));
		assertEquals("dontAsk", a.get(a.indexOf("--permission-mode") + 1));
		assertEquals("opus", a.get(a.indexOf("--model") + 1));
		assertEquals("high", a.get(a.indexOf("--effort") + 1));
		assertEquals("sys.md", a.get(a.indexOf("--system-prompt-file") + 1));
		assertTrue(a.contains("--no-session-persistence"));
		assertFalse(a.contains("--bare"), "--bare only accepts API keys");
		assertFalse(a.contains("--dangerously-skip-permissions"));

		List<String> one = ClaudeCli.oneShotArgs("haiku", null, Path.of("sys.md"));
		assertFalse(one.contains("--mcp-config"));
		assertFalse(one.contains("--effort"));
		assertEquals("", one.get(one.indexOf("--tools") + 1));
	}

	@Test
	void environmentPrefersTheSubscription() {
		Map<String, String> env = new ClaudeCli(new ClaudeCli.Config("", "tok"), ProcessLauncher.SYSTEM, Path.of("."))
				.env();
		assertTrue(env.containsKey("ANTHROPIC_API_KEY"));
		assertNull(env.get("ANTHROPIC_API_KEY"));
		assertNull(env.get("ANTHROPIC_AUTH_TOKEN"));
		assertEquals("tok", env.get("CLAUDE_CODE_OAUTH_TOKEN"));
		Map<String, String> noToken = new ClaudeCli(new ClaudeCli.Config("", ""), ProcessLauncher.SYSTEM,
				Path.of(".")).env();
		assertFalse(noToken.containsKey("CLAUDE_CODE_OAUTH_TOKEN"));
	}

	@Test
	void parsesAuthStatus() {
		ClaudeCli.Status in = ClaudeCli.parseStatus("2.1.283 (Claude Code)",
				"{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"subscriptionType\":\"max\",\"email\":\"k@example.com\"}",
				0);
		assertEquals(ClaudeCli.State.LOGGED_IN, in.state());
		assertEquals("max", in.subscription());
		assertEquals("2.1.283 (Claude Code)", in.version());

		assertEquals(ClaudeCli.State.NOT_LOGGED_IN,
				ClaudeCli.parseStatus(null, "{\"loggedIn\":false,\"authMethod\":\"none\"}", 1).state());
		assertEquals(ClaudeCli.State.API_KEY,
				ClaudeCli.parseStatus(null, "{\"loggedIn\":true,\"authMethod\":\"api_key\"}", 0).state());
		assertEquals(ClaudeCli.State.NOT_LOGGED_IN, ClaudeCli.parseStatus(null, "Not logged in", 1).state());
		assertEquals(ClaudeCli.State.LOGGED_IN,
				ClaudeCli.parseStatus(null, "warning\n{\"loggedIn\":true,\"authMethod\":\"oauth_token\"}", 0).state());
	}

	@Test
	void statusReportsMissingCli() {
		ClaudeCli cli = new ClaudeCli(new ClaudeCli.Config("/does/not/exist/claude", ""), ProcessLauncher.SYSTEM,
				Path.of("."));
		assertEquals(ClaudeCli.State.NOT_FOUND, cli.status().state());
	}

	@Test
	void shimsGetNoArgumentsCmdWouldInterpret() {
		assertEquals(List.of("cmd.exe", "/c", "claude.cmd", "--model", "claude-opus-5", "--tools", ""),
				ClaudeCli.command(Path.of("claude.cmd"), List.of("--model", "claude-opus-5", "--tools", "")));
		for (String bad : List.of("opus & calc", "opus|x", "%PATH%", "a\"b", "x^y", "!v!")) {
			org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
					() -> ClaudeCli.command(Path.of("claude.cmd"), List.of("--model", bad)), bad);
		}
		assertEquals(List.of("claude.exe", "--model", "opus & calc"),
				ClaudeCli.command(Path.of("claude.exe"), List.of("--model", "opus & calc")), "no shell without a shim");
	}
}

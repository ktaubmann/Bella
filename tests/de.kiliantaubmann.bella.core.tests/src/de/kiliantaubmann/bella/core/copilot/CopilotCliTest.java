package de.kiliantaubmann.bella.core.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

class CopilotCliTest {

	@TempDir
	Path tmp;

	@Test
	void findsWingetLinkAndPath() {
		Path link = Path.of("C:\\Users\\k\\AppData\\Local", "Microsoft", "WinGet", "Links", "copilot.exe");
		assertEquals(Optional.of(link), CopilotCli.find("",
				Map.of("Path", "C:\\Windows", "LOCALAPPDATA", "C:\\Users\\k\\AppData\\Local"), "Windows 11",
				Path.of("C:\\Users\\k"), link::equals));
		Set<Path> files = Set.of(Path.of("/usr/local/bin/copilot"), Path.of("/home/k/.local/bin/copilot"));
		assertEquals(Optional.of(Path.of("/usr/local/bin/copilot")),
				CopilotCli.find(null, Map.of("PATH", "/usr/bin:/usr/local/bin"), "Linux", Path.of("/home/k"),
						files::contains));
		assertEquals(Optional.of(Path.of("/home/k/.local/bin/copilot")),
				CopilotCli.find(null, Map.of("PATH", "/usr/bin"), "Mac OS X", Path.of("/home/k"), files::contains));
	}

	@Test
	void commandLineSwitchesOffEverythingButBellasTools() {
		List<String> a = CopilotCli.acpArgs("gpt-5", "high", List.of("github-tools", "bella", "my-db"));
		assertTrue(a.contains("--acp"));
		assertTrue(a.contains("--disable-builtin-mcps"));
		assertTrue(a.contains("--no-auto-update"));
		assertTrue(a.contains("--disallow-temp-dir"));
		assertTrue(a.contains("--no-custom-instructions"));
		assertEquals(List.of("shell", "write", "url"), valuesOf(a, "--deny-tool"));
		assertEquals(List.of("bella"), valuesOf(a, "--allow-tool"));
		assertEquals(List.of("github-tools", "my-db"), valuesOf(a, "--disable-mcp-server"));
		assertEquals(List.of("gpt-5"), valuesOf(a, "--model"));
		assertEquals(List.of("high"), valuesOf(a, "--reasoning-effort"));
		assertFalse(a.contains("--allow-all-tools"));
		assertFalse(a.contains("--yolo"));
		assertFalse(a.contains("--allow-all"));

		List<String> plain = CopilotCli.acpArgs("", null, List.of());
		assertFalse(plain.contains("--model"));
		assertFalse(plain.contains("--reasoning-effort"));
	}

	private static List<String> valuesOf(List<String> args, String flag) {
		List<String> v = new java.util.ArrayList<>();
		for (int i = 0; i + 1 < args.size(); i++) {
			if (args.get(i).equals(flag)) {
				v.add(args.get(i + 1));
			}
		}
		return v;
	}

	@Test
	void environmentUsesTheConfiguredTokenAndDropsClassicTokens() {
		CopilotCli withToken = new CopilotCli(new CopilotCli.Config("", "github_pat_x"), ProcessLauncher.SYSTEM, tmp);
		Map<String, String> env = withToken.env(Map.of("GH_TOKEN", "ghp_classic", "GITHUB_TOKEN", "gho_ok"));
		assertEquals("github_pat_x", env.get("COPILOT_GITHUB_TOKEN"));
		assertTrue(env.containsKey("GH_TOKEN"));
		assertNull(env.get("GH_TOKEN"));
		assertFalse(env.containsKey("GITHUB_TOKEN"), "fine-grained and OAuth tokens stay");
		assertEquals("false", env.get("COPILOT_AUTO_UPDATE"));

		Map<String, String> none = new CopilotCli(new CopilotCli.Config("", ""), ProcessLauncher.SYSTEM, tmp)
				.env(Map.of());
		assertFalse(none.containsKey("COPILOT_GITHUB_TOKEN"));
	}

	@Test
	void readsUserMcpServerNames() {
		assertEquals(List.of("a", "b"),
				CopilotCli.mcpServerNames("{\"mcpServers\":{\"a\":{\"command\":\"x\"},\"b\":{\"url\":\"y\"}}}"));
		assertEquals(List.of(), CopilotCli.mcpServerNames("not json"));
		assertEquals(List.of(), CopilotCli.mcpServerNames("{}"));
	}

	@Test
	void statusReportsLogonAndModels() throws IOException {
		Path exe = Files.createFile(tmp.resolve("copilot"));
		FakeAcp fake = new FakeAcp();
		CopilotCli cli = new CopilotCli(new CopilotCli.Config(exe.toString(), ""), fake, tmp.resolve("w"));
		CopilotCli.Status ok = cli.status();
		assertEquals(CopilotCli.State.LOGGED_IN, ok.state());
		assertEquals(List.of("claude-sonnet-4.5", "gpt-5"), ok.models());

		fake.loggedIn = false;
		assertEquals(CopilotCli.State.NOT_LOGGED_IN, cli.status().state());
		assertTrue(fake.started.stream().noneMatch(FakeAcp.Proc::isAlive), "status checks end their processes");

		CopilotCli missing = new CopilotCli(new CopilotCli.Config(tmp.resolve("nope").toString(), ""), fake, tmp);
		assertEquals(CopilotCli.State.NOT_FOUND, missing.status().state());
	}

	@Test
	void modelsAreOptionalInTheSessionResult() {
		assertEquals(List.of(), CopilotCli.models(Json.parseObject("{\"sessionId\":\"s\"}")));
	}
}

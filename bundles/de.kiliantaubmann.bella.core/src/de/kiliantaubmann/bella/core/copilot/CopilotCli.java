package de.kiliantaubmann.bella.core.copilot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.util.Executables;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

/**
 * The locally installed GitHub Copilot CLI ({@code copilot}). Bella starts it
 * in Agent Client Protocol mode ({@code --acp}) so Claude or GPT models from
 * the developer's Copilot subscription can be used.
 * <p>
 * The command line switches off the CLI's own abilities: no shell, no file
 * writes, no web access, no GitHub MCP server and none of the developer's own
 * MCP servers. Only the tools of Bella's MCP server ("bella") are allowed; what
 * they may do is decided by Bella's tool policy.
 */
public final class CopilotCli {

	/** Name of Bella's MCP server as the CLI sees it. */
	public static final String MCP_SERVER_NAME = "bella";

	/**
	 * @param executable configured path to {@code copilot}, empty for automatic detection
	 * @param token      fine-grained PAT with the "Copilot Requests" permission, may be empty
	 */
	public record Config(String executable, String token) {
	}

	public enum State {
		NOT_FOUND, NOT_LOGGED_IN, LOGGED_IN, ERROR
	}

	/** Result of a status check. */
	public record Status(State state, String version, List<String> models, String detail) {
	}

	private final Config config;
	private final ProcessLauncher launcher;
	private final Path workDir;

	public CopilotCli(Config config, ProcessLauncher launcher, Path workDir) {
		this.config = config;
		this.launcher = launcher;
		this.workDir = workDir;
	}

	public Path workDir() {
		return workDir;
	}

	// ---- discovery --------------------------------------------------------------------

	public Optional<Path> find() {
		return find(config.executable(), System.getenv(), System.getProperty("os.name", ""),
				Path.of(System.getProperty("user.home", ".")), p -> Files.isRegularFile(p));
	}

	static Optional<Path> find(String configured, Map<String, String> env, String osName, Path home,
			Predicate<Path> exists) {
		boolean windows = Executables.isWindows(osName);
		List<String> names = windows ? List.of("copilot.exe", "copilot.cmd") : List.of("copilot");
		List<Path> extra = new ArrayList<>();
		if (windows) {
			String localAppData = env.get("LOCALAPPDATA");
			if (localAppData != null) {
				extra.add(Path.of(localAppData, "Microsoft", "WinGet", "Links", "copilot.exe"));
			}
			String appData = env.get("APPDATA");
			if (appData != null) {
				extra.add(Path.of(appData, "npm", "copilot.cmd"));
			}
			extra.add(home.resolve(".local").resolve("bin").resolve("copilot.exe"));
		} else {
			extra.add(home.resolve(".local/bin/copilot"));
			extra.add(home.resolve(".npm-global/bin/copilot"));
			extra.add(home.resolve(".volta/bin/copilot"));
			extra.add(Path.of("/opt/homebrew/bin/copilot"));
			extra.add(Path.of("/usr/local/bin/copilot"));
			extra.add(Path.of("/usr/bin/copilot"));
		}
		return Executables.find(configured, env, osName, exists, names, extra);
	}

	// ---- command line -------------------------------------------------------------------

	/**
	 * Arguments for {@code copilot --acp}.
	 *
	 * @param userMcpServers MCP servers from the developer's Copilot configuration; all are disabled
	 */
	public static List<String> acpArgs(String model, String effort, List<String> userMcpServers) {
		List<String> a = new ArrayList<>(List.of("--acp", "--no-auto-update", "--disable-builtin-mcps",
				"--deny-tool", "shell", "--deny-tool", "write", "--deny-tool", "url",
				"--allow-tool", MCP_SERVER_NAME, "--disallow-temp-dir", "--no-ask-user", "--no-custom-instructions"));
		for (String server : userMcpServers) {
			if (!MCP_SERVER_NAME.equals(server)) {
				a.add("--disable-mcp-server");
				a.add(server);
			}
		}
		if (model != null && !model.isBlank()) {
			a.add("--model");
			a.add(model.trim());
		}
		if (effort != null && !effort.isBlank()) {
			a.add("--reasoning-effort");
			a.add(effort.trim());
		}
		return a;
	}

	/**
	 * Environment for the CLI. A token configured in Bella wins; classic
	 * {@code ghp_} tokens in {@code GH_TOKEN}/{@code GITHUB_TOKEN} are removed
	 * because the CLI would prefer them over the stored login and they are not
	 * accepted for Copilot.
	 */
	Map<String, String> env(Map<String, String> current) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("COPILOT_AUTO_UPDATE", "false");
		if (config.token() != null && !config.token().isBlank()) {
			env.put("COPILOT_GITHUB_TOKEN", config.token().trim());
		}
		for (String k : List.of("GH_TOKEN", "GITHUB_TOKEN", "COPILOT_GITHUB_TOKEN")) {
			String v = current.get(k);
			if (v != null && v.trim().startsWith("ghp_") && !env.containsKey(k)) {
				env.put(k, null);
			}
		}
		return env;
	}

	/** Names of the MCP servers in the developer's Copilot configuration. */
	List<String> userMcpServers() {
		String home = System.getenv("COPILOT_HOME");
		Path dir = home != null && !home.isBlank() ? Path.of(home)
				: Path.of(System.getProperty("user.home", "."), ".copilot");
		try {
			return mcpServerNames(Files.readString(dir.resolve("mcp-config.json"), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return List.of();
		}
	}

	static List<String> mcpServerNames(String json) {
		try {
			JsonElement e = Json.parseStrict(json);
			JsonObject servers = e.isJsonObject() ? Json.obj(e.getAsJsonObject(), "mcpServers") : null;
			return servers == null ? List.of() : List.copyOf(servers.keySet());
		} catch (JsonParseException ex) {
			return List.of();
		}
	}

	public ProcessLauncher.CliProcess start(List<String> args) throws LlmException {
		Path exe = find().orElseThrow(() -> new LlmException(0, notFoundMessage()));
		try {
			Files.createDirectories(workDir);
			return launcher.start(Executables.command(exe, args), env(System.getenv()), workDir);
		} catch (IOException e) {
			throw new LlmException("Could not start the Copilot CLI (" + exe + "): " + e.getMessage(), e);
		}
	}

	/** Starts {@code copilot --acp} and completes the handshake. */
	Acp startAcp(String model, String effort, Acp.NotificationHandler notifications, Acp.RequestHandler requests)
			throws LlmException {
		ProcessLauncher.CliProcess p = start(acpArgs(model, effort, userMcpServers()));
		Acp acp = new Acp(p, notifications, requests);
		try {
			acp.request("initialize", initializeParams(), 60_000);
			return acp;
		} catch (Acp.AcpException | IOException e) {
			acp.close();
			throw new LlmException("The Copilot CLI did not start: " + e.getMessage(), e);
		}
	}

	static JsonObject initializeParams() {
		JsonObject fs = new JsonObject();
		fs.addProperty("readTextFile", false);
		fs.addProperty("writeTextFile", false);
		JsonObject caps = new JsonObject();
		caps.add("fs", fs);
		caps.addProperty("terminal", false);
		JsonObject info = new JsonObject();
		info.addProperty("name", "bella");
		info.addProperty("version", "bella");
		JsonObject p = new JsonObject();
		p.addProperty("protocolVersion", 1);
		p.add("clientCapabilities", caps);
		p.add("clientInfo", info);
		return p;
	}

	/** {@code session/new} parameters; {@code mcpUrl} is {@code null} for sessions without tools. */
	JsonObject newSessionParams(String mcpUrl, String mcpToken) {
		JsonObject p = new JsonObject();
		p.addProperty("cwd", workDir.toAbsolutePath().toString());
		JsonArray servers = new JsonArray();
		if (mcpUrl != null) {
			JsonObject auth = new JsonObject();
			auth.addProperty("name", "Authorization");
			auth.addProperty("value", "Bearer " + mcpToken);
			JsonArray headers = new JsonArray();
			headers.add(auth);
			JsonObject s = new JsonObject();
			s.addProperty("type", "http");
			s.addProperty("name", MCP_SERVER_NAME);
			s.addProperty("url", mcpUrl);
			s.add("headers", headers);
			servers.add(s);
		}
		p.add("mcpServers", servers);
		return p;
	}

	String notFoundMessage() {
		String configured = config.executable();
		return configured != null && !configured.isBlank()
				? "Copilot CLI not found at " + configured + "."
				: "GitHub Copilot CLI (copilot) not found. Install it (e.g. 'winget install GitHub.Copilot' or "
						+ "'npm install -g @github/copilot'), run 'copilot login' once, or set its path in Bella's preferences.";
	}

	static String loginMessage() {
		return "GitHub Copilot is not logged in. Run 'copilot login' in a terminal, or store a fine-grained token "
				+ "with the 'Copilot Requests' permission in Bella's preferences.";
	}

	// ---- status -----------------------------------------------------------------------

	/** Version and logon state; blocks up to about a minute. */
	public Status status() {
		if (find().isEmpty()) {
			return new Status(State.NOT_FOUND, null, List.of(), notFoundMessage());
		}
		String version = null;
		try {
			version = firstLine(run(List.of("version")));
		} catch (LlmException | IOException e) {
			// the ACP check below reports the real problem
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		Acp acp = null;
		try {
			acp = startAcp(null, null, (m, p) -> {
			}, (m, p) -> {
				throw new Acp.AcpException(-32601, "not supported");
			});
			JsonObject session = acp.request("session/new", newSessionParams(null, null), 60_000);
			return new Status(State.LOGGED_IN, version, models(session), null);
		} catch (Acp.AcpException e) {
			return new Status(e.isAuthRequired() ? State.NOT_LOGGED_IN : State.ERROR, version, List.of(),
					e.getMessage());
		} catch (LlmException | IOException e) {
			return new Status(State.ERROR, version, List.of(), e.getMessage());
		} finally {
			if (acp != null) {
				acp.close();
			}
		}
	}

	/** Model IDs from a {@code session/new} result, if the CLI reports them. */
	static List<String> models(JsonObject session) {
		JsonObject models = Json.obj(session, "models");
		JsonArray available = Json.arr(models, "availableModels");
		List<String> ids = new ArrayList<>();
		if (available != null) {
			for (JsonElement m : available) {
				String id = m.isJsonObject() ? Json.str(m.getAsJsonObject(), "modelId") : null;
				if (id != null) {
					ids.add(id);
				}
			}
		}
		return ids;
	}

	private String run(List<String> args) throws LlmException, IOException, InterruptedException {
		ProcessLauncher.CliProcess p = start(args);
		p.stdin().close();
		byte[] out;
		try (InputStream in = p.stdout()) {
			out = in.readAllBytes();
		}
		if (p.waitFor(20_000) == -1) {
			p.destroy();
		}
		return new String(out, StandardCharsets.UTF_8);
	}

	private static String firstLine(String s) {
		String t = s == null ? "" : s.trim();
		int nl = t.indexOf('\n');
		t = nl < 0 ? t : t.substring(0, nl).trim();
		return t.isEmpty() ? null : t;
	}
}

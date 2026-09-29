package de.kiliantaubmann.bella.core.claudecode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Executables;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;

/**
 * The locally installed Claude Code CLI ({@code claude}). Bella uses it to
 * reach Claude through the developer's Claude subscription instead of an API
 * key: the CLI owns login and billing, Bella only starts it headless.
 * <p>
 * Every command Bella builds switches off the CLI's own tools
 * ({@code --tools ""}), so the model can neither run shell commands nor touch
 * files; in the chat it only sees Bella's tools through Bella's MCP server.
 */
public final class ClaudeCli {

	/** Name of Bella's MCP server as the CLI sees it; tools appear as {@code mcp__bella__*}. */
	public static final String MCP_SERVER_NAME = "bella";

	/**
	 * @param executable configured path to {@code claude}, empty for automatic detection
	 * @param oauthToken long-lived subscription token from {@code claude setup-token}, may be empty
	 */
	public record Config(String executable, String oauthToken) {
	}

	public enum State {
		NOT_FOUND, NOT_LOGGED_IN, LOGGED_IN, API_KEY, ERROR
	}

	/** Result of {@code claude auth status}. */
	public record Status(State state, String version, String authMethod, String subscription, String detail) {
	}

	private final Config config;
	private final ProcessLauncher launcher;
	private final Path workDir;

	public ClaudeCli(Config config, ProcessLauncher launcher, Path workDir) {
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

	/**
	 * Looks for the CLI: the configured path, then {@code PATH}, then the usual
	 * install locations (Eclipse started from the macOS Dock or a Windows
	 * shortcut often has no npm directory on its {@code PATH}).
	 */
	static Optional<Path> find(String configured, Map<String, String> env, String osName, Path home,
			Predicate<Path> exists) {
		boolean windows = Executables.isWindows(osName);
		List<String> names = windows ? List.of("claude.exe", "claude.cmd") : List.of("claude");
		List<Path> extra = new ArrayList<>();
		if (windows) {
			extra.add(home.resolve(".local").resolve("bin").resolve("claude.exe"));
			String appData = env.get("APPDATA");
			if (appData != null) {
				extra.add(Path.of(appData, "npm", "claude.cmd"));
			}
			String localAppData = env.get("LOCALAPPDATA");
			if (localAppData != null) {
				extra.add(Path.of(localAppData, "Programs", "claude", "claude.exe"));
			}
		} else {
			extra.add(home.resolve(".local/bin/claude"));
			extra.add(home.resolve(".claude/local/claude"));
			extra.add(home.resolve(".npm-global/bin/claude"));
			extra.add(home.resolve(".volta/bin/claude"));
			extra.add(Path.of("/opt/homebrew/bin/claude"));
			extra.add(Path.of("/usr/local/bin/claude"));
			extra.add(Path.of("/usr/bin/claude"));
		}
		return Executables.find(configured, env, osName, exists, names, extra);
	}

	// ---- commands ---------------------------------------------------------------------

	/** Full command line for the executable plus {@code args}. */
	static List<String> command(Path executable, List<String> args) {
		return Executables.command(executable, args);
	}

	/** Arguments for a long-lived chat process that talks stream-json on stdin/stdout. */
	public static List<String> sessionArgs(String model, String effort, Path systemPromptFile, Path mcpConfigFile) {
		List<String> a = headless(model, effort, systemPromptFile);
		a.add("--mcp-config");
		a.add(mcpConfigFile.toString());
		a.add("--strict-mcp-config");
		a.add("--allowedTools");
		a.add("mcp__" + MCP_SERVER_NAME);
		return a;
	}

	/** Arguments for a single request without tools (completion, generate, rewrite). */
	public static List<String> oneShotArgs(String model, String effort, Path systemPromptFile) {
		List<String> a = headless(model, effort, systemPromptFile);
		a.add("--strict-mcp-config");
		return a;
	}

	private static List<String> headless(String model, String effort, Path systemPromptFile) {
		List<String> a = new ArrayList<>(List.of("-p", "--input-format", "stream-json", "--output-format",
				"stream-json", "--verbose", "--include-partial-messages", "--tools", "", "--permission-mode",
				"dontAsk", "--no-session-persistence", "--disable-slash-commands"));
		if (model != null && !model.isBlank()) {
			a.add("--model");
			a.add(model.trim());
		}
		if (effort != null && !effort.isBlank()) {
			a.add("--effort");
			a.add(effort.trim());
		}
		if (systemPromptFile != null) {
			a.add("--system-prompt-file");
			a.add(systemPromptFile.toString());
		}
		return a;
	}

	/**
	 * Environment for the CLI. API keys are removed so the CLI uses the
	 * subscription login (an inherited {@code ANTHROPIC_API_KEY} would take
	 * precedence and bill the API account instead).
	 */
	Map<String, String> env() {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("ANTHROPIC_API_KEY", null);
		env.put("ANTHROPIC_AUTH_TOKEN", null);
		env.put("DISABLE_AUTOUPDATER", "1");
		// Tool calls may wait for the developer's confirmation dialog.
		env.put("MCP_TOOL_TIMEOUT", "1800000");
		if (config.oauthToken() != null && !config.oauthToken().isBlank()) {
			env.put("CLAUDE_CODE_OAUTH_TOKEN", config.oauthToken().trim());
		}
		return env;
	}

	public ProcessLauncher.CliProcess start(List<String> args) throws LlmException {
		Path exe = find().orElseThrow(() -> new LlmException(0, notFoundMessage()));
		try {
			Files.createDirectories(workDir);
			return launcher.start(command(exe, args), env(), workDir);
		} catch (IOException e) {
			throw new LlmException("Could not start the Claude Code CLI (" + exe + "): " + e.getMessage(), e);
		}
	}

	private String notFoundMessage() {
		String configured = config.executable();
		return configured != null && !configured.isBlank()
				? "Claude Code CLI not found at " + configured + "."
				: "Claude Code CLI (claude) not found. Install it (https://claude.com/claude-code), "
						+ "run 'claude auth login' once, or set its path in Bella's preferences.";
	}

	// ---- status -----------------------------------------------------------------------

	/** Runs {@code claude --version} and {@code claude auth status --json}. Blocks up to ~40 s. */
	public Status status() {
		if (find().isEmpty()) {
			return new Status(State.NOT_FOUND, null, null, null, notFoundMessage());
		}
		try {
			String version = run(List.of("--version")).output().trim();
			Run auth = run(List.of("auth", "status", "--json"));
			return parseStatus(version.isEmpty() ? null : version, auth.output(), auth.exit());
		} catch (LlmException | IOException e) {
			return new Status(State.ERROR, null, null, null, e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Status(State.ERROR, null, null, null, "interrupted");
		}
	}

	private record Run(String output, int exit) {
	}

	private Run run(List<String> args) throws LlmException, IOException, InterruptedException {
		ProcessLauncher.CliProcess p = start(args);
		p.stdin().close();
		byte[] out;
		try (InputStream in = p.stdout()) {
			out = in.readAllBytes();
		}
		int exit = p.waitFor(20_000);
		if (exit == -1) {
			p.destroy();
		}
		String text = new String(out, StandardCharsets.UTF_8);
		if (text.isBlank()) {
			text = p.stderrTail();
		}
		return new Run(text, exit);
	}

	static Status parseStatus(String version, String output, int exit) {
		JsonObject o = null;
		try {
			String trimmed = output == null ? "" : output.trim();
			int brace = trimmed.indexOf('{');
			if (brace >= 0) {
				JsonElement e = Json.parseStrict(trimmed.substring(brace));
				o = e.isJsonObject() ? e.getAsJsonObject() : null;
			}
		} catch (JsonParseException e) {
			o = null;
		}
		if (o == null) {
			return new Status(exit == 0 ? State.ERROR : State.NOT_LOGGED_IN, version, null, null,
					output == null ? "" : output.trim());
		}
		boolean loggedIn = bool(o, "loggedIn") || bool(o, "logged_in") || bool(o, "authenticated");
		String method = first(o, "authMethod", "auth_method", "method");
		String subscription = first(o, "subscriptionType", "subscription_type", "subscription", "plan");
		String detail = first(o, "email", "account", "organization");
		State state;
		if (!loggedIn) {
			state = State.NOT_LOGGED_IN;
		} else if (method != null && method.toLowerCase(Locale.ROOT).contains("api_key")) {
			state = State.API_KEY;
		} else {
			state = State.LOGGED_IN;
		}
		return new Status(state, version, method, subscription, detail);
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
	}

	private static String first(JsonObject o, String... keys) {
		for (String k : keys) {
			String v = Json.str(o, k);
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}
}

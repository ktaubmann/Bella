package de.kiliantaubmann.bella.core.mcp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * Console commands and configuration entries that register Bella's MCP
 * server, opened for other programs, with Claude Code, the GitHub Copilot CLI
 * and editors such as VS Code or Cursor. They contain the bearer token.
 */
public final class McpSetupCommands {

	/**
	 * Name of the opened server in the clients' configuration. Not
	 * {@code bella}: that is the private server of Bella's own CLI chats, and
	 * Bella's Copilot chat switches off every other server of the developer.
	 */
	public static final String SERVER_NAME = "bella-adt";

	/** The shell a command is quoted for. */
	public enum Shell {
		BASH, POWERSHELL, CMD;

		/** PowerShell on Windows, else bash/zsh. */
		public static Shell ofSystem() {
			return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? POWERSHELL : BASH;
		}
	}

	private McpSetupCommands() {
	}

	/** The server entry for Claude Code ({@code type http}, token header, tool timeout). */
	public static JsonObject claudeCodeEntry(String url, String token) {
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token);
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", url);
		server.add("headers", headers);
		server.addProperty("timeout", McpServer.TOOL_TIMEOUT_MILLIS);
		return server;
	}

	/** The server entry for the Copilot CLI: as for Claude Code, plus all tools allowed. */
	public static JsonObject copilotEntry(String url, String token) {
		JsonObject server = claudeCodeEntry(url, token);
		JsonArray tools = new JsonArray();
		tools.add("*");
		server.add("tools", tools);
		return server;
	}

	/** {@code {"mcpServers": {name: entry}}}. */
	public static JsonObject servers(String name, JsonObject entry) {
		JsonObject servers = new JsonObject();
		servers.add(name, entry);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root;
	}

	/**
	 * Registers the server for all of the developer's Claude Code projects.
	 * {@code add-json} carries the timeout, which {@code claude mcp add}
	 * cannot set; PowerShell and cmd pass the JSON on standard input, since
	 * they would mangle its quotes as an argument.
	 */
	public static String claudeCode(String url, String token, Shell shell) {
		String json = Json.GSON.toJson(claudeCodeEntry(url, token));
		String add = "claude mcp add-json --scope user " + SERVER_NAME + " ";
		return switch (shell) {
		case BASH -> add + "'" + json + "'";
		case POWERSHELL -> "'" + json + "' | " + add + "-";
		case CMD -> "echo " + json + "| " + add + "-";
		};
	}

	/** Starts the Copilot CLI with the server from {@code configFile} (see {@link #copilotFile}). */
	public static String copilot(Path configFile, Shell shell) {
		String arg = "@" + configFile.toAbsolutePath();
		return "copilot --additional-mcp-config " + switch (shell) {
		case BASH -> "'" + arg + "'";
		case POWERSHELL, CMD -> "\"" + arg + "\"";
		};
	}

	/** Content of the file the Copilot command names. */
	public static String copilotFile(String url, String token) {
		return Json.GSON.toJson(servers(SERVER_NAME, copilotEntry(url, token)));
	}

	/**
	 * The developer's Copilot configuration ({@code mcp-config.json}) with the
	 * server added or replaced; other servers and settings stay.
	 *
	 * @param existing the file's content, {@code null} or blank if there is none
	 * @throws IllegalArgumentException if the file is not a JSON object
	 */
	public static String withCopilotServer(String existing, String url, String token) {
		JsonObject root;
		if (existing == null || existing.isBlank()) {
			root = new JsonObject();
		} else {
			try {
				JsonElement e = Json.parseStrict(existing);
				if (!e.isJsonObject()) {
					throw new IllegalArgumentException("mcp-config.json is not a JSON object");
				}
				root = e.getAsJsonObject();
			} catch (JsonParseException e) {
				throw new IllegalArgumentException("mcp-config.json is not valid JSON: " + e.getMessage(), e);
			}
		}
		JsonElement servers = root.get("mcpServers");
		if (servers == null || !servers.isJsonObject()) {
			root.add("mcpServers", new JsonObject());
		}
		root.getAsJsonObject("mcpServers").add(SERVER_NAME, copilotEntry(url, token));
		return Json.PRETTY.toJson(root) + "\n";
	}

	/** The Copilot CLI's configuration folder: {@code COPILOT_HOME}, else {@code ~/.copilot}. */
	public static Path copilotHome(Map<String, String> env) {
		String home = env.get("COPILOT_HOME");
		return home != null && !home.isBlank() ? Path.of(home)
				: Path.of(System.getProperty("user.home", "."), ".copilot");
	}
}

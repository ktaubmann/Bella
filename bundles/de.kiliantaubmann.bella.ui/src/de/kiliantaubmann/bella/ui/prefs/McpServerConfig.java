package de.kiliantaubmann.bella.ui.prefs;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * An MCP server Bella connects to, e.g. ARC-1. Tokens are kept in secure
 * storage, not here.
 *
 * @param id      short id used in tool names, e.g. {@code arc1}
 * @param name    display name
 * @param http    {@code true} for Streamable HTTP, {@code false} for stdio
 * @param url     endpoint for HTTP
 * @param command command line for stdio, e.g. {@code npx -y arc-1@latest}
 * @param enabled whether Bella uses it
 */
public record McpServerConfig(String id, String name, boolean http, String url, String command, boolean enabled) {

	public static List<McpServerConfig> parse(String json) {
		List<McpServerConfig> list = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return list;
		}
		try {
			JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
			for (JsonElement e : arr) {
				JsonObject o = e.getAsJsonObject();
				list.add(new McpServerConfig(Json.str(o, "id"), Json.str(o, "name"),
						!"stdio".equals(Json.str(o, "transport")), Json.str(o, "url"), Json.str(o, "command"),
						!o.has("enabled") || o.get("enabled").getAsBoolean()));
			}
		} catch (RuntimeException e) {
			// ignore corrupt preference
		}
		return list;
	}

	public static String toJson(List<McpServerConfig> servers) {
		JsonArray arr = new JsonArray();
		for (McpServerConfig s : servers) {
			JsonObject o = new JsonObject();
			o.addProperty("id", s.id());
			o.addProperty("name", s.name());
			o.addProperty("transport", s.http() ? "http" : "stdio");
			o.addProperty("url", s.url() == null ? "" : s.url());
			o.addProperty("command", s.command() == null ? "" : s.command());
			o.addProperty("enabled", s.enabled());
			arr.add(o);
		}
		return Json.GSON.toJson(arr);
	}
}

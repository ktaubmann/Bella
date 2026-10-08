package de.kiliantaubmann.bella.core.claudecode;

import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Reads one turn of the CLI's {@code --output-format stream-json} output and
 * turns it into listener calls. Unknown message types are ignored, so newer
 * CLI versions do not break Bella.
 * <p>
 * Tool calls are not reported from here: they reach Bella's MCP server, which
 * reports them with the real tool spec and result.
 */
final class StreamJson {

	private static final String TOOL_PREFIX = "mcp__" + ClaudeCli.MCP_SERVER_NAME + "__";

	private final ConversationListener listener;
	private final StringBuilder text = new StringBuilder();
	private int deltasSinceMessage;
	private String model;
	private String mcpStatus;
	private boolean done;
	private boolean error;
	private String subtype;
	private String resultText;
	private Usage usage = Usage.NONE;
	private JsonObject controlRequest;

	StreamJson(ConversationListener listener) {
		this.listener = listener;
	}

	/** @return {@code true} once the turn's {@code result} message was read */
	boolean accept(String line) {
		if (line == null || line.isBlank()) {
			return done;
		}
		JsonObject msg;
		try {
			JsonElement e = Json.parseStrict(line.trim());
			if (!e.isJsonObject()) {
				return done;
			}
			msg = e.getAsJsonObject();
		} catch (JsonParseException e) {
			return done; // e.g. a stray log line
		}
		String type = String.valueOf(Json.str(msg, "type"));
		if (Json.str(msg, "parent_tool_use_id") != null) {
			return done; // output of a sub-agent; Bella's sessions have none
		}
		switch (type) {
		case "system" -> system(msg);
		case "stream_event" -> streamEvent(Json.obj(msg, "event"));
		case "assistant" -> assistant(Json.obj(msg, "message"));
		case "result" -> result(msg);
		case "control_request" -> controlRequest = msg;
		default -> {
			// user (tool results echoed back), control_response, rate limit info …
		}
		}
		return done;
	}

	private void system(JsonObject msg) {
		if (!"init".equals(Json.str(msg, "subtype"))) {
			return;
		}
		if (Json.str(msg, "model") != null) {
			model = Json.str(msg, "model");
		}
		JsonArray servers = Json.arr(msg, "mcp_servers");
		if (servers != null) {
			for (JsonElement s : servers) {
				if (s.isJsonObject() && ClaudeCli.MCP_SERVER_NAME.equals(Json.str(s.getAsJsonObject(), "name"))) {
					mcpStatus = Json.str(s.getAsJsonObject(), "status");
				}
			}
		}
	}

	private void streamEvent(JsonObject event) {
		if (event == null) {
			return;
		}
		String type = Json.str(event, "type");
		if ("content_block_delta".equals(type)) {
			JsonObject delta = Json.obj(event, "delta");
			String kind = Json.str(delta, "type");
			if ("text_delta".equals(kind)) {
				String t = Json.str(delta, "text");
				if (t != null && !t.isEmpty()) {
					text.append(t);
					deltasSinceMessage++;
					listener.onText(t);
				}
			} else if ("thinking_delta".equals(kind)) {
				String t = Json.str(delta, "thinking");
				if (t != null && !t.isEmpty()) {
					listener.onThinking(t);
				}
			}
		} else if ("content_block_start".equals(type)) {
			JsonObject block = Json.obj(event, "content_block");
			if ("tool_use".equals(Json.str(block, "type"))) {
				listener.onToolUseStart(Json.str(block, "id"), toolName(Json.str(block, "name")));
			} else if ("text".equals(Json.str(block, "type")) && text.length() > 0
					&& text.charAt(text.length() - 1) != '\n') {
				// a later text block (after tool calls) is a new paragraph, not the end of the last sentence
				text.append("\n\n");
				listener.onText("\n\n");
			}
		} else if ("message_start".equals(type)) {
			String m = Json.str(Json.obj(event, "message"), "model");
			if (m != null) {
				model = m;
			}
		}
	}

	/**
	 * Complete assistant messages repeat what was streamed. Their text is only
	 * shown when no deltas arrived (older CLIs, or synthetic messages such as
	 * "please log in").
	 */
	private void assistant(JsonObject message) {
		if (message == null) {
			return;
		}
		if (Json.str(message, "model") != null && !"<synthetic>".equals(Json.str(message, "model"))) {
			model = Json.str(message, "model");
		}
		if (deltasSinceMessage == 0) {
			JsonArray content = Json.arr(message, "content");
			if (content != null) {
				for (JsonElement b : content) {
					JsonObject block = b.isJsonObject() ? b.getAsJsonObject() : null;
					if (block != null && "text".equals(Json.str(block, "type"))) {
						String t = Json.str(block, "text");
						if (t != null && !t.isEmpty()) {
							if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
								text.append("\n\n");
								listener.onText("\n\n");
							}
							text.append(t);
							listener.onText(t);
						}
					}
				}
			}
		}
		deltasSinceMessage = 0;
	}

	private void result(JsonObject msg) {
		done = true;
		subtype = Json.str(msg, "subtype");
		resultText = Json.str(msg, "result");
		JsonElement isError = msg.get("is_error");
		error = (isError != null && isError.isJsonPrimitive() && isError.getAsBoolean())
				|| (subtype != null && subtype.startsWith("error"));
		JsonObject u = Json.obj(msg, "usage");
		if (u != null) {
			usage = new Usage(Json.integer(u, "input_tokens", 0), Json.integer(u, "output_tokens", 0),
					Json.integer(u, "cache_read_input_tokens", 0), Json.integer(u, "cache_creation_input_tokens", 0));
		}
		if (!error && text.length() == 0 && resultText != null && !resultText.isEmpty()) {
			text.append(resultText);
			listener.onText(resultText);
		}
	}

	static String toolName(String name) {
		return name != null && name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
	}

	/**
	 * A notice key for the chat, {@code null} when the turn succeeded. Keys:
	 * {@code cc_login}, {@code cc_limit:<detail>}, {@code cc_error:<detail>}.
	 */
	String errorNotice() {
		if (!error) {
			return null;
		}
		String detail = resultText != null && !resultText.isBlank() ? resultText.trim() : String.valueOf(subtype);
		return classify(detail);
	}

	static String classify(String detail) {
		String d = detail.toLowerCase(Locale.ROOT);
		if (d.contains("/login") || d.contains("not logged in") || d.contains("authenticat") || d.contains("oauth")
				|| d.contains("api key") || d.contains("401")) {
			return "cc_login";
		}
		if (d.contains("limit") && (d.contains("usage") || d.contains("rate") || d.contains("reached"))) {
			return "cc_limit:" + detail;
		}
		return "cc_error:" + detail;
	}

	boolean done() {
		return done;
	}

	boolean isError() {
		return error;
	}

	String subtype() {
		return subtype;
	}

	String model() {
		return model;
	}

	/** Status of Bella's MCP server as reported in {@code system/init}, {@code null} if not reported. */
	String mcpStatus() {
		return mcpStatus;
	}

	String text() {
		return text.toString();
	}

	/** A request from the CLI that needs an answer, consumed on read. */
	JsonObject takeControlRequest() {
		JsonObject r = controlRequest;
		controlRequest = null;
		return r;
	}

	ChatResult toResult(String fallbackModel) {
		JsonArray content = new JsonArray();
		if (text.length() > 0) {
			content.add(Json.textBlock(text.toString()));
		}
		StopReason stop = error ? StopReason.OTHER : StopReason.END_TURN;
		return new ChatResult(Json.message("assistant", content), text.toString(), java.util.List.of(), stop,
				error ? resultText : null, model != null ? model : fallbackModel, usage);
	}
}

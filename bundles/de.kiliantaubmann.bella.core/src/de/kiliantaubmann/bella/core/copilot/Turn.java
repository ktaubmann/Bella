package de.kiliantaubmann.bella.core.copilot;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * One prompt turn of an ACP session: turns {@code session/update}
 * notifications into listener calls and decides permission requests.
 * Unknown update types are ignored, so newer CLI versions do not break Bella.
 */
final class Turn {

	/** Tool kinds Bella never lets the CLI run on its own. */
	private static final List<String> FORBIDDEN_KINDS = List.of("execute", "edit", "delete", "move", "fetch");
	private static final Pattern BELLA_TOOL = Pattern.compile("(^|[^a-z0-9])" + CopilotCli.MCP_SERVER_NAME
			+ "([-_/.:(\\s]|$)");

	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]+");

	private final ConversationListener listener;
	private final StringBuilder text = new StringBuilder();
	private volatile boolean finished;

	Turn(ConversationListener listener) {
		this.listener = listener;
	}

	void finish() {
		finished = true;
	}

	boolean finished() {
		return finished;
	}

	String text() {
		return text.toString();
	}

	void onUpdate(JsonObject params) {
		if (finished) {
			return;
		}
		JsonObject update = Json.obj(params, "update");
		String kind = Json.str(update, "sessionUpdate");
		if (kind == null) {
			return;
		}
		switch (kind) {
		case "agent_message_chunk" -> {
			String t = contentText(update);
			if (!t.isEmpty()) {
				text.append(t);
				listener.onText(t);
			}
		}
		case "agent_thought_chunk" -> {
			String t = contentText(update);
			if (!t.isEmpty()) {
				listener.onThinking(t);
			}
		}
		case "tool_call" -> listener.onToolUseStart(Json.str(update, "toolCallId"), Json.str(update, "title"));
		default -> {
			// tool_call_update, plan, available_commands_update, … – tool results come from Bella's MCP server
		}
		}
	}

	private static String contentText(JsonObject update) {
		JsonElement c = update.get("content");
		StringBuilder sb = new StringBuilder();
		if (c != null && c.isJsonObject()) {
			append(sb, c.getAsJsonObject());
		} else if (c != null && c.isJsonArray()) {
			for (JsonElement e : c.getAsJsonArray()) {
				if (e.isJsonObject()) {
					append(sb, e.getAsJsonObject());
				}
			}
		}
		return sb.toString();
	}

	private static void append(StringBuilder sb, JsonObject block) {
		if ("text".equals(Json.str(block, "type")) && Json.str(block, "text") != null) {
			sb.append(Json.str(block, "text"));
		}
	}

	/**
	 * Answer to {@code session/request_permission}: only tools of Bella's MCP
	 * server may run (Bella's own policy decides inside the server); shell,
	 * file edits, web access and anything unknown are rejected.
	 *
	 * @param isBellaToolName whether a name is one of the tools Bella offers;
	 *                        {@code null} when no Bella tools are attached (everything is rejected)
	 */
	static JsonObject decidePermission(JsonObject params, Predicate<String> isBellaToolName,
			Consumer<String> onDenied) {
		JsonObject toolCall = Json.obj(params, "toolCall");
		String kind = Json.str(toolCall, "kind");
		String title = String.valueOf(Json.str(toolCall, "title"));
		boolean allowed = isBellaToolName != null
				&& (kind == null || !FORBIDDEN_KINDS.contains(kind.toLowerCase(Locale.ROOT)))
				&& isBellaTool(toolCall, isBellaToolName);
		String optionId = pick(Json.arr(params, "options"), allowed ? "allow_once" : "reject_once");
		JsonObject outcome = new JsonObject();
		if (optionId == null) {
			outcome.addProperty("outcome", "cancelled");
		} else {
			outcome.addProperty("outcome", "selected");
			outcome.addProperty("optionId", optionId);
		}
		if (!allowed) {
			onDenied.accept(title);
		}
		JsonObject result = new JsonObject();
		result.add("outcome", outcome);
		return result;
	}

	static boolean isBellaTool(JsonObject toolCall, Predicate<String> isBellaToolName) {
		if (toolCall == null) {
			return false;
		}
		JsonObject raw = Json.obj(toolCall, "rawInput");
		for (String key : List.of("toolName", "tool", "name")) {
			String n = Json.str(raw, key);
			if (n != null && isBellaToolName.test(n)) {
				return true;
			}
		}
		JsonObject meta = Json.obj(toolCall, "_meta");
		for (String key : List.of("serverName", "mcpServer", "server")) {
			if (CopilotCli.MCP_SERVER_NAME.equals(Json.str(meta, key))) {
				return true;
			}
		}
		String title = Json.str(toolCall, "title");
		if (title == null) {
			return false;
		}
		if (BELLA_TOOL.matcher(title.toLowerCase(Locale.ROOT)).find()) {
			return true;
		}
		// Title may be just the tool name, e.g. "adt_read_source" or "adt_read_source(…)".
		java.util.regex.Matcher m = TOKEN.matcher(title);
		while (m.find()) {
			if (isBellaToolName.test(m.group())) {
				return true;
			}
		}
		return false;
	}

	private static String pick(JsonArray options, String wantedKind) {
		if (options == null) {
			return null;
		}
		String fallback = null;
		for (JsonElement e : options) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			String kind = String.valueOf(Json.str(o, "kind"));
			if (kind.equals(wantedKind)) {
				return Json.str(o, "optionId");
			}
			if (fallback == null && kind.startsWith(wantedKind.substring(0, wantedKind.indexOf('_')))) {
				fallback = Json.str(o, "optionId");
			}
		}
		return fallback;
	}

	/** Notice key for a JSON-RPC error, see {@code chat.notice.cp_*}. */
	static String notice(Acp.AcpException e) {
		if (e.isAuthRequired()) {
			return "cp_login";
		}
		String m = String.valueOf(e.getMessage());
		String l = m.toLowerCase(Locale.ROOT);
		if (l.contains("quota") || l.contains("rate limit") || l.contains("premium") || l.contains("limit reached")) {
			return "cp_limit:" + m;
		}
		return "cp_error:" + m;
	}

	static StopReason stopReason(String acp) {
		if (acp == null) {
			return StopReason.OTHER;
		}
		return switch (acp) {
		case "end_turn" -> StopReason.END_TURN;
		case "max_tokens" -> StopReason.MAX_TOKENS;
		case "refusal" -> StopReason.REFUSAL;
		default -> StopReason.OTHER;
		};
	}

	/** Token counts of an ACP {@code session/prompt} result; {@link Usage#NONE} when it has none. */
	static Usage usage(JsonObject promptResult) {
		JsonObject u = promptResult == null ? null : Json.obj(promptResult, "usage");
		if (u == null) {
			return Usage.NONE;
		}
		return new Usage(Json.integer(u, "inputTokens", 0), Json.integer(u, "outputTokens", 0),
				Json.integer(u, "cachedReadTokens", 0), Json.integer(u, "cachedWriteTokens", 0));
	}

	ChatResult result(JsonObject promptResult, String model) {
		return result(Json.str(promptResult, "stopReason"), usage(promptResult), model);
	}

	ChatResult result(String stopReason, String model) {
		return result(stopReason, Usage.NONE, model);
	}

	ChatResult result(String stopReason, Usage usage, String model) {
		JsonArray content = new JsonArray();
		if (text.length() > 0) {
			content.add(Json.textBlock(text.toString()));
		}
		return new ChatResult(Json.message("assistant", content), text.toString(), List.of(),
				stopReason(stopReason), null, model == null || model.isBlank() ? "copilot" : model, usage);
	}
}

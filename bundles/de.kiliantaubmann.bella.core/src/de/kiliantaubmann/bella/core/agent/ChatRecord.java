package de.kiliantaubmann.bella.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

/**
 * A chat as the chat history keeps it: what the developer saw (messages,
 * answers, tool calls, notices, answered questions), the package and request
 * it worked in, and for chats Bella runs against an API the exact model
 * history, so a chat can be shown again and continued.
 * <p>
 * Entries are JSON objects with a type {@code t}; an entry with the same key
 * {@code k} replaces the earlier one, so a streamed text segment is kept once
 * in its last state. Thread safe.
 */
public final class ChatRecord {

	/** Longest title, taken from the first message. */
	static final int TITLE_CHARS = 60;
	/** Kept of a tool's input and output; the chat shows them folded anyway. */
	static final int TOOL_INPUT_CHARS = 2_000;
	static final int TOOL_OUTPUT_CHARS = 2_000;
	static final int THINKING_CHARS = 8_000;
	/** Tools whose results are business data or debugger values: their output is not stored. */
	static final Set<String> DATA_TOOLS = Set.of("adt_table_contents", "debug_context", "adt_diagnose", "adt_http_send");
	static final String NOT_STORED = "(not stored: may contain business data)";

	private final String id;
	private final long created;
	private long updated;
	private String title = "";
	private String provider = "";
	private String devPackage = "";
	private String transport = "";
	private String mode = "";
	private final List<JsonObject> entries = new ArrayList<>();
	private List<JsonObject> modelHistory = List.of();

	public ChatRecord() {
		this(UUID.randomUUID().toString(), System.currentTimeMillis());
	}

	ChatRecord(String id, long created) {
		this.id = id;
		this.created = created;
		this.updated = created;
	}

	public String id() {
		return id;
	}

	public synchronized long created() {
		return created;
	}

	public synchronized long updated() {
		return updated;
	}

	public synchronized String title() {
		return title;
	}

	/** Conversation type and model the chat ran with, e.g. {@code ChatSession:claude-opus-4}. */
	public synchronized String provider() {
		return provider;
	}

	public synchronized String devPackage() {
		return devPackage;
	}

	public synchronized String transport() {
		return transport;
	}

	/** Name of the chat mode the chat was in, {@code ""} if unknown. */
	public synchronized String mode() {
		return mode;
	}

	public synchronized List<JsonObject> entries() {
		List<JsonObject> copy = new ArrayList<>(entries.size());
		entries.forEach(e -> copy.add(e.deepCopy()));
		return copy;
	}

	/** The model history of a chat Bella ran against an API, empty otherwise. */
	public synchronized List<JsonObject> modelHistory() {
		return modelHistory;
	}

	/** Whether the developer wrote anything; an empty chat is not kept. */
	public synchronized boolean isEmpty() {
		return entries.stream().noneMatch(e -> "user".equals(Json.str(e, "t")));
	}

	// ---- recording ------------------------------------------------------------------------

	/** A message of the developer, as the chat shows it (without the editor context sent along). */
	public synchronized void user(int messageId, String text) {
		if (title.isEmpty()) {
			title = titleOf(text);
		}
		JsonObject e = entry("user", "u" + messageId, messageId);
		e.addProperty("text", text);
		put(e);
	}

	/** Start of Bella's answer. */
	public synchronized void answer(int messageId) {
		put(entry("bella", "b" + messageId, messageId));
	}

	/** A text segment of an answer in its current state, as Markdown. */
	public synchronized void text(int messageId, int segment, String markdown) {
		JsonObject e = entry("text", messageId + "_" + segment, messageId);
		e.addProperty("seg", segment);
		e.addProperty("md", markdown);
		put(e);
	}

	public synchronized void thinking(int messageId, int segment, String text) {
		JsonObject e = entry("thinking", messageId + "_" + segment, messageId);
		e.addProperty("seg", segment);
		e.addProperty("text", clip(text, THINKING_CHARS));
		put(e);
	}

	/** A tool call; {@code state} is {@code ok}, {@code error} or {@code denied}, {@code null} while it runs. */
	public synchronized void tool(int messageId, int segment, String name, String input, String state,
			String output) {
		JsonObject e = entry("tool", messageId + "_" + segment, messageId);
		e.addProperty("seg", segment);
		e.addProperty("name", name);
		e.addProperty("input", clip(input == null ? "" : input, TOOL_INPUT_CHARS));
		if (state != null) {
			e.addProperty("state", state);
			e.addProperty("output", isDataTool(name) && "ok".equals(state) ? NOT_STORED
					: clip(output == null ? "" : output, TOOL_OUTPUT_CHARS));
		}
		put(e);
	}

	/** Questions of {@code ask_user} with the answers, {@code null} if not answered. */
	public synchronized void questions(int messageId, int segment, JsonObject questions, JsonArray answers) {
		JsonObject e = entry("ask", messageId + "_" + segment, messageId);
		e.addProperty("seg", segment);
		e.add("questions", questions == null ? new JsonObject() : questions.deepCopy());
		if (answers != null) {
			e.add("answers", answers.deepCopy());
		}
		put(e);
	}

	/** A notice below message {@code messageId}; {@code html} is escaped already. */
	public synchronized void notice(int messageId, String kind, String html) {
		JsonObject e = entry("notice", "n" + entries.size(), messageId);
		e.addProperty("kind", kind);
		e.addProperty("html", html);
		entries.add(e);
		updated = System.currentTimeMillis();
	}

	public synchronized void context(String provider, String devPackage, String transport, String mode) {
		this.provider = nz(provider);
		this.devPackage = nz(devPackage);
		this.transport = nz(transport);
		this.mode = nz(mode);
	}

	/** Keeps the model history; results of data tools are replaced by {@link #NOT_STORED}. */
	public synchronized void modelHistory(List<JsonObject> history) {
		this.modelHistory = history == null ? List.of() : withoutData(history);
	}

	static boolean isDataTool(String name) {
		return name != null && (DATA_TOOLS.contains(name) || name.endsWith("_SAPQuery"));
	}

	private static List<JsonObject> withoutData(List<JsonObject> history) {
		Set<String> dataCalls = new java.util.HashSet<>();
		List<JsonObject> out = new ArrayList<>(history.size());
		for (JsonObject message : history) {
			JsonArray content = Json.arr(message, "content");
			if (content == null) {
				out.add(message);
				continue;
			}
			JsonObject copy = null;
			for (int i = 0; i < content.size(); i++) {
				if (!content.get(i).isJsonObject()) {
					continue;
				}
				JsonObject block = content.get(i).getAsJsonObject();
				String type = Json.str(block, "type");
				if ("tool_use".equals(type) && isDataTool(Json.str(block, "name"))) {
					dataCalls.add(nz(Json.str(block, "id")));
				} else if ("tool_result".equals(type) && dataCalls.contains(nz(Json.str(block, "tool_use_id")))) {
					if (copy == null) {
						copy = message.deepCopy();
					}
					Json.arr(copy, "content").get(i).getAsJsonObject().addProperty("content", NOT_STORED);
				}
			}
			out.add(copy == null ? message : copy);
		}
		return List.copyOf(out);
	}

	private static JsonObject entry(String type, String key, int messageId) {
		JsonObject e = new JsonObject();
		e.addProperty("t", type);
		e.addProperty("k", key);
		e.addProperty("id", messageId);
		return e;
	}

	private void put(JsonObject e) {
		String key = Json.str(e, "k");
		for (int i = entries.size() - 1; i >= 0; i--) {
			if (key.equals(Json.str(entries.get(i), "k"))) {
				entries.set(i, e);
				updated = System.currentTimeMillis();
				return;
			}
		}
		entries.add(e);
		updated = System.currentTimeMillis();
	}

	/** The highest message id in the chat, so a continued chat goes on after it. */
	public synchronized int lastMessageId() {
		int max = 0;
		for (JsonObject e : entries) {
			max = Math.max(max, Json.integer(e, "id", 0));
		}
		return max;
	}

	// ---- continuing -----------------------------------------------------------------------

	/**
	 * The conversation as text for a model that cannot take the history over
	 * (Claude Code, Copilot, or another model): the developer's messages,
	 * Bella's answers, the tools used and the answered questions. The oldest
	 * part is cut if it is longer than {@code maxChars}.
	 */
	public synchronized String transcript(int maxChars) {
		StringBuilder sb = new StringBuilder();
		for (JsonObject e : entries) {
			switch (nz(Json.str(e, "t"))) {
			case "user" -> sb.append("\n\nDeveloper: ").append(nz(Json.str(e, "text")));
			case "bella" -> sb.append("\n\nBella:");
			case "text" -> sb.append('\n').append(nz(Json.str(e, "md")));
			case "tool" -> sb.append("\n[tool ").append(nz(Json.str(e, "name"))).append(": ")
					.append(Json.str(e, "state") == null ? "stopped" : Json.str(e, "state")).append(']');
			case "ask" -> appendAnswers(sb, e);
			default -> {
				// thinking and notices are not part of the conversation
			}
			}
		}
		String text = sb.toString().strip();
		if (text.length() <= maxChars) {
			return text;
		}
		String cut = text.substring(text.length() - maxChars);
		int nl = cut.indexOf('\n');
		return "[… earlier part of the conversation left out]\n" + (nl >= 0 ? cut.substring(nl + 1) : cut);
	}

	private static void appendAnswers(StringBuilder sb, JsonObject e) {
		JsonArray qs = Json.arr(Json.obj(e, "questions"), "questions");
		JsonArray as = Json.arr(e, "answers");
		if (qs == null) {
			return;
		}
		for (int i = 0; i < qs.size(); i++) {
			JsonObject q = qs.get(i).isJsonObject() ? qs.get(i).getAsJsonObject() : new JsonObject();
			sb.append("\n[asked the developer: ").append(nz(Json.str(q, "question"))).append(" → ");
			JsonObject a = as != null && i < as.size() && as.get(i).isJsonObject() ? as.get(i).getAsJsonObject() : null;
			if (a == null) {
				sb.append("not answered");
			} else {
				List<String> parts = new ArrayList<>();
				JsonArray chosen = Json.arr(a, "chosen");
				if (chosen != null) {
					chosen.forEach(c -> parts.add(c.getAsString()));
				}
				String other = Json.str(a, "other");
				if (other != null && !other.isBlank()) {
					parts.add(other);
				}
				sb.append(String.join(", ", parts));
			}
			sb.append(']');
		}
	}

	// ---- storing --------------------------------------------------------------------------

	/** Header fields first, so a listing can stop reading before the entries. */
	public synchronized JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("title", title);
		o.addProperty("created", created);
		o.addProperty("updated", updated);
		o.addProperty("provider", provider);
		o.addProperty("package", devPackage);
		o.addProperty("transport", transport);
		o.addProperty("mode", mode);
		JsonArray es = new JsonArray();
		entries.forEach(es::add);
		o.add("entries", es);
		JsonArray h = new JsonArray();
		modelHistory.forEach(h::add);
		o.add("modelHistory", h);
		return o;
	}

	public static ChatRecord fromJson(JsonObject o) {
		String id = Json.str(o, "id");
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("chat without id");
		}
		ChatRecord r = new ChatRecord(id, longOf(o, "created"));
		r.title = nz(Json.str(o, "title"));
		r.provider = nz(Json.str(o, "provider"));
		r.devPackage = nz(Json.str(o, "package"));
		r.transport = nz(Json.str(o, "transport"));
		r.mode = nz(Json.str(o, "mode"));
		JsonArray es = Json.arr(o, "entries");
		if (es != null) {
			for (JsonElement e : es) {
				if (e.isJsonObject()) {
					r.entries.add(e.getAsJsonObject());
				}
			}
		}
		JsonArray h = Json.arr(o, "modelHistory");
		List<JsonObject> history = new ArrayList<>();
		if (h != null) {
			for (JsonElement e : h) {
				if (e.isJsonObject()) {
					history.add(e.getAsJsonObject());
				}
			}
		}
		r.modelHistory = List.copyOf(history);
		r.updated = Math.max(r.created, longOf(o, "updated"));
		return r;
	}

	static long longOf(JsonObject o, String key) {
		JsonElement e = o.get(key);
		try {
			return e == null || !e.isJsonPrimitive() ? 0 : e.getAsLong();
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	static String titleOf(String text) {
		String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
		return t.length() <= TITLE_CHARS ? t : t.substring(0, TITLE_CHARS - 1).strip() + "…";
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "\n…";
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}

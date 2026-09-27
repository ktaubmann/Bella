package de.kiliantaubmann.bella.core.util;

import java.io.IOException;
import java.io.StringReader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Small Gson helpers shared by the providers and the MCP client. */
public final class Json {

	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {
	}

	public static JsonObject parseObject(String text) {
		JsonElement e = JsonParser.parseString(text);
		if (!e.isJsonObject()) {
			throw new JsonParseException("expected a JSON object");
		}
		return e.getAsJsonObject();
	}

	/**
	 * Parses a complete JSON document strictly. Gson's default parser is
	 * lenient and silently accepts truncated or malformed input, which is exactly
	 * what streamed tool arguments can produce, so tool input goes through here.
	 */
	@SuppressWarnings("deprecation")
	public static JsonElement parseStrict(String text) throws JsonParseException {
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.setLenient(false);
			JsonElement element = JsonParser.parseReader(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new JsonParseException("trailing content after JSON value");
			}
			return element;
		} catch (IOException e) {
			throw new JsonParseException(e);
		}
	}

	public static String str(JsonObject o, String key) {
		if (o == null) {
			return null;
		}
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
	}

	public static JsonObject obj(JsonObject o, String key) {
		if (o == null) {
			return null;
		}
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	public static JsonArray arr(JsonObject o, String key) {
		if (o == null) {
			return null;
		}
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	public static int integer(JsonObject o, String key, int fallback) {
		if (o == null) {
			return fallback;
		}
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return e.getAsInt();
	}

	public static JsonObject textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		return block;
	}

	public static JsonObject message(String role, JsonArray content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.add("content", content);
		return m;
	}

	public static JsonObject userText(String text) {
		JsonArray content = new JsonArray();
		content.add(textBlock(text));
		return message("user", content);
	}
}

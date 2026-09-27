package de.kiliantaubmann.bella.core.tools;

import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Shallow JSON-schema check for tool input: the input is an object, required
 * properties are present and top-level property types match. Streamed tool
 * input is not validated by the API, so this runs before every tool call.
 */
public final class SchemaCheck {

	private SchemaCheck() {
	}

	/** @return an error message, or {@code null} when the input is acceptable */
	public static String validate(JsonObject schema, JsonElement input) {
		if (input == null || !input.isJsonObject()) {
			return "input must be a JSON object";
		}
		JsonObject obj = input.getAsJsonObject();
		JsonElement required = schema.get("required");
		if (required != null && required.isJsonArray()) {
			for (JsonElement r : required.getAsJsonArray()) {
				String name = r.getAsString();
				if (!obj.has(name) || obj.get(name).isJsonNull()) {
					return "missing required property '" + name + "'";
				}
			}
		}
		JsonElement props = schema.get("properties");
		if (props != null && props.isJsonObject()) {
			for (Map.Entry<String, JsonElement> p : props.getAsJsonObject().entrySet()) {
				JsonElement value = obj.get(p.getKey());
				if (value == null || value.isJsonNull() || !p.getValue().isJsonObject()) {
					continue;
				}
				JsonElement type = p.getValue().getAsJsonObject().get("type");
				if (type != null && !matchesType(type, value)) {
					return "property '" + p.getKey() + "' has the wrong type, expected " + type;
				}
			}
		}
		return null;
	}

	private static boolean matchesType(JsonElement type, JsonElement value) {
		if (type.isJsonArray()) {
			for (JsonElement t : type.getAsJsonArray()) {
				if (matchesType(t, value)) {
					return true;
				}
			}
			return false;
		}
		String t = type.getAsString();
		return switch (t) {
		case "string" -> value.isJsonPrimitive() && ((JsonPrimitive) value).isString();
		case "integer" -> value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber()
				&& value.getAsDouble() == Math.rint(value.getAsDouble());
		case "number" -> value.isJsonPrimitive() && ((JsonPrimitive) value).isNumber();
		case "boolean" -> value.isJsonPrimitive() && ((JsonPrimitive) value).isBoolean();
		case "array" -> value instanceof JsonArray;
		case "object" -> value.isJsonObject();
		default -> true;
		};
	}
}

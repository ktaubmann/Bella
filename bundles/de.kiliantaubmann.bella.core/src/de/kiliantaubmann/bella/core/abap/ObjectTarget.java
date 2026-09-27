package de.kiliantaubmann.bella.core.abap;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolSpec;

/**
 * The repository object a tool call is about, derived from the tool input.
 * Used to recognise writes aimed at an object that is open in an editor.
 *
 * @param name ABAP object name, upper case (e.g. {@code ZCL_FOO}, {@code /ABC/CL_BAR})
 * @param type ADT type such as {@code CLAS}, {@code PROG}; may be {@code null}
 */
public record ObjectTarget(String name, String type) {

	private static final List<String> NAME_KEYS = List.of("name", "objectName", "object_name", "object", "className",
			"class_name", "programName", "program");
	private static final List<String> TYPE_KEYS = List.of("type", "objectType", "object_type");
	private static final List<String> URI_KEYS = List.of("uri", "objectUri", "object_uri", "url", "adtUri", "adt_uri");
	private static final List<String> SOURCE_KEYS = List.of("source", "source_code", "sourceCode", "content", "code");

	private static final Map<String, String> URI_TYPES = Map.of(
			"oo/classes", "CLAS",
			"oo/interfaces", "INTF",
			"programs/programs", "PROG",
			"programs/includes", "INCL",
			"functions/groups", "FUGR",
			"ddic/ddl/sources", "DDLS",
			"bo/behaviordefinitions", "BDEF");

	private static final Pattern ADT_URI = Pattern
			.compile("/sap/bc/adt/((?:oo|programs|functions|ddic/ddl|bo)/[a-z]+)/([^/?#]+)(?:/fmodules/([^/?#]+))?");

	public static Optional<ObjectTarget> fromAdtUri(String uri) {
		if (uri == null) {
			return Optional.empty();
		}
		Matcher m = ADT_URI.matcher(uri);
		if (!m.find()) {
			return Optional.empty();
		}
		String type = URI_TYPES.get(m.group(1));
		String name = decode(m.group(2));
		if (m.group(3) != null) {
			return Optional.of(new ObjectTarget(decode(m.group(3)), "FUNC"));
		}
		return Optional.of(new ObjectTarget(name, type));
	}

	private static String decode(String s) {
		return URLDecoder.decode(s, StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
	}

	public static Optional<ObjectTarget> fromToolInput(JsonObject input) {
		if (input == null) {
			return Optional.empty();
		}
		for (String k : URI_KEYS) {
			Optional<ObjectTarget> t = fromAdtUri(string(input, k));
			if (t.isPresent()) {
				return t;
			}
		}
		String name = firstString(input, NAME_KEYS);
		if (name == null || name.isBlank()) {
			return Optional.empty();
		}
		String type = firstString(input, TYPE_KEYS);
		return Optional.of(new ObjectTarget(name.trim().toUpperCase(Locale.ROOT),
				type == null ? null : normaliseType(type)));
	}

	/** {@code CLAS/OC} → {@code CLAS}; {@code class} → {@code CLAS}. */
	static String normaliseType(String type) {
		String t = type.trim().toUpperCase(Locale.ROOT);
		int slash = t.indexOf('/');
		if (slash > 0) {
			t = t.substring(0, slash);
		}
		return switch (t) {
		case "CLASS" -> "CLAS";
		case "INTERFACE" -> "INTF";
		case "PROGRAM", "REPORT" -> "PROG";
		case "INCLUDE" -> "INCL";
		case "FUNCTION", "FUNCTION_MODULE" -> "FUNC";
		default -> t;
		};
	}

	public static String sourceFromToolInput(JsonObject input) {
		return input == null ? null : firstString(input, SOURCE_KEYS);
	}

	/**
	 * Whether a call writes source code: Bella's {@code adt_write_source}, or a
	 * write tool of an MCP server (ARC-1's SAPWrite) that carries source code
	 * and is not a delete.
	 */
	public static boolean isSourceWrite(ToolSpec tool, JsonObject input) {
		if (Capability.WRITE_SOURCE.equals(tool.capability())) {
			String action = firstString(input, List.of("action", "operation", "mode"));
			if (action != null && action.toLowerCase(Locale.ROOT).contains("delete")) {
				return false;
			}
			return sourceFromToolInput(input) != null;
		}
		return false;
	}

	/** Whether this target denotes the given object (name compare, type compare only if both known). */
	public boolean matches(String otherName, String otherType) {
		if (otherName == null || !name.equalsIgnoreCase(otherName.trim())) {
			return false;
		}
		return type == null || otherType == null || type.equalsIgnoreCase(normaliseType(otherType));
	}

	private static String firstString(JsonObject o, List<String> keys) {
		for (String k : keys) {
			String v = string(o, k);
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}

package de.kiliantaubmann.bella.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Decides per tool call whether it runs automatically, needs the user's
 * confirmation, or is refused. User rules are evaluated first, then the
 * built-in defaults: reading runs automatically, writing and activating asks,
 * releasing transports is refused.
 */
public final class ToolPolicy {

	public enum Decision {
		AUTO, CONFIRM, DENY
	}

	/** A glob rule on the tool name; {@code *} matches any run of characters. */
	public record Rule(String glob, Decision decision) {

		boolean matches(String toolName) {
			return toRegex(glob).matcher(toolName).matches();
		}

		private static Pattern toRegex(String glob) {
			StringBuilder sb = new StringBuilder();
			for (char c : glob.toCharArray()) {
				switch (c) {
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				default -> sb.append(Pattern.quote(String.valueOf(c)));
				}
			}
			return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
		}
	}

	/** Defaults, first match wins. */
	public static final List<Rule> DEFAULT_RULES = List.of(
			new Rule("*transport_release*", Decision.DENY),
			new Rule("*release_transport*", Decision.DENY),
			new Rule("adt_write_source", Decision.CONFIRM),
			new Rule("adt_create_object", Decision.CONFIRM),
			new Rule("adt_activate", Decision.CONFIRM),
			new Rule("adt_*", Decision.AUTO),
			new Rule("mcp_*SAPRead", Decision.AUTO),
			new Rule("mcp_*SAPSearch", Decision.AUTO),
			new Rule("mcp_*SAPNavigate", Decision.AUTO),
			new Rule("mcp_*SAPContext", Decision.AUTO),
			new Rule("mcp_*SAPLint", Decision.AUTO),
			new Rule("mcp_*SAPDiagnose", Decision.AUTO));

	private static final List<String> ACTION_KEYS = List.of("action", "operation", "op", "type", "mode");

	private final List<Rule> userRules;

	public ToolPolicy(List<Rule> userRules) {
		this.userRules = List.copyOf(userRules);
	}

	public static ToolPolicy defaults() {
		return new ToolPolicy(List.of());
	}

	/**
	 * Parses rules from preference text, one {@code pattern=DECISION} per line;
	 * blank lines and lines starting with {@code #} are ignored.
	 */
	public static List<Rule> parseRules(String text) {
		List<Rule> rules = new ArrayList<>();
		if (text == null) {
			return rules;
		}
		for (String raw : text.split("\\R")) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			int eq = line.lastIndexOf('=');
			if (eq <= 0) {
				continue;
			}
			try {
				Decision d = Decision.valueOf(line.substring(eq + 1).trim().toUpperCase(Locale.ROOT));
				rules.add(new Rule(line.substring(0, eq).trim(), d));
			} catch (IllegalArgumentException e) {
				// ignore malformed line
			}
		}
		return rules;
	}

	public Decision decide(ToolSpec tool, JsonObject input) {
		if (releasesTransport(tool, input)) {
			return Decision.DENY;
		}
		for (Rule r : userRules) {
			if (r.matches(tool.name())) {
				return r.decision();
			}
		}
		for (Rule r : DEFAULT_RULES) {
			if (r.matches(tool.name())) {
				return r.decision();
			}
		}
		return tool.kind() == ToolSpec.Kind.READ ? Decision.AUTO : Decision.CONFIRM;
	}

	/**
	 * Multi-purpose transport tools (ARC-1's SAPTransport) take the operation
	 * as an argument, so the tool name alone cannot tell a harmless listing
	 * from a release. Releasing a transport is always refused.
	 */
	static boolean releasesTransport(ToolSpec tool, JsonObject input) {
		if (!tool.name().toLowerCase(Locale.ROOT).contains("transport") || input == null) {
			return false;
		}
		for (Map.Entry<String, JsonElement> e : input.entrySet()) {
			if (ACTION_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT)) && e.getValue().isJsonPrimitive()
					&& e.getValue().getAsString().toLowerCase(Locale.ROOT).contains("release")) {
				return true;
			}
		}
		return false;
	}
}

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
 * releasing transports is refused. The {@link ChatMode} applies last: plan
 * mode refuses everything that is not read only, the free modes run what
 * would ask (see {@link #decide}).
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
			// table contents leave the system for the model provider: ask first
			new Rule("adt_table_contents", Decision.CONFIRM),
			new Rule("mcp_*SAPQuery", Decision.CONFIRM),
			new Rule("adt_*", Decision.AUTO),
			new Rule("mcp_*SAPRead", Decision.AUTO),
			new Rule("mcp_*SAPSearch", Decision.AUTO),
			new Rule("mcp_*SAPNavigate", Decision.AUTO),
			new Rule("mcp_*SAPContext", Decision.AUTO),
			new Rule("mcp_*SAPLint", Decision.AUTO),
			new Rule("mcp_*SAPDiagnose", Decision.AUTO));

	private static final List<String> ACTION_KEYS = List.of("action", "operation", "op", "type", "mode");

	private final List<Rule> userRules;
	private final ChatMode mode;

	public ToolPolicy(List<Rule> userRules) {
		this(userRules, ChatMode.NORMAL);
	}

	public ToolPolicy(List<Rule> userRules, ChatMode mode) {
		this.userRules = List.copyOf(userRules);
		this.mode = mode == null ? ChatMode.NORMAL : mode;
	}

	/** The same rules in another chat mode. */
	public ToolPolicy withMode(ChatMode newMode) {
		return new ToolPolicy(userRules, newMode);
	}

	public ChatMode mode() {
		return mode;
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
		Decision configured = configured(tool, input);
		if (configured == Decision.DENY || blockedByPlan(tool)) {
			return Decision.DENY;
		}
		if (configured != Decision.CONFIRM) {
			return configured;
		}
		boolean data = Capability.TABLE_CONTENTS.equals(tool.capability());
		boolean runs = switch (mode) {
		case AUTO -> true;
		case ACTIVATE -> tool.kind() == ToolSpec.Kind.WRITE && !data;
		case READ_DATA -> data;
		default -> false;
		};
		return runs ? Decision.AUTO : Decision.CONFIRM;
	}

	/**
	 * In suggest mode a tool that is not read only may only run as far as the
	 * write guard turns it into a proposal in the open editor; anything else
	 * is refused (see {@link #refusal}).
	 */
	public boolean editorOnly(ToolSpec tool) {
		return mode == ChatMode.SUGGEST && tool.kind() != ToolSpec.Kind.READ;
	}

	private Decision configured(ToolSpec tool, JsonObject input) {
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

	/** Plan mode lets only tools through that are known to be read only. */
	private boolean blockedByPlan(ToolSpec tool) {
		return mode == ChatMode.PLAN && tool.kind() != ToolSpec.Kind.READ;
	}

	/** What the model is told when {@link #decide} refused a call. */
	public String refusal(ToolSpec tool) {
		if (blockedByPlan(tool)) {
			return "Refused in plan mode: Bella does not change anything in this mode. Do not retry; describe the change in "
					+ "your plan instead.";
		}
		if (editorOnly(tool)) {
			return "Refused in suggest mode: nothing is saved, created or activated in the SAP system. Do not retry; "
					+ "propose the change in the open editor (adt_write_source) or as a code block.";
		}
		return "Refused by Bella's tool policy. Do not retry this call; tell the developer.";
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

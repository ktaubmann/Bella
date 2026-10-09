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
 * would ask (see {@link #decide}), except for rules with {@link Decision#ASK},
 * which ask in every mode.
 */
public final class ToolPolicy {

	public enum Decision {
		AUTO, CONFIRM, DENY,
		/**
		 * Asks in every chat mode, also in Automode; for actions that are hard
		 * to undo (deleting, transports, Git, system settings). {@link #decide}
		 * reports it as {@link #CONFIRM}.
		 */
		ASK
	}

	/**
	 * A glob rule on the tool name, optionally followed by {@code :action} to
	 * match only calls whose action argument ({@code action}, {@code operation}
	 * …) matches; {@code *} matches any run of characters.
	 */
	public record Rule(String glob, Decision decision) {

		boolean matches(String toolName) {
			return matches(toolName, null);
		}

		boolean matches(String toolName, JsonObject input) {
			int colon = glob.indexOf(':');
			if (colon < 0) {
				return toRegex(glob).matcher(toolName).matches();
			}
			if (!toRegex(glob.substring(0, colon)).matcher(toolName).matches()) {
				return false;
			}
			String action = action(input);
			if (action == null) {
				action = DEFAULT_ACTIONS.get(toolName.toLowerCase(Locale.ROOT));
			}
			return action != null && toRegex(glob.substring(colon + 1)).matcher(action).matches();
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
			// hard to undo or system wide: ask in every chat mode
			new Rule("adt_delete_object", Decision.ASK),
			new Rule("adt_transport_manage", Decision.ASK),
			new Rule("adt_git_write", Decision.ASK),
			new Rule("adt_package_manage:delete", Decision.ASK),
			new Rule("adt_trace_control", Decision.ASK),
			new Rule("adt_http_send", Decision.ASK),
			new Rule("adt_format_settings", Decision.ASK),
			// the developer names package and transport request; the call shows what the model understood
			new Rule("adt_dev_package:set", Decision.ASK),
			// stepping on may run COMMIT WORK in the developer's session
			new Rule("debug_step", Decision.ASK),
			// the same actions through ARC-1
			new Rule("mcp_*SAPWrite:delete*", Decision.ASK),
			new Rule("mcp_*SAPTransport:create", Decision.ASK),
			new Rule("mcp_*SAPTransport:delete", Decision.ASK),
			new Rule("mcp_*SAPTransport:reassign", Decision.ASK),
			new Rule("mcp_*SAPTransport:remove_object", Decision.ASK),
			new Rule("mcp_*SAPGit:clone", Decision.ASK),
			new Rule("mcp_*SAPGit:pull", Decision.ASK),
			new Rule("mcp_*SAPGit:push", Decision.ASK),
			new Rule("mcp_*SAPGit:stage", Decision.ASK),
			new Rule("mcp_*SAPGit:switch_branch", Decision.ASK),
			new Rule("mcp_*SAPManage:delete_package", Decision.ASK),
			new Rule("adt_write_source", Decision.CONFIRM),
			new Rule("adt_create_object", Decision.CONFIRM),
			new Rule("adt_activate", Decision.CONFIRM),
			new Rule("adt_write_text_elements", Decision.CONFIRM),
			// table contents leave the system for the model provider: ask first
			new Rule("adt_table_contents", Decision.CONFIRM),
			new Rule("adt_diagnose:authorization_trace", Decision.CONFIRM),
			// variable values leave the system for the model provider, as table contents do
			new Rule("debug_context", Decision.CONFIRM),
			new Rule("debug_breakpoint", Decision.CONFIRM),
			// the answer of a service is business data too
			new Rule("adt_diagnose:odata_request", Decision.CONFIRM),
			new Rule("mcp_*SAPQuery", Decision.CONFIRM),
			new Rule("adt_*", Decision.AUTO),
			new Rule("mcp_*SAPRead", Decision.AUTO),
			new Rule("mcp_*SAPSearch", Decision.AUTO),
			new Rule("mcp_*SAPNavigate", Decision.AUTO),
			new Rule("mcp_*SAPContext", Decision.AUTO),
			new Rule("mcp_*SAPLint", Decision.AUTO),
			new Rule("mcp_*SAPDiagnose", Decision.AUTO));

	private static final List<String> ACTION_KEYS = List.of("action", "operation", "op", "type", "mode");

	/** The action argument of a multi-purpose tool call, or {@code null}. */
	static String action(JsonObject input) {
		if (input == null) {
			return null;
		}
		for (String key : List.of("action", "operation", "op")) {
			JsonElement e = input.get(key);
			if (e != null && e.isJsonPrimitive()) {
				// the tools trim the action before dispatching, so the rules must see it the same way
				return e.getAsString().trim();
			}
		}
		return null;
	}

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
	 * Tools that were merged or renamed, old name → the rules that match the same calls now. User rules
	 * written for the old names keep their effect instead of silently matching nothing; a DENY on short
	 * dumps, for example, must still hold for adt_diagnose 'short_dumps'. Each old tool maps to exactly the
	 * actions it covered, so a rule never reaches further than it did.
	 */
	static final Map<String, List<String>> RENAMED = Map.of(
			"adt_short_dumps", List.of("adt_diagnose:short_dumps"),
			"adt_where_used", List.of("adt_navigate:references"),
			"adt_transport_info", List.of("adt_transports:for_object"),
			"adt_list_transports", List.of("adt_transports:list", "adt_transports:layers", "adt_transports:targets"),
			"adt_settings_write", List.of("adt_format_settings"));

	/** The action a multi-purpose tool runs when the call names none. */
	static final Map<String, String> DEFAULT_ACTIONS = Map.of("adt_transports", "list");

	/**
	 * Parses rules from preference text, one {@code pattern=DECISION} per line;
	 * blank lines and lines starting with {@code #} are ignored. Rules naming merged tools, also through a
	 * wildcard such as {@code adt_short_dump*}, get rules for the tools that do the same now.
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
				String glob = line.substring(0, eq).trim();
				List<String> renamed = RENAMED.get(glob.toLowerCase(Locale.ROOT));
				if (renamed != null) {
					renamed.forEach(g -> rules.add(new Rule(g, d)));
					continue;
				}
				rules.add(new Rule(glob, d));
				if (glob.indexOf(':') < 0 && glob.contains("*")) {
					// a wildcard that covered a merged tool covers its new place too
					Pattern old = Rule.toRegex(glob);
					RENAMED.forEach((name, now) -> {
						if (old.matcher(name).matches()) {
							now.forEach(g -> rules.add(new Rule(g, d)));
						}
					});
				}
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
		if (configured == Decision.ASK) {
			return Decision.CONFIRM;
		}
		if (configured != Decision.CONFIRM) {
			return configured;
		}
		boolean data = Capability.TABLE_CONTENTS.equals(tool.capability())
				|| Capability.DEBUG_STATE.equals(tool.capability());
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
			if (r.matches(tool.name(), input)) {
				return r.decision();
			}
		}
		for (Rule r : DEFAULT_RULES) {
			if (r.matches(tool.name(), input)) {
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
		if (tool.name().equals("debug_step") && (blockedByPlan(tool) || editorOnly(tool))) {
			return "Refused in this chat mode: stepping or resuming runs the program, which may change data. Do not "
					+ "retry; tell the developer to step in the debugger or to switch the chat mode.";
		}
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

package de.kiliantaubmann.bella.core.lint;

import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/** The {@code abap_lint} tool: Bella's style check, needs no SAP system. */
public final class LintToolProvider implements ToolProvider {

	private final Supplier<NamingRules> naming;

	public LintToolProvider() {
		this(() -> NamingRules.NONE);
	}

	/** @param naming the project's naming rules, checked as rule {@code naming} */
	public LintToolProvider(Supplier<NamingRules> naming) {
		this.naming = naming;
	}

	@Override
	public String id() {
		return ToolRegistry.LINT_PROVIDER_ID;
	}

	@Override
	public String displayName() {
		return "Bella style check";
	}

	@Override
	public List<ToolSpec> listTools() {
		JsonObject source = new JsonObject();
		source.addProperty("type", "string");
		source.addProperty("description", "ABAP code to check.");
		JsonObject props = new JsonObject();
		props.add("source", source);
		JsonObject release = new JsonObject();
		release.addProperty("type", "string");
		release.addProperty("description", "SAP_BASIS release of the target system, e.g. 750 (from adt_list_systems); "
				+ "reports syntax the release does not know yet.");
		props.add("release", release);
		JsonObject cloud = new JsonObject();
		cloud.addProperty("type", "boolean");
		cloud.addProperty("description", "true for ABAP Cloud: classic statements, lists, dynpros and non-strict SQL "
				+ "become errors.");
		props.add("cloud", cloud);
		JsonObject fix = new JsonObject();
		fix.addProperty("type", "boolean");
		fix.addProperty("description", "true to also return the code with the fixes that have one correct form "
				+ "applied (obsolete statements, NOT … IS, RECEIVING, xsdbool( ), ELSE after RETURN, layout); write it "
				+ "back yourself.");
		props.add("fix", fix);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("source");
		schema.add("required", required);
		return List.of(ToolSpec.of("abap_lint",
				"Bella's style check for ABAP code, without SAP access, after abaplint and Clean ABAP: obsolete "
						+ "statements, SQL performance and strict SQL, unchecked sy-subrc, error handling, security "
						+ "(authority checks, dynamic SQL, RFC errors), unused or unreachable code, size and nesting, "
						+ "readability, texts without text symbols, the project's naming rules and modern forms. With "
						+ "'release' or 'cloud' also syntax the target system does not have; CDS sources get CDS rules. "
						+ "Run it on code you write and fix the errors and warnings; 'fix' rewrites the mechanical ones.",
				schema, null, ToolSpec.Kind.READ));
	}

	@Override
	public ToolResult call(String name, JsonObject input, CancelToken cancel) {
		if (!"abap_lint".equals(name)) {
			return ToolResult.error("Unknown tool " + name);
		}
		String source = Json.str(input, "source");
		if (source == null || source.isBlank()) {
			return ToolResult.error("No source given.");
		}
		AbapLint.Target target = AbapLint.Target.of(Json.str(input, "release"), flag(input, "cloud"));
		if (!flag(input, "fix")) {
			return ToolResult.ok(AbapLint.format(AbapLint.check(source, naming.get(), target)));
		}
		AbapLintFix.Result fixed = AbapLintFix.fix(source, target);
		if (fixed.applied().isEmpty()) {
			return ToolResult.ok("Nothing to fix mechanically.\n\n" + AbapLint.format(AbapLint.check(source, naming.get(),
					target)));
		}
		return ToolResult.ok("Fixed " + fixed.applied().size() + " findings (not saved; write the code back yourself):\n"
				+ String.join("\n", fixed.applied()) + "\n\n```abap\n" + fixed.source() + "\n```\n\nRemaining findings:\n"
				+ AbapLint.format(AbapLint.check(fixed.source(), naming.get(), target)));
	}

	private static boolean flag(JsonObject input, String name) {
		return input.has(name) && input.get(name).isJsonPrimitive() && input.get(name).getAsBoolean();
	}
}

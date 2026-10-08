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
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("source");
		schema.add("required", required);
		return List.of(ToolSpec.of("abap_lint",
				"Bella's style check for ABAP code, without SAP access: obsolete statements (MOVE, CALL METHOD, "
						+ "CREATE OBJECT, header lines, FORM …), SELECT *, SELECT in loops, SELECT … ENDSELECT, unchecked "
						+ "SELECT SINGLE, CATCH cx_root, empty CATCH, break-points and aborting messages, texts without text "
						+ "symbols, unused variables, unreachable code, repeated ELSEIF conditions, BEGIN/END OF names, deep "
						+ "nesting, long or complex methods, missing @ in strict Open SQL, long lines and keyword case, the "
						+ "project's naming rules and modern forms (inline declarations, xsdbool, line_exists, CORRESPONDING, "
						+ "RAISE EXCEPTION NEW). With 'release' or 'cloud' also syntax the target system does not have. "
						+ "CDS data definitions get CDS rules (obsolete DDIC-based views, association names). Run it on code "
						+ "you write and fix the findings that apply.",
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
		boolean cloud = input.has("cloud") && input.get("cloud").isJsonPrimitive() && input.get("cloud").getAsBoolean();
		return ToolResult.ok(AbapLint.format(AbapLint.check(source, naming.get(),
				AbapLint.Target.of(Json.str(input, "release"), cloud))));
	}
}

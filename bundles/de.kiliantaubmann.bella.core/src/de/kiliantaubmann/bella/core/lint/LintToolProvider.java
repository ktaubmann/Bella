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
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("source");
		schema.add("required", required);
		return List.of(ToolSpec.of("abap_lint",
				"Bella's style check for ABAP code, without SAP access: obsolete statements (MOVE, CALL METHOD, "
						+ "CREATE OBJECT, header lines, FORM …), SELECT *, SELECT in loops, SELECT … ENDSELECT, unchecked "
						+ "SELECT SINGLE, CATCH cx_root, empty CATCH, break-points and aborting messages, and the project's "
						+ "naming rules. Run it on code you write and fix the findings that apply.",
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
		return ToolResult.ok(AbapLint.format(AbapLint.check(source, naming.get())));
	}
}

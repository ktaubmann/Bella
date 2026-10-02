package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * Bella's own SAP tools, executed through the developer's ADT logon. No
 * extra server or credentials are needed.
 */
public final class AdtToolProvider implements ToolProvider {

	private final AdtBackend backend;
	private final Supplier<String> defaultDestination;

	/**
	 * @param defaultDestination destination of the active editor, used when the
	 *                           model does not name a system; may return {@code null}
	 */
	public AdtToolProvider(AdtBackend backend, Supplier<String> defaultDestination) {
		this.backend = backend;
		this.defaultDestination = defaultDestination;
	}

	@Override
	public String id() {
		return ToolRegistry.ADT_PROVIDER_ID;
	}

	@Override
	public String displayName() {
		return "ABAP Development Tools";
	}

	// ---- schemas -------------------------------------------------------------

	private static JsonObject schema(String[] required, String... props) {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		JsonObject p = new JsonObject();
		for (int i = 0; i + 2 < props.length; i += 3) {
			JsonObject prop = new JsonObject();
			prop.addProperty("type", props[i + 1]);
			prop.addProperty("description", props[i + 2]);
			p.add(props[i], prop);
		}
		s.add("properties", p);
		JsonArray req = new JsonArray();
		for (String r : required) {
			req.add(r);
		}
		s.add("required", req);
		return s;
	}

	private static final String SYSTEM_DESC = "ABAP project / system id to use. Omit to use the system of the active editor.";
	private static final String NAME_DESC = "Object name, e.g. ZCL_SALES_ORDER.";
	private static final String TYPE_DESC = "Object type: CLAS, INTF, PROG, INCL, FUGR (function group), FUNC (function module), TABL (table or structure), DTEL, DOMA, TTYP, MSAG, DDLS, BDEF, SRVD. Omit if unknown.";

	private static String[] objectProps(String... extra) {
		List<String> p = new ArrayList<>(List.of("name", "string", NAME_DESC, "type", "string", TYPE_DESC, "system",
				"string", SYSTEM_DESC));
		p.addAll(List.of(extra));
		return p.toArray(String[]::new);
	}

	@Override
	public List<ToolSpec> listTools() {
		List<ToolSpec> t = new ArrayList<>();
		t.add(ToolSpec.of("adt_list_systems", "List the ABAP projects (SAP systems) in the workspace and whether they are logged on.",
				schema(new String[0]), null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_search_objects",
				"Search repository objects by name pattern (wildcard *), e.g. ZCL_SALES*. Returns name, type, package and description.",
				schema(new String[] { "query" }, "query", "string", "Name pattern, * as wildcard.", "type", "string",
						"Optional object type filter, e.g. CLAS.", "max_results", "integer", "Default 50.", "system",
						"string", SYSTEM_DESC),
				Capability.SEARCH, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_read_source",
				"Read the saved source or definition of a repository object: classes, interfaces, programs, CDS views, function modules, "
						+ "DDIC tables and structures (fields and types), data elements, domains (incl. fixed values), table types and message classes. "
						+ "For classes, 'include' selects main (default), definitions, implementations, macros or testclasses.",
				schema(new String[] { "name" }, objectProps("include", "string",
						"Class include: main, definitions, implementations, macros, testclasses.")),
				Capability.READ_SOURCE, ToolSpec.Kind.READ));
		JsonObject contextSchema = schema(new String[0], "name", "string",
				"Object whose used objects should be looked up, e.g. ZCL_SALES_ORDER.", "type", "string", TYPE_DESC,
				"source", "string", "ABAP code whose used objects should be looked up (e.g. code you are about to change).",
				"system", "string", SYSTEM_DESC);
		JsonObject names = new JsonObject();
		names.addProperty("type", "array");
		names.addProperty("description", "Object names to look up directly, e.g. [\"MARA\", \"BAPI_USER_GET_DETAIL\"].");
		JsonObject nameItem = new JsonObject();
		nameItem.addProperty("type", "string");
		names.add("items", nameItem);
		contextSchema.getAsJsonObject("properties").add("names", names);
		t.add(ToolSpec.of("adt_context",
				"Definitions of the objects a piece of code uses, in one call: public section of classes, interfaces, "
						+ "table and structure fields, CDS views, function module signatures, data elements and table types. "
						+ "Give 'name' (an object), 'source' (code) and/or 'names'. Call before writing code that uses tables, "
						+ "structures, classes or function modules, so you use real field names and signatures.",
				contextSchema, null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_where_used", "Where-used list of a repository object.",
				schema(new String[] { "name" }, objectProps()), Capability.WHERE_USED, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_syntax_check",
				"Syntax check of an object. Pass 'source' to check code that is not saved yet (e.g. a proposed change) without writing it.",
				schema(new String[] { "name" }, objectProps("source", "string", "Optional full source to check instead of the saved version.")),
				Capability.SYNTAX_CHECK, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_run_unit_tests", "Run the ABAP Unit tests of an object and report failures.",
				schema(new String[] { "name" }, objectProps()), Capability.UNIT_TEST, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_atc_check", "Run ATC (ABAP Test Cockpit) checks on an object and list findings.",
				schema(new String[] { "name" }, objectProps("check_variant", "string",
						"ATC check variant; omit for the system default.")),
				Capability.ATC, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_write_source",
				"Replace the complete source of an object (main source or a class include). If the object is open in the developer's editor, the code is written into the editor instead and not saved. Otherwise it is saved (not activated) in the SAP system.",
				schema(new String[] { "name", "source" }, objectProps("source", "string", "Complete new source code.",
						"include", "string", "Class include, default main.", "transport", "string",
						"Transport request, required for non-local objects unless already assigned.")),
				Capability.WRITE_SOURCE, ToolSpec.Kind.WRITE));
		t.add(ToolSpec.of("adt_create_object",
				"Create a new class (CLAS), interface (INTF) or program (PROG), optionally with initial source. Does not activate.",
				schema(new String[] { "name", "type", "description", "package" }, objectProps("description", "string",
						"Short description (max. 60 characters).", "package", "string", "Package, e.g. $TMP or ZSALES.",
						"transport", "string", "Transport request for non-local packages.", "source", "string",
						"Optional initial source code.")),
				Capability.CREATE_OBJECT, ToolSpec.Kind.WRITE));
		JsonObject activateSchema = schema(new String[] { "objects" }, "system", "string", SYSTEM_DESC);
		JsonObject objects = new JsonObject();
		objects.addProperty("type", "array");
		objects.addProperty("description", "Objects to activate together.");
		JsonObject item = schema(new String[] { "name" }, "name", "string", NAME_DESC, "type", "string", TYPE_DESC);
		objects.add("items", item);
		activateSchema.getAsJsonObject("properties").add("objects", objects);
		t.add(ToolSpec.of("adt_activate", "Activate one or more objects in the SAP system.", activateSchema,
				Capability.ACTIVATE, ToolSpec.Kind.WRITE));
		return t;
	}

	// ---- execution -------------------------------------------------------------

	@Override
	public ToolResult call(String name, JsonObject in, CancelToken cancel) {
		try {
			return switch (name) {
			case "adt_list_systems" -> listSystems();
			case "adt_search_objects" -> searchObjects(in, cancel);
			case "adt_read_source" -> readSource(in, cancel);
			case "adt_context" -> context(in, cancel);
			case "adt_where_used" -> whereUsed(in, cancel);
			case "adt_syntax_check" -> syntaxCheck(in, cancel);
			case "adt_run_unit_tests" -> unitTests(in, cancel);
			case "adt_atc_check" -> atc(in, cancel);
			case "adt_write_source" -> writeSource(in, cancel);
			case "adt_create_object" -> create(in, cancel);
			case "adt_activate" -> activate(in, cancel);
			default -> ToolResult.error("Unknown ADT tool " + name);
			};
		} catch (IOException e) {
			return ToolResult.error(e.getMessage());
		}
	}

	private ToolResult listSystems() {
		StringBuilder sb = new StringBuilder();
		for (AdtSystem s : backend.systems()) {
			sb.append("- ").append(s.label()).append(" [destination ").append(s.destinationId()).append("]")
					.append(s.loggedOn() ? " logged on" : " NOT logged on").append('\n');
		}
		return ToolResult.ok(sb.isEmpty() ? "No ABAP projects in the workspace." : sb.toString());
	}

	/** Picks the system: explicit argument (project, SID or destination), else the active editor's. */
	AdtSystem system(JsonObject in) throws IOException {
		String wanted = Json.str(in, "system");
		List<AdtSystem> systems = backend.systems();
		if (systems.isEmpty()) {
			throw new AdtException(404, "There is no ABAP project in the workspace.");
		}
		AdtSystem chosen = null;
		if (wanted != null && !wanted.isBlank()) {
			for (AdtSystem s : systems) {
				if (s.destinationId().equalsIgnoreCase(wanted) || s.projectName().equalsIgnoreCase(wanted)
						|| wanted.equalsIgnoreCase(s.systemId())) {
					chosen = s;
					break;
				}
			}
			if (chosen == null) {
				throw new AdtException(404, "Unknown system '" + wanted + "'. Use adt_list_systems.");
			}
		} else {
			String def = defaultDestination.get();
			for (AdtSystem s : systems) {
				if (s.destinationId().equals(def)) {
					chosen = s;
				}
			}
			if (chosen == null) {
				chosen = systems.stream().filter(AdtSystem::loggedOn).findFirst().orElse(systems.get(0));
			}
		}
		if (!chosen.loggedOn()) {
			throw new AdtException(401, "Not logged on to " + chosen.label()
					+ ". Ask the developer to open the project in Eclipse and log on.");
		}
		return chosen;
	}

	private AdtClient client(AdtSystem s) {
		return new AdtClient(backend.stateless(s.destinationId()));
	}

	private AdtObjectRef resolve(AdtClient c, JsonObject in, CancelToken cancel) throws IOException {
		return c.resolve(Json.str(in, "name").trim(), Json.str(in, "type"), cancel);
	}

	private ToolResult searchObjects(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		int max = Math.max(1, Math.min(200, Json.integer(in, "max_results", 50)));
		List<AdtObjectRef> refs = c.search(Json.str(in, "query"), Json.str(in, "type"), max, cancel);
		if (refs.isEmpty()) {
			return ToolResult.ok("No objects found.");
		}
		StringBuilder sb = new StringBuilder();
		for (AdtObjectRef r : refs) {
			sb.append(r.name()).append(" (").append(r.type()).append(')');
			if (!r.packageName().isEmpty()) {
				sb.append(" package ").append(r.packageName());
			}
			if (!r.description().isEmpty()) {
				sb.append(" – ").append(r.description());
			}
			sb.append('\n');
		}
		return ToolResult.ok(sb.toString());
	}

	private ToolResult readSource(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		String include = Json.str(in, "include");
		String src = include == null || include.isBlank() || include.equalsIgnoreCase("main")
				? c.readDefinition(ref, cancel)
				: c.readSource(AdtObjectRef.objectUri(ref.uri()), include, cancel);
		return ToolResult.ok(src.isEmpty() ? "(empty source)" : src);
	}

	private ToolResult context(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		List<AbapReferences.Reference> candidates = new ArrayList<>();
		JsonArray names = Json.arr(in, "names");
		if (names != null) {
			for (JsonElement e : names) {
				if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
					candidates.add(new AbapReferences.Reference(e.getAsString().trim().toUpperCase(Locale.ROOT),
							AbapReferences.Hint.ANY));
				}
			}
		}
		String name = Json.str(in, "name");
		String self = null;
		if (name != null && !name.isBlank()) {
			AdtObjectRef ref = resolve(c, in, cancel);
			self = ref.name();
			candidates = AbapReferences.merge(candidates, AbapReferences.extract(c.readDefinition(ref, cancel)));
		}
		String source = Json.str(in, "source");
		if (source != null && !source.isBlank()) {
			candidates = AbapReferences.merge(candidates, AbapReferences.extract(source));
		}
		String own = self;
		candidates = candidates.stream().filter(r -> !r.name().equalsIgnoreCase(own)).toList();
		if (candidates.isEmpty()) {
			return ToolResult.ok("No referenced repository objects found.");
		}
		AdtContext.Result r;
		try {
			r = AdtContext.build(c, candidates, AdtContext.Limits.DEFAULT, cancel);
		} catch (CancelToken.CancelledException e) {
			return ToolResult.error("Cancelled.");
		}
		if (r.isEmpty()) {
			if (!r.failed().isEmpty()) {
				return ToolResult.error("Could not read from the SAP system (this does not mean the objects are "
						+ "missing): " + String.join("; ", r.failed()));
			}
			if (!r.notFound().isEmpty()) {
				return ToolResult.ok("None of these objects exist in the system: " + String.join(", ", r.notFound()));
			}
			return ToolResult.ok("Not loaded (limit reached, use adt_read_source): " + String.join(", ", r.skipped()));
		}
		String text = r.text();
		if (!r.skipped().isEmpty()) {
			text += "Not loaded (limit reached, use adt_read_source): " + String.join(", ", r.skipped()) + "\n";
		}
		return ToolResult.ok(text);
	}

	private ToolResult whereUsed(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		List<AdtObjectRef> refs = c.whereUsed(AdtObjectRef.objectUri(ref.uri()), cancel);
		if (refs.isEmpty()) {
			return ToolResult.ok("No usages found.");
		}
		StringBuilder sb = new StringBuilder();
		for (AdtObjectRef r : refs) {
			sb.append(r.name()).append(" (").append(r.type()).append(")\n");
		}
		return ToolResult.ok(sb.toString());
	}

	private ToolResult syntaxCheck(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		List<AdtClient.Message> msgs = c.syntaxCheck(AdtObjectRef.objectUri(ref.uri()), Json.str(in, "source"), cancel);
		return ToolResult.ok(msgs.isEmpty() ? "No syntax errors." : format(msgs));
	}

	private ToolResult unitTests(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		return ToolResult.ok(c.runUnitTests(AdtObjectRef.objectUri(ref.uri()), cancel));
	}

	private ToolResult atc(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		List<AdtClient.Message> msgs = c.atcCheck(AdtObjectRef.objectUri(ref.uri()), Json.str(in, "check_variant"),
				cancel);
		return ToolResult.ok(msgs.isEmpty() ? "No ATC findings." : format(msgs));
	}

	private ToolResult writeSource(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtObjectRef ref = resolve(client(s), in, cancel);
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			String tr = AdtClient.writeSource(session, AdtObjectRef.objectUri(ref.uri()), Json.str(in, "include"),
					Json.str(in, "source"), Json.str(in, "transport"), cancel);
			return ToolResult.ok("Saved " + ref.name() + " in " + s.label() + (tr.isEmpty() ? "" : " (transport " + tr + ")")
					+ ". Not activated yet.");
		}
	}

	private ToolResult create(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		AdtObjectRef ref = c.create(Json.str(in, "type"), Json.str(in, "name"), Json.str(in, "description"),
				Json.str(in, "package"), Json.str(in, "transport"), s.user(), cancel);
		String source = Json.str(in, "source");
		if (source != null && !source.isBlank()) {
			try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
				AdtClient.writeSource(session, ref.uri(), null, source, Json.str(in, "transport"), cancel);
			}
		}
		return ToolResult.ok("Created " + ref.name() + " (" + ref.type() + ") in package "
				+ Json.str(in, "package").toUpperCase(Locale.ROOT) + " on " + s.label() + ". Not activated yet.");
	}

	private ToolResult activate(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		List<AdtObjectRef> refs = new ArrayList<>();
		JsonArray objects = Json.arr(in, "objects");
		if (objects != null) {
			for (JsonElement e : objects) {
				JsonObject o = e.getAsJsonObject();
				refs.add(c.resolve(Json.str(o, "name"), Json.str(o, "type"), cancel));
			}
		}
		if (refs.isEmpty()) {
			return ToolResult.error("No objects given.");
		}
		List<AdtClient.Message> msgs = c.activate(refs, cancel);
		boolean errors = msgs.stream().anyMatch(m -> m.severity().equals("Error"));
		String names = String.join(", ", refs.stream().map(AdtObjectRef::name).toList());
		if (errors) {
			return ToolResult.error("Activation failed for " + names + ":\n" + format(msgs));
		}
		return ToolResult.ok("Activated " + names + " on " + s.label() + (msgs.isEmpty() ? "." : ":\n" + format(msgs)));
	}

	private static String format(List<AdtClient.Message> msgs) {
		StringBuilder sb = new StringBuilder();
		for (AdtClient.Message m : msgs) {
			sb.append(m.format()).append('\n');
		}
		return sb.toString();
	}
}

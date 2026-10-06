package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapEdit;
import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.abap.AbapSlices;
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
	private final Supplier<String> writePackages;
	private final Function<String, String> atcVariants;
	private final SourceCache cache = new SourceCache();
	private volatile Supplier<String> hiddenColumns = () -> "";
	/** Inactive objects per destination, kept briefly so a turn with many reads asks once. */
	private final Map<String, Inactive> inactive = new ConcurrentHashMap<>();

	private record Inactive(long readAt, List<String> names) {
	}

	/** How long the inactive-objects list is reused; writes and activations clear it at once. */
	static final long INACTIVE_TTL_MILLIS = 30_000;

	/**
	 * @param defaultDestination destination of the active editor, used when the
	 *                           model does not name a system; may return {@code null}
	 */
	public AdtToolProvider(AdtBackend backend, Supplier<String> defaultDestination) {
		this(backend, defaultDestination, () -> "");
	}

	/**
	 * @param writePackages packages the tools may write to, create in and
	 *                      activate in, e.g. {@code $TMP, Z*, Y*}; empty allows all
	 */
	public AdtToolProvider(AdtBackend backend, Supplier<String> defaultDestination, Supplier<String> writePackages) {
		this(backend, defaultDestination, writePackages, d -> "");
	}

	/**
	 * @param atcVariants ATC check variant per destination id; empty for the
	 *                    system default
	 */
	public AdtToolProvider(AdtBackend backend, Supplier<String> defaultDestination, Supplier<String> writePackages,
			Function<String, String> atcVariants) {
		this.backend = backend;
		this.defaultDestination = defaultDestination;
		this.writePackages = writePackages;
		this.atcVariants = atcVariants;
	}

	/**
	 * Columns whose values {@code adt_table_contents} replaces with
	 * {@link #HIDDEN}, e.g. names, addresses and bank data.
	 *
	 * @param patterns comma, semicolon or space separated column names, {@code *}
	 *                 as wildcard; empty to show all values
	 */
	public AdtToolProvider hideColumns(Supplier<String> patterns) {
		this.hiddenColumns = patterns == null ? () -> "" : patterns;
		return this;
	}

	/** What a hidden cell shows. */
	static final String HIDDEN = "***";

	/** Package patterns from a comma, semicolon or space separated list; {@code *} is a wildcard. */
	static List<String> packagePatterns(String list) {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (String p : list.split("[,;\\s]+")) {
				if (!p.isBlank()) {
					out.add(p.trim().toUpperCase(Locale.ROOT));
				}
			}
		}
		return out;
	}

	static boolean packageAllowed(String pkg, List<String> patterns) {
		String p = pkg.toUpperCase(Locale.ROOT);
		for (String pattern : patterns) {
			String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
			if (p.matches(regex)) {
				return true;
			}
		}
		return false;
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
		t.add(ToolSpec.of("adt_list_systems",
				"List the ABAP projects (SAP systems) in the workspace, whether they are logged on, and their release "
						+ "(SAP_BASIS) or whether they are ABAP Cloud systems. Check it before writing code that depends on the release.",
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
						+ "For classes, 'include' selects main (default), definitions, implementations, macros or testclasses. "
						+ "To save tokens, read one method with 'method' or only matching lines with 'grep' instead of the whole source.",
				schema(new String[] { "name" }, objectProps("include", "string",
						"Class include: main, definitions, implementations, macros, testclasses.", "method", "string",
						"Only this method: its declaration and METHOD … ENDMETHOD, e.g. GET_ITEMS or ZIF_X~SAVE.", "grep",
						"string", "Only lines matching this regex (case-insensitive), with line numbers and 2 lines of context.",
						"version", "string",
						"auto (default: newest saved version, with a note if it is not activated), active or inactive.")),
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
						"ATC check variant; omit for the one set in Bella's preferences, else the system default.")),
				Capability.ATC, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_transport_info",
				"Which transport request a change needs: whether the object's package records changes, the request the object "
						+ "is already locked in, and the developer's open requests that fit. Use it before writing a non-local object. "
						+ "Releasing transports is not possible.",
				schema(new String[] { "name" }, objectProps("package", "string",
						"Package, needed when the object does not exist yet.", "create", "boolean",
						"true if the object is about to be created; default false (change).")),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_list_transports",
				"Transport requests of a user with their tasks and number of objects: modifiable ones by default, "
						+ "released ones with status R.",
				schema(new String[0], "user", "string", "Owner; default the logged-on user, '*' for all users.", "status",
						"string", "D modifiable (default) or R released.", "system", "string", SYSTEM_DESC),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_transport_review",
				"Everything needed to review a transport request, read only: header, tasks, objects, per source the diff "
						+ "against the version before the request, syntax check, ATC and ABAP Unit results, objects that are "
						+ "not activated, and customer objects the changed code uses that are missing, inactive or held in "
						+ "another open request. With 'object' only that object's complete diff.",
				schema(new String[] { "request" }, "request", "string", "Transport request number, e.g. DEVK900123.",
						"object", "string", "Only this object of the request, with its whole diff.", "checks", "boolean",
						"Run syntax check, ATC and ABAP Unit (default true).", "system", "string", SYSTEM_DESC),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_short_dumps",
				"Runtime errors (short dumps, ST22): without 'id' a list of the newest dumps (by default the developer's own), "
						+ "with 'id' the full dump text including the source position.",
				schema(new String[0], "id", "string", "Dump id from the list, to read one dump.", "user", "string",
						"Only dumps of this user; default the logged-on user, '*' for all users.", "max_results", "integer",
						"Default 10, at most 50.", "system", "string", SYSTEM_DESC),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_table_contents",
				"Read rows of a database table or CDS view (data preview, like SE16 or ADT's SQL console). Give 'table' with "
						+ "optional 'columns' and 'where', or a complete ABAP SQL SELECT in 'sql' (joins, aggregates). "
						+ "Read only. Use it to understand data or to check what a program wrote.",
				schema(new String[0], "table", "string", "Table or CDS view, e.g. MARA or T000.", "columns", "string",
						"Comma separated columns, default all, e.g. matnr, mtart.", "where", "string",
						"ABAP SQL condition without WHERE, e.g. mtart = 'FERT' AND ersda >= '20240101'.", "sql",
						"string", "Instead of table/columns/where: a complete ABAP SQL SELECT statement.", "max_rows",
						"integer", "Default 100, at most " + TABLE_MAX_ROWS + ".", "system", "string", SYSTEM_DESC),
				Capability.TABLE_CONTENTS, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_write_source",
				"Replace the complete source of an object (main source or a class include), or with 'method' only the body of one method. If the object is open in the developer's editor, the code is written into the editor instead and not saved. Otherwise it is saved (not activated) in the SAP system.",
				schema(new String[] { "name", "source" }, objectProps("source", "string",
						"Complete new source code, or with 'method' the new method body.", "method", "string",
						"Replace only the body of this method (between METHOD and ENDMETHOD).",
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
		JsonObject activateSchema = schema(new String[] { "objects" }, "system", "string", SYSTEM_DESC,
				"run_unit_tests", "boolean", "After a successful activation run the ABAP Unit tests of the activated "
						+ "classes, programs and function groups and add the result (default false).");
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
	public Optional<String> refuse(String name, JsonObject in, CancelToken cancel) {
		List<String> patterns = packagePatterns(writePackages.get());
		if (patterns.isEmpty() || !List.of("adt_write_source", "adt_create_object", "adt_activate").contains(name)) {
			return Optional.empty();
		}
		try {
			if (name.equals("adt_create_object")) {
				String pkg = Json.str(in, "package");
				return checkPackage(Json.str(in, "name"), pkg == null ? "" : pkg.trim(), patterns);
			}
			AdtClient c = client(system(in));
			List<JsonObject> objects = new ArrayList<>();
			if (name.equals("adt_activate")) {
				JsonArray arr = Json.arr(in, "objects");
				if (arr != null) {
					arr.forEach(e -> objects.add(e.getAsJsonObject()));
				}
			} else {
				objects.add(in);
			}
			for (JsonObject o : objects) {
				AdtObjectRef ref = c.resolve(Json.str(o, "name").trim(), Json.str(o, "type"), cancel);
				String pkg = ref.packageName().isEmpty() ? c.packageOf(ref.uri(), cancel)
						: ref.packageName().toUpperCase(Locale.ROOT);
				Optional<String> refused = checkPackage(ref.name(), pkg, patterns);
				if (refused.isPresent()) {
					return refused;
				}
			}
			return Optional.empty();
		} catch (IOException | RuntimeException e) {
			return Optional.of("Could not check the package before writing: " + e.getMessage());
		}
	}

	private static Optional<String> checkPackage(String object, String pkg, List<String> patterns) {
		if (pkg.isEmpty()) {
			return Optional.of("The package of " + object + " could not be determined, so Bella does not write to it. "
					+ "Allowed packages: " + String.join(", ", patterns) + ".");
		}
		if (packageAllowed(pkg, patterns)) {
			return Optional.empty();
		}
		return Optional.of(object + " is in package " + pkg.toUpperCase(Locale.ROOT)
				+ ", where Bella may not write, create or activate (allowed: " + String.join(", ", patterns)
				+ "; Preferences → Bella → SAP-Tools & ARC-1). Do not retry; tell the developer.");
	}

	@Override
	public ToolResult call(String name, JsonObject in, CancelToken cancel) {
		try {
			return switch (name) {
			case "adt_list_systems" -> listSystems(cancel);
			case "adt_search_objects" -> searchObjects(in, cancel);
			case "adt_read_source" -> readSource(in, cancel);
			case "adt_context" -> context(in, cancel);
			case "adt_where_used" -> whereUsed(in, cancel);
			case "adt_syntax_check" -> syntaxCheck(in, cancel);
			case "adt_run_unit_tests" -> unitTests(in, cancel);
			case "adt_atc_check" -> atc(in, cancel);
			case "adt_transport_info" -> transportInfo(in, cancel);
			case "adt_short_dumps" -> shortDumps(in, cancel);
			case "adt_list_transports" -> listTransports(in, cancel);
			case "adt_transport_review" -> transportReview(in, cancel);
			case "adt_table_contents" -> tableContents(in, cancel);
			case "adt_write_source" -> writeSource(in, cancel);
			case "adt_create_object" -> create(in, cancel);
			case "adt_activate" -> activate(in, cancel);
			default -> ToolResult.error("Unknown ADT tool " + name);
			};
		} catch (IOException e) {
			return ToolResult.error(e.getMessage());
		}
	}

	private ToolResult listSystems(CancelToken cancel) {
		StringBuilder sb = new StringBuilder();
		for (AdtSystem s : backend.systems()) {
			sb.append("- ").append(s.label()).append(" [destination ").append(s.destinationId()).append("]")
					.append(s.loggedOn() ? " logged on" : " NOT logged on");
			if (s.loggedOn()) {
				AdtSystemInfo.of(s.destinationId(), client(s), cancel).ifPresent(i -> sb.append(", ").append(i.describe()));
			}
			sb.append('\n');
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
		return new AdtClient(backend.stateless(s.destinationId()), cache, s.destinationId())
				.atcVariant(atcVariants.apply(s.destinationId()));
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

	private List<String> inactiveObjects(AdtSystem s, AdtClient c, CancelToken cancel) throws IOException {
		Inactive cached = inactive.get(s.destinationId());
		long now = System.currentTimeMillis();
		if (cached != null && now - cached.readAt() < INACTIVE_TTL_MILLIS) {
			return cached.names();
		}
		List<String> names = c.inactiveObjects(cancel);
		inactive.put(s.destinationId(), new Inactive(now, names));
		return names;
	}

	private ToolResult readSource(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem sys = system(in);
		AdtClient c = client(sys);
		AdtObjectRef ref = resolve(c, in, cancel);
		String include = Json.str(in, "include");
		boolean main = include == null || include.isBlank() || include.equalsIgnoreCase("main");
		String version = Json.str(in, "version");
		version = version == null || version.isBlank() ? "auto" : version.trim().toLowerCase(Locale.ROOT);
		if (!List.of("auto", "active", "inactive").contains(version)) {
			return ToolResult.error("Unknown version '" + version + "'; use auto, active or inactive.");
		}
		String uri = AdtObjectRef.objectUri(ref.uri());
		String src;
		if (!version.equals("auto") && !AdtClient.xmlOnly(ref.type())) {
			src = c.readSource(uri, include, version, cancel);
		} else {
			src = main ? c.readDefinition(ref, cancel) : c.readSource(uri, include, cancel);
		}
		String note = "";
		if (version.equals("auto") && !AdtClient.xmlOnly(ref.type())
				&& inactiveObjects(sys, c, cancel).contains(ref.name())) {
			note = "Note: " + ref.name() + " has saved changes that are not activated yet; this is that inactive version. "
					+ "Use version=active for the active one.\n\n";
		}
		String method = Json.str(in, "method");
		if (method != null && !method.isBlank()) {
			Optional<AbapSlices.Slice> slice = AbapSlices.method(src, ref.name(), method);
			if (slice.isEmpty()) {
				return ToolResult.error("Method " + method.toUpperCase(Locale.ROOT) + " is not implemented in "
						+ ref.name() + (main ? "" : " (" + include + ")") + ". Implemented methods: "
						+ String.join(", ", AbapSlices.methodNames(src)));
			}
			src = "Lines from " + slice.get().firstLine() + ":\n" + slice.get().text();
		}
		String grep = Json.str(in, "grep");
		if (grep != null && !grep.isBlank()) {
			src = AbapSlices.grep(src, grep, 2, GREP_MAX_LINES);
		}
		return ToolResult.ok(note + (src.isEmpty() ? "(empty source)" : src));
	}

	/** Lines a grep result returns at most. */
	static final int GREP_MAX_LINES = 200;

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
		AdtSystem sys = system(in);
		AdtClient c = client(sys);
		AdtObjectRef ref = resolve(c, in, cancel);
		String source = Json.str(in, "source");
		boolean inactive = source == null && inactiveObjects(sys, c, cancel).contains(ref.name());
		List<AdtClient.Message> msgs = c.syntaxCheck(AdtObjectRef.objectUri(ref.uri()), source, inactive, cancel);
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

	private ToolResult transportInfo(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		boolean create = in.has("create") && in.get("create").isJsonPrimitive() && in.get("create").getAsBoolean();
		String name = Json.str(in, "name").trim();
		String pkg = Json.str(in, "package");
		String uri;
		if (create) {
			String direct = AdtObjectRef.uriFor(name, AdtClient.searchType(Json.str(in, "type")));
			if (direct == null) {
				return ToolResult.error("Give 'type' (CLAS, INTF, PROG, …) for an object that does not exist yet.");
			}
			uri = direct;
		} else {
			AdtObjectRef ref = resolve(c, in, cancel);
			uri = ref.uri();
			if (pkg == null || pkg.isBlank()) {
				pkg = ref.packageName().isEmpty() ? c.packageOf(uri, cancel) : ref.packageName();
			}
		}
		if (pkg == null || pkg.isBlank()) {
			return ToolResult.error("Give 'package': the package of " + name.toUpperCase(Locale.ROOT) + " is unknown.");
		}
		AdtClient.TransportCheck t = c.transportCheck(uri, pkg.trim().toUpperCase(Locale.ROOT), create ? "I" : "", cancel);
		if (!t.errors().isEmpty()) {
			return ToolResult.error("SAP's transport check failed: " + String.join("; ", t.errors()));
		}
		StringBuilder sb = new StringBuilder(name.toUpperCase(Locale.ROOT)).append(" in package ")
				.append(t.packageName().isEmpty() ? pkg.toUpperCase(Locale.ROOT) : t.packageName()).append(": ");
		if (t.local() || !t.recordingRequired()) {
			return ToolResult.ok(sb.append("local, no transport request needed.").toString());
		}
		sb.append("changes are recorded in a transport request.\n");
		if (!t.lockedIn().isEmpty()) {
			sb.append("Already locked in request ").append(t.lockedIn()).append("; use it.\n");
		}
		if (t.candidates().isEmpty()) {
			sb.append("No open request of the developer fits; ask them for one (Bella cannot create or release requests).");
		} else {
			sb.append("Open requests that fit:\n");
			t.candidates().forEach(r -> sb.append("- ").append(r).append('\n'));
		}
		return ToolResult.ok(sb.toString());
	}

	private ToolResult listTransports(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		String user = Json.str(in, "user");
		user = user == null || user.isBlank() ? s.user() : user.trim();
		String status = Json.str(in, "status");
		List<AdtTransportRequest> list = client(s).transports(user == null ? "*" : user, status, cancel);
		if (list.isEmpty()) {
			return ToolResult.ok("No " + ("R".equalsIgnoreCase(status) ? "released" : "modifiable")
					+ " transport requests" + (user == null ? "" : " of " + user.toUpperCase(Locale.ROOT)) + ".");
		}
		StringBuilder sb = new StringBuilder();
		for (AdtTransportRequest t : list) {
			sb.append(t.id()).append("  ").append(t.description()).append("  (").append(t.owner());
			if (!t.target().isBlank()) {
				sb.append(", target ").append(t.target());
			}
			sb.append(", ").append(t.tasks().size()).append(" tasks, ").append(t.entries().size()).append(" objects)\n");
		}
		return ToolResult.ok(sb.toString());
	}

	private ToolResult transportReview(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		String id = Json.str(in, "request");
		if (id == null || id.isBlank()) {
			return ToolResult.error("Give 'request', the transport request number.");
		}
		Optional<AdtTransportRequest> tr = c.transport(id, cancel);
		if (tr.isEmpty()) {
			return ToolResult.error("Transport request " + id.trim().toUpperCase(Locale.ROOT) + " does not exist.");
		}
		String object = Json.str(in, "object");
		boolean single = object != null && !object.isBlank();
		boolean checks = !in.has("checks") || !in.get("checks").isJsonPrimitive() || in.get("checks").getAsBoolean();
		try {
			return ToolResult.ok(TransportReview.build(c, tr.get(), object, checks && !single,
					single ? TransportReview.Limits.SINGLE : TransportReview.Limits.DEFAULT, cancel));
		} catch (CancelToken.CancelledException e) {
			return ToolResult.error("Cancelled.");
		}
	}

	/** Characters of a dump text a tool result carries at most. */
	static final int DUMP_TEXT_CHARS = 12_000;

	private ToolResult shortDumps(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		String id = Json.str(in, "id");
		if (id != null && !id.isBlank()) {
			String text = c.dumpText(id, cancel);
			return ToolResult.ok(text.length() > DUMP_TEXT_CHARS
					? text.substring(0, DUMP_TEXT_CHARS) + "\n… (cut after " + DUMP_TEXT_CHARS + " characters)"
					: text);
		}
		String user = Json.str(in, "user");
		user = user == null || user.isBlank() ? s.user() : user.trim().equals("*") ? null : user;
		int max = Math.max(1, Math.min(50, Json.integer(in, "max_results", 10)));
		List<AdtClient.Dump> dumps = c.dumps(user, max, cancel);
		if (dumps.isEmpty()) {
			return ToolResult.ok("No short dumps" + (user == null ? "" : " of " + user.toUpperCase(Locale.ROOT)) + ".");
		}
		StringBuilder sb = new StringBuilder();
		for (AdtClient.Dump d : dumps) {
			sb.append(d.time()).append("  ").append(d.error()).append(" in ").append(d.program()).append(" (")
					.append(d.user()).append(")  id: ").append(d.id()).append('\n');
		}
		return ToolResult.ok(sb.toString());
	}

	private ToolResult writeSource(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		AdtObjectRef ref = resolve(c, in, cancel);
		String uri = AdtObjectRef.objectUri(ref.uri());
		String include = Json.str(in, "include");
		String source = Json.str(in, "source");
		String method = Json.str(in, "method");
		if (method != null && !method.isBlank()) {
			String current = c.readSource(uri, include, cancel);
			Optional<String> updated = AbapEdit.replaceMethod(current, method, source == null ? "" : source);
			if (updated.isEmpty()) {
				return ToolResult.error("Method " + method.toUpperCase(Locale.ROOT) + " is not implemented in "
						+ ref.name() + ". Implemented methods: " + String.join(", ", AbapSlices.methodNames(current)));
			}
			source = updated.get();
		}
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			String tr = AdtClient.writeSource(session, uri, include, source, Json.str(in, "transport"), cancel);
			return ToolResult.ok("Saved " + (method == null || method.isBlank() ? "" : "method " + method.toUpperCase(Locale.ROOT) + " of ")
					+ ref.name() + " in " + s.label() + (tr.isEmpty() ? "" : " (transport " + tr + ")") + ". Not activated yet.");
		} finally {
			c.invalidate(uri);
			inactive.remove(s.destinationId());
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
			} finally {
				c.invalidate(ref.uri());
				inactive.remove(s.destinationId());
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
		List<AdtClient.Message> msgs;
		try {
			msgs = c.activate(refs, cancel);
		} finally {
			refs.forEach(r -> c.invalidate(AdtObjectRef.objectUri(r.uri())));
			inactive.remove(s.destinationId());
		}
		boolean errors = msgs.stream().anyMatch(m -> m.severity().equals("Error"));
		String names = String.join(", ", refs.stream().map(AdtObjectRef::name).toList());
		if (errors) {
			return ToolResult.error("Activation failed for " + names + ":\n" + format(msgs));
		}
		String done = "Activated " + names + " on " + s.label() + (msgs.isEmpty() ? "." : ":\n" + format(msgs));
		boolean test = in.has("run_unit_tests") && in.get("run_unit_tests").isJsonPrimitive()
				&& in.get("run_unit_tests").getAsBoolean();
		return ToolResult.ok(test ? done + "\n\n" + unitTestsAfterActivation(c, refs, cancel) : done);
	}

	/** Object types that can hold ABAP Unit tests. */
	private static final List<String> TESTABLE = List.of("CLAS", "PROG", "FUGR");

	private static String unitTestsAfterActivation(AdtClient c, List<AdtObjectRef> refs, CancelToken cancel) {
		List<String> uris = new ArrayList<>();
		for (AdtObjectRef r : refs) {
			String type = r.type() == null ? "" : r.type().toUpperCase(Locale.ROOT);
			if (TESTABLE.stream().anyMatch(type::startsWith)) {
				uris.add(AdtObjectRef.objectUri(r.uri()));
			}
		}
		if (uris.isEmpty()) {
			return "ABAP Unit: none of the activated objects can hold tests.";
		}
		try {
			return "ABAP Unit:\n" + c.runUnitTests(uris, cancel);
		} catch (IOException e) {
			return "ABAP Unit could not run: " + e.getMessage();
		}
	}

	/** Rows {@code adt_table_contents} returns at most. */
	static final int TABLE_MAX_ROWS = 1000;
	/** Characters a cell is cut to. */
	static final int TABLE_CELL_CHARS = 80;
	/** Characters a table result carries at most. */
	static final int TABLE_RESULT_CHARS = 40_000;

	private static final Pattern SQL_NAME = Pattern.compile("[A-Za-z/][A-Za-z0-9_/]*");

	private ToolResult tableContents(JsonObject in, CancelToken cancel) throws IOException {
		String sql = Json.str(in, "sql");
		if (sql == null || sql.isBlank()) {
			String table = Json.str(in, "table");
			if (table == null || !SQL_NAME.matcher(table.trim()).matches()) {
				return ToolResult.error("Give 'table' (a table or CDS view name) or a SELECT statement in 'sql'.");
			}
			String columns = Json.str(in, "columns");
			String where = Json.str(in, "where");
			sql = "SELECT " + (columns == null || columns.isBlank() ? "*" : columns.trim()) + " FROM "
					+ table.trim().toUpperCase(Locale.ROOT)
					+ (where == null || where.isBlank() ? "" : " WHERE " + where.trim());
		}
		int max = Math.max(1, Math.min(TABLE_MAX_ROWS, Json.integer(in, "max_rows", 100)));
		AdtSystem s = system(in);
		AdtClient.TableData data = hide(client(s).tableContents(sql, max, cancel), packagePatterns(hiddenColumns.get()));
		return ToolResult.ok(formatTable(data, s.label()));
	}

	/** The data with the values of the hidden columns replaced; the column names stay. */
	static AdtClient.TableData hide(AdtClient.TableData data, List<String> patterns) {
		if (patterns.isEmpty()) {
			return data;
		}
		List<Integer> hidden = new ArrayList<>();
		for (int i = 0; i < data.columns().size(); i++) {
			if (packageAllowed(data.columns().get(i), patterns)) {
				hidden.add(i);
			}
		}
		if (hidden.isEmpty()) {
			return data;
		}
		List<List<String>> rows = new ArrayList<>();
		for (List<String> row : data.rows()) {
			List<String> copy = new ArrayList<>(row);
			for (int i : hidden) {
				if (i < copy.size() && !copy.get(i).isBlank()) {
					copy.set(i, HIDDEN);
				}
			}
			rows.add(copy);
		}
		return new AdtClient.TableData(data.columns(), rows, data.totalRows());
	}

	/** Markdown table with cells cut and pipes escaped, and how many rows the statement found. */
	static String formatTable(AdtClient.TableData data, String system) {
		if (data.rows().isEmpty()) {
			return "No rows found on " + system + "."
					+ (data.columns().isEmpty() ? "" : " Columns: " + String.join(", ", data.columns()));
		}
		StringBuilder sb = new StringBuilder();
		sb.append(data.rows().size()).append(data.totalRows() > data.rows().size() ? " of " + data.totalRows() : "")
				.append(" rows from ").append(system).append(":\n\n| ").append(String.join(" | ", data.columns()))
				.append(" |\n|").append("---|".repeat(data.columns().size())).append('\n');
		int shown = 0;
		for (List<String> row : data.rows()) {
			StringBuilder line = new StringBuilder("|");
			for (String cell : row) {
				String v = cell.replace("|", "\\|").replace('\n', ' ').strip();
				line.append(' ').append(v.length() > TABLE_CELL_CHARS ? v.substring(0, TABLE_CELL_CHARS) + "…" : v)
						.append(" |");
			}
			if (sb.length() + line.length() > TABLE_RESULT_CHARS) {
				sb.append("… ").append(data.rows().size() - shown)
						.append(" more rows not shown; select fewer columns or rows.\n");
				break;
			}
			sb.append(line).append('\n');
			shown++;
		}
		return sb.toString();
	}

	private static String format(List<AdtClient.Message> msgs) {
		StringBuilder sb = new StringBuilder();
		for (AdtClient.Message m : msgs) {
			sb.append(m.format()).append('\n');
		}
		return sb.toString();
	}
}

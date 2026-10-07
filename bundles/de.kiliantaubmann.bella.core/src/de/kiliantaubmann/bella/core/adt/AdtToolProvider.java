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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapEdit;
import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.abap.AbapSlices;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.abap.ClassSurgery;
import de.kiliantaubmann.bella.core.abap.CodeEdits;
import de.kiliantaubmann.bella.core.abap.TextDeltas;
import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.LineDiff;

/**
 * Bella's own SAP tools, executed through the developer's ADT logon. No
 * extra server or credentials are needed.
 */
public final class AdtToolProvider implements ToolProvider {

	private final AdtBackend backend;
	private final Supplier<String> defaultDestination;
	private final Supplier<String> writePackages;
	private final Function<String, String> atcVariants;
	private final Supplier<NamingRules> naming;
	private final SourceCache cache = new SourceCache();
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
		this(backend, defaultDestination, writePackages, atcVariants, () -> NamingRules.NONE);
	}

	/**
	 * @param naming the project's naming rules, checked with Bella's style check
	 *               on the code a write or create saves
	 */
	public AdtToolProvider(AdtBackend backend, Supplier<String> defaultDestination, Supplier<String> writePackages,
			Function<String, String> atcVariants, Supplier<NamingRules> naming) {
		this.backend = backend;
		this.defaultDestination = defaultDestination;
		this.writePackages = writePackages;
		this.atcVariants = atcVariants;
		this.naming = naming;
	}

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

	private static final String TEXT_PART_DESC = "symbols (text symbols TEXT-nnn), selections (selection texts) or "
			+ "headings (list and column headings).";
	private static final String SYSTEM_DESC = "ABAP project / system id to use. Omit to use the system of the active editor.";
	private static final String NAME_DESC = "Object name, e.g. ZCL_SALES_ORDER.";
	private static final String TYPE_DESC = "Object type: CLAS, INTF, PROG, INCL, FUGR (function group), FUNC (function module), TABL (table or structure), DTEL, DOMA, TTYP, MSAG, DDLS, BDEF, SRVD. Omit if unknown.";

	private static final String CREATE_TYPE_DESC = "CLAS, INTF, PROG, INCL, FUGR (function group), FUNC (function "
			+ "module, needs 'group'), MSAG (message class), DTEL, DOMA, TTYP, TABL/DT (table), TABL/DS (structure), "
			+ "DDLS, DCLS, DDLX, BDEF, SRVD, SRVB (service binding).";

	/** Fields of DDIC objects, shared by adt_create_object and adt_write_metadata. */
	private static final String[] DDIC_PROPS = { "domain", "string", "DTEL: the domain it is based on.",
			"data_type", "string", "DTEL without domain, or DOMA: built-in type such as CHAR, NUMC, DEC, INT4.",
			"length", "integer", "DTEL/DOMA: length.", "decimals", "integer", "DTEL/DOMA: decimal places.",
			"output_length", "integer", "DOMA: output length (default the length).", "short_label", "string",
			"DTEL: short field label (10).", "medium_label", "string", "DTEL: medium field label (20).",
			"long_label", "string", "DTEL: long field label (40).", "heading_label", "string",
			"DTEL: column heading (55).", "search_help", "string", "DTEL: search help.", "value_table", "string",
			"DOMA: value table.", "lowercase", "boolean", "DOMA: lower case allowed.", "sign", "boolean",
			"DOMA: sign allowed.", "conversion_exit", "string", "DOMA: conversion routine, e.g. ALPHA.", "row_type",
			"string", "TTYP: row type, a built-in type (STRING, I …) or a DDIC structure.", "row_type_kind", "string",
			"TTYP: builtin or structure (default: guessed from row_type).", "service_definition", "string",
			"SRVB: the service definition.", "binding_type", "string", "SRVB: e.g. ODATA V4 UI or ODATA V2 Web API.",
			"odata_version", "string", "SRVB: V2 or V4.", "category", "string", "SRVB: 0 UI, 1 Web API." };

	private static String[] createProps() {
		List<String> p = new ArrayList<>(List.of(objectProps("description", "string",
				"Short description (max. 60 characters).", "package", "string",
				"Package, e.g. $TMP or ZSALES; not needed for FUNC, which belongs to the package of its group.",
				"transport", "string", "Transport request (not a task) for non-local packages.", "source", "string",
				"Optional initial source code for source-based types. For FUNC the interface goes into the FUNCTION "
						+ "statement, without the *\" comment block.",
				"group", "string", "FUNC, and INCL of a function group: the function group.", "processing_type",
				"string", "FUNC: normal (default), rfc or update.", "update_task_kind", "string",
				"FUNC with processing_type update: startImmediate, immediateStartNoRestart or startDelayed.",
				"language", "string", "Original language (2 letters); default the logon language.")));
		p.addAll(List.of(DDIC_PROPS));
		return p.toArray(String[]::new);
	}

	private static String[] metadataProps() {
		List<String> p = new ArrayList<>(List.of(objectProps("description", "string", "New short description.",
				"transport", "string", "Transport request, required for non-local objects unless already assigned.")));
		p.addAll(List.of(DDIC_PROPS));
		return p.toArray(String[]::new);
	}

	/** The array fields: MSAG messages and DOMA fixed values. */
	private static void addArrays(JsonObject schema) {
		JsonObject props = schema.getAsJsonObject("properties");
		props.add("messages", arrayOf("MSAG: messages as {number, text} (number 000-999, text up to 73 characters).",
				"number", "string", "text", "string"));
		props.add("fixed_values", arrayOf("DOMA: fixed values as {low, high, text}; replaces all fixed values.",
				"low", "string", "high", "string", "text", "string"));
	}

	private static JsonObject arrayOf(String description, String... itemProps) {
		JsonObject a = new JsonObject();
		a.addProperty("type", "array");
		a.addProperty("description", description);
		JsonObject item = new JsonObject();
		item.addProperty("type", "object");
		JsonObject ip = new JsonObject();
		for (int i = 0; i + 1 < itemProps.length; i += 2) {
			JsonObject f = new JsonObject();
			f.addProperty("type", itemProps[i + 1]);
			ip.add(itemProps[i], f);
		}
		item.add("properties", ip);
		a.add("items", item);
		return a;
	}

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
		t.add(ToolSpec.of("adt_atc_check",
				"Run ATC (ABAP Test Cockpit) checks on an object and list the findings by priority. ATC checks the "
						+ "active version: activate first (or activate with run_atc, which runs ATC after a successful activation).",
				schema(new String[] { "name" }, objectProps("check_variant", "string",
						"ATC check variant; omit for the one set in Bella's preferences, else the system default.")),
				Capability.ATC, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_quickfix",
				"SAP's quick fixes (Ctrl+1 in ADT) for a syntax or ATC finding. action 'list' shows the fixes for a "
						+ "line (and column, if known); 'preview' runs one fix ('proposal': its number or uri from the list) "
						+ "and returns the changed code without saving it. Apply the result with adt_write_source.",
				schema(new String[] { "name", "line" }, objectProps("action", "string", "list (default) or preview.",
						"line", "integer", "Line of the finding (from 1).", "column", "integer",
						"Column of the finding (from 0); omit to try the tokens of the line.", "include", "string",
						"Class include, default main.", "proposal", "string",
						"preview: number of the fix in the list (1, 2 …) or its uri.")),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_format",
				"SAP's pretty printer with the system's settings. action 'format' (default) returns 'source' (or the "
						+ "saved source of 'name') formatted, without saving it; 'get_settings' shows indentation and "
						+ "keyword case.",
				schema(new String[0], objectProps("action", "string", "format (default) or get_settings.", "source",
						"string", "Code to format.", "include", "string", "Class include, default main.")),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_settings_write",
				"Change the pretty printer settings for everybody on the system (indentation, keyword case). Bella "
						+ "always asks first.",
				schema(new String[] { "indentation", "style" }, "indentation", "boolean", "Indent code.", "style",
						"string", "keywordUpper, keywordLower, keywordAuto or none.", "system", "string", SYSTEM_DESC),
				null, ToolSpec.Kind.WRITE));
		t.add(ToolSpec.of("adt_object_info",
				"More about one object, read only. action 'api_state': release state of an SAP object (C0 extend, C1 "
						+ "use in cloud and key user apps, C2 remote API …) and its successor, before using it in ABAP "
						+ "Cloud or clean core code; 'versions': version history of the source; 'version_source': the "
						+ "source of one version ('version': its number); 'variants': variants of a program.",
				schema(new String[] { "name", "action" }, objectProps("action", "string",
						"api_state, versions, version_source or variants.", "version", "string",
						"version_source: version number from 'versions'.", "include", "string",
						"Class include for versions, default main.")),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_navigate",
				"Code navigation, read only. 'definition': where the symbol at line/column is defined; 'references': "
						+ "objects that use the object, or the symbol at line/column; 'completion': ADT's code "
						+ "completion at line/column; 'hierarchy': superclass, interfaces and subclasses of a class. "
						+ "Lines count from 1, columns from 0.",
				schema(new String[] { "name", "action" }, objectProps("action", "string",
						"definition, references, completion or hierarchy.", "line", "integer", "Line (from 1).",
						"column", "integer", "Column (from 0).", "include", "string", "Class include, default main.",
						"source", "string", "Unsaved source to navigate in; default the saved source.")),
				null, ToolSpec.Kind.READ));
		t.add(ToolSpec.of("adt_text_elements",
				"Read the text pool of a program, class or function group: text symbols (TEXT-001), selection texts "
						+ "(labels of PARAMETERS and SELECT-OPTIONS) or list headings.",
				schema(new String[] { "name", "part" }, objectProps("part", "string", TEXT_PART_DESC)), null,
				ToolSpec.Kind.READ));
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
		JsonObject createSchema = schema(new String[] { "name", "type", "description" }, createProps());
		addArrays(createSchema);
		createSchema.getAsJsonObject("properties").getAsJsonObject("type").addProperty("description", CREATE_TYPE_DESC);
		t.add(ToolSpec.of("adt_create_object",
				"Create a new object, optionally with initial source; does not activate. Types: " + CREATE_TYPE_DESC
						+ " Message classes take 'messages', data elements 'domain' or 'data_type' and labels, domains "
						+ "'data_type' and 'length', table types 'row_type', service bindings 'service_definition'.",
				createSchema, Capability.CREATE_OBJECT, ToolSpec.Kind.WRITE));
		JsonObject metadataSchema = schema(new String[] { "name", "type" }, metadataProps());
		addArrays(metadataSchema);
		JsonObject remove = new JsonObject();
		remove.addProperty("type", "array");
		remove.addProperty("description", "MSAG: numbers of messages to delete.");
		JsonObject str = new JsonObject();
		str.addProperty("type", "string");
		remove.add("items", str);
		metadataSchema.getAsJsonObject("properties").add("remove_numbers", remove);
		t.add(ToolSpec.of("adt_write_metadata",
				"Change the metadata of a data element (DTEL), domain (DOMA), table type (TTYP) or the messages of a "
						+ "message class (MSAG). Only the given fields change; Bella reads the object first and keeps "
						+ "everything else. Message classes: 'messages' adds or replaces messages by number, "
						+ "'remove_numbers' deletes some. Does not activate (message classes need no activation).",
				metadataSchema, null, ToolSpec.Kind.WRITE));
		t.add(ToolSpec.of("adt_edit_code",
				"Targeted change of a class, program or include that keeps the rest of the source. Classes: "
						+ "'add_method' ('source': the METHODS clause, 'visibility'; adds an empty implementation), "
						+ "'edit_method_signature' ('method', 'source': the new METHODS clause), 'edit_class_definition' "
						+ "('source': CLASS … DEFINITION … ENDCLASS.), 'change_method_visibility' ('method', 'visibility'; "
						+ "keeps the body), 'delete_method' ('method'). Programs and includes: 'edit_unit' ('unit', "
						+ "'source': the whole FORM or MODULE), 'add_unit' ('source'). Write method bodies with "
						+ "adt_write_source 'method'. Bella refuses a change that adds syntax errors. Like "
						+ "adt_write_source, open objects are changed in the editor only.",
				schema(new String[] { "name", "action" }, objectProps("action", "string",
						String.join(", ", CodeEdits.ACTIONS) + ".", "method", "string", "Method name.", "source",
						"string", "METHODS clause, class definition or FORM/MODULE, depending on the action.",
						"visibility", "string", "public (default), protected or private.", "unit", "string",
						"edit_unit: name of the FORM or MODULE.", "transport", "string",
						"Transport request, required for non-local objects unless already assigned.")),
				null, ToolSpec.Kind.WRITE));
		t.add(ToolSpec.of("adt_delete_object",
				"Delete an object from the SAP system. Bella checks the where-used list first and refuses while "
						+ "other objects use it, unless 'force' is true; it always asks the developer.",
				schema(new String[] { "name", "type" }, objectProps("transport", "string",
						"Transport request for non-local objects.", "force", "boolean",
						"Delete even though other objects use it.")),
				null, ToolSpec.Kind.WRITE));
		t.add(ToolSpec.of("adt_write_text_elements",
				"Replace one part of the text pool of a program (PROG), class (CLAS, symbols only) or function group "
						+ "(FUGR). Use it for the selection texts of PARAMETERS and SELECT-OPTIONS and for the text symbols "
						+ "behind TEXT-nnn, instead of setting texts in code. Read the part first with adt_text_elements "
						+ "and keep the other entries. Saved directly in the SAP system (also when the object is open in "
						+ "the editor) and active at once; no activation needed.",
				schema(new String[] { "name", "type", "part", "texts" }, objectProps("part", "string", TEXT_PART_DESC,
						"texts", "string", "The complete new part, one entry per line. selections: S_VBELN=Delivery "
								+ "(name of the parameter or select-option, text up to 30 characters). symbols: 001=Text, "
								+ "optionally preceded by a line @MaxLength:40. headings: listHeader=Title, "
								+ "columnHeader_1=Column titles.",
						"transport", "string", "Transport request, required for non-local objects unless already assigned.")),
				null, ToolSpec.Kind.WRITE));
		JsonObject activateSchema = schema(new String[] { "objects" }, "system", "string", SYSTEM_DESC,
				"run_unit_tests", "boolean", "After a successful activation run the ABAP Unit tests of the activated "
						+ "classes, programs and function groups and add the result (default false).",
				"run_atc", "boolean", "After a successful activation run ATC on the activated objects and add the "
						+ "findings (default false). Set it on the last activation of a task, not on every one.");
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
		if (patterns.isEmpty() || !WRITES.contains(name)) {
			return Optional.empty();
		}
		try {
			String group = Json.str(in, "group");
			boolean inGroup = group != null && !group.isBlank()
					&& List.of("FUNC", "INCL").contains(AdtDdic.normalizeType(Json.str(in, "type")));
			if (name.equals("adt_create_object") && !inGroup) {
				String pkg = Json.str(in, "package");
				return checkPackage(Json.str(in, "name"), pkg == null ? "" : pkg.trim(), patterns);
			}
			AdtClient c = client(system(in));
			if (name.equals("adt_create_object")) {
				// a function module or group include belongs to the package of its group
				return checkPackage(Json.str(in, "name"), c.packageOf(AdtDdic.groupUri(group), cancel), patterns);
			}
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

	/** Tools that change objects and are bound to the allowed packages. */
	private static final List<String> WRITES = List.of("adt_write_source", "adt_create_object", "adt_activate",
			"adt_write_text_elements", "adt_write_metadata", "adt_edit_code", "adt_delete_object");

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
			case "adt_text_elements" -> textElements(in, cancel);
			case "adt_quickfix" -> quickfix(in, cancel);
			case "adt_object_info" -> objectInfo(in, cancel);
			case "adt_navigate" -> navigate(in, cancel);
			case "adt_edit_code" -> editCode(in, cancel);
			case "adt_delete_object" -> deleteObject(in, cancel);
			case "adt_format" -> format(in, cancel);
			case "adt_settings_write" -> writeSettings(in, cancel);
			case "adt_write_text_elements" -> writeTextElements(in, cancel);
			case "adt_transport_info" -> transportInfo(in, cancel);
			case "adt_short_dumps" -> shortDumps(in, cancel);
			case "adt_list_transports" -> listTransports(in, cancel);
			case "adt_transport_review" -> transportReview(in, cancel);
			case "adt_table_contents" -> tableContents(in, cancel);
			case "adt_write_source" -> writeSource(in, cancel);
			case "adt_create_object" -> create(in, cancel);
			case "adt_write_metadata" -> writeMetadata(in, cancel);
			case "adt_activate" -> activate(in, cancel);
			default -> ToolResult.error("Unknown ADT tool " + name);
			};
		} catch (IOException | IllegalArgumentException e) {
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
		return ToolResult.ok(msgs.isEmpty() ? "No ATC findings." : formatAtc(msgs, false));
	}

	/** ATC findings by priority (1 = error, 2 = warning, 3 = information), with the object when several were checked. */
	static String formatAtc(List<AdtClient.Message> msgs, boolean withObject) {
		StringBuilder sb = new StringBuilder();
		for (AdtClient.Message m : msgs) {
			String priority = switch (m.severity()) {
			case "Error" -> "1";
			case "Warning" -> "2";
			default -> "3";
			};
			sb.append("Priority ").append(priority);
			if (withObject && m.uri() != null && !m.uri().isEmpty()) {
				String uri = AdtObjectRef.objectUri(m.uri());
				sb.append(' ').append(uri.substring(uri.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT));
			}
			if (m.line() > 0) {
				sb.append(" line ").append(m.line());
			}
			if (!m.include().isEmpty()) {
				sb.append(" in include ").append(m.include());
			}
			sb.append(": ").append(m.text()).append('\n');
		}
		return sb.toString();
	}

	private static final String QUICKFIX_HINT = "\nadt_quickfix lists SAP's own fixes for a finding's line.";

	/** Columns tried when the model does not know the column of a finding. */
	private static final int MAX_TOKENS_TRIED = 8;

	private ToolResult quickfix(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		String objectUri = AdtObjectRef.objectUri(ref.uri());
		String include = Json.str(in, "include");
		String sourceUri = AdtObjectRef.sourceUri(objectUri, include);
		String source = c.readSource(objectUri, include, cancel);
		int line = Json.integer(in, "line", 0);
		String[] lines = source.replace("\r\n", "\n").split("\n", -1);
		if (line < 1 || line > lines.length) {
			return ToolResult.error("Line " + line + " is outside the source (" + lines.length + " lines).");
		}
		List<Integer> columns = new ArrayList<>();
		if (in.has("column") && in.get("column").isJsonPrimitive()) {
			columns.add(Math.max(0, Json.integer(in, "column", 0)));
		} else {
			Matcher m = Pattern.compile("\\S+").matcher(lines[line - 1]);
			while (m.find() && columns.size() < MAX_TOKENS_TRIED) {
				columns.add(m.start());
			}
		}
		List<AdtQuickfix.Proposal> proposals = List.of();
		int column = columns.isEmpty() ? 0 : columns.get(0);
		for (int col : columns) {
			proposals = AdtQuickfix.proposals(c, sourceUri, source, line, col, cancel);
			if (!proposals.isEmpty()) {
				column = col;
				break;
			}
		}
		String where = ref.name() + " line " + line + ":" + column;
		if (proposals.isEmpty()) {
			return ToolResult.ok("SAP has no quick fix for " + ref.name() + " line " + line + ".");
		}
		String action = Json.str(in, "action");
		if (action == null || action.isBlank() || action.equalsIgnoreCase("list")) {
			StringBuilder sb = new StringBuilder("Quick fixes for " + where + ":\n");
			for (int i = 0; i < proposals.size(); i++) {
				AdtQuickfix.Proposal p = proposals.get(i);
				sb.append(i + 1).append(". ").append(p.name());
				if (!p.description().isBlank() && !p.description().equals(p.name())) {
					sb.append(" (").append(p.description()).append(')');
				}
				sb.append("  [").append(p.uri()).append("]\n");
			}
			return ToolResult.ok(sb.append("Preview one with action 'preview' and 'proposal'.").toString());
		}
		if (!action.equalsIgnoreCase("preview")) {
			return ToolResult.error("Unknown action " + action + "; use list or preview.");
		}
		String wanted = Json.str(in, "proposal");
		AdtQuickfix.Proposal chosen = null;
		if (wanted != null && wanted.trim().matches("\\d+")) {
			int i = Integer.parseInt(wanted.trim());
			chosen = i >= 1 && i <= proposals.size() ? proposals.get(i - 1) : null;
		} else if (wanted != null) {
			chosen = proposals.stream().filter(p -> p.uri().equals(wanted.trim())).findFirst().orElse(null);
		}
		if (chosen == null) {
			return ToolResult.error("Give 'proposal', the number (1-" + proposals.size() + ") or uri of a quick fix.");
		}
		List<AdtQuickfix.Delta> deltas = AdtQuickfix.apply(c, chosen, sourceUri, source, line, column, cancel);
		List<TextDeltas.Delta> own = new ArrayList<>();
		List<String> others = new ArrayList<>();
		for (AdtQuickfix.Delta d : deltas) {
			if (AdtQuickfix.sameSource(d.uri(), sourceUri)) {
				own.add(d.delta());
			} else {
				others.add(AdtObjectRef.objectUri(d.uri()));
			}
		}
		StringBuilder sb = new StringBuilder("Quick fix \"" + chosen.name() + "\" for " + where + ", not saved.");
		if (!others.isEmpty()) {
			sb.append("\nIt also changes other sources, which this preview leaves out: ")
					.append(String.join(", ", others.stream().distinct().toList())).append('.');
		}
		if (own.isEmpty()) {
			return ToolResult.ok(sb.append("\nIt changes nothing in this source.").toString());
		}
		String fixed;
		try {
			fixed = TextDeltas.apply(source, own);
		} catch (IllegalArgumentException e) {
			return ToolResult.error("SAP's quick fix returned changes Bella cannot apply: " + e.getMessage());
		}
		sb.append("\n```diff\n").append(LineDiff.unified(source, fixed, "before", "after", 2).text()).append("```");
		int first = own.stream().mapToInt(TextDeltas.Delta::startLine).min().orElse(line);
		int last = own.stream().mapToInt(TextDeltas.Delta::endLine).max().orElse(line);
		Optional<AbapStructureScanner.Block> routine = AbapStructureScanner.routineAt(fixed, offsetOfLine(fixed, first));
		if (routine.isPresent() && routine.get().kind() == AbapStructureScanner.Kind.METHOD
				&& routine.get().contains(offsetOfLine(fixed, last))) {
			return ToolResult.ok(sb.append("\nApply it with adt_write_source, 'method' ").append(routine.get().name())
					.append(", and this body:\n```abap\n").append(routine.get().body(fixed).strip()).append("\n```")
					.toString());
		}
		return ToolResult.ok(sb.append("\nApply it with adt_write_source and this complete source:\n```abap\n")
				.append(fixed).append("\n```").toString());
	}

	private static int offsetOfLine(String text, int line) {
		int offset = 0;
		for (int i = 1; i < line && offset >= 0; i++) {
			offset = text.indexOf('\n', offset) + 1;
			if (offset == 0) {
				return text.length();
			}
		}
		return offset;
	}

	private ToolResult objectInfo(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		String objectUri = AdtObjectRef.objectUri(ref.uri());
		String action = Json.str(in, "action");
		switch (action == null ? "" : action.trim().toLowerCase(Locale.ROOT)) {
		case "api_state" -> {
			List<AdtCodeIntel.Contract> contracts = AdtCodeIntel.releaseState(c, objectUri, cancel);
			if (contracts.isEmpty()) {
				return ToolResult.ok(ref.name() + " has no release state (not released for any contract).");
			}
			StringBuilder sb = new StringBuilder("Release state of " + ref.name() + ":\n");
			for (AdtCodeIntel.Contract k : contracts) {
				sb.append("- ").append(k.contract()).append(": ")
						.append(k.stateDescription().isEmpty() ? k.state() : k.stateDescription());
				if (k.cloud() || k.keyUser()) {
					sb.append(" (").append(k.cloud() ? "ABAP Cloud" : "").append(k.cloud() && k.keyUser() ? ", " : "")
							.append(k.keyUser() ? "key user apps" : "").append(')');
				}
				if (!k.successors().isEmpty()) {
					sb.append("; successor ").append(String.join(", ", k.successors()));
				}
				sb.append('\n');
			}
			return ToolResult.ok(sb.toString());
		}
		case "versions", "version_source" -> {
			List<AdtRevisions.Revision> revisions = c.revisions(versionsUri(ref, objectUri, Json.str(in, "include")),
					cancel);
			if (revisions.isEmpty()) {
				return ToolResult.ok(ref.name() + " has no version history.");
			}
			if (action.equalsIgnoreCase("versions")) {
				StringBuilder sb = new StringBuilder("Versions of " + ref.name() + ", newest first:\n");
				for (AdtRevisions.Revision r : revisions) {
					sb.append(r.number().isEmpty() ? r.id() : r.number()).append("  ").append(r.timestamp()).append("  ")
							.append(r.author()).append(r.transport().isEmpty() ? "" : "  " + r.transport()).append('\n');
				}
				return ToolResult.ok(sb.toString());
			}
			String wanted = Json.str(in, "version");
			for (AdtRevisions.Revision r : revisions) {
				if (wanted != null && (wanted.trim().equals(r.number()) || wanted.trim().equals(r.id()))) {
					return ToolResult.ok(c.revisionText(r.uri(), cancel));
				}
			}
			return ToolResult.error("Give 'version', one of the numbers from action 'versions'.");
		}
		case "variants" -> {
			AdtResponse r = c.exchange(AdtRequest.get(objectUri + "/variants", "application/*"), cancel);
			if (!r.ok()) {
				return ToolResult.error("Could not read the variants: " + AdtErrors.message(r));
			}
			String body = r.body() == null ? "" : r.body();
			return ToolResult.ok(body.isBlank() ? ref.name() + " has no variants."
					: body.length() > DUMP_TEXT_CHARS ? body.substring(0, DUMP_TEXT_CHARS) + "\n…" : body);
		}
		default -> {
			return ToolResult.error("action is api_state, versions, version_source or variants.");
		}
		}
	}

	private static String versionsUri(AdtObjectRef ref, String objectUri, String include) {
		String type = ref.type() == null ? "" : ref.type().toUpperCase(Locale.ROOT);
		if (type.startsWith("DDLS") || type.startsWith("DCLS")) {
			return objectUri + "/versions";
		}
		if (include != null && !include.isBlank() && !include.equalsIgnoreCase("main")) {
			return AdtObjectRef.sourceUri(objectUri, include) + "/versions";
		}
		return objectUri + "/source/main/versions";
	}

	private static final Pattern CLASS_NAME = Pattern.compile("(?:/[A-Z0-9_]+/)?[A-Z0-9_]+");

	private ToolResult navigate(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		String action = Json.str(in, "action");
		String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		if (a.equals("hierarchy")) {
			String name = Json.str(in, "name").trim().toUpperCase(Locale.ROOT);
			if (!CLASS_NAME.matcher(name).matches()) {
				return ToolResult.error("Invalid class name " + name + ".");
			}
			AdtClient.TableData own = c.tableContents(
					"SELECT clsname, refclsname, reltype FROM seometarel WHERE clsname = '" + name + "'", 100, cancel);
			AdtClient.TableData sub = c.tableContents(
					"SELECT clsname FROM seometarel WHERE refclsname = '" + name + "' AND reltype = '2'", 100, cancel);
			String superclass = "";
			List<String> interfaces = new ArrayList<>();
			int refCol = own.columns().indexOf("REFCLSNAME");
			int typeCol = own.columns().indexOf("RELTYPE");
			for (List<String> row : own.rows()) {
				String rel = row.get(typeCol).trim();
				if (rel.equals("2")) {
					superclass = row.get(refCol).trim();
				} else if (rel.equals("1")) {
					interfaces.add(row.get(refCol).trim());
				}
			}
			List<String> subclasses = sub.rows().stream().map(r -> r.get(0).trim()).toList();
			return ToolResult.ok(name + ": superclass " + (superclass.isEmpty() ? "none" : superclass) + "; interfaces "
					+ (interfaces.isEmpty() ? "none" : String.join(", ", interfaces)) + "; subclasses "
					+ (subclasses.isEmpty() ? "none" : String.join(", ", subclasses)) + ".");
		}
		AdtObjectRef ref = resolve(c, in, cancel);
		String objectUri = AdtObjectRef.objectUri(ref.uri());
		String sourceUri = AdtObjectRef.sourceUri(objectUri, Json.str(in, "include"));
		int line = Json.integer(in, "line", 0);
		int column = Math.max(0, Json.integer(in, "column", 0));
		if (a.equals("references")) {
			String uri = line > 0 ? sourceUri + "#start=" + line + "," + column : objectUri;
			List<AdtObjectRef> refs = c.whereUsed(uri, cancel);
			if (refs.isEmpty()) {
				return ToolResult.ok("No references found.");
			}
			StringBuilder sb = new StringBuilder(refs.size() + " references:\n");
			refs.stream().limit(100).forEach(r -> sb.append("- ").append(r.name()).append(" (").append(r.type())
					.append(r.packageName().isEmpty() ? "" : ", " + r.packageName()).append(")\n"));
			return ToolResult.ok(sb.toString());
		}
		if (line < 1) {
			return ToolResult.error("Give 'line' (from 1) and 'column' (from 0).");
		}
		String source = Json.str(in, "source");
		if (source == null || source.isBlank()) {
			source = c.readSource(objectUri, Json.str(in, "include"), cancel);
		}
		if (a.equals("definition")) {
			AdtCodeIntel.Target t = AdtCodeIntel.definition(c, sourceUri, source, line, column, cancel);
			if (t == null) {
				return ToolResult.ok("ADT finds no definition at line " + line + ":" + column + ".");
			}
			String where = AdtObjectRef.objectUri(t.uri());
			return ToolResult.ok("Defined in " + (t.name().isEmpty() ? where.substring(where.lastIndexOf('/') + 1)
					.toUpperCase(Locale.ROOT) : t.name()) + (t.type().isEmpty() ? "" : " (" + t.type() + ")")
					+ (t.line() > 0 ? " line " + t.line() : "") + ": " + t.uri());
		}
		if (a.equals("completion")) {
			List<AdtCodeIntel.Proposal> proposals = AdtCodeIntel.completion(c, sourceUri, source, line, column, cancel);
			if (proposals.isEmpty()) {
				return ToolResult.ok("No completion proposals.");
			}
			StringBuilder sb = new StringBuilder();
			proposals.stream().limit(50).forEach(p -> sb.append(p.text())
					.append(p.description().isEmpty() ? "" : " - " + p.description()).append('\n'));
			return ToolResult.ok(sb.toString());
		}
		return ToolResult.error("action is definition, references, completion or hierarchy.");
	}

	private ToolResult editCode(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		AdtObjectRef ref = resolve(c, in, cancel);
		String uri = AdtObjectRef.objectUri(ref.uri());
		String before = c.readSource(uri, null, cancel);
		String after;
		try {
			after = CodeEdits.apply(before, ref.name(), in);
		} catch (ClassSurgery.SurgeryException e) {
			return ToolResult.error(e.getMessage());
		}
		if (after.equals(before)) {
			return ToolResult.ok("Nothing to change in " + ref.name() + ".");
		}
		String action = Json.str(in, "action").trim().toLowerCase(Locale.ROOT);
		if (!action.equals("edit_method_signature")) {
			// a signature change may break the body until it is rewritten; everything else must stay compilable
			Optional<String> added = newSyntaxErrors(c, uri, before, after, cancel);
			if (added.isPresent()) {
				return ToolResult.error("Not saved: the change would add syntax errors:\n" + added.get());
			}
		}
		String tr;
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			tr = AdtClient.writeSource(session, uri, null, after, Json.str(in, "transport"), cancel);
		} finally {
			c.invalidate(uri);
			inactive.remove(s.destinationId());
		}
		return ToolResult.ok("Saved " + action + " in " + ref.name() + " in " + s.label()
				+ (tr.isEmpty() ? "" : " (transport " + tr + ")") + ". Not activated yet."
				+ "\n```diff\n" + LineDiff.unified(before, after, "before", "after", 1).text() + "```"
				+ syntaxAfterWrite(c, uri, cancel));
	}

	/** Syntax errors the new source has and the old one did not; empty when none or when the check cannot run. */
	private static Optional<String> newSyntaxErrors(AdtClient c, String uri, String before, String after,
			CancelToken cancel) {
		try {
			List<String> old = c.syntaxCheck(uri, before, cancel).stream().filter(m -> m.severity().equals("Error"))
					.map(AdtClient.Message::text).toList();
			List<AdtClient.Message> now = c.syntaxCheck(uri, after, cancel).stream()
					.filter(m -> m.severity().equals("Error") && !old.contains(m.text())).toList();
			return now.isEmpty() ? Optional.empty() : Optional.of(format(now));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	private ToolResult deleteObject(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		AdtObjectRef ref = resolve(c, in, cancel);
		String uri = AdtObjectRef.objectUri(ref.uri());
		boolean force = in.has("force") && in.get("force").isJsonPrimitive() && in.get("force").getAsBoolean();
		if (!force) {
			List<AdtObjectRef> users = c.whereUsed(uri, cancel).stream()
					.filter(u -> !u.name().equalsIgnoreCase(ref.name())).toList();
			if (!users.isEmpty()) {
				return ToolResult.error(ref.name() + " is used by " + users.size() + " objects, e.g. " + String.join(", ",
						users.stream().limit(10).map(AdtObjectRef::name).toList())
						+ ". Not deleted; delete it anyway only if the developer wants that ('force': true).");
			}
		}
		String tr;
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			tr = AdtClient.delete(session, uri, Json.str(in, "transport"), cancel);
		} finally {
			c.invalidate(uri);
			inactive.remove(s.destinationId());
		}
		return ToolResult.ok("Deleted " + ref.name() + " in " + s.label() + (tr.isEmpty() ? "" : " (transport " + tr + ")")
				+ ".");
	}

	private ToolResult format(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		String action = Json.str(in, "action");
		if ("get_settings".equalsIgnoreCase(action)) {
			AdtQuickfix.Settings st = AdtQuickfix.settings(c, cancel);
			return ToolResult.ok("Pretty printer: indentation " + (st.indentation() ? "on" : "off") + ", keywords "
					+ st.style() + ".");
		}
		String source = Json.str(in, "source");
		if (source == null || source.isBlank()) {
			String name = Json.str(in, "name");
			if (name == null || name.isBlank()) {
				return ToolResult.error("Give 'source' or 'name'.");
			}
			AdtObjectRef ref = resolve(c, in, cancel);
			source = c.readSource(AdtObjectRef.objectUri(ref.uri()), Json.str(in, "include"), cancel);
		}
		String formatted = AdtQuickfix.prettyPrint(c, source, cancel);
		return ToolResult.ok(formatted.equals(source) ? "Already formatted; nothing changes."
				: "Formatted (not saved):\n```abap\n" + formatted + "\n```");
	}

	private ToolResult writeSettings(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		String style = Json.str(in, "style");
		if (style == null || !AdtQuickfix.STYLES.contains(style.trim())) {
			return ToolResult.error("style is one of " + String.join(", ", AdtQuickfix.STYLES) + ".");
		}
		boolean indentation = !in.has("indentation") || !in.get("indentation").isJsonPrimitive()
				|| in.get("indentation").getAsBoolean();
		AdtQuickfix.writeSettings(client(s), new AdtQuickfix.Settings(indentation, style.trim()), cancel);
		return ToolResult.ok("Pretty printer settings of " + s.label() + ": indentation " + (indentation ? "on" : "off")
				+ ", keywords " + style.trim() + ".");
	}

	private ToolResult textElements(JsonObject in, CancelToken cancel) throws IOException {
		AdtClient c = client(system(in));
		AdtObjectRef ref = resolve(c, in, cancel);
		String part = textPart(in);
		String texts = c.textElements(ref.type(), ref.name(), part, cancel);
		return ToolResult.ok(texts.isBlank() ? "No " + part + " maintained for " + ref.name() + "." : texts);
	}

	private static final Pattern SELECTION_TEXT = Pattern.compile("[A-Za-z0-9_]{1,8}=.*");

	private ToolResult writeTextElements(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		AdtObjectRef ref = resolve(c, in, cancel);
		String part = textPart(in);
		String texts = Json.str(in, "texts");
		if (texts == null) {
			return ToolResult.error("Give 'texts', the complete new " + part + ", one entry per line.");
		}
		texts = texts.replace("\r\n", "\n");
		if (part.equals("selections")) {
			for (String line : texts.split("\n")) {
				if (!line.isBlank() && !SELECTION_TEXT.matcher(line.strip()).matches()) {
					return ToolResult.error("Selection texts go one per line as NAME=Text, with the name of the "
							+ "parameter or select-option (up to 8 characters); not understood: " + line.strip());
				}
			}
		}
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			String tr = AdtClient.writeTextElements(session, ref.type(), ref.name(), part, texts,
					Json.str(in, "transport"), cancel);
			return ToolResult.ok("Saved the " + part + " of " + ref.name() + " in " + s.label()
					+ (tr.isEmpty() ? "" : " (transport " + tr + ")") + ". Text elements are active at once.");
		}
	}

	private static String textPart(JsonObject in) {
		String part = Json.str(in, "part");
		part = part == null || part.isBlank() ? "symbols" : part.trim().toLowerCase(Locale.ROOT);
		if (!AdtClient.TEXT_PARTS.contains(part)) {
			throw new IllegalArgumentException("Unknown part '" + part + "'; use symbols, selections or headings.");
		}
		return part;
	}

	/**
	 * Bella's style check of the code just saved and a syntax check of the
	 * saved (inactive) version, appended to a write's result so the model
	 * fixes them before activating, as ARC-1 does on SAPWrite.
	 */
	private String checksAfterWrite(AdtClient c, String objectUri, String written, boolean methodBody,
			CancelToken cancel) {
		StringBuilder sb = new StringBuilder();
		List<AbapLint.Finding> lint = AbapLint.check(written, naming.get());
		if (!lint.isEmpty()) {
			sb.append("\n\nBella's style check of the code written")
					.append(methodBody ? " (line numbers count within the method body)" : "")
					.append("; fix the findings that apply before activating:\n").append(AbapLint.format(lint));
		}
		try {
			List<AdtClient.Message> msgs = c.syntaxCheck(objectUri, null, true, cancel);
			sb.append(msgs.isEmpty() ? "\n\nSyntax check: no errors."
					: "\n\nSyntax check of the saved version:\n" + format(msgs) + QUICKFIX_HINT);
		} catch (IOException | RuntimeException e) {
			sb.append("\n\nSyntax check could not run: ").append(e.getMessage());
		}
		return sb.toString();
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
		String tr;
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			tr = AdtClient.writeSource(session, uri, include, source, Json.str(in, "transport"), cancel);
		} finally {
			c.invalidate(uri);
			inactive.remove(s.destinationId());
		}
		boolean oneMethod = method != null && !method.isBlank();
		return ToolResult.ok("Saved " + (oneMethod ? "method " + method.toUpperCase(Locale.ROOT) + " of " : "")
				+ ref.name() + " in " + s.label() + (tr.isEmpty() ? "" : " (transport " + tr + ")") + ". Not activated yet."
				+ checksAfterWrite(c, uri, oneMethod ? Json.str(in, "source") : source, oneMethod, cancel));
	}

	private ToolResult create(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		String type = AdtDdic.normalizeType(Json.str(in, "type"));
		String name = Json.str(in, "name");
		String pkg = Json.str(in, "package");
		String transport = Json.str(in, "transport");
		boolean inGroup = Json.str(in, "group") != null && !Json.str(in, "group").isBlank();
		if ((pkg == null || pkg.isBlank()) && !(type.equals("FUNC") || type.equals("INCL") && inGroup)) {
			return ToolResult.error("Give 'package', e.g. $TMP.");
		}
		if (type.equals("MSAG") && transport != null && !transport.isBlank()
				&& c.transport(transport, cancel).isEmpty()) {
			// some releases drop the messages silently when given a task instead of a request
			return ToolResult.error(transport.trim().toUpperCase(Locale.ROOT) + " is not a transport request. Message "
					+ "classes need the request number, not the number of a task (adt_list_transports).");
		}
		String processing = Json.str(in, "processing_type");
		String updateKind = Json.str(in, "update_task_kind");
		if (type.equals("FUNC") && processing != null && !List.of("normal", "rfc", "update").contains(processing)) {
			return ToolResult.error("processing_type is normal, rfc or update.");
		}
		if ("update".equals(processing) == (updateKind == null || updateKind.isBlank()) && type.equals("FUNC")
				&& processing != null) {
			return ToolResult.error("update_task_kind goes with processing_type update, and only with it.");
		}
		String language = Json.str(in, "language");
		AdtDdic.CreateRequest req = AdtDdic.create(type, name, Json.str(in, "description"), pkg, transport,
				language == null || language.isBlank() ? s.language() : language, s.user(), in);
		List<AdtDdic.Message> messages = type.equals("MSAG") ? AdtDdic.messages(in) : List.of();
		AdtObjectRef ref = c.create(req, name, pkg, Json.str(in, "description"), cancel);
		String source = Json.str(in, "source");
		boolean written = source != null && !source.isBlank() && !AdtDdic.METADATA_ONLY.contains(type);
		if (type.equals("FUNC") && written) {
			source = AdtDdic.stripParameterComments(source);
		}
		StringBuilder notes = new StringBuilder();
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			// SAP stores only a shell for these on the POST; the metadata follows with a PUT
			String lang = AdtDdic.language(language == null || language.isBlank() ? s.language() : language);
			switch (type) {
			case "DTEL", "TTYP" -> AdtClient.writeMetadata(session, ref.uri(), req.body(),
					type.equals("DTEL") ? AdtDdic.DATAELEMENT_TYPE : AdtDdic.TABLETYPE_TYPE, transport, cancel);
			case "MSAG" -> {
				if (!messages.isEmpty()) {
					AdtClient.writeMetadata(session, ref.uri(), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
							+ AdtDdic.messageClassXml(ref.name(), Json.str(in, "description"),
									pkg.trim().toUpperCase(Locale.ROOT), lang, messages),
							AdtDdic.MESSAGECLASS_TYPE, transport, cancel);
					notes.append(" ").append(messages.size()).append(" messages written.");
				}
			}
			case "FUNC" -> {
				if (processing != null && !processing.equals("normal")) {
					String meta = c.readMetadata(ref.uri() + "?version=inactive", cancel);
					AdtClient.writeMetadata(session, ref.uri(),
							AdtDdic.withProcessingType(meta, processing, updateKind), AdtDdic.FUNCTION_MODULE_TYPE,
							transport, cancel);
					String stored = AdtDdic.processingType(c.readMetadata(ref.uri() + "?version=inactive", cancel));
					notes.append(stored.equals(processing) ? " Processing type " + processing + "."
							: " SAP kept processing type " + stored + " instead of " + processing
									+ "; set it in the function module's properties.");
				}
			}
			default -> {
				// nothing to add
			}
			}
			if (written) {
				AdtClient.writeSource(session, ref.uri(), null, source, transport, cancel);
			}
		} finally {
			c.invalidate(ref.uri());
			inactive.remove(s.destinationId());
		}
		String where = type.equals("FUNC") || type.equals("INCL") && inGroup
				? "function group " + Json.str(in, "group").trim().toUpperCase(Locale.ROOT)
				: "package " + pkg.trim().toUpperCase(Locale.ROOT);
		String checks = !written ? ""
				: AdtDdic.ABAP_SOURCE.contains(type) ? checksAfterWrite(c, ref.uri(), source, false, cancel)
						: syntaxAfterWrite(c, ref.uri(), cancel);
		return ToolResult.ok("Created " + ref.name() + " (" + type + ") in " + where + " on " + s.label() + "."
				+ notes + (type.equals("MSAG") ? "" : " Not activated yet.") + checks);
	}

	/** Changes DDIC metadata or the messages of a message class, keeping all fields not given. */
	private ToolResult writeMetadata(JsonObject in, CancelToken cancel) throws IOException {
		AdtSystem s = system(in);
		AdtClient c = client(s);
		String type = AdtDdic.normalizeType(Json.str(in, "type"));
		if (!List.of("DTEL", "DOMA", "TTYP", "MSAG").contains(type)) {
			return ToolResult.error("adt_write_metadata changes DTEL, DOMA, TTYP and MSAG; use adt_write_source for "
					+ "source-based objects.");
		}
		String name = Json.str(in, "name").trim().toUpperCase(Locale.ROOT);
		String uri = AdtDdic.objectUri(type, name, null);
		String current = c.readMetadata(uri, cancel);
		AdtDdic.Header h = AdtDdic.header(current);
		String description = Json.str(in, "description") == null ? h.description() : Json.str(in, "description");
		String lang = AdtDdic.language(h.language().isEmpty() ? s.language() : h.language());
		String body;
		String contentType;
		String summary;
		switch (type) {
		case "DTEL" -> {
			body = AdtDdic.dataElementXml(name, description, h.pkg(), lang, null,
					AdtDdic.dataElementFields(in, AdtDdic.parseDataElement(current)));
			contentType = AdtDdic.DATAELEMENT_TYPE;
			summary = "data element";
		}
		case "DOMA" -> {
			body = AdtDdic.domainXml(name, description, h.pkg(), lang, null,
					AdtDdic.domainFields(in, AdtDdic.parseDomain(current)));
			contentType = AdtDdic.DOMAIN_TYPE;
			summary = "domain";
		}
		case "TTYP" -> {
			String rowType = Json.str(in, "row_type");
			if (rowType == null || rowType.isBlank()) {
				return ToolResult.error("Give 'row_type'; it is the only table type field Bella changes.");
			}
			body = AdtDdic.tableTypeXml(name, description, h.pkg(), lang, null, rowType, Json.str(in, "row_type_kind"));
			contentType = AdtDdic.TABLETYPE_TYPE;
			summary = "table type";
		}
		default -> {
			AdtDdic.MessageClass mc = AdtDdic.parseMessageClass(current);
			List<String> remove = new ArrayList<>();
			JsonArray arr = Json.arr(in, "remove_numbers");
			if (arr != null) {
				arr.forEach(e -> remove.add(e.getAsString().trim()));
			}
			List<AdtDdic.Message> merged = AdtDdic.mergeMessages(mc.messages(), AdtDdic.messages(in), remove);
			body = AdtDdic.messageClassXml(name, description, h.pkg(), lang, merged);
			contentType = AdtDdic.MESSAGECLASS_TYPE;
			summary = "message class (" + merged.size() + " messages)";
		}
		}
		String tr;
		try (AdtTransport.Session session = backend.stateful(s.destinationId())) {
			tr = AdtClient.writeMetadata(session, uri, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + body,
					contentType, Json.str(in, "transport"), cancel);
		} finally {
			c.invalidate(uri);
			inactive.remove(s.destinationId());
		}
		return ToolResult.ok("Saved the " + summary + " " + name + " in " + s.label()
				+ (tr.isEmpty() ? "" : " (transport " + tr + ")")
				+ (type.equals("MSAG") ? "." : ". Not activated yet."));
	}

	/** Syntax check of a saved source that Bella's style check does not understand (CDS, RAP, DDIC sources). */
	private static String syntaxAfterWrite(AdtClient c, String objectUri, CancelToken cancel) {
		try {
			List<AdtClient.Message> msgs = c.syntaxCheck(objectUri, null, true, cancel);
			return msgs.isEmpty() ? "\n\nSyntax check: no errors." : "\n\nSyntax check of the saved version:\n" + format(msgs);
		} catch (IOException | RuntimeException e) {
			return "\n\nSyntax check could not run: " + e.getMessage();
		}
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
		boolean atc = in.has("run_atc") && in.get("run_atc").isJsonPrimitive() && in.get("run_atc").getAsBoolean();
		return ToolResult.ok(done + (test ? "\n\n" + unitTestsAfterActivation(c, refs, cancel) : "")
				+ (atc ? "\n\n" + atcAfterActivation(c, refs, cancel) : ""));
	}

	/** One ATC run over the activated objects, with what to do about the findings. */
	private static String atcAfterActivation(AdtClient c, List<AdtObjectRef> refs, CancelToken cancel) {
		try {
			List<AdtClient.Message> msgs = c.atcCheck(
					refs.stream().map(r -> AdtObjectRef.objectUri(r.uri())).toList(), null, cancel);
			if (msgs.isEmpty()) {
				return "ATC: no findings.";
			}
			return "ATC findings:\n" + formatAtc(msgs, refs.size() > 1)
					+ "Fix priority 1 and 2 findings now, then save, activate and check again; fix priority 3 where it "
					+ "is simple. For each finding you leave, tell the developer why (e.g. a false positive that needs "
					+ "an exemption)." + QUICKFIX_HINT;
		} catch (IOException e) {
			return "ATC could not run: " + e.getMessage();
		}
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
	/** {@code *} or column names, optionally with a table alias ({@code m~matnr}), separated by commas. */
	private static final Pattern SQL_COLUMNS = Pattern.compile(
			"\\*|[A-Za-z/][A-Za-z0-9_/]*(?:~[A-Za-z0-9_/]+)?(?:\\s*,\\s*[A-Za-z/][A-Za-z0-9_/]*(?:~[A-Za-z0-9_/]+)?)*");

	private ToolResult tableContents(JsonObject in, CancelToken cancel) throws IOException {
		String sql = Json.str(in, "sql");
		if (sql == null || sql.isBlank()) {
			String table = Json.str(in, "table");
			if (table == null || !SQL_NAME.matcher(table.trim()).matches()) {
				return ToolResult.error("Give 'table' (a table or CDS view name) or a SELECT statement in 'sql'.");
			}
			String columns = Json.str(in, "columns");
			if (columns != null && !columns.isBlank() && !SQL_COLUMNS.matcher(columns.trim()).matches()) {
				return ToolResult.error("'columns' takes column names separated by commas (e.g. matnr, mtart); "
						+ "for expressions, joins or aliases write the whole statement in 'sql'.");
			}
			String where = Json.str(in, "where");
			sql = "SELECT " + (columns == null || columns.isBlank() ? "*" : columns.trim()) + " FROM "
					+ table.trim().toUpperCase(Locale.ROOT)
					+ (where == null || where.isBlank() ? "" : " WHERE " + where.trim());
		}
		int max = Math.max(1, Math.min(TABLE_MAX_ROWS, Json.integer(in, "max_rows", 100)));
		AdtSystem s = system(in);
		AdtClient.TableData data = client(s).tableContents(sql, max, cancel);
		return ToolResult.ok(formatTable(data, s.label()));
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

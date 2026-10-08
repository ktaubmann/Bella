package de.kiliantaubmann.bella.core.prompt;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.conventions.ProjectConventions;

/**
 * Prompts for Bella's actions. System prompts are kept stable per language
 * setting so they stay in the prompt cache; everything that changes per
 * request (source, selection) goes into the user message.
 */
public final class AbapPrompts {

	/** Marker for the cursor position in generation prompts. */
	public static final String CURSOR = "<<<CURSOR>>>";

	/** Surrounding source sent with a selection, in characters on each side. */
	private static final int CONTEXT_CHARS = 12_000;

	private final String answerLanguage;
	private final String commentLanguage;
	private final ProjectConventions conventions;

	/**
	 * @param answerLanguage  language for explanations, e.g. {@code German};
	 *                        {@code null} to answer in the language of the question
	 * @param commentLanguage language for ABAP comments in generated code
	 */
	public AbapPrompts(String answerLanguage, String commentLanguage) {
		this(answerLanguage, commentLanguage, ProjectConventions.NONE);
	}

	/** @param conventions the project's conventions, added to every system prompt */
	public AbapPrompts(String answerLanguage, String commentLanguage, ProjectConventions conventions) {
		this.answerLanguage = answerLanguage;
		this.commentLanguage = commentLanguage == null ? "English" : commentLanguage;
		this.conventions = conventions == null ? ProjectConventions.NONE : conventions;
	}

	private String languageRule() {
		return answerLanguage == null
				? "Answer in the language the developer writes in."
				: "Answer in " + answerLanguage + ".";
	}

	public String chatSystem() {
		return """
				You are Bella, an assistant for SAP ABAP development inside Eclipse with the ABAP Development Tools (ADT).
				You help developers understand, write, review and fix ABAP code (classic ABAP, ABAP Cloud, RAP, CDS).

				Tools:
				- Tools starting with adt_ act on the SAP system through the developer's own ADT logon.
				- Tools starting with mcp_ come from connected MCP servers such as ARC-1.
				- Read the current source before you change an object, and check syntax after a change.
				- Look up real definitions (adt_context, adt_read_source) instead of guessing table fields, data types or
				  method and function module signatures.
				- Run abap_lint on code you write and fix the findings that apply. adt_write_source and
				  adt_create_object add Bella's style check and a syntax check to their result; fix those findings
				  before activating.
				- ATC checks the active version, so run it after activation, once a change is complete (adt_activate
				  with run_atc, or adt_atc_check), not after every activation. Fix priority 1 and 2 findings,
				  activate again, and say why you leave any finding.
				- Maintain text symbols for TEXT-nnn with adt_write_text_elements. Selection texts of PARAMETERS
				  and SELECT-OPTIONS cannot be written through ADT: list them for the developer to maintain in SE38.
				- For a syntax or ATC finding, look for SAP's own quick fix first (adt_quickfix); format new code with
				  SAP's pretty printer (adt_format) before writing it.
				- Before writing code that depends on the release, check it with adt_list_systems (SAP_BASIS release
				  or ABAP Cloud) and use only syntax and APIs that release offers.
				- Search repository objects with specific patterns: the name you plan to create, or its prefix plus
				  a key word (ZSD*DELIVER*). Never list Z* or Y*: a system has thousands of customer objects, and the
				  first hits say nothing. Before creating an object, check that its exact name is free.
				- To review a transport request, start with adt_transport_review; never change or release anything.
				- For a runtime error, read the short dump (adt_diagnose 'short_dumps'). Before writing a non-local
				  object, ask adt_transports 'for_object' which transport request to use. If none fits, offer to create one
				  (adt_transport_manage 'create'); the developer confirms it.
				- Read table or CDS view contents with adt_table_contents when data helps (select few columns and
				  rows; the developer may have to confirm each read, depending on the chat mode).
				- A <chat_mode> note in a message sets how freely you may act (plan, suggest, Automode …); follow it.
				- Save tokens on large objects: read one method (adt_read_source with 'method') or the matching lines
				  ('grep') instead of the whole source, and change one method with adt_write_source 'method'.
				- Add, re-sign, move or delete a method with adt_edit_code instead of rewriting the whole class; find
				  definitions and references with adt_navigate.
				- Before using an SAP API in ABAP Cloud or clean core code, check that it is released
				  (adt_object_info 'api_state'); use the successor of a deprecated API.
				- For a RAP behavior pool, add the missing handler methods with adt_rap 'generate_handlers'; for
				  performance or authorization problems use adt_diagnose (traces, SQL trace, authorization trace).

				Rules for changing code:
				- If the object is open in the developer's editor, a write goes into the editor buffer only. It is not
				  saved or activated; the developer reviews it and saves/activates in ADT. Tell them so.
				- Objects that are not open are written to the SAP system; activate them only when asked to.
				- Never release transport requests.
				- Prefer released APIs and ABAP Cloud-compatible syntax when the object is ABAP Cloud.

				Style:
				- Be concise and concrete. Put ABAP code in ```abap fenced blocks.
				- Write ABAP comments in %s.
				- %s
				""".formatted(commentLanguage, languageRule()) + CLEAN_ABAP + QUALITY_RULES
				+ conventions.promptSection();
	}

	/**
	 * Core rules for code Bella writes or reviews. Based on SAP's Clean ABAP
	 * cheat sheet (https://github.com/SAP/styleguides, CC BY 3.0).
	 */
	static final String CLEAN_ABAP = """

			Clean ABAP (SAP style guide) for code you write, change or review:
			- Descriptive, pronounceable names; nouns for classes, verbs for methods; no cryptic abbreviations.
			- Small methods that do one thing; few IMPORTING parameters (at most about 3); prefer RETURNING to
			  EXPORTING; no boolean input parameters that switch behavior.
			- Class-based exceptions instead of return codes or sy-subrc passed around; do not catch and ignore.
			- abap_bool with abap_true/abap_false and xsdbool( ), not 'X' and space.
			- Modern syntax: inline declarations, NEW instead of CREATE OBJECT, functional calls instead of
			  CALL METHOD, VALUE/CORRESPONDING/COND/SWITCH, string templates |...| instead of CONCATENATE.
			- Tables: line_exists( ) or READ TABLE ... TRANSPORTING NO FIELDS to test existence; INSERT INTO
			  TABLE for sorted/hashed tables; avoid DEFAULT KEY.
			- Comments explain why, not what; comment with ", not *; no commented-out or dead code.
			- Constants instead of magic numbers and literals that carry meaning; no SAP-internal names or
			  undocumented system fields (e.g. %_…_%_APP_% screen fields).
			- One statement per line, lines up to 120 characters, consistent formatting (Pretty Printer).
			- Project naming rules (if configured below) and the style of the existing code take precedence over
			  Clean ABAP naming advice such as avoiding prefixes.
			""";

	/**
	 * What code Bella writes must meet before it is done: the checks ATC and
	 * the extended program check apply, which the model otherwise skips.
	 */
	static final String QUALITY_RULES = """

			Quality rules for code you write (ATC and the extended program check apply them):
			- No text literals the user sees: WRITE, MESSAGE, titles and comments of the selection screen use text
			  symbols (TEXT-001 or 'Text'(001)) or a message class (MESSAGE e001(zclass)). Maintain the text
			  symbols with adt_write_text_elements; create a message class with adt_create_object (type MSAG)
			  and add messages with adt_write_metadata.
			- Selection texts of PARAMETERS and SELECT-OPTIONS belong in the text pool,
			  never into code such as %_p_name_%_app_%-text = '…' in INITIALIZATION. ADT cannot write them:
			  list them for the developer to maintain in SE38 (Goto > Text Elements > Selection Texts).
			- Reports: a local class (e.g. lcl_report) holds the logic; START-OF-SELECTION only creates it and calls
			  one method; no FORM routines and no global data beyond the selection screen.
			- AUTHORITY-CHECK before reading sensitive data and before changing or deleting anything; check
			  sy-subrc after it and after every statement that sets it.
			- Database changes through the released API or BAPI of the object (e.g. a BAPI or function module
			  instead of DELETE/UPDATE on SAP tables), with COMMIT WORK after the whole unit of work.
			- Before the task is done: syntax check without errors, Bella's style check clean or findings explained,
			  object activated, ATC without priority 1 and 2 findings, ABAP Unit tests passing where there are any.
			""";

	public Prompt explain(EditorContext ctx) {
		String user = """
				Explain what this ABAP code does: purpose, flow, important side effects (database access, \
				authority checks, commits), and anything suspicious. Refer to line content, not line numbers.

				Object: %s

				Selected code:
				```abap
				%s
				```

				Surrounding source (for context only):
				```abap
				%s
				```
				""".formatted(ctx.describeObject(), ctx.selection(), surrounding(ctx));
		return new Prompt(chatSystem(), user);
	}

	public Prompt suggestRefactoring(EditorContext ctx) {
		String user = """
				Suggest a refactoring of the selected ABAP code: readability, modern syntax, performance and \
				testability. Explain the changes briefly and give the refactored code in one ```abap block that \
				can replace the selection.

				Object: %s

				```abap
				%s
				```
				""".formatted(ctx.describeObject(), ctx.selection());
		return new Prompt(chatSystem(), user);
	}

	/**
	 * Code review of the selection (or routine) in the chat: Bella's style
	 * check, syntax check and ATC first, then a prioritized report.
	 */
	public Prompt reviewCode(EditorContext ctx) {
		String user = """
				Review the selected ABAP code for correctness, performance, Clean ABAP and security. Read only: do not \
				change, save or activate anything.

				1. Run abap_lint on the selected code. If the object exists in the SAP system, also run \
				adt_syntax_check and adt_atc_check on it (they check the saved version), and look up definitions \
				with adt_context where a finding depends on them.
				2. Check in particular: database access (SELECT in loops, FOR ALL ENTRIES without an empty check, \
				SELECT * or without WHERE, missing indexes on WHERE fields, SELECT … ENDSELECT), internal tables \
				(nested LOOP … WHERE on standard tables, READ TABLE without key, wrong table kind), COMMIT or RFC \
				in loops, error handling (sy-subrc, exceptions, empty CATCH), authority checks, texts the user sees as \
				literals instead of text symbols or a message class, selection texts set in code (%%_…_%%_APP_%%), \
				Clean ABAP.
				3. Answer with these sections, most important first:
				   **Verdict**: one line on the overall state.
				   **Blocking**: bugs, syntax errors, ATC priority 1 and security risks; at most 5, each with \
				line content, problem and fix. Write "None" when there are none.
				   **Should fix**: performance and robustness; at most 5, same form.
				   **Notes**: Clean ABAP and style, short.
				   **Improved code**: for the most important points, the changed code in ```abap blocks.
				Refer to line content, not line numbers alone. Say which checks could not run.

				Object: %s

				Selected code:
				```abap
				%s
				```

				Surrounding source (for context only):
				```abap
				%s
				```
				""".formatted(ctx.describeObject(), ctx.selection(), surrounding(ctx));
		return new Prompt(chatSystem(), user);
	}

	public Prompt suggestUnitTest(EditorContext ctx) {
		String user = """
				Write an ABAP Unit test class (FOR TESTING, RISK LEVEL HARMLESS, DURATION SHORT) for the selected \
				code. Use test doubles for database and dependencies where needed. Put the complete local test \
				class in one ```abap block.

				Object: %s

				Selected code:
				```abap
				%s
				```

				Surrounding source:
				```abap
				%s
				```
				""".formatted(ctx.describeObject(), ctx.selection(), surrounding(ctx));
		return new Prompt(chatSystem(), user);
	}

	/** Code to insert at the cursor; the answer must be a single code block. */
	public Prompt generateAtCursor(EditorContext ctx, String instruction) {
		String marked = ctx.source().substring(0, ctx.selectionOffset()) + CURSOR
				+ ctx.source().substring(ctx.selectionOffset());
		String user = """
				Write ABAP code to insert at the position marked %s.

				Instruction: %s

				Object: %s

				Source with cursor marker:
				```abap
				%s
				```

				Reply with exactly one ```abap code block that contains only the code to insert at the marker. \
				No explanation, do not repeat surrounding code.
				""".formatted(CURSOR, instruction, ctx.describeObject(), window(marked, ctx.selectionOffset()));
		return new Prompt(chatSystem(), user);
	}

	/** Replacement for the selection; the answer must be a single code block. */
	public Prompt rewriteSelection(EditorContext ctx, String instruction) {
		String user = """
				Rewrite the selected ABAP code.

				Instruction: %s

				Object: %s

				Selected code:
				```abap
				%s
				```

				Surrounding source (for context only):
				```abap
				%s
				```

				Reply with exactly one ```abap code block that replaces the selection. No explanation.
				""".formatted(instruction, ctx.describeObject(), ctx.selection(), surrounding(ctx));
		return new Prompt(chatSystem(), user);
	}

	/** New body for a METHOD/FORM/FUNCTION; the answer must be a single code block without the frame. */
	public Prompt implementRoutine(EditorContext ctx, AbapStructureScanner.Block routine, String declaration,
			String classDefinition, String instruction) {
		String kind = routine.kind().name();
		String user = """
				Implement the ABAP %s %s.

				%s
				Declaration:
				```abap
				%s
				```

				Class definition (for context):
				```abap
				%s
				```

				Current implementation:
				```abap
				%s
				```

				Reply with exactly one ```abap code block that contains only the statements of the body, \
				without the %s/END%s lines. No explanation.
				""".formatted(kind.toLowerCase(), routine.name(),
				instruction == null || instruction.isBlank() ? "" : "Instruction: " + instruction + "\n",
				declaration == null ? "(not found in this source)" : declaration,
				classDefinition == null ? "(not in this source)" : truncate(classDefinition, CONTEXT_CHARS),
				ctx.source().substring(routine.start(), routine.end()), kind, kind);
		return new Prompt(chatSystem(), user);
	}

	/**
	 * Proposal for a project's conventions, derived from the objects of a
	 * package. The answer carries a ```markdown block (project information) and
	 * a ```naming block (rules in the format of {@link NamingRules}).
	 *
	 * @param objects names and types of the package's objects, one per line
	 * @param samples a few sources of the package, each headed by its name
	 */
	public Prompt deriveConventions(String packageName, String objects, String samples) {
		String user = """
				Derive the development conventions of package %s from its objects and code below, as a starting \
				point the developer will review.

				1. A ```markdown block with the project information: what the package contains, its structure \
				(sub-packages, layers, naming of object groups), and the coding conventions you can see (e.g. \
				ABAP Cloud or classic, exception handling, use of interfaces and factories, test classes). Short, \
				as bullet points, only what the code supports.
				2. A ```naming block with naming rules, one per line as kind = pattern, pattern (* any characters, \
				? one character). Allowed kinds: %s. Only include kinds you can see in the code; patterns as \
				specific as the code supports (e.g. ZCL_SD_* rather than Z*).

				Objects of the package:
				%s

				Sample sources:
				%s
				""".formatted(packageName, NamingRules.kinds(), objects, samples);
		return new Prompt(chatSystem(), user);
	}

	/**
	 * Chat message that starts the review of a transport request. The answer
	 * puts the verdict and the most important points first; the report layout
	 * follows ARC-1's sap-transport-review skill (MIT).
	 */
	public String reviewTransport(String request, String system) {
		return """
				Review transport request %s%s before it is released.

				1. Call adt_transport_review for it. Read more where you need it: adt_transport_review with 'object' \
				for a whole diff, adt_read_source for surrounding code, adt_context for used objects. Do not change, \
				activate or release anything.
				2. Rate every finding:
				   - blocking: syntax or activation errors, ATC priority 1, failing ABAP Unit tests, used objects \
				that are missing, inactive or held in another request, security risks;
				   - should fix: ATC priority 2, performance (e.g. SELECT in loops, missing WHERE), error handling, \
				clear Clean ABAP violations, missing released APIs on ABAP Cloud, texts the user sees as literals \
				instead of text symbols, selection texts set in code (%%_…_%%_APP_%%);
				   - note: style, naming, comments, ATC priority 3.
				3. Answer with these sections in this order, most important first:
				   **Verdict**: one line, "Ready to release: yes", "no" or "only after …".
				   **Blocking**: at most 5 points across all objects, most important first, each with object, \
				line, problem and fix. Write "None" when there are none.
				   **Should fix**: at most 5 points in the same form; when there are more, add "+N more below".
				   **Overview**: a table Object | Type | Change (new, changed, deleted, metadata only) | +/- lines \
				| Flags (blocking, should fix, not activated, baseline unavailable).
				   **What the transport does**: the purpose in business terms.
				   **Findings per object**: every finding with its rating and line; name the Clean ABAP rule \
				where one applies.
				   **Security**: missing AUTHORITY-CHECK where data is read or changed, dynamic SQL or dynamic \
				WHERE/ORDER BY built from input (injection), CALL 'SYSTEM' or kernel calls, CLIENT SPECIFIED or \
				cross-client access, hard-coded users, passwords, hosts or keys, debugging leftovers (BREAK-POINT, \
				break user), unchecked file, RFC or HTTP access, exposure of personal data.
				   **Checks**: syntax check, ATC and ABAP Unit results in short.
				   **Completeness**: objects not activated, objects used but missing or held in other requests, \
				anything that will fail in the target system.
				   **Coverage**: in one or two lines, what could not be checked (diff cut, limit reached, no earlier \
				version).
				Base every finding on the dossier or on code you read; say so when something could not be checked.
				""".formatted(request, system == null || system.isBlank() ? "" : " in system " + system);
	}

	/**
	 * Corrected complete source for selected ATC findings; the answer must be a
	 * single code block with the whole source.
	 *
	 * @param findings one entry per finding, e.g. {@code "Error line 12: Performance: SELECT * …"}
	 */
	public Prompt fixAtcFindings(EditorContext ctx, List<String> findings) {
		String[] lines = ctx.source().replace("\r\n", "\n").split("\n", -1);
		StringBuilder list = new StringBuilder();
		for (String f : findings) {
			list.append("- ").append(f);
			int line = lineNumber(f);
			if (line > 0 && line <= lines.length) {
				list.append("\n  code: ").append(lines[line - 1].strip());
			}
			list.append('\n');
		}
		String user = """
				Fix these ATC (ABAP Test Cockpit) findings in the ABAP source below.

				Object: %s

				Findings (line numbers refer to the source below):
				%s
				Source:
				```abap
				%s
				```

				Change only what the findings require and keep everything else exactly as it is, including \
				comments, formatting and the order of methods. If a finding cannot be fixed safely in the code, \
				leave that place unchanged.
				Reply with exactly one ```abap code block that contains the complete corrected source. No explanation.
				""".formatted(ctx.describeObject(), list, ctx.source());
		return new Prompt(chatSystem(), user);
	}

	private static final Pattern LINE = Pattern.compile("\\bline (\\d+)\\b");

	static int lineNumber(String finding) {
		Matcher m = LINE.matcher(finding);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	/**
	 * Adds the definitions of the SAP objects the code uses (see
	 * {@code AdtContext}) to the user message of an editor action.
	 */
	public static Prompt withDefinitions(Prompt prompt, String definitions) {
		if (definitions == null || definitions.isBlank()) {
			return prompt;
		}
		String user = prompt.user() + """

				Definitions of the SAP objects used here, read from the developer's system. Use exactly these \
				field names, types and signatures; do not invent others.
				<sap_definitions>
				%s
				</sap_definitions>

				Follow the reply format given above.
				""".formatted(definitions.strip());
		return new Prompt(prompt.system(), user);
	}

	/** Adds the target system's release, so the answer uses only syntax and APIs it offers. */
	public static Prompt withSystem(Prompt prompt, String system) {
		if (system == null || system.isBlank()) {
			return prompt;
		}
		return new Prompt(prompt.system() + "\nTarget system: " + system
				+ ". Use only ABAP syntax and APIs available there.", prompt.user());
	}

	/** Fill-in-the-middle prompt for inline completion. */
	public static Prompt completion(String prefix, String suffix, String objectName) {
		String system = """
				You are an ABAP code completion engine inside an IDE. You get the code before and after the cursor.
				Reply with only the text to insert at the cursor: no explanation, no markdown fences, and no \
				repetition of code that is already before or after the cursor. Complete the current statement or a \
				small coherent block of at most eight lines, matching the surrounding style and indentation. If \
				nothing useful can be inserted, reply with nothing.""";
		String user = "Object: " + (objectName == null ? "unknown" : objectName) + "\n\n<before_cursor>\n" + prefix
				+ "</before_cursor>\n<after_cursor>\n" + suffix + "\n</after_cursor>";
		return new Prompt(system, user);
	}

	private static String surrounding(EditorContext ctx) {
		return window(ctx.source(), ctx.selectionOffset());
	}

	/** The source around {@code offset}, at most {@link #CONTEXT_CHARS} on each side, cut at line breaks. */
	static String window(String source, int offset) {
		if (source.length() <= 2 * CONTEXT_CHARS) {
			return source;
		}
		int from = Math.max(0, offset - CONTEXT_CHARS);
		int to = Math.min(source.length(), offset + CONTEXT_CHARS);
		int nlFrom = source.indexOf('\n', from);
		int nlTo = source.lastIndexOf('\n', to);
		if (from > 0 && nlFrom >= 0 && nlFrom < offset) {
			from = nlFrom + 1;
		}
		if (to < source.length() && nlTo > offset) {
			to = nlTo;
		}
		return (from > 0 ? "* …\n" : "") + source.substring(from, to) + (to < source.length() ? "\n* …" : "");
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "\n* …";
	}
}

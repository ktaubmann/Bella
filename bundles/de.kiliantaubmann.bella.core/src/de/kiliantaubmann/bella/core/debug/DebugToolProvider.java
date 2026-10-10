package de.kiliantaubmann.bella.core.debug;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * The debugger tools: {@code debug_context} reads the stopped session,
 * {@code debug_breakpoint} sets and removes line breakpoints,
 * {@code debug_step} steps and resumes.
 */
public final class DebugToolProvider implements ToolProvider {

	/** How long {@code debug_step} waits for the session to stop again. */
	static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);

	static final String NO_SESSION = "No ABAP debug session is stopped in Eclipse. Ask the developer to start the "
			+ "program in the debugger (a breakpoint in the editor, then run it) and to tell you when it stops.";

	private final DebugBackend backend;
	private final Duration stepTimeout;

	public DebugToolProvider(DebugBackend backend) {
		this(backend, STEP_TIMEOUT);
	}

	DebugToolProvider(DebugBackend backend, Duration stepTimeout) {
		this.backend = backend;
		this.stepTimeout = stepTimeout;
	}

	@Override
	public String id() {
		return ToolRegistry.DEBUG_PROVIDER_ID;
	}

	@Override
	public String displayName() {
		return "ABAP debugger";
	}

	@Override
	public List<ToolSpec> listTools() {
		return List.of(
				ToolSpec.of("debug_context",
						"Experimental. State of the ABAP debug session the developer runs in Eclipse: the line the debugger stands "
								+ "at, the call stack, the variables of the current frame (tables and structures two "
								+ "levels deep, at most " + DebugSnapshot.MAX_CHILDREN + " rows), the exception it "
								+ "stopped at and the breakpoints. With 'variable' one variable in full, e.g. "
								+ "LT_ITEMS/[3] for a table row or LO_ORDER/MV_STATUS for an attribute.",
						schema(new String[0], "variable", "string",
								"Path of one variable as in the Variables view, parts separated by /."),
						Capability.DEBUG_STATE, ToolSpec.Kind.READ),
				ToolSpec.of("debug_breakpoint",
						"Experimental. Set or remove a line breakpoint in Eclipse, as the developer would in the editor; an object "
								+ "that is not open is opened in an editor first. Suggest breakpoints with the reason "
								+ "first and set them when the developer agrees.",
						schema(new String[] { "action", "name", "line" }, "action", "string", "set or remove.",
								"name", "string", "Program, class, function group or include, e.g. ZCL_ORDER.",
								"type", "string", "Object type: PROG, CLAS, FUGR, INCL. Omit if unknown.",
								"line", "integer", "Line (from 1) of the main source or include."),
						// changes Eclipse only, not the SAP system: also offered in the suggest and plan modes
						null, ToolSpec.Kind.READ),
				ToolSpec.of("debug_step",
						"Experimental. Go on in the stopped debug session: into (step into), over (step over), return (to the "
								+ "caller) or resume (run to the next breakpoint). Returns the new state. Only when the "
								+ "developer asked for it: resuming may run COMMIT WORK and change data.",
						schema(new String[] { "action" }, "action", "string", "into, over, return or resume."),
						null, ToolSpec.Kind.WRITE));
	}

	private static JsonObject schema(String[] required, String... props) {
		JsonObject p = new JsonObject();
		for (int i = 0; i + 2 < props.length; i += 3) {
			JsonObject prop = new JsonObject();
			prop.addProperty("type", props[i + 1]);
			prop.addProperty("description", props[i + 2]);
			p.add(props[i], prop);
		}
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", p);
		JsonArray req = new JsonArray();
		for (String r : required) {
			req.add(r);
		}
		s.add("required", req);
		return s;
	}

	@Override
	public ToolResult call(String name, JsonObject input, CancelToken cancel) throws Exception {
		try {
			return switch (name) {
			case "debug_context" -> context(Json.str(input, "variable"));
			case "debug_breakpoint" -> breakpoint(input);
			case "debug_step" -> step(Json.str(input, "action"));
			default -> ToolResult.error("Unknown tool " + name);
			};
		} catch (IllegalStateException e) {
			return ToolResult.error(e.getMessage());
		}
	}

	private ToolResult context(String variable) throws Exception {
		if (variable != null && !variable.isBlank()) {
			Optional<DebugSnapshot.Variable> v = backend.variable(variable.strip());
			if (v.isEmpty()) {
				return backend.snapshot().isEmpty() ? ToolResult.error(NO_SESSION)
						: ToolResult.error("No variable " + variable.strip() + " in the current frame. Use the names "
								+ "debug_context lists, parts separated by /.");
			}
			return ToolResult.ok(DebugSnapshot.format(v.get()));
		}
		Optional<DebugSnapshot> s = backend.snapshot();
		String breakpoints = breakpointList();
		if (s.isEmpty()) {
			return ToolResult.ok(NO_SESSION + breakpoints);
		}
		return ToolResult.ok(s.get().format() + breakpoints);
	}

	private String breakpointList() throws Exception {
		List<DebugBackend.Breakpoint> list = backend.breakpoints();
		if (list.isEmpty()) {
			return "\nBreakpoints: none\n";
		}
		StringBuilder sb = new StringBuilder("\nBreakpoints:\n");
		for (DebugBackend.Breakpoint b : list) {
			sb.append("- ").append(b.object()).append(" line ").append(b.line())
					.append(b.enabled() ? "" : " (disabled)").append('\n');
		}
		return sb.toString();
	}

	private ToolResult breakpoint(JsonObject input) throws Exception {
		String action = lower(Json.str(input, "action"));
		String object = Json.str(input, "name");
		int line = Json.integer(input, "line", 0);
		if (!action.equals("set") && !action.equals("remove")) {
			return ToolResult.error("'action' is set or remove.");
		}
		if (object == null || object.isBlank() || line < 1) {
			return ToolResult.error("Give 'name' (the object) and 'line' (from 1).");
		}
		String obj = object.strip().toUpperCase(Locale.ROOT);
		String type = Json.str(input, "type");
		boolean on = action.equals("set");
		backend.setBreakpoint(obj, type == null ? "" : type.strip().toUpperCase(Locale.ROOT), line, on);
		return ToolResult.ok((on ? "Set a breakpoint in " : "Removed the breakpoint in ") + obj + " at line " + line
				+ "." + breakpointList());
	}

	private ToolResult step(String action) throws Exception {
		DebugBackend.Step step = switch (lower(action)) {
		case "into" -> DebugBackend.Step.INTO;
		case "over" -> DebugBackend.Step.OVER;
		case "return" -> DebugBackend.Step.RETURN;
		case "resume" -> DebugBackend.Step.RESUME;
		default -> null;
		};
		if (step == null) {
			return ToolResult.error("'action' is into, over, return or resume.");
		}
		if (backend.snapshot().isEmpty()) {
			return ToolResult.error(NO_SESSION);
		}
		Optional<DebugSnapshot> after = backend.step(step, stepTimeout);
		if (after.isEmpty()) {
			return ToolResult.ok("The program runs on and has not stopped within " + stepTimeout.toSeconds()
					+ " seconds (or it has ended). Ask the developer to tell you when it stops again.");
		}
		return ToolResult.ok(after.get().format());
	}

	private static String lower(String s) {
		return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
	}
}

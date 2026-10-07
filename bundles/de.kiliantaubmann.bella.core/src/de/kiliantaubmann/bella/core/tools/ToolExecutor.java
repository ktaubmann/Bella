package de.kiliantaubmann.bella.core.tools;

import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * Runs a tool call the way Bella always does, no matter who asked for it
 * (Bella's own chat loop or the Claude Code CLI through Bella's MCP server):
 * validate input, apply the policy, let the write guard redirect writes to
 * open objects into the editor, ask for confirmation, then execute.
 */
public final class ToolExecutor {

	/** Asks the developer whether a tool call may run. Blocks until answered. */
	public interface Confirmer {
		boolean confirm(ToolSpec tool, JsonObject input);
	}

	/** Notified before and after a tool runs, e.g. to show it in the chat. */
	public interface Observer {

		Observer NONE = new Observer() {
		};

		default void onToolCall(ToolSpec tool, ToolCall call) {
		}

		default void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
		}
	}

	private static final String AREA = "tool";
	/** Input of {@code adt_activate} that runs ABAP Unit after a successful activation. */
	static final String AUTO_TEST = "run_unit_tests";

	private final ToolRegistry tools;
	private final Supplier<ToolPolicy> policy;
	private final Confirmer confirmer;
	private final WriteGuard writeGuard;

	public ToolExecutor(ToolRegistry tools, Supplier<ToolPolicy> policy, Confirmer confirmer, WriteGuard writeGuard) {
		this.tools = tools;
		this.policy = policy;
		this.confirmer = confirmer;
		this.writeGuard = writeGuard == null ? WriteGuard.NONE : writeGuard;
	}

	public ToolRegistry registry() {
		return tools;
	}

	public ToolResult run(ToolCall call, Observer observer, CancelToken cancel) {
		Optional<ToolSpec> spec = tools == null ? Optional.empty() : tools.find(call.name());
		if (spec.isEmpty()) {
			Log.warn(AREA, "unknown tool " + call.name());
			return ToolResult.error("Unknown tool: " + call.name());
		}
		ToolSpec tool = spec.get();
		observer.onToolCall(tool, call);
		long start = System.nanoTime();
		Log.debug(AREA, () -> tool.name() + " input: " + Log.clip(call.inputValid() ? Json.GSON.toJson(call.input())
				: call.rawInput()));
		ToolResult result;
		if (!call.inputValid()) {
			JsonObject err = new JsonObject();
			err.addProperty("INVALID_JSON", call.rawInput());
			err.addProperty("reason", call.inputError());
			result = ToolResult.error(Json.GSON.toJson(err));
			Log.warn(AREA, tool.name() + ": invalid input JSON: " + call.inputError());
		} else {
			result = decideAndRun(tool, call.input(), cancel);
		}
		ToolResult r = result;
		String content = r.content() == null ? "" : r.content();
		if (r.isError()) {
			Log.warn(AREA, tool.name() + " -> error (" + Log.millisSince(start) + " ms): " + Log.clip(content, 500));
		} else {
			Log.info(AREA, tool.name() + " -> ok, " + content.length() + " chars (" + Log.millisSince(start) + " ms)");
		}
		Log.debug(AREA, () -> tool.name() + " result:\n" + Log.clip(content));
		observer.onToolResult(tool, call, result);
		return result;
	}

	private ToolResult decideAndRun(ToolSpec tool, JsonObject input, CancelToken cancel) {
		ToolPolicy rules = policy.get();
		ToolPolicy.Decision decision = rules.decide(tool, input);
		Log.info(AREA, tool.name() + " (" + tool.providerId() + "): policy " + decision
				+ (rules.mode() == ChatMode.NORMAL ? "" : ", mode " + rules.mode()));
		if (decision == ToolPolicy.Decision.DENY) {
			return ToolResult.error(rules.refusal(tool));
		}
		// The write guard runs before confirmation: writing into an open editor
		// is harmless (nothing is saved) and the developer sees the diff there.
		Optional<ToolResult> intercepted = writeGuard.intercept(tool, input);
		if (intercepted.isPresent()) {
			Log.info(AREA, tool.name() + ": redirected into the open editor");
			return intercepted.get();
		}
		if (rules.editorOnly(tool)) {
			Log.info(AREA, tool.name() + ": refused in suggest mode");
			return ToolResult.error(rules.refusal(tool));
		}
		Optional<ToolProvider> owner = tools.providerOf(tool.name());
		if (owner.isEmpty()) {
			return ToolResult.error("Tool provider is no longer available: " + tool.name());
		}
		Optional<String> refused = owner.get().refuse(tool.remoteName(), input, cancel);
		if (refused.isPresent()) {
			Log.info(AREA, tool.name() + ": refused by the provider: " + refused.get());
			return ToolResult.error(refused.get());
		}
		if (decision == ToolPolicy.Decision.CONFIRM && !confirmer.confirm(tool, input)) {
			Log.info(AREA, tool.name() + ": declined by the developer");
			return ToolResult.error("The developer declined this tool call.");
		}
		JsonObject effective = input;
		if (rules.mode() == ChatMode.AUTO && ToolRegistry.ADT_PROVIDER_ID.equals(tool.providerId())
				&& "adt_activate".equals(tool.remoteName())) {
			// Automode tests every activation; the model can neither forget nor switch it off.
			effective = input.deepCopy();
			effective.addProperty(AUTO_TEST, true);
		}
		try {
			return owner.get().call(tool.remoteName(), effective, cancel);
		} catch (Exception e) {
			Log.error(AREA, tool.name() + " threw an exception", e);
			return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}

package de.kiliantaubmann.bella.core.tools;

import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

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
			return ToolResult.error("Unknown tool: " + call.name());
		}
		ToolSpec tool = spec.get();
		observer.onToolCall(tool, call);
		ToolResult result;
		if (!call.inputValid()) {
			JsonObject err = new JsonObject();
			err.addProperty("INVALID_JSON", call.rawInput());
			err.addProperty("reason", call.inputError());
			result = ToolResult.error(Json.GSON.toJson(err));
		} else {
			result = decideAndRun(tool, call.input(), cancel);
		}
		observer.onToolResult(tool, call, result);
		return result;
	}

	private ToolResult decideAndRun(ToolSpec tool, JsonObject input, CancelToken cancel) {
		ToolPolicy.Decision decision = policy.get().decide(tool, input);
		if (decision == ToolPolicy.Decision.DENY) {
			return ToolResult.error("Refused by Bella's tool policy. Do not retry this call; tell the developer.");
		}
		// The write guard runs before confirmation: writing into an open editor
		// is harmless (nothing is saved) and the developer sees the diff there.
		Optional<ToolResult> intercepted = writeGuard.intercept(tool, input);
		if (intercepted.isPresent()) {
			return intercepted.get();
		}
		if (decision == ToolPolicy.Decision.CONFIRM && !confirmer.confirm(tool, input)) {
			return ToolResult.error("The developer declined this tool call.");
		}
		Optional<ToolProvider> owner = tools.providerOf(tool.name());
		if (owner.isEmpty()) {
			return ToolResult.error("Tool provider is no longer available: " + tool.name());
		}
		try {
			return owner.get().call(tool.remoteName(), input, cancel);
		} catch (Exception e) {
			return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}

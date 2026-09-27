package de.kiliantaubmann.bella.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.tools.WriteGuard;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.CancelToken.CancelledException;
import de.kiliantaubmann.bella.core.util.Json;

/**
 * A conversation with the model including the client-side tool loop: the
 * model asks for a tool, Bella checks policy, confirmation and the write
 * guard, runs the tool and sends the result back until the model is done.
 * The history is append-only, so thinking blocks stay valid and the prompt
 * cache keeps hitting.
 */
public final class ChatSession {

	/** Progress callbacks, called on the thread that runs {@link #send}. */
	public interface Listener extends StreamListener {

		default void onTurnStart() {
		}

		default void onToolCall(ToolSpec tool, ToolCall call) {
		}

		default void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
		}

		/** Something the developer should know that is not model text (refusal, truncation, policy). */
		default void onNotice(String message) {
		}

		default void onTurnEnd(ChatResult last) {
		}
	}

	/** Asks the developer whether a tool call may run. Blocks until answered. */
	public interface Confirmer {
		boolean confirm(ToolSpec tool, JsonObject input);
	}

	/** Model settings for a chat. */
	public record Settings(String model, int maxTokens, String effort) {
	}

	public static final int MAX_TOOL_ROUNDS = 25;

	private final List<JsonObject> history = new ArrayList<>();
	private final Supplier<LlmProvider> provider;
	private final Supplier<Settings> settings;
	private final ToolRegistry tools;
	private final Supplier<ToolPolicy> policy;
	private final Confirmer confirmer;
	private final WriteGuard writeGuard;
	private volatile String system;

	public ChatSession(Supplier<LlmProvider> provider, Supplier<Settings> settings, String system, ToolRegistry tools,
			Supplier<ToolPolicy> policy, Confirmer confirmer, WriteGuard writeGuard) {
		this.provider = provider;
		this.settings = settings;
		this.system = system;
		this.tools = tools;
		this.policy = policy;
		this.confirmer = confirmer;
		this.writeGuard = writeGuard == null ? WriteGuard.NONE : writeGuard;
	}

	public synchronized List<JsonObject> history() {
		return List.copyOf(history);
	}

	public synchronized void reset(String newSystem) {
		history.clear();
		system = newSystem;
	}

	/**
	 * Adds a user message and runs the model (and tools) until the turn ends.
	 *
	 * @return the last model result
	 */
	public ChatResult send(String userText, Listener listener, CancelToken cancel)
			throws LlmException, CancelledException {
		synchronized (this) {
			history.add(Json.userText(userText));
		}
		listener.onTurnStart();
		ChatResult result = null;
		for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
			cancel.throwIfCancelled();
			Settings s = settings.get();
			List<ToolSpec> available = tools == null ? List.of() : tools.tools();
			ChatRequest request;
			synchronized (this) {
				request = new ChatRequest(s.model(), system, history, available, s.maxTokens(),
						ChatRequest.Purpose.CHAT, s.effort());
			}
			try {
				result = provider.get().chat(request, listener, cancel);
			} catch (CancelledException | LlmException e) {
				rollbackDanglingUser();
				throw e;
			}
			synchronized (this) {
				history.add(result.assistantMessage());
			}
			if (result.stopReason() == StopReason.REFUSAL) {
				listener.onNotice("refusal:" + (result.stopDetail() == null ? "" : result.stopDetail()));
				break;
			}
			if (result.stopReason() == StopReason.MAX_TOKENS && !result.toolCalls().isEmpty()) {
				// A tool input cut off at max_tokens may still parse; never run it.
				listener.onNotice("max_tokens_tool");
				answerSkippedTools(result, "Output was cut off at max_tokens; this tool call was not executed.");
				break;
			}
			if (result.stopReason() == StopReason.MAX_TOKENS) {
				listener.onNotice("max_tokens");
				break;
			}
			if (result.toolCalls().isEmpty()) {
				if (result.stopReason() == StopReason.PAUSE_TURN) {
					continue;
				}
				break;
			}
			JsonArray results = new JsonArray();
			for (ToolCall call : result.toolCalls()) {
				cancel.throwIfCancelled();
				ToolResult r = runTool(call, listener, cancel);
				JsonObject block = new JsonObject();
				block.addProperty("type", "tool_result");
				block.addProperty("tool_use_id", call.id());
				block.addProperty("content", r.content() == null || r.content().isEmpty() ? "(empty)" : r.content());
				if (r.isError()) {
					block.addProperty("is_error", true);
				}
				results.add(block);
			}
			// All results of one turn go back in a single user message.
			synchronized (this) {
				history.add(Json.message("user", results));
			}
			if (round == MAX_TOOL_ROUNDS - 1) {
				listener.onNotice("max_rounds");
			}
		}
		listener.onTurnEnd(result);
		return result;
	}

	/**
	 * If a request fails, the conversation must not end with a user message
	 * that has no answer; drop the trailing user text so the developer can
	 * simply retry.
	 */
	private synchronized void rollbackDanglingUser() {
		if (history.isEmpty()) {
			return;
		}
		JsonObject last = history.get(history.size() - 1);
		if ("user".equals(Json.str(last, "role"))) {
			JsonArray content = Json.arr(last, "content");
			boolean onlyText = content != null && content.size() > 0;
			if (content != null) {
				for (var e : content) {
					if (!"text".equals(Json.str(e.getAsJsonObject(), "type"))) {
						onlyText = false;
					}
				}
			}
			if (onlyText) {
				history.remove(history.size() - 1);
			}
		}
	}

	private void answerSkippedTools(ChatResult result, String why) {
		JsonArray results = new JsonArray();
		for (ToolCall call : result.toolCalls()) {
			JsonObject block = new JsonObject();
			block.addProperty("type", "tool_result");
			block.addProperty("tool_use_id", call.id());
			block.addProperty("content", why);
			block.addProperty("is_error", true);
			results.add(block);
		}
		synchronized (this) {
			history.add(Json.message("user", results));
		}
	}

	ToolResult runTool(ToolCall call, Listener listener, CancelToken cancel) {
		Optional<ToolSpec> spec = tools == null ? Optional.empty() : tools.find(call.name());
		if (spec.isEmpty()) {
			return ToolResult.error("Unknown tool: " + call.name());
		}
		ToolSpec tool = spec.get();
		listener.onToolCall(tool, call);
		ToolResult result;
		if (!call.inputValid()) {
			JsonObject err = new JsonObject();
			err.addProperty("INVALID_JSON", call.rawInput());
			err.addProperty("reason", call.inputError());
			result = ToolResult.error(Json.GSON.toJson(err));
		} else {
			result = decideAndRun(tool, call, cancel);
		}
		listener.onToolResult(tool, call, result);
		return result;
	}

	private ToolResult decideAndRun(ToolSpec tool, ToolCall call, CancelToken cancel) {
		ToolPolicy.Decision decision = policy.get().decide(tool, call.input());
		if (decision == ToolPolicy.Decision.DENY) {
			return ToolResult.error("Refused by Bella's tool policy. Do not retry this call; tell the developer.");
		}
		// The write guard runs before confirmation: writing into an open editor
		// is harmless (nothing is saved) and the developer sees the diff there.
		Optional<ToolResult> intercepted = writeGuard.intercept(tool, call.input());
		if (intercepted.isPresent()) {
			return intercepted.get();
		}
		if (decision == ToolPolicy.Decision.CONFIRM && !confirmer.confirm(tool, call.input())) {
			return ToolResult.error("The developer declined this tool call.");
		}
		Optional<ToolProvider> owner = tools.providerOf(tool.name());
		if (owner.isEmpty()) {
			return ToolResult.error("Tool provider is no longer available: " + tool.name());
		}
		try {
			return owner.get().call(tool.remoteName(), call.input(), cancel);
		} catch (Exception e) {
			return ToolResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}
}

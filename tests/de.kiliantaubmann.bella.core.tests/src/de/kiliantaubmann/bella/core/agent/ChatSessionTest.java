package de.kiliantaubmann.bella.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class ChatSessionTest {

	/** Provider that replays scripted results and records requests. */
	static final class ScriptedLlm implements LlmProvider {
		final Deque<ChatResult> results = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

		@Override
		public String id() {
			return "fake";
		}

		@Override
		public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel) {
			requests.add(request);
			return results.poll();
		}
	}

	static ChatResult toolUse(StopReason stop, ToolCall... calls) {
		JsonArray content = new JsonArray();
		for (ToolCall c : calls) {
			JsonObject b = new JsonObject();
			b.addProperty("type", "tool_use");
			b.addProperty("id", c.id());
			b.addProperty("name", c.name());
			b.add("input", c.input());
			content.add(b);
		}
		return new ChatResult(Json.message("assistant", content), "", List.of(calls), stop, null, "m", Usage.NONE);
	}

	static ChatResult text(String t) {
		JsonArray content = new JsonArray();
		content.add(Json.textBlock(t));
		return new ChatResult(Json.message("assistant", content), t, List.of(), StopReason.END_TURN, null, "m",
				Usage.NONE);
	}

	static final class Tools implements ToolProvider {
		final List<String> calls = new ArrayList<>();

		@Override
		public String id() {
			return "adt";
		}

		@Override
		public String displayName() {
			return "ADT";
		}

		@Override
		public List<ToolSpec> listTools() {
			JsonObject schema = Json.parseObject("{\"type\":\"object\"}");
			return List.of(ToolSpec.of("adt_read_source", "", schema, Capability.READ_SOURCE, ToolSpec.Kind.READ),
					ToolSpec.of("adt_write_source", "", schema, Capability.WRITE_SOURCE, ToolSpec.Kind.WRITE),
					ToolSpec.of("adt_transport_release", "", schema, null, ToolSpec.Kind.WRITE));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			calls.add(remoteName);
			return ToolResult.ok("result of " + remoteName);
		}
	}

	private static ToolCall call(String id, String name) {
		return new ToolCall(id, name, Json.parseObject("{\"name\":\"ZCL_X\",\"source\":\"x\"}"), "{}", null);
	}

	private ChatSession session(ScriptedLlm llm, Tools tools, boolean confirm, List<String> confirmed,
			de.kiliantaubmann.bella.core.tools.WriteGuard guard) {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		return new ChatSession(() -> llm, () -> new ChatSession.Settings("m", 1000, null), "sys", registry,
				ToolPolicy::defaults, (tool, input) -> {
					confirmed.add(tool.name());
					return confirm;
				}, guard);
	}

	@Test
	void runsReadToolsAutomaticallyAndLoopsUntilDone() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.TOOL_USE, call("t1", "adt_read_source")));
		llm.results.add(text("It reads orders."));
		Tools tools = new Tools();
		List<String> confirmed = new ArrayList<>();
		ChatResult r = session(llm, tools, true, confirmed, null).send("explain", new ChatSession.Listener() {
		}, CancelToken.NONE);
		assertEquals("It reads orders.", r.text());
		assertEquals(List.of("adt_read_source"), tools.calls);
		assertTrue(confirmed.isEmpty());
		List<JsonObject> second = llm.requests.get(1).messages();
		assertEquals(3, second.size());
		JsonObject result = second.get(2).getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("tool_result", result.get("type").getAsString());
		assertEquals("t1", result.get("tool_use_id").getAsString());
	}

	@Test
	void writeNeedsConfirmationAndDeclineIsReported() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.TOOL_USE, call("t1", "adt_write_source")));
		llm.results.add(text("ok"));
		Tools tools = new Tools();
		List<String> confirmed = new ArrayList<>();
		ChatSession s = session(llm, tools, false, confirmed, null);
		s.send("change it", new ChatSession.Listener() {
		}, CancelToken.NONE);
		assertEquals(List.of("adt_write_source"), confirmed);
		assertTrue(tools.calls.isEmpty());
		JsonObject result = s.history().get(2).getAsJsonArray("content").get(0).getAsJsonObject();
		assertTrue(result.get("is_error").getAsBoolean());
	}

	@Test
	void writeGuardRedirectsBeforeConfirmation() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.TOOL_USE, call("t1", "adt_write_source")));
		llm.results.add(text("done"));
		Tools tools = new Tools();
		List<String> confirmed = new ArrayList<>();
		ChatSession s = session(llm, tools, true, confirmed,
				(tool, input) -> Optional.of(ToolResult.ok("written into editor")));
		s.send("change it", new ChatSession.Listener() {
		}, CancelToken.NONE);
		assertTrue(confirmed.isEmpty());
		assertTrue(tools.calls.isEmpty());
		assertEquals("written into editor", s.history().get(2).getAsJsonArray("content").get(0).getAsJsonObject()
				.get("content").getAsString());
	}

	@Test
	void deniedToolsNeverRun() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.TOOL_USE, call("t1", "adt_transport_release")));
		llm.results.add(text("sorry"));
		Tools tools = new Tools();
		session(llm, tools, true, new ArrayList<>(), null).send("release", new ChatSession.Listener() {
		}, CancelToken.NONE);
		assertTrue(tools.calls.isEmpty());
	}

	@Test
	void toolCallsCutOffAtMaxTokensAreNotExecuted() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.MAX_TOKENS, call("t1", "adt_read_source")));
		Tools tools = new Tools();
		List<String> notices = new ArrayList<>();
		ChatSession s = session(llm, tools, true, new ArrayList<>(), null);
		s.send("x", new ChatSession.Listener() {
			@Override
			public void onNotice(String message) {
				notices.add(message);
			}
		}, CancelToken.NONE);
		assertTrue(tools.calls.isEmpty());
		assertEquals(List.of("max_tokens_tool"), notices);
		// the dangling tool_use is answered so the next turn is valid
		assertEquals("user", s.history().get(s.history().size() - 1).get("role").getAsString());
	}

	@Test
	void invalidToolInputIsReturnedAsError() throws Exception {
		ScriptedLlm llm = new ScriptedLlm();
		llm.results.add(toolUse(StopReason.TOOL_USE,
				new ToolCall("t1", "adt_read_source", new JsonObject(), "{\"name\":", "invalid JSON")));
		llm.results.add(text("retrying"));
		Tools tools = new Tools();
		ChatSession s = session(llm, tools, true, new ArrayList<>(), null);
		s.send("x", new ChatSession.Listener() {
		}, CancelToken.NONE);
		assertTrue(tools.calls.isEmpty());
		String content = s.history().get(2).getAsJsonArray("content").get(0).getAsJsonObject().get("content")
				.getAsString();
		assertTrue(content.contains("INVALID_JSON"));
	}
}

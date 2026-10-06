package de.kiliantaubmann.bella.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.agent.MaskingConversation;
import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.MaskingProvider;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.testutil.LogRecorder;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

/** The model sees only placeholders; the developer, the editor and SAP only real names. */
class MaskingBoundariesTest {

	/** Replays results, streams their text in two pieces, records requests. */
	static final class Model implements LlmProvider {
		final Deque<ChatResult> results = new ArrayDeque<>();
		final List<ChatRequest> requests = new ArrayList<>();

		@Override
		public String id() {
			return "fake";
		}

		@Override
		public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel) {
			requests.add(request);
			ChatResult r = results.poll();
			String t = r.text();
			if (!t.isEmpty()) {
				listener.onText(t.substring(0, t.length() / 2));
				listener.onText(t.substring(t.length() / 2));
			}
			return r;
		}
	}

	static final class Sap implements ToolProvider {
		final List<String> names = new ArrayList<>();

		@Override
		public String id() {
			return ToolRegistry.ADT_PROVIDER_ID;
		}

		@Override
		public String displayName() {
			return "ADT";
		}

		@Override
		public List<ToolSpec> listTools() {
			return List.of(ToolSpec.of("adt_read_source", "", Json.parseObject("{\"type\":\"object\"}"), null,
					ToolSpec.Kind.READ));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			names.add(Json.str(input, "name"));
			return ToolResult.ok("CLASS zcl_secret DEFINITION. \" owner anna@acme.de");
		}
	}

	private static Masker masker() {
		return new Masker(() -> new Masker.Settings(true, true, true, List.of(), List.of(), List.of()));
	}

	private static ChatResult text(String t) {
		JsonArray content = new JsonArray();
		content.add(Json.textBlock(t));
		return new ChatResult(Json.message("assistant", content), t, List.of(), StopReason.END_TURN, null, "m",
				Usage.NONE);
	}

	private static ChatResult toolUse(ToolCall c) {
		JsonArray content = new JsonArray();
		JsonObject b = new JsonObject();
		b.addProperty("type", "tool_use");
		b.addProperty("id", c.id());
		b.addProperty("name", c.name());
		b.add("input", c.input());
		content.add(b);
		return new ChatResult(Json.message("assistant", content), "", List.of(c), StopReason.TOOL_USE, null, "m",
				Usage.NONE);
	}

	@Test
	void chatToolLoopStaysMaskedForTheModelOnly() throws Exception {
		Masker masker = masker();
		Model model = new Model();
		model.results.add(toolUse(new ToolCall("t1", "adt_read_source",
				Json.parseObject("{\"name\":\"ZCL_MASK1\"}"), "{}", null)));
		model.results.add(text("ZCL_MASK1 belongs to maskmail2@example.invalid."));
		Sap sap = new Sap();
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(sap);
		registry.refresh(e -> {
		});
		Conversation chat = MaskingConversation.wrap(new ChatSession(() -> model,
				() -> new ChatSession.Settings("m", 1000, null), "sys", registry, ToolPolicy::defaults,
				(tool, input) -> true, null, masker), masker);

		StringBuilder shown = new StringBuilder();
		List<String> toolInputs = new ArrayList<>();
		List<String> toolResults = new ArrayList<>();
		chat.ask("Explain ZCL_SECRET", new ConversationListener() {
			@Override
			public void onText(String delta) {
				shown.append(delta);
			}

			@Override
			public void onToolCall(ToolSpec tool, ToolCall call) {
				toolInputs.add(Json.GSON.toJson(call.input()));
			}

			@Override
			public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
				toolResults.add(result.content());
			}
		}, CancelToken.NONE);

		String sent = Json.GSON.toJson(model.requests.get(1).messages());
		assertTrue(sent.contains("Explain ZCL_MASK1"), sent);
		assertTrue(sent.contains("zcl_mask1 DEFINITION"), sent);
		assertFalse(sent.toLowerCase().contains("secret") || sent.contains("anna@acme.de"), sent);
		assertEquals(List.of("ZCL_SECRET"), sap.names, "SAP gets the real name");
		assertTrue(toolInputs.get(0).contains("ZCL_SECRET"), "the developer sees the real input");
		assertTrue(toolResults.get(0).contains("anna@acme.de"), "and the real result");
		assertEquals("ZCL_SECRET belongs to anna@acme.de.", shown.toString());
	}

	@Test
	void singleRequestsAreMaskedAndTheAnswerUnmasked() throws Exception {
		Masker masker = masker();
		Model model = new Model();
		model.results.add(text("METHOD run. zcl_mask1=>go( ). ENDMETHOD."));
		LlmProvider p = MaskingProvider.wrap(model, masker);
		ChatResult r = p.chat(new ChatRequest("m", "Object ZCL_SECRET", List.of(Json.userText("Implement zcl_secret")),
				List.of(), 100, ChatRequest.Purpose.CHAT, null), StreamListener.NONE, CancelToken.NONE);
		ChatRequest sent = model.requests.get(0);
		assertTrue(sent.system().startsWith("Object ZCL_MASK1"), sent.system());
		assertTrue(sent.system().contains("placeholders"), "the model is told about placeholders");
		assertTrue(Json.GSON.toJson(sent.messages()).contains("Implement zcl_mask1"));
		assertEquals("METHOD run. zcl_secret=>go( ). ENDMETHOD.", r.text());
	}

	@Test
	void theLogFileGetsTheSamePlaceholders() {
		Masker masker = masker();
		String forModel = masker.mask("ZCL_SECRET");
		Log.mask(masker::mask);
		try (LogRecorder r = LogRecorder.start(Log.Level.DEBUG)) {
			Log.debug("tool", () -> "adt_read_source input: {\"name\":\"ZCL_SECRET\"} owner anna@acme.de");
			Log.error("adt", "failed for zcl_secret", new IllegalStateException("ZCL_SECRET is locked"));
			String all = r.all();
			assertFalse(all.toLowerCase().contains("secret") || all.contains("anna@acme.de"), all);
			assertTrue(all.contains(forModel) && all.contains(forModel.toLowerCase()), all);
		} finally {
			Log.mask(null);
		}
	}
}

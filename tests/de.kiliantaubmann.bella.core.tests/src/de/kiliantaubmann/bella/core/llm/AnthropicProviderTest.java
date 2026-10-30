package de.kiliantaubmann.bella.core.llm;

import static de.kiliantaubmann.bella.core.testutil.FakeHttp.sse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.testutil.FakeHttp;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AnthropicProviderTest {

	private static final JsonObject SCHEMA = Json.parseObject(
			"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}");
	private static final ToolSpec TOOL = ToolSpec.of("adt_read_source", "Read", SCHEMA, null, ToolSpec.Kind.READ);

	private static ChatRequest chat(String model, String effort) {
		return new ChatRequest(model, "sys", List.of(Json.userText("hi")), List.of(TOOL), 1000,
				ChatRequest.Purpose.CHAT, effort);
	}

	private static AnthropicProvider provider(FakeHttp http, boolean fallback) {
		return new AnthropicProvider(() -> "key", null, fallback, http, 2);
	}

	@Test
	void chatBodyUsesAdaptiveThinkingCachingEagerToolsAndFallback() {
		AnthropicProvider p = provider(new FakeHttp(), true);
		JsonObject body = p.buildBody(chat("claude-opus-5", "high"));
		assertEquals("adaptive", body.getAsJsonObject("thinking").get("type").getAsString());
		assertEquals("ephemeral", body.getAsJsonObject("cache_control").get("type").getAsString());
		assertEquals("high", body.getAsJsonObject("output_config").get("effort").getAsString());
		assertEquals("default", body.get("fallbacks").getAsString());
		JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
		assertTrue(tool.get("eager_input_streaming").getAsBoolean());
		assertTrue(body.get("stream").getAsBoolean());
		assertEquals(AnthropicProvider.FALLBACK_BETA + "," + AnthropicProvider.CONTEXT_BETA,
				p.headers(chat("claude-opus-5", null)).get("anthropic-beta"));
	}

	@Test
	void rareToolsGoBehindToolSearchAndTheSystemPromptNamesThem() {
		ToolSpec rare = ToolSpec.of("adt_git", "abapGit", SCHEMA, null, ToolSpec.Kind.READ).asDeferred();
		ChatRequest r = new ChatRequest("claude-opus-5-5", "sys", List.of(Json.userText("hi")), List.of(TOOL, rare),
				1000, ChatRequest.Purpose.CHAT, null);
		JsonObject body = provider(new FakeHttp(), false).buildBody(r);
		JsonArray tools = body.getAsJsonArray("tools");
		assertEquals(3, tools.size());
		assertEquals("tool_search_tool_bm25_20251119", tools.get(0).getAsJsonObject().get("type").getAsString());
		assertNull(tools.get(1).getAsJsonObject().get("defer_loading"));
		assertTrue(tools.get(2).getAsJsonObject().get("defer_loading").getAsBoolean());
		String system = body.getAsJsonArray("system").get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(system.startsWith("sys\n\n") && system.contains(AnthropicProvider.TOOL_SEARCH)
				&& system.endsWith(": adt_git."), system);

		// a model without tool search, or nothing left to load up front: every tool is sent as before
		for (ChatRequest plain : List.of(
				new ChatRequest("claude-3-7-sonnet", "sys", List.of(Json.userText("hi")), List.of(TOOL, rare), 1000,
						ChatRequest.Purpose.CHAT, null),
				new ChatRequest("claude-opus-5-5", "sys", List.of(Json.userText("hi")), List.of(rare), 1000,
						ChatRequest.Purpose.CHAT, null))) {
			JsonObject b = provider(new FakeHttp(), false).buildBody(plain);
			b.getAsJsonArray("tools").forEach(t -> {
				assertNull(t.getAsJsonObject().get("defer_loading"));
				assertNull(t.getAsJsonObject().get("type"));
			});
			assertEquals("sys", b.getAsJsonArray("system").get(0).getAsJsonObject().get("text").getAsString());
		}
	}

	@Test
	void longChatsClearOldToolResultsAndTheCacheCanLastAnHour() {
		ToolSpec ask = ToolSpec.of("ask_user", "Ask", SCHEMA, null, ToolSpec.Kind.READ);
		JsonObject body = provider(new FakeHttp(), false).buildBody(new ChatRequest("claude-sonnet-5-5", "sys",
				List.of(Json.userText("hi")), List.of(TOOL, ask), 1000, ChatRequest.Purpose.CHAT, null));
		JsonObject edit = body.getAsJsonObject("context_management").getAsJsonArray("edits").get(0).getAsJsonObject();
		assertEquals("clear_tool_uses_20250919", edit.get("type").getAsString());
		assertEquals(AnthropicProvider.CLEAR_TRIGGER_TOKENS, edit.getAsJsonObject("trigger").get("value").getAsInt());
		assertEquals("ask_user", edit.getAsJsonArray("exclude_tools").get(0).getAsString());
		assertNull(provider(new FakeHttp(), false).buildBody(chat("claude-sonnet-5-5", null))
				.getAsJsonObject("context_management").getAsJsonArray("edits").get(0).getAsJsonObject()
				.get("exclude_tools"), "only tools of the request");
		assertNull(body.getAsJsonObject("cache_control").get("ttl"));
		assertEquals(AnthropicProvider.CONTEXT_BETA,
				provider(new FakeHttp(), false).headers(chat("claude-sonnet-5-5", null)).get("anthropic-beta"));

		AnthropicProvider longCache = new AnthropicProvider(() -> "key", null, false, true, new FakeHttp(), 2);
		assertEquals("1h", longCache.buildBody(chat("claude-opus-5", null)).getAsJsonObject("cache_control")
				.get("ttl").getAsString());
		// older models know neither
		ChatRequest old = chat("claude-3-7-sonnet", null);
		assertNull(provider(new FakeHttp(), false).buildBody(old).get("context_management"));
		assertFalse(provider(new FakeHttp(), false).headers(old).containsKey("anthropic-beta"));
	}

	@Test
	void toolSearchBlocksAreKeptForTheNextRequest() throws Exception {
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", sse(
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"tool_search_tool_bm25\"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"git\\\"}\"}}",
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":{\"type\":\"tool_search_tool_search_result\",\"tool_references\":[{\"type\":\"tool_reference\",\"tool_name\":\"adt_git\"}]}}}",
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"adt_read_source\",\"input\":{}}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"name\\\":\\\"ZCL_X\\\"}\"}}",
				"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"context_management\":{\"applied_edits\":[]}}"));
		ChatResult r = provider(http, false).chat(chat("claude-opus-5-5", null), StreamListener.NONE, CancelToken.NONE);
		assertEquals(1, r.toolCalls().size(), "the search runs on the server");
		assertEquals("toolu_1", r.toolCalls().get(0).id());
		JsonArray content = r.assistantMessage().getAsJsonArray("content");
		assertEquals(3, content.size());
		JsonObject search = content.get(0).getAsJsonObject();
		assertEquals("server_tool_use", search.get("type").getAsString());
		assertEquals("git", search.getAsJsonObject("input").get("query").getAsString());
		assertEquals("adt_git", content.get(1).getAsJsonObject().getAsJsonObject("content")
				.getAsJsonArray("tool_references").get(0).getAsJsonObject().get("tool_name").getAsString());
	}

	@Test
	void completionOnHaikuSendsNoThinkingEffortOrFallback() {
		AnthropicProvider p = provider(new FakeHttp(), true);
		ChatRequest r = new ChatRequest("claude-haiku-4-5", "sys", List.of(Json.userText("x")), List.of(), 200,
				ChatRequest.Purpose.COMPLETION, "low");
		JsonObject body = p.buildBody(r);
		assertNull(body.get("thinking"));
		assertNull(body.get("output_config"));
		assertNull(body.get("fallbacks"));
		assertNull(body.get("cache_control"));
		assertFalse(p.headers(r).containsKey("anthropic-beta"));
	}

	@Test
	void fallbackIsOffWhenDisabledOrUnsupported() {
		assertNull(provider(new FakeHttp(), false).buildBody(chat("claude-opus-5", null)).get("fallbacks"));
		assertNull(provider(new FakeHttp(), true).buildBody(chat("claude-sonnet-5", null)).get("fallbacks"));
	}

	@Test
	void streamsTextThinkingAndToolUse() throws Exception {
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", sse(
				"message_start", "{\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":4}}}",
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}",
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me \"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"look.\"}}",
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"adt_read_source\",\"input\":{}}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"name\\\":\"}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"ZCL_X\\\"}\"}}",
				"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":42}}",
				"message_stop", "{\"type\":\"message_stop\"}"));
		List<String> text = new ArrayList<>();
		ChatResult r = provider(http, false).chat(chat("claude-opus-5", null), new StreamListener() {
			@Override
			public void onText(String delta) {
				text.add(delta);
			}
		}, CancelToken.NONE);
		assertEquals(List.of("Let me ", "look."), text);
		assertEquals("Let me look.", r.text());
		assertEquals(StopReason.TOOL_USE, r.stopReason());
		assertEquals(1, r.toolCalls().size());
		ToolCall call = r.toolCalls().get(0);
		assertTrue(call.inputValid());
		assertEquals("ZCL_X", call.input().get("name").getAsString());
		JsonArray content = r.assistantMessage().getAsJsonArray("content");
		JsonObject thinking = content.get(0).getAsJsonObject();
		assertEquals("hmm", thinking.get("thinking").getAsString());
		assertEquals("sig", thinking.get("signature").getAsString());
		assertEquals(4, r.usage().cacheReadTokens());
		assertEquals(42, r.usage().outputTokens());
		assertEquals("key", http.sent.get(0).headers().get("x-api-key"));
		assertEquals("2023-06-01", http.sent.get(0).headers().get("anthropic-version"));
	}

	@Test
	void invalidToolJsonIsReportedNotParsedLeniently() throws Exception {
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", sse(
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"adt_read_source\",\"input\":{}}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"name\\\": \\\"ZCL\"}}",
				"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"));
		ChatResult r = provider(http, false).chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE);
		assertFalse(r.toolCalls().get(0).inputValid());
		assertEquals(StopReason.MAX_TOKENS, r.stopReason());
	}

	@Test
	void schemaViolationIsReported() throws Exception {
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", sse(
				"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"adt_read_source\",\"input\":{}}}",
				"content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
				"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}"));
		ChatResult r = provider(http, false).chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE);
		assertTrue(r.toolCalls().get(0).inputError().contains("name"));
	}

	@Test
	void fallbackBoundaryDropsEarlierThinkingAndToolUse() {
		List<JsonObject> content = new ArrayList<>();
		content.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"a\",\"signature\":\"s\"}"));
		content.add(Json.parseObject("{\"type\":\"text\",\"text\":\"partial\"}"));
		content.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"x\",\"name\":\"n\",\"input\":{}}"));
		content.add(Json.parseObject("{\"type\":\"fallback\",\"from\":{\"model\":\"a\"},\"to\":{\"model\":\"b\"}}"));
		content.add(Json.parseObject("{\"type\":\"text\",\"text\":\"rest\"}"));
		List<JsonObject> out = AnthropicProvider.Accumulator.dropBlocksBeforeFallback(content);
		assertEquals(2, out.size());
		assertEquals("partial", out.get(0).get("text").getAsString());
		assertEquals("rest", out.get(1).get("text").getAsString());
	}

	@Test
	void retriesOverloadedThenSucceeds() throws Exception {
		FakeHttp http = new FakeHttp()
				.respond(529, Map.of("retry-after", "0"), "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")
				.respond(200, "text/event-stream", sse(
						"content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"ok\"}}",
						"message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"));
		ChatResult r = provider(http, false).chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE);
		assertEquals("ok", r.text());
		assertEquals(2, http.sent.size());
	}

	@Test
	void badRequestIsNotRetriedAndCarriesMessage() {
		FakeHttp http = new FakeHttp().respond(400, "application/json",
				"{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad field\"}}");
		LlmException e = assertThrows(LlmException.class,
				() -> provider(http, false).chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE));
		assertEquals(400, e.status());
		assertTrue(e.getMessage().contains("bad field"));
		assertEquals(1, http.sent.size());
	}

	@Test
	void refusalCarriesExplanation() throws Exception {
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", sse("message_delta",
				"{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"nope\"}}}"));
		ChatResult r = provider(http, false).chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE);
		assertEquals(StopReason.REFUSAL, r.stopReason());
		assertEquals("nope", r.stopDetail());
	}

	@Test
	void missingKeyFailsFast() {
		AnthropicProvider p = new AnthropicProvider(() -> "", null, false, new FakeHttp());
		assertThrows(LlmException.class, () -> p.chat(chat("claude-opus-5", null), StreamListener.NONE, CancelToken.NONE));
	}
}

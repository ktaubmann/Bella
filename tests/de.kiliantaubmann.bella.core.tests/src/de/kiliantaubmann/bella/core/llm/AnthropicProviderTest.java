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
		assertEquals(AnthropicProvider.FALLBACK_BETA, p.headers(chat("claude-opus-5", null)).get("anthropic-beta"));
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

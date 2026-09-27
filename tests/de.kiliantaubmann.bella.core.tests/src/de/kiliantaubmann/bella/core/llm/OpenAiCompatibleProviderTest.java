package de.kiliantaubmann.bella.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.testutil.FakeHttp;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class OpenAiCompatibleProviderTest {

	@Test
	void convertsToolUseAndResults() {
		JsonArray out = new JsonArray();
		OpenAiCompatibleProvider.convertMessage(Json.parseObject(
				"{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"},{\"type\":\"text\",\"text\":\"Reading\"},{\"type\":\"tool_use\",\"id\":\"c1\",\"name\":\"t\",\"input\":{\"a\":1}}]}"),
				out);
		OpenAiCompatibleProvider.convertMessage(Json.parseObject(
				"{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c1\",\"content\":\"done\"}]}"),
				out);
		JsonObject assistant = out.get(0).getAsJsonObject();
		assertEquals("Reading", assistant.get("content").getAsString());
		JsonObject fn = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function");
		assertEquals("{\"a\":1}", fn.get("arguments").getAsString());
		JsonObject tool = out.get(1).getAsJsonObject();
		assertEquals("tool", tool.get("role").getAsString());
		assertEquals("c1", tool.get("tool_call_id").getAsString());
	}

	@Test
	void streamsToolCallsAcrossChunks() throws Exception {
		String body = "data: {\"model\":\"llama3\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"adt_read_source\",\"arguments\":\"{\\\"na\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"me\\\":\\\"ZX\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
				+ "data: [DONE]\n\n";
		FakeHttp http = new FakeHttp().respond(200, "text/event-stream", body);
		OpenAiCompatibleProvider p = new OpenAiCompatibleProvider(() -> "k", "http://localhost:11434/v1/", http);
		ToolSpec tool = ToolSpec.of("adt_read_source", "", Json.parseObject("{\"type\":\"object\"}"), null,
				ToolSpec.Kind.READ);
		ChatResult r = p.chat(new ChatRequest("llama3", "sys", List.of(Json.userText("x")), List.of(tool), 100,
				ChatRequest.Purpose.CHAT, null), StreamListener.NONE, CancelToken.NONE);
		assertEquals("Hi", r.text());
		assertEquals(StopReason.TOOL_USE, r.stopReason());
		assertEquals("ZX", r.toolCalls().get(0).input().get("name").getAsString());
		assertEquals("http://localhost:11434/v1/chat/completions", http.sent.get(0).uri().toString());
		assertEquals("Bearer k", http.sent.get(0).headers().get("authorization"));
		assertTrue(http.sent.get(0).body().contains("\"role\":\"system\""));
	}
}

package de.kiliantaubmann.bella.core.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class CopilotProviderTest {

	@TempDir
	Path tmp;
	private final FakeAcp fake = new FakeAcp();
	private CopilotProvider provider;

	@BeforeEach
	void setUp() throws IOException {
		Path exe = Files.createFile(tmp.resolve("copilot"));
		provider = new CopilotProvider(new CopilotCli(new CopilotCli.Config(exe.toString(), ""), fake, tmp.resolve("w")));
	}

	private static ChatRequest request(ChatRequest.Purpose purpose, List<ToolSpec> tools) {
		return new ChatRequest("gpt-5", "Nur ABAP-Code.", List.of(Json.userText("METHOD get_orders.")), tools, 256,
				purpose, "high");
	}

	@Test
	void singleRequestStreamsAndEndsTheProcess() throws Exception {
		fake.script = (p, id, sid, text) -> {
			p.update(sid, "agent_message_chunk", "SELECT * FROM vbak");
			p.update(sid, "agent_message_chunk", " INTO TABLE @rt_orders.");
			JsonObject result = FakeAcp.stopReason("end_turn");
			result.add("usage", Json.parseObject(
					"{\"inputTokens\":15482,\"outputTokens\":984,\"cachedReadTokens\":7,\"cachedWriteTokens\":15479}"));
			p.reply(id, result);
		};
		StringBuilder streamed = new StringBuilder();
		ChatResult r = provider.chat(request(ChatRequest.Purpose.COMPLETION, List.of()), new StreamListener() {
			@Override
			public void onText(String delta) {
				streamed.append(delta);
			}
		}, CancelToken.NONE);

		assertEquals("SELECT * FROM vbak INTO TABLE @rt_orders.", r.text());
		assertEquals(r.text(), streamed.toString());
		assertEquals(new Usage(15482, 984, 7, 15479), r.usage());
		FakeAcp.Proc p = fake.started.get(0);
		assertEquals("<instructions>\nNur ABAP-Code.\n</instructions>\n\nMETHOD get_orders.", p.prompts.get(0));
		assertEquals(0, p.newSessionParams.getAsJsonArray("mcpServers").size(), "no tools in single requests");
		assertEquals("gpt-5", p.arg("--model"));
		assertFalse(p.command.contains("--reasoning-effort"), "completions use the model default");
		assertFalse(p.isAlive());
	}

	@Test
	void chatPurposePassesEffort() throws Exception {
		provider.chat(request(ChatRequest.Purpose.CHAT, List.of()), StreamListener.NONE, CancelToken.NONE);
		assertEquals("high", fake.started.get(0).arg("--reasoning-effort"));
	}

	@Test
	void requestsWithToolsAreRejected() {
		ToolSpec t = ToolSpec.of("adt_read_source", "", Json.parseObject("{\"type\":\"object\"}"),
				Capability.READ_SOURCE, ToolSpec.Kind.READ);
		assertThrows(LlmException.class, () -> provider.chat(request(ChatRequest.Purpose.CHAT, List.of(t)),
				StreamListener.NONE, CancelToken.NONE));
		assertTrue(fake.started.isEmpty());
	}

	@Test
	void notLoggedInExplainsWhatToDo() {
		fake.loggedIn = false;
		LlmException e = assertThrows(LlmException.class, () -> provider
				.chat(request(ChatRequest.Purpose.COMPLETION, List.of()), StreamListener.NONE, CancelToken.NONE));
		assertTrue(e.getMessage().contains("copilot login"), e.getMessage());
		assertFalse(fake.started.get(0).isAlive());
	}

	@Test
	void everyPermissionRequestIsRejected() throws Exception {
		fake.script = (p, id, sid, text) -> {
			p.requestPermission(7, sid, "bella-adt_write_source", "other");
			p.reply(id, FakeAcp.stopReason("end_turn"));
		};
		provider.chat(request(ChatRequest.Purpose.CHAT, List.of()), StreamListener.NONE, CancelToken.NONE);
		assertEquals("reject", fake.started.get(0).responseTo(7).getAsJsonObject("result")
				.getAsJsonObject("outcome").get("optionId").getAsString());
	}

	@Test
	void earlierTurnsAreQuoted() {
		JsonArray answer = new JsonArray();
		answer.add(Json.textBlock("erste Antwort"));
		ChatRequest r = new ChatRequest("m", null,
				List.of(Json.userText("erste Frage"), Json.message("assistant", answer), Json.userText("zweite Frage")),
				List.of(), 100, ChatRequest.Purpose.CHAT, null);
		String text = CopilotProvider.promptText(r);
		assertTrue(text.contains("[assistant]\nerste Antwort"), text);
		assertTrue(text.endsWith("zweite Frage"), text);
		assertFalse(text.contains("<instructions>"));
	}
}

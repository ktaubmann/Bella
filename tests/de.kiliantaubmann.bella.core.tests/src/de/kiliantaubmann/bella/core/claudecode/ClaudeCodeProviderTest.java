package de.kiliantaubmann.bella.core.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class ClaudeCodeProviderTest {

	@TempDir
	Path tmp;
	private Path exe;

	@BeforeEach
	void setUp() throws IOException {
		exe = Files.createFile(tmp.resolve("claude"));
	}

	private ClaudeCodeProvider provider(FakeCli fake) {
		return new ClaudeCodeProvider(new ClaudeCli(new ClaudeCli.Config(exe.toString(), ""), fake, tmp.resolve("w")));
	}

	private static ChatRequest request(ChatRequest.Purpose purpose, List<ToolSpec> tools) {
		return new ChatRequest("haiku", "Nur ABAP-Code.", List.of(Json.userText("METHOD get_orders.")), tools, 256,
				purpose, "high");
	}

	@Test
	void completionRunsOneCliCallWithPromptOnStdin() throws Exception {
		AtomicReference<String> system = new AtomicReference<>();
		FakeCli fake = new FakeCli((p, msg) -> {
			try {
				system.set(Files.readString(Path.of(p.arg("--system-prompt-file"))));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			p.emit(FakeCli.init());
			p.emit(FakeCli.delta("SELECT * FROM vbak"));
			p.emit(FakeCli.delta(" INTO TABLE @rt_orders."));
			p.emit(FakeCli.success("SELECT * FROM vbak INTO TABLE @rt_orders."));
		});
		StringBuilder streamed = new StringBuilder();
		ChatResult r = provider(fake).chat(request(ChatRequest.Purpose.COMPLETION, List.of()), new StreamListener() {
			@Override
			public void onText(String delta) {
				streamed.append(delta);
			}
		}, CancelToken.NONE);

		assertEquals("SELECT * FROM vbak INTO TABLE @rt_orders.", r.text());
		assertEquals(r.text(), streamed.toString());
		assertEquals("Nur ABAP-Code.", system.get());
		FakeCli.Proc p = fake.started.get(0);
		assertEquals("haiku", p.arg("--model"));
		assertFalse(p.command.contains("--effort"), "completions use the model default");
		assertFalse(p.command.contains("--mcp-config"));
		assertEquals("", p.arg("--tools"));
		JsonObject sent = p.received.get(0);
		assertEquals("METHOD get_orders.", sent.getAsJsonObject("message").getAsJsonArray("content").get(0)
				.getAsJsonObject().get("text").getAsString());
		assertFalse(p.isAlive());
		try (var files = Files.list(tmp.resolve("w"))) {
			assertEquals(0, files.count(), "system prompt file is removed");
		}
	}

	@Test
	void chatPurposePassesEffort() throws Exception {
		FakeCli fake = new FakeCli((p, msg) -> p.emit(FakeCli.success("ok")));
		provider(fake).chat(request(ChatRequest.Purpose.CHAT, List.of()), StreamListener.NONE, CancelToken.NONE);
		assertEquals("high", fake.started.get(0).arg("--effort"));
	}

	@Test
	void requestsWithToolsAreRejected() {
		ToolSpec t = ToolSpec.of("adt_read_source", "", Json.parseObject("{\"type\":\"object\"}"),
				Capability.READ_SOURCE, ToolSpec.Kind.READ);
		FakeCli fake = new FakeCli((p, msg) -> {
		});
		assertThrows(LlmException.class,
				() -> provider(fake).chat(request(ChatRequest.Purpose.CHAT, List.of(t)), StreamListener.NONE,
						CancelToken.NONE));
		assertTrue(fake.started.isEmpty());
	}

	@Test
	void loginProblemsExplainWhatToDo() {
		FakeCli fake = new FakeCli((p, msg) -> p.emit(
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key · Please run /login\"}"));
		LlmException e = assertThrows(LlmException.class, () -> provider(fake)
				.chat(request(ChatRequest.Purpose.COMPLETION, List.of()), StreamListener.NONE, CancelToken.NONE));
		assertTrue(e.getMessage().contains("claude auth login"));
	}

	@Test
	void earlierTurnsAreQuoted() {
		ChatRequest r = new ChatRequest("m", "s",
				List.of(Json.userText("erste Frage"), Json.message("assistant", arr(Json.textBlock("erste Antwort"))),
						Json.userText("zweite Frage")),
				List.of(), 100, ChatRequest.Purpose.CHAT, null);
		JsonObject msg = ClaudeCodeProvider.userMessage(r);
		var content = msg.getAsJsonObject("message").getAsJsonArray("content");
		assertEquals(2, content.size());
		String earlier = content.get(0).getAsJsonObject().get("text").getAsString();
		assertTrue(earlier.contains("[assistant]\nerste Antwort"));
		assertEquals("zweite Frage", content.get(1).getAsJsonObject().get("text").getAsString());
	}

	private static com.google.gson.JsonArray arr(JsonObject o) {
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		a.add(o);
		return a;
	}
}

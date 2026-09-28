package de.kiliantaubmann.bella.core.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.mcp.McpClient;
import de.kiliantaubmann.bella.core.mcp.StreamableHttpTransport;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Json;

class ClaudeCodeSessionTest {

	@TempDir
	Path tmp;

	private Path exe;
	private final McpServerTestTools tools = new McpServerTestTools();
	private volatile String model = "opus";
	private ClaudeCodeSession session;

	static final class McpServerTestTools implements ToolProvider {
		final List<String> calls = new CopyOnWriteArrayList<>();

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
			return List.of(ToolSpec.of("adt_read_source", "Reads source", Json.parseObject("{\"type\":\"object\"}"),
					Capability.READ_SOURCE, ToolSpec.Kind.READ));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			calls.add(remoteName + ":" + Json.str(input, "name"));
			return ToolResult.ok("result of " + remoteName);
		}
	}

	static final class Recorder implements ConversationListener {
		final StringBuilder text = new StringBuilder();
		final List<String> notices = new CopyOnWriteArrayList<>();
		final List<String> tools = new CopyOnWriteArrayList<>();
		ChatResult last;
		volatile boolean sawText;

		@Override
		public void onText(String delta) {
			text.append(delta);
			sawText = true;
		}

		@Override
		public void onNotice(String message) {
			notices.add(message);
		}

		@Override
		public void onToolCall(ToolSpec tool, ToolCall call) {
			tools.add("call:" + tool.name());
		}

		@Override
		public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
			tools.add("result:" + result.content());
		}

		@Override
		public void onTurnEnd(ChatResult last) {
			this.last = last;
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		exe = Files.createFile(tmp.resolve("claude"));
	}

	@AfterEach
	void tearDown() {
		if (session != null) {
			session.close();
		}
	}

	private ClaudeCodeSession session(FakeCli fake) {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		ToolExecutor executor = new ToolExecutor(registry, ToolPolicy::defaults, (tool, input) -> true, null);
		ClaudeCli cli = new ClaudeCli(new ClaudeCli.Config(exe.toString(), ""), fake, tmp.resolve("work"));
		session = new ClaudeCodeSession(cli, () -> new ChatSession.Settings(model, 1000, null), "Du bist Bella.",
				executor, "0.2.0");
		return session;
	}

	/** Answers every user message with a streamed text. */
	private static final BiConsumer<FakeCli.Proc, JsonObject> ECHO = (p, msg) -> {
		if (!"user".equals(Json.str(msg, "type"))) {
			return;
		}
		String text = msg.getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject().get("text")
				.getAsString();
		p.emit(FakeCli.init());
		p.emit(FakeCli.delta("Antwort auf: "));
		p.emit(FakeCli.delta(text));
		p.emit(FakeCli.assistantText("Antwort auf: " + text));
		p.emit(FakeCli.success("Antwort auf: " + text));
	};

	@Test
	void keepsOneProcessForTheWholeChat() throws Exception {
		List<String> systemPrompts = new ArrayList<>();
		FakeCli fake = new FakeCli((p, msg) -> {
			try {
				systemPrompts.add(Files.readString(Path.of(p.arg("--system-prompt-file"))));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			ECHO.accept(p, msg);
		});
		ClaudeCodeSession s = session(fake);
		Recorder r1 = new Recorder();
		s.ask("Hallo", r1, CancelToken.NONE);
		Recorder r2 = new Recorder();
		s.ask("Noch was", r2, CancelToken.NONE);

		assertEquals("Antwort auf: Hallo", r1.text.toString());
		assertEquals("Antwort auf: Noch was", r2.text.toString());
		assertEquals("claude-opus-5", r2.last.model());
		assertEquals(1, fake.started.size());
		FakeCli.Proc p = fake.started.get(0);
		assertEquals(2, p.received.size());
		assertEquals(List.of("Du bist Bella.", "Du bist Bella."), systemPrompts);
		assertEquals(exe.toString(), p.command.get(0));
		assertEquals("", p.command.get(p.command.indexOf("--tools") + 1));
		assertTrue(p.command.contains("--strict-mcp-config"));
		assertFalse(p.command.contains("--bare"));
		assertEquals(tmp.resolve("work"), p.workDir);
		assertTrue(p.env.containsKey("ANTHROPIC_API_KEY"));
		assertTrue(r1.notices.isEmpty());
	}

	@Test
	void cliCallsBellasToolsThroughTheMcpServer() throws Exception {
		FakeCli fake = new FakeCli((p, msg) -> {
			if (!"user".equals(Json.str(msg, "type"))) {
				return;
			}
			try {
				// What the real CLI does with --mcp-config: connect and call a tool.
				JsonObject cfg = Json.parseObject(Files.readString(Path.of(p.arg("--mcp-config"))));
				JsonObject bella = cfg.getAsJsonObject("mcpServers").getAsJsonObject("bella");
				String auth = bella.getAsJsonObject("headers").get("Authorization").getAsString();
				try (McpClient client = new McpClient(new StreamableHttpTransport(URI.create(Json.str(bella, "url")),
						auth.substring("Bearer ".length()), HttpTransport.jdk()))) {
					client.initialize("claude-code");
					McpClient.CallResult r = client.callTool("adt_read_source",
							Json.parseObject("{\"name\":\"ZCL_ORDERS\"}"), CancelToken.NONE);
					p.emit(FakeCli.init());
					p.emit(FakeCli.delta("Gelesen: " + r.text()));
					p.emit(FakeCli.success("x"));
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
		ClaudeCodeSession s = session(fake);
		Recorder r = new Recorder();
		s.ask("Lies ZCL_ORDERS", r, CancelToken.NONE);
		assertEquals("Gelesen: result of adt_read_source", r.text.toString());
		assertEquals(List.of("call:adt_read_source", "result:result of adt_read_source"), r.tools);
		assertEquals(List.of("adt_read_source:ZCL_ORDERS"), tools.calls);
	}

	@Test
	void stopInterruptsTheTurnAndKeepsTheProcess() throws Exception {
		FakeCli fake = new FakeCli((p, msg) -> {
			if ("control_request".equals(Json.str(msg, "type"))) {
				p.emit("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":"
						+ Json.GSON.toJson(Json.str(msg, "request_id")) + "}}");
				p.emit("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true}");
			} else if (msg.getAsJsonObject("message").toString().contains("lang")) {
				p.emit(FakeCli.init());
				p.emit(FakeCli.delta("Ich denke lange"));
			} else {
				ECHO.accept(p, msg);
			}
		});
		ClaudeCodeSession s = session(fake);
		Recorder r = new Recorder();
		CancelToken cancel = new CancelToken();
		CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
			try {
				s.ask("Denk lange nach", r, cancel);
				return null;
			} catch (Exception e) {
				return e;
			}
		});
		waitFor(() -> r.sawText);
		cancel.cancel();
		assertTrue(outcome.get(5, TimeUnit.SECONDS) instanceof CancelToken.CancelledException);
		FakeCli.Proc p = fake.started.get(0);
		assertTrue(p.isAlive());
		assertEquals("interrupt", p.received.get(1).getAsJsonObject("request").get("subtype").getAsString());

		Recorder next = new Recorder();
		s.ask("Weiter", next, CancelToken.NONE);
		assertEquals("Antwort auf: Weiter", next.text.toString());
		assertEquals(1, fake.started.size());
		assertTrue(next.notices.isEmpty());
	}

	@Test
	void unresponsiveCliIsKilledAndTheNextTurnSaysSo() throws Exception {
		FakeCli fake = new FakeCli((p, msg) -> {
			if (msg.toString().contains("hänge")) {
				p.emit(FakeCli.delta("…"));
			} else if ("user".equals(Json.str(msg, "type"))) {
				ECHO.accept(p, msg);
			}
		});
		ClaudeCodeSession s = session(fake);
		Recorder r = new Recorder();
		CancelToken cancel = new CancelToken();
		CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
			try {
				s.ask("ich hänge", r, cancel);
				return null;
			} catch (Exception e) {
				return e;
			}
		});
		waitFor(() -> r.sawText);
		cancel.cancel();
		assertTrue(outcome.get(ClaudeCodeSession.INTERRUPT_GRACE_MS + 3000, TimeUnit.MILLISECONDS)
				instanceof CancelToken.CancelledException);
		assertFalse(fake.started.get(0).isAlive());

		Recorder next = new Recorder();
		s.ask("Hallo", next, CancelToken.NONE);
		assertEquals(2, fake.started.size());
		assertEquals(List.of("cc_restarted"), next.notices);
	}

	@Test
	void crashIsReportedWithStderr() {
		FakeCli fake = new FakeCli((p, msg) -> p.destroy());
		ClaudeCodeSession s = session(fake);
		LlmException e = assertThrows(LlmException.class, () -> s.ask("x", new Recorder(), CancelToken.NONE));
		assertTrue(e.getMessage().contains("fake stderr"));
	}

	@Test
	void subscriptionErrorsBecomeNotices() throws Exception {
		FakeCli fake = new FakeCli((p, msg) -> p.emit(
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Claude AI usage limit reached|1760000000\"}"));
		Recorder r = new Recorder();
		session(fake).ask("x", r, CancelToken.NONE);
		assertEquals(1, r.notices.size());
		assertTrue(r.notices.get(0).startsWith("cc_limit:"));
	}

	@Test
	void modelChangeStartsANewProcess() throws Exception {
		FakeCli fake = new FakeCli(ECHO);
		ClaudeCodeSession s = session(fake);
		s.ask("a", new Recorder(), CancelToken.NONE);
		model = "sonnet";
		Recorder r = new Recorder();
		s.ask("b", r, CancelToken.NONE);
		assertEquals(2, fake.started.size());
		assertFalse(fake.started.get(0).isAlive());
		FakeCli.Proc second = fake.started.get(1);
		assertEquals("sonnet", second.arg("--model"));
		assertEquals(List.of("cc_restarted"), r.notices);
	}

	@Test
	void closeKillsTheProcessAndRemovesTheTokenFile() throws Exception {
		FakeCli fake = new FakeCli(ECHO);
		ClaudeCodeSession s = session(fake);
		s.ask("a", new Recorder(), CancelToken.NONE);
		Path mcp = Path.of(fake.started.get(0).arg("--mcp-config"));
		assertTrue(Files.exists(mcp));
		s.close();
		assertFalse(fake.started.get(0).isAlive());
		assertFalse(Files.exists(mcp));
	}

	private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timeout");
			}
			Thread.sleep(10);
		}
	}
}

package de.kiliantaubmann.bella.core.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.llm.LlmException;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.mcp.McpClient;
import de.kiliantaubmann.bella.core.mcp.StreamableHttpTransport;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Json;

class CopilotSessionTest {

	@TempDir
	Path tmp;

	private Path exe;
	private final FakeAcp fake = new FakeAcp();
	private final Tools tools = new Tools();
	private volatile String model = "claude-sonnet-4.5";
	private CopilotSession session;

	static final class Tools implements ToolProvider {
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
					Capability.READ_SOURCE, ToolSpec.Kind.READ),
					ToolSpec.of("adt_write_source", "Writes source", Json.parseObject("{\"type\":\"object\"}"),
							Capability.WRITE_SOURCE, ToolSpec.Kind.WRITE));
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
		volatile ChatResult last;
		volatile boolean sawText;
		volatile boolean ended;

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
			ended = true;
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		exe = Files.createFile(tmp.resolve("copilot"));
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		ToolExecutor executor = new ToolExecutor(registry, ToolPolicy::defaults, (tool, input) -> true, null);
		CopilotCli cli = new CopilotCli(new CopilotCli.Config(exe.toString(), ""), fake, tmp.resolve("work"));
		session = new CopilotSession(cli, () -> new ChatSession.Settings(model, 1000, "high"), "Du bist Bella.",
				executor, "0.3.0");
	}

	@AfterEach
	void tearDown() {
		session.close();
	}

	@Test
	void keepsOneProcessAndSendsTheSystemPromptOnce() throws Exception {
		Recorder r1 = new Recorder();
		session.ask("Hallo", r1, CancelToken.NONE);
		Recorder r2 = new Recorder();
		session.ask("Noch was", r2, CancelToken.NONE);

		assertEquals("Antwort auf: Hallo", r1.text.toString());
		assertEquals("Antwort auf: Noch was", r2.text.toString());
		assertEquals(1, fake.started.size());
		FakeAcp.Proc p = fake.started.get(0);
		assertTrue(p.prompts.get(0).startsWith("<instructions>\nDu bist Bella.\n</instructions>"));
		assertEquals("Noch was", p.prompts.get(1));
		assertTrue(p.command.contains("--acp"));
		assertEquals("claude-sonnet-4.5", p.arg("--model"));
		assertEquals("high", p.arg("--reasoning-effort"));
		assertEquals(tmp.resolve("work"), p.workDir);

		JsonObject server = p.newSessionParams.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
		assertEquals("http", Json.str(server, "type"));
		assertEquals("bella", Json.str(server, "name"));
		assertTrue(Json.str(server, "url").startsWith("http://127.0.0.1:"));
		assertTrue(server.getAsJsonArray("headers").get(0).getAsJsonObject().get("value").getAsString()
				.startsWith("Bearer "));
		assertEquals(tmp.resolve("work").toAbsolutePath().toString(), Json.str(p.newSessionParams, "cwd"));
	}

	@Test
	void planModeAlsoHoldsForCopilot() throws Exception {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		List<String> confirmations = new CopyOnWriteArrayList<>();
		ToolExecutor plan = new ToolExecutor(registry, () -> ToolPolicy.defaults().withMode(ChatMode.PLAN),
				(tool, input) -> confirmations.add(tool.name()), null);
		CopilotCli cli = new CopilotCli(new CopilotCli.Config(exe.toString(), ""), fake, tmp.resolve("work"));
		try (CopilotSession planned = new CopilotSession(cli, () -> new ChatSession.Settings(model, 1000, "high"),
				"Du bist Bella.", plan, "0.3.0")) {
			fake.script = (p, id, sid, text) -> {
				try {
					JsonObject server = p.newSessionParams.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
					String auth = server.getAsJsonArray("headers").get(0).getAsJsonObject().get("value").getAsString();
					try (McpClient client = new McpClient(new StreamableHttpTransport(
							URI.create(Json.str(server, "url")), auth.substring("Bearer ".length()),
							HttpTransport.jdk()))) {
						client.initialize("copilot");
						McpClient.CallResult write = client.callTool("adt_write_source",
								Json.parseObject("{\"name\":\"ZCL_ORDERS\",\"source\":\"x\"}"), CancelToken.NONE);
						McpClient.CallResult read = client.callTool("adt_read_source",
								Json.parseObject("{\"name\":\"ZCL_ORDERS\"}"), CancelToken.NONE);
						p.update(sid, "agent_message_chunk", (write.isError() ? "refused: " + write.text() : "written")
								+ " | " + read.text());
						p.reply(id, FakeAcp.stopReason("end_turn"));
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
			Recorder r = new Recorder();
			planned.ask(ChatMode.PLAN.apply("Baue ZCL_ORDERS um"), r, CancelToken.NONE);
			assertTrue(r.text.toString().startsWith("refused: Refused in plan mode"), r.text.toString());
			assertTrue(r.text.toString().endsWith("| result of adt_read_source"), "reading still works");
			assertEquals(List.of("adt_read_source:ZCL_ORDERS"), tools.calls, "nothing was written");
			assertTrue(confirmations.isEmpty(), "refused before any confirmation");
			assertTrue(fake.started.get(0).prompts.get(0).contains("<chat_mode>Plan mode."),
					"Copilot gets the plan-mode instruction");
		}
	}

	@Test
	void copilotCallsBellasToolsThroughTheMcpServer() throws Exception {
		fake.script = (p, id, sid, text) -> {
			try {
				JsonObject server = p.newSessionParams.getAsJsonArray("mcpServers").get(0).getAsJsonObject();
				String auth = server.getAsJsonArray("headers").get(0).getAsJsonObject().get("value").getAsString();
				try (McpClient client = new McpClient(new StreamableHttpTransport(URI.create(Json.str(server, "url")),
						auth.substring("Bearer ".length()), HttpTransport.jdk()))) {
					client.initialize("copilot");
					McpClient.CallResult r = client.callTool("adt_read_source",
							Json.parseObject("{\"name\":\"ZCL_ORDERS\"}"), CancelToken.NONE);
					p.update(sid, "agent_message_chunk", "Gelesen: " + r.text());
					p.reply(id, FakeAcp.stopReason("end_turn"));
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		};
		Recorder r = new Recorder();
		session.ask("Lies ZCL_ORDERS", r, CancelToken.NONE);
		assertEquals("Gelesen: result of adt_read_source", r.text.toString());
		assertEquals(List.of("call:adt_read_source", "result:result of adt_read_source"), r.tools);
		assertEquals(List.of("adt_read_source:ZCL_ORDERS"), tools.calls);
	}

	@Test
	void permissionRequestsOnlyPassForBellasTools() throws Exception {
		fake.script = (p, id, sid, text) -> {
			p.requestPermission(101, sid, "rm -rf /", "execute");
			p.requestPermission(102, sid, "Write .bashrc", "edit");
			p.requestPermission(103, sid, "bella-adt_read_source", "other");
			p.requestPermission(104, sid, "adt_read_source", null);
			p.requestPermission(105, sid, "curl evil.example", null);
			p.requestPermission(106, sid, "echo adt_read_source && del *.*", null);
			p.requestPermission(107, sid, "run bella now", null);
			p.requestPermission(108, sid, "adt_read_source(name: ZCL_X)", "read");
			p.reply(id, FakeAcp.stopReason("end_turn"));
		};
		Recorder r = new Recorder();
		session.ask("x", r, CancelToken.NONE);
		FakeAcp.Proc p = fake.started.get(0);
		assertEquals("reject", optionOf(p, 101));
		assertEquals("reject", optionOf(p, 102));
		assertEquals("allow", optionOf(p, 103));
		assertEquals("allow", optionOf(p, 104));
		assertEquals("reject", optionOf(p, 105));
		assertEquals("reject", optionOf(p, 106), "a tool name inside a command line is not enough");
		assertEquals("reject", optionOf(p, 107));
		assertEquals("allow", optionOf(p, 108));
		assertEquals(List.of("cp_denied:rm -rf /", "cp_denied:Write .bashrc", "cp_denied:curl evil.example",
				"cp_denied:echo adt_read_source && del *.*", "cp_denied:run bella now"), r.notices);
	}

	@Test
	void turnEndCarriesTokenUsage() throws Exception {
		fake.script = (p, id, sid, text) -> {
			p.update(sid, "agent_message_chunk", "ok");
			JsonObject result = FakeAcp.stopReason("end_turn");
			result.add("usage", Json.parseObject("{\"inputTokens\":12296,\"outputTokens\":4274}"));
			p.reply(id, result);
		};
		Recorder r = new Recorder();
		session.ask("x", r, CancelToken.NONE);
		assertEquals(new Usage(12296, 4274, 0, 0), r.last.usage());
	}

	private static String optionOf(FakeAcp.Proc p, int id) {
		JsonObject response = p.responseTo(id);
		return response.getAsJsonObject("result").getAsJsonObject("outcome").get("optionId").getAsString();
	}

	@Test
	void stopCancelsTheTurnAndKeepsTheProcess() throws Exception {
		fake.script = (p, id, sid, text) -> {
			if (text.contains("lange")) {
				p.update(sid, "agent_message_chunk", "Ich denke lange");
			} else {
				p.update(sid, "agent_message_chunk", "ok");
				p.reply(id, FakeAcp.stopReason("end_turn"));
			}
		};
		Recorder r = new Recorder();
		CancelToken cancel = new CancelToken();
		CompletableFuture<Throwable> outcome = run(() -> session.ask("Denk lange nach", r, cancel));
		waitFor(() -> r.sawText);
		cancel.cancel();
		assertTrue(outcome.get(5, TimeUnit.SECONDS) instanceof CancelToken.CancelledException);
		FakeAcp.Proc p = fake.started.get(0);
		assertTrue(p.isAlive());
		assertTrue(p.received.stream().anyMatch(m -> "session/cancel".equals(Json.str(m, "method"))));

		Recorder next = new Recorder();
		session.ask("Weiter", next, CancelToken.NONE);
		assertEquals("ok", next.text.toString());
		assertEquals(1, fake.started.size());
		assertTrue(next.notices.isEmpty());
	}

	@Test
	void unresponsiveCliIsKilledAndTheNextTurnSaysSo() throws Exception {
		fake.ignoreCancel = true;
		fake.script = (p, id, sid, text) -> {
			if (text.contains("hänge")) {
				p.update(sid, "agent_message_chunk", "…");
			} else {
				p.update(sid, "agent_message_chunk", "ok");
				p.reply(id, FakeAcp.stopReason("end_turn"));
			}
		};
		Recorder r = new Recorder();
		CancelToken cancel = new CancelToken();
		CompletableFuture<Throwable> outcome = run(() -> session.ask("ich hänge", r, cancel));
		waitFor(() -> r.sawText);
		cancel.cancel();
		assertTrue(outcome.get(CopilotSession.INTERRUPT_GRACE_MS + 3000, TimeUnit.MILLISECONDS)
				instanceof CancelToken.CancelledException);
		assertFalse(fake.started.get(0).isAlive());

		Recorder next = new Recorder();
		session.ask("Hallo", next, CancelToken.NONE);
		assertEquals(2, fake.started.size());
		assertEquals(List.of("cp_restarted"), next.notices);
		assertTrue(fake.started.get(1).prompts.get(0).startsWith("<instructions>"), "system prompt after restart");
	}

	@Test
	void notLoggedInBecomesANotice() throws Exception {
		fake.loggedIn = false;
		Recorder r = new Recorder();
		session.ask("x", r, CancelToken.NONE);
		assertEquals(List.of("cp_login"), r.notices);
		assertTrue(r.ended);
		assertNull(r.last);
		assertFalse(fake.started.get(0).isAlive());
	}

	@Test
	void promptErrorsBecomeNotices() throws Exception {
		fake.script = (p, id, sid, text) -> p.error(id, -32000, "You have exceeded your premium request quota");
		Recorder r = new Recorder();
		session.ask("x", r, CancelToken.NONE);
		assertEquals(1, r.notices.size());
		assertTrue(r.notices.get(0).startsWith("cp_limit:"), r.notices.toString());
	}

	@Test
	void crashIsReportedWithStderr() {
		fake.script = (p, id, sid, text) -> p.destroy();
		LlmException e = assertThrows(LlmException.class, () -> session.ask("x", new Recorder(), CancelToken.NONE));
		assertTrue(e.getMessage().contains("fake stderr"), e.getMessage());
	}

	@Test
	void modelChangeStartsANewProcess() throws Exception {
		session.ask("a", new Recorder(), CancelToken.NONE);
		model = "gpt-5";
		Recorder r = new Recorder();
		session.ask("b", r, CancelToken.NONE);
		assertEquals(2, fake.started.size());
		assertFalse(fake.started.get(0).isAlive());
		assertEquals("gpt-5", fake.started.get(1).arg("--model"));
		assertEquals(List.of("cp_restarted"), r.notices);
	}

	@Test
	void closeEndsTheProcess() throws Exception {
		session.ask("a", new Recorder(), CancelToken.NONE);
		session.close();
		assertFalse(fake.started.get(0).isAlive());
	}

	interface Body {
		void run() throws Exception;
	}

	private static CompletableFuture<Throwable> run(Body body) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				body.run();
				return null;
			} catch (Exception e) {
				return e;
			}
		});
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

package de.kiliantaubmann.bella.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

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

class McpServerTest {

	static final class Tools implements ToolProvider {
		final List<String> calls = new CopyOnWriteArrayList<>();
		/** Set: a call waits for it, as a slow SAP request would. */
		volatile CountDownLatch slow;
		final CountDownLatch running = new CountDownLatch(1);

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
			JsonObject schema = Json.parseObject(
					"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}");
			return List.of(ToolSpec.of("adt_read_source", "Reads source", schema, Capability.READ_SOURCE,
					ToolSpec.Kind.READ),
					ToolSpec.of("adt_write_source", "Writes source", schema, Capability.WRITE_SOURCE,
							ToolSpec.Kind.WRITE),
					ToolSpec.of("adt_transport_release", "Releases", schema, null, ToolSpec.Kind.WRITE));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			calls.add(remoteName + ":" + Json.str(input, "name"));
			CountDownLatch wait = slow;
			if (wait != null) {
				running.countDown();
				try {
					wait.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return ToolResult.ok("result of " + remoteName);
		}
	}

	private final Tools tools = new Tools();
	private final List<String> confirmed = new ArrayList<>();
	/** The client each confirmation was asked for, as the dialog names it. */
	private final List<String> callers = new ArrayList<>();
	private volatile boolean allow;
	/** Set: the confirmation waits for it or for the call's cancellation, like the dialog. */
	private volatile CountDownLatch answer;
	private final CountDownLatch asking = new CountDownLatch(1);
	private McpServer server;
	private McpClient client;

	@BeforeEach
	void start() throws IOException {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		ToolExecutor.Confirmer confirmer = new ToolExecutor.Confirmer() {

			@Override
			public boolean confirm(ToolSpec tool, JsonObject input) {
				return confirm(tool, input, CancelToken.NONE);
			}

			@Override
			public boolean confirm(ToolSpec tool, JsonObject input, CancelToken cancel) {
				confirmed.add(tool.name());
				callers.add(String.valueOf(McpServer.callerName()));
				CountDownLatch wait = answer;
				if (wait != null) {
					asking.countDown();
					cancel.onCancel(wait::countDown);
					try {
						wait.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				return allow;
			}
		};
		ToolExecutor executor = new ToolExecutor(registry, ToolPolicy::defaults, confirmer, (tool, input) -> "ZCL_OPEN".equals(Json.str(input, "name")) && tool.kind() == ToolSpec.Kind.WRITE
				? Optional.of(ToolResult.ok("written into the editor"))
				: Optional.empty());
		server = new McpServer(executor, "0.2.0");
		server.start();
		client = new McpClient(new StreamableHttpTransport(URI.create(server.url()), server.token(),
				HttpTransport.jdk()));
		client.initialize("test");
	}

	@AfterEach
	void stop() {
		client.close();
		server.close();
	}

	@Test
	void listsToolsWithReadOnlyHints() throws IOException {
		assertEquals("bella", client.serverName());
		List<McpClient.Tool> list = client.listTools();
		assertEquals(3, list.size());
		McpClient.Tool read = list.stream().filter(t -> t.name().equals("adt_read_source")).findFirst().orElseThrow();
		assertTrue(read.annotations().get("readOnlyHint").getAsBoolean());
		assertEquals("object", Json.str(read.inputSchema(), "type"));
	}

	@Test
	void readRunsWithoutConfirmation() throws IOException {
		McpClient.CallResult r = client.callTool("adt_read_source", Json.parseObject("{\"name\":\"ZCL_A\"}"),
				CancelToken.NONE);
		assertFalse(r.isError());
		assertEquals("result of adt_read_source", r.text());
		assertEquals(List.of("adt_read_source:ZCL_A"), tools.calls);
		assertTrue(confirmed.isEmpty());
	}

	@Test
	void writeAsksAndHonoursDecline() throws IOException {
		allow = false;
		McpClient.CallResult r = client.callTool("adt_write_source", Json.parseObject("{\"name\":\"ZCL_A\"}"),
				CancelToken.NONE);
		assertTrue(r.isError());
		assertEquals(List.of("adt_write_source"), confirmed);
		assertTrue(tools.calls.isEmpty());

		allow = true;
		r = client.callTool("adt_write_source", Json.parseObject("{\"name\":\"ZCL_A\"}"), CancelToken.NONE);
		assertFalse(r.isError());
		assertEquals(List.of("adt_write_source:ZCL_A"), tools.calls);
	}

	@Test
	void writesToOpenObjectsGoToTheEditor() throws IOException {
		McpClient.CallResult r = client.callTool("adt_write_source", Json.parseObject("{\"name\":\"ZCL_OPEN\"}"),
				CancelToken.NONE);
		assertFalse(r.isError());
		assertEquals("written into the editor", r.text());
		assertTrue(confirmed.isEmpty());
		assertTrue(tools.calls.isEmpty());
	}

	@Test
	void deniedAndInvalidCallsNeverRun() throws IOException {
		allow = true;
		McpClient.CallResult denied = client.callTool("adt_transport_release",
				Json.parseObject("{\"name\":\"K900001\"}"), CancelToken.NONE);
		assertTrue(denied.isError());
		McpClient.CallResult invalid = client.callTool("adt_read_source", new JsonObject(), CancelToken.NONE);
		assertTrue(invalid.isError());
		assertTrue(invalid.text().contains("INVALID_JSON"));
		McpClient.CallResult unknown = client.callTool("rm_rf", new JsonObject(), CancelToken.NONE);
		assertTrue(unknown.isError());
		assertTrue(tools.calls.isEmpty());
	}

	@Test
	void rejectsMissingOrWrongToken() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
		HttpResponse<String> none = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(401, none.statusCode());
		HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer 00").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(401, wrong.statusCode());
		HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer " + server.token()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(405, get.statusCode());
		McpClient wrongClient = new McpClient(
				new StreamableHttpTransport(URI.create(server.url()), "nope", HttpTransport.jdk()));
		assertThrows(IOException.class, () -> wrongClient.initialize("test"));
		assertTrue(tools.calls.isEmpty());
	}

	@Test
	void claudeCodeConfigPointsAtLoopbackWithToken() {
		JsonObject cfg = server.claudeCodeConfig("bella");
		JsonObject bella = cfg.getAsJsonObject("mcpServers").getAsJsonObject("bella");
		assertEquals("http", Json.str(bella, "type"));
		assertTrue(Json.str(bella, "url").startsWith("http://127.0.0.1:"));
		assertEquals("Bearer " + server.token(),
				bella.getAsJsonObject("headers").get("Authorization").getAsString());
		// without it the CLI aborts a call after five idle minutes, e.g. while the confirmation is open
		assertEquals(McpServer.TOOL_TIMEOUT_MILLIS, bella.get("timeout").getAsInt());
	}

	@Test
	void anOpenedServerOffersOnlyTheChosenToolsOnItsPortWithItsToken() throws Exception {
		// not refreshed: another program may come before any chat read the tool list
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		ToolExecutor executor = new ToolExecutor(registry, ToolPolicy::defaults, (t, i) -> true,
				(t, i) -> Optional.empty());
		int port;
		try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
			port = free.getLocalPort();
		}
		try (McpServer opened = new McpServer(executor, "0.2.0", "fixed-token", port,
				t -> t.kind() == ToolSpec.Kind.READ, "just reading")) {
			opened.refreshToolsWith(() -> registry.refresh(e -> {
			}));
			opened.start();
			assertEquals("http://127.0.0.1:" + port + "/mcp", opened.url());
			assertEquals("fixed-token", opened.token());
			McpClient c = new McpClient(new StreamableHttpTransport(URI.create(opened.url()), "fixed-token",
					HttpTransport.jdk()));
			try {
				c.initialize("test");
				assertEquals(List.of("adt_read_source"), c.listTools().stream().map(McpClient.Tool::name).toList());
				McpClient.CallResult hidden = c.callTool("adt_write_source", Json.parseObject("{\"name\":\"ZCL_A\"}"),
						CancelToken.NONE);
				assertTrue(hidden.isError() && hidden.text().contains("Unknown tool"), hidden.text());
				assertTrue(tools.calls.isEmpty(), tools.calls.toString());
				assertFalse(c.callTool("adt_read_source", Json.parseObject("{\"name\":\"ZCL_A\"}"), CancelToken.NONE)
						.isError());
			} finally {
				c.close();
			}
		}
	}

	@Test
	void refusesRequestsAWebPageCouldSend() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
		HttpResponse<String> foreign = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer " + server.token()).header("Origin", "https://evil.example")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(403, foreign.statusCode());
		HttpResponse<String> local = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer " + server.token()).header("Origin", "http://localhost:3000")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, local.statusCode());
		assertTrue(McpServer.local(java.util.Map.of("host", "127.0.0.1:47011")));
		assertTrue(McpServer.local(java.util.Map.of("host", "localhost")));
		assertTrue(McpServer.local(java.util.Map.of("host", "[::1]:47011")));
		assertTrue(McpServer.local(java.util.Map.of()));
		// DNS rebinding: the page's own host name pointed at 127.0.0.1
		assertFalse(McpServer.local(java.util.Map.of("host", "evil.example:47011")));
		assertFalse(McpServer.local(java.util.Map.of("host", "localhost.evil.example")));
		assertFalse(McpServer.local(java.util.Map.of("host", "127.0.0.1", "origin", "null")));
		assertFalse(McpServer.local(java.util.Map.of("host", "127.0.0.1", "origin", "http://127.0.0.1.evil.example")));
		assertTrue(tools.calls.isEmpty());
	}

	private CompletableFuture<HttpResponse<String>> post(HttpClient http, String body) {
		return post(http, body, null);
	}

	private CompletableFuture<HttpResponse<String>> post(HttpClient http, String body, String session) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer " + server.token()).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (session != null) {
			b.header("Mcp-Session-Id", session);
		}
		return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** Opens a session as a client named {@code name} and returns its id. */
	private String initialize(HttpClient http, String name) throws Exception {
		HttpResponse<String> r = post(http, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
				+ "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"" + name + "\",\"version\":\"1\"}}}")
				.get(10, TimeUnit.SECONDS);
		assertEquals(200, r.statusCode(), r.body());
		return r.headers().firstValue("Mcp-Session-Id").orElseThrow();
	}

	private static String readBody(int id, String name) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ "adt_read_source\",\"arguments\":{\"name\":\"" + name + "\"}}}";
	}

	@Test
	void eachClientIsNamedInItsOwnConfirmation() throws Exception {
		allow = true;
		HttpClient http = HttpClient.newHttpClient();
		String other = initialize(http, "Other CLI");
		// Bella's own client, initialized last, must not lend its name to the other one's call
		client.callTool("adt_write_source", Json.parseObject("{\"name\":\"ZCL_A\"}"), CancelToken.NONE);
		post(http, callBody(2, "ZCL_B"), other).get(10, TimeUnit.SECONDS);
		post(http, callBody(3, "ZCL_C")).get(10, TimeUnit.SECONDS);
		assertEquals(List.of("bella", "Other CLI", "null"), callers);
		assertEquals(null, McpServer.callerName());
	}

	@Test
	void anUnknownOrEndedSessionIsRefused() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		assertEquals(404, post(http, readBody(1, "ZCL_A"), "no-such-session").get(10, TimeUnit.SECONDS).statusCode());
		String session = initialize(http, "Other CLI");
		assertEquals(200, post(http, readBody(2, "ZCL_A"), session).get(10, TimeUnit.SECONDS).statusCode());
		HttpResponse<String> ended = http.send(HttpRequest.newBuilder(URI.create(server.url()))
				.header("Authorization", "Bearer " + server.token()).header("Mcp-Session-Id", session).DELETE().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, ended.statusCode());
		assertEquals(404, post(http, readBody(3, "ZCL_A"), session).get(10, TimeUnit.SECONDS).statusCode());
		assertEquals(List.of("adt_read_source:ZCL_A"), tools.calls);
	}

	private static String callBody(int id, String name) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
				+ "adt_write_source\",\"arguments\":{\"name\":\"" + name + "\"}}}";
	}

	private static String cancelBody(int id) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + id
				+ ",\"reason\":\"Request timed out\"}}";
	}

	@Test
	void aCallCancelledWhileAskingNeverRuns() throws Exception {
		allow = true; // the developer allows it, but only after the CLI gave up
		answer = new CountDownLatch(1);
		HttpClient http = HttpClient.newHttpClient();
		CompletableFuture<HttpResponse<String>> call = post(http, callBody(7, "ZCL_A"));
		assertTrue(asking.await(10, TimeUnit.SECONDS));
		assertEquals(202, post(http, cancelBody(7)).get(10, TimeUnit.SECONDS).statusCode());
		String response = call.get(10, TimeUnit.SECONDS).body();
		assertTrue(response.contains("Nothing was changed") && response.contains("\"isError\":true"), response);
		assertTrue(tools.calls.isEmpty(), tools.calls.toString());
		// a cancellation for a finished or unknown request is ignored
		assertEquals(202, post(http, cancelBody(99)).get(10, TimeUnit.SECONDS).statusCode());
	}

	@Test
	void aCallCancelledWhileRunningIsReportedWithTheNextCall() throws Exception {
		allow = true;
		tools.slow = new CountDownLatch(1);
		HttpClient http = HttpClient.newHttpClient();
		CompletableFuture<HttpResponse<String>> call = post(http, callBody(8, "ZCL_SLOW"));
		assertTrue(tools.running.await(10, TimeUnit.SECONDS));
		post(http, cancelBody(8)).get(10, TimeUnit.SECONDS);
		tools.slow.countDown();
		call.get(10, TimeUnit.SECONDS);
		tools.slow = null;

		// another client never sees it
		McpClient.CallResult foreign = client.callTool("adt_read_source", Json.parseObject("{\"name\":\"ZCL_X\"}"),
				CancelToken.NONE);
		assertEquals("result of adt_read_source", foreign.text());

		String next = post(http, readBody(9, "ZCL_B")).get(10, TimeUnit.SECONDS).body();
		assertTrue(next.contains("still ran to the end"), next);
		assertTrue(next.contains("- adt_write_source {\\\"name\\\":\\\"ZCL_SLOW\\\"} ran: result of adt_write_source"),
				next);
		assertTrue(next.contains("result of adt_read_source"), next);
		String after = post(http, readBody(10, "ZCL_C")).get(10, TimeUnit.SECONDS).body();
		assertFalse(after.contains("still ran to the end"), after);
	}

	/** Runs a write in {@code session} that the client gives up on while it runs. */
	private void lateWrite(HttpClient http, String session, int id) throws Exception {
		allow = true;
		tools.slow = new CountDownLatch(1);
		CompletableFuture<HttpResponse<String>> call = post(http, callBody(id, "ZCL_SLOW"), session);
		assertTrue(tools.running.await(10, TimeUnit.SECONDS));
		post(http, cancelBody(id), session).get(10, TimeUnit.SECONDS);
		tools.slow.countDown();
		call.get(10, TimeUnit.SECONDS);
		tools.slow = null;
	}

	@Test
	void aLateResultReachesTheClientsNextSessionAfterAReconnect() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String first = initialize(http, "Other CLI");
		lateWrite(http, first, 8);
		// reconnects without ending the old session
		String second = initialize(http, "Other CLI");
		String next = post(http, readBody(9, "ZCL_B"), second).get(10, TimeUnit.SECONDS).body();
		assertTrue(next.contains("still ran to the end") && next.contains("ZCL_SLOW"), next);
		// another client never sees it
		String foreign = post(http, readBody(10, "ZCL_C"), initialize(http, "Third")).get(10, TimeUnit.SECONDS)
				.body();
		assertFalse(foreign.contains("still ran to the end"), foreign);
	}

	@Test
	void aLateResultOfAnEndedSessionReachesTheClientsNextOne() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		String first = initialize(http, "Other CLI");
		lateWrite(http, first, 8);
		http.send(HttpRequest.newBuilder(URI.create(server.url())).header("Authorization", "Bearer " + server.token())
				.header("Mcp-Session-Id", first).DELETE().build(), HttpResponse.BodyHandlers.ofString());
		String second = initialize(http, "Other CLI");
		String next = post(http, readBody(9, "ZCL_B"), second).get(10, TimeUnit.SECONDS).body();
		assertTrue(next.contains("still ran to the end") && next.contains("ZCL_SLOW"), next);
		String after = post(http, readBody(10, "ZCL_C"), second).get(10, TimeUnit.SECONDS).body();
		assertFalse(after.contains("still ran to the end"), after);
	}

	@Test
	void anInitializeInABatchOpensASession() throws Exception {
		allow = true;
		HttpClient http = HttpClient.newHttpClient();
		HttpResponse<String> r = post(http, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
				+ "{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"Batch CLI\",\"version\":\"1\"}}}]")
				.get(10, TimeUnit.SECONDS);
		assertEquals(200, r.statusCode(), r.body());
		String session = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
		post(http, callBody(2, "ZCL_A"), session).get(10, TimeUnit.SECONDS);
		assertEquals(List.of("Batch CLI"), callers);
	}
}

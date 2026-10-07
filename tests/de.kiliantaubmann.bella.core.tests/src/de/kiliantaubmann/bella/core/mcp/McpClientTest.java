package de.kiliantaubmann.bella.core.mcp;

import static de.kiliantaubmann.bella.core.testutil.FakeHttp.sse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.testutil.FakeHttp;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Json;

class McpClientTest {

	/** Transport answering from a script keyed by method. */
	static final class ScriptedTransport implements McpTransport {
		final List<JsonObject> requests = new ArrayList<>();
		final List<JsonObject> notifications = new ArrayList<>();
		final Map<String, String> results;

		ScriptedTransport(Map<String, String> results) {
			this.results = results;
		}

		@Override
		public JsonObject request(JsonObject request, CancelToken cancel) {
			requests.add(request);
			String method = request.get("method").getAsString();
			String key = method;
			if (request.getAsJsonObject("params").has("cursor")) {
				key = method + "#" + request.getAsJsonObject("params").get("cursor").getAsString();
			}
			JsonObject response = Json.parseObject(results.get(key));
			response.add("id", request.get("id"));
			return response;
		}

		@Override
		public void notify(JsonObject n) {
			notifications.add(n);
		}

		@Override
		public void close() {
		}
	}

	@Test
	void initializesListsWithPaginationAndCalls() throws Exception {
		ScriptedTransport t = new ScriptedTransport(Map.of(
				"initialize", "{\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"arc-1\",\"version\":\"1.2\"}}}",
				"tools/list", "{\"result\":{\"tools\":[{\"name\":\"SAPRead\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"p2\"}}",
				"tools/list#p2", "{\"result\":{\"tools\":[{\"name\":\"SAPWrite\",\"annotations\":{\"destructiveHint\":true}}]}}",
				"tools/call", "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"REPORT z.\"}],\"isError\":false}}"));
		McpToolProvider p = new McpToolProvider("arc1", "ARC-1", new McpClient(t), "0.1");
		List<ToolSpec> tools = p.listTools();
		assertEquals(2, tools.size());
		assertEquals(Capability.READ_SOURCE, tools.get(0).capability());
		assertEquals(ToolSpec.Kind.READ, tools.get(0).kind());
		assertEquals(ToolSpec.Kind.WRITE, tools.get(1).kind());
		assertEquals("notifications/initialized", t.notifications.get(0).get("method").getAsString());
		assertEquals("REPORT z.", p.call("SAPRead", new JsonObject(), CancelToken.NONE).content());
		assertEquals(1, t.requests.stream().filter(r -> r.get("method").getAsString().equals("initialize")).count());
	}

	@Test
	void jsonRpcErrorsBecomeExceptions() {
		ScriptedTransport t = new ScriptedTransport(Map.of(
				"initialize", "{\"error\":{\"code\":-32600,\"message\":\"bad\"}}"));
		McpClient c = new McpClient(t);
		McpException e = assertThrows(McpException.class, () -> c.initialize("0.1"));
		assertEquals(-32600, e.code());
	}

	@Test
	void streamableHttpHandlesSseAndSessionId() throws Exception {
		FakeHttp http = new FakeHttp()
				.respond(200, Map.of("content-type", "text/event-stream", "mcp-session-id", "abc"),
						sse("message", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}",
								"message", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"))
				.respond(202, "application/json", "")
				.respond(200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"x\":1}}");
		StreamableHttpTransport t = new StreamableHttpTransport(URI.create("http://localhost:3000/mcp"), "tok", http);
		JsonObject r1 = t.request(Json.parseObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"),
				CancelToken.NONE);
		assertTrue(r1.getAsJsonObject("result").get("ok").getAsBoolean());
		t.setProtocolVersion("2025-06-18");
		t.notify(Json.parseObject("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		t.request(Json.parseObject("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"),
				CancelToken.NONE);
		assertEquals("Bearer tok", http.sent.get(0).headers().get("authorization"));
		assertEquals("abc", http.sent.get(2).headers().get("mcp-session-id"));
		assertEquals("2025-06-18", http.sent.get(2).headers().get("mcp-protocol-version"));
	}

	@Test
	void unreachableHttpServerGetsReadableMessage() {
		HttpTransport down = (uri, headers, body, cancel) -> {
			throw new ConnectException();
		};
		McpToolProvider p = new McpToolProvider("demo", "Demo", new McpClient(
				new StreamableHttpTransport(URI.create("http://localhost:1/mcp?token=secret"), null, down)), "0.1");
		IOException e = assertThrows(IOException.class, p::listTools);
		assertTrue(e.getMessage().contains("not reachable at http://localhost:1/mcp (connection refused)"),
				e.getMessage());
		assertFalse(e.getMessage().contains("secret"), e.getMessage());
	}

	@Test
	void startsNewSessionAfterServerRestart() throws Exception {
		String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
		String list = "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"tools\":[{\"name\":\"DemoTool\"}]}}";
		FakeHttp http = new FakeHttp()
				.respond(200, Map.of("content-type", "application/json", "mcp-session-id", "s1"), init)
				.respond(202, "application/json", "")
				.respond(404, "application/json", "")
				.respond(200, Map.of("content-type", "application/json", "mcp-session-id", "s2"), init)
				.respond(202, "application/json", "")
				.respond(200, "application/json", list);
		McpToolProvider p = new McpToolProvider("demo", "Demo",
				new McpClient(new StreamableHttpTransport(URI.create("http://localhost:1/mcp"), null, http)), "0.1");
		List<ToolSpec> tools = p.listTools();
		assertEquals(List.of("DemoTool"), tools.stream().map(ToolSpec::remoteName).toList());
		assertEquals("s1", http.sent.get(2).headers().get("mcp-session-id"));
		assertTrue(http.sent.get(3).body().contains("\"initialize\""));
		assertFalse(http.sent.get(3).headers().containsKey("mcp-session-id"));
		assertEquals("s2", http.sent.get(5).headers().get("mcp-session-id"));
	}

	@Test
	void splitsCommandLines() {
		assertEquals(List.of("npx", "-y", "arc-1@latest", "--url", "http://a b"),
				StdioTransport.splitCommand("npx -y arc-1@latest --url \"http://a b\""));
	}
}

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
		final List<String> calls = new ArrayList<>();

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
			return ToolResult.ok("result of " + remoteName);
		}
	}

	private final Tools tools = new Tools();
	private final List<String> confirmed = new ArrayList<>();
	private volatile boolean allow;
	private McpServer server;
	private McpClient client;

	@BeforeEach
	void start() throws IOException {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(tools);
		registry.refresh(e -> {
		});
		ToolExecutor executor = new ToolExecutor(registry, ToolPolicy::defaults, (tool, input) -> {
			confirmed.add(tool.name());
			return allow;
		}, (tool, input) -> "ZCL_OPEN".equals(Json.str(input, "name")) && tool.kind() == ToolSpec.Kind.WRITE
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
	}
}

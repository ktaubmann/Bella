package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Exposes the tools of one MCP server (e.g. ARC-1). Known ARC-1 tools get
 * capability tags so the registry can prefer Bella's own ADT tools, or ARC-1's,
 * depending on the user's choice.
 */
public final class McpToolProvider implements ToolProvider {

	/** ARC-1 tool name → capability. */
	private static final Map<String, String> ARC1_CAPABILITIES = Map.of(
			"SAPSearch", Capability.SEARCH,
			"SAPRead", Capability.READ_SOURCE,
			"SAPWrite", Capability.WRITE_SOURCE,
			"SAPActivate", Capability.ACTIVATE,
			"SAPQuery", Capability.TABLE_CONTENTS);

	private static final List<String> ARC1_READ_ONLY = List.of("SAPRead", "SAPSearch", "SAPNavigate", "SAPContext",
			"SAPLint", "SAPDiagnose", "SAPQuery");

	/**
	 * How long a tool list is used before the server is asked again. The registry refreshes before every chat
	 * question; without this, each question would wait for a round trip to every server first. Changed settings
	 * create a new provider, so they never see an old list.
	 */
	static final long TOOLS_TTL_MILLIS = 5 * 60_000L;
	/** After a failed listing (server down, timeout) the error is repeated without asking the server again. */
	static final long RETRY_AFTER_MILLIS = 60_000L;

	private final String serverId;
	private final String displayName;
	private final McpClient client;
	private final String clientVersion;
	private final LongSupplier clock;

	private List<ToolSpec> cachedTools;
	private long cachedAt;
	private IOException lastFailure;
	private long failedAt;

	public McpToolProvider(String serverId, String displayName, McpClient client, String clientVersion) {
		this(serverId, displayName, client, clientVersion, System::currentTimeMillis);
	}

	McpToolProvider(String serverId, String displayName, McpClient client, String clientVersion, LongSupplier clock) {
		this.serverId = serverId;
		this.displayName = displayName;
		this.client = client;
		this.clientVersion = clientVersion;
		this.clock = clock;
	}

	@Override
	public String id() {
		return "mcp:" + serverId;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public synchronized List<ToolSpec> listTools() throws IOException {
		long now = clock.getAsLong();
		if (cachedTools != null && now - cachedAt < TOOLS_TTL_MILLIS) {
			return cachedTools;
		}
		if (lastFailure != null && now - failedAt < RETRY_AFTER_MILLIS) {
			// report the failure again without waiting for the server; its tools stay out of the chat meanwhile
			throw lastFailure;
		}
		List<ToolSpec> specs = new ArrayList<>();
		try {
			for (McpClient.Tool t : inSession(client::listTools)) {
				specs.add(ToolSpec.of(t.name(), t.description(), t.inputSchema(), ARC1_CAPABILITIES.get(t.name()),
						kindOf(t)));
			}
		} catch (IOException e) {
			lastFailure = e;
			failedAt = now;
			cachedTools = null;
			throw e;
		}
		lastFailure = null;
		cachedTools = List.copyOf(specs);
		cachedAt = now;
		return cachedTools;
	}

	static ToolSpec.Kind kindOf(McpClient.Tool t) {
		if (ARC1_READ_ONLY.contains(t.name())) {
			return ToolSpec.Kind.READ;
		}
		JsonObject a = t.annotations();
		if (a != null) {
			JsonElement ro = a.get("readOnlyHint");
			if (ro != null && ro.isJsonPrimitive() && ro.getAsBoolean()) {
				return ToolSpec.Kind.READ;
			}
			return ToolSpec.Kind.WRITE;
		}
		return ToolSpec.Kind.UNKNOWN;
	}

	@Override
	public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) throws IOException {
		McpClient.CallResult r = inSession(() -> client.callTool(remoteName, input, cancel));
		return new ToolResult(r.text(), r.isError());
	}

	private interface Request<T> {
		T run() throws IOException;
	}

	/**
	 * Runs a request in an initialized session. When the server has forgotten
	 * the session (e.g. it was restarted), a new one is started once; the
	 * server rejected the request unseen, so repeating it is safe.
	 */
	private <T> T inSession(Request<T> request) throws IOException {
		client.initialize(clientVersion);
		try {
			return request.run();
		} catch (McpSessionExpiredException e) {
			client.initialize(clientVersion);
			return request.run();
		}
	}

	public void close() {
		client.close();
	}
}

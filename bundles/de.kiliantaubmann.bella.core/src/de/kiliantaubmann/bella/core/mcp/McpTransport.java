package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;

/** Carries JSON-RPC messages to an MCP server. */
public interface McpTransport extends AutoCloseable {

	/** Sends a request and waits for the response with the same id. */
	JsonObject request(JsonObject request, CancelToken cancel) throws IOException;

	/** Sends a notification (no response expected). */
	void notify(JsonObject notification) throws IOException;

	/** Called once the server announced the negotiated protocol version. */
	default void setProtocolVersion(String version) {
	}

	@Override
	void close();
}

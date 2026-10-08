package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;

/** The server no longer knows the MCP session, e.g. after a restart; initializing again helps. */
public class McpSessionExpiredException extends IOException {

	private static final long serialVersionUID = 1L;

	public McpSessionExpiredException(String message) {
		super(message);
	}
}

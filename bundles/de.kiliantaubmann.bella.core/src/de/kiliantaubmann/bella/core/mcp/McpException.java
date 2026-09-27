package de.kiliantaubmann.bella.core.mcp;

import java.io.IOException;

/** JSON-RPC error returned by an MCP server. */
public class McpException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int code;

	public McpException(int code, String message) {
		super(message);
		this.code = code;
	}

	public int code() {
		return code;
	}
}

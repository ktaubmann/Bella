package de.kiliantaubmann.bella.core.tools;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;

/** Source of tools: Bella's own ADT tools or an MCP server such as ARC-1. */
public interface ToolProvider {

	/** Stable id, e.g. {@code adt} or {@code mcp:arc1}. */
	String id();

	/** Human readable name for the UI. */
	String displayName();

	List<ToolSpec> listTools() throws Exception;

	/**
	 * Executes a tool.
	 *
	 * @param remoteName the provider's own name for the tool
	 */
	ToolResult call(String remoteName, JsonObject input, CancelToken cancel) throws Exception;

	/**
	 * Checks a call before the developer is asked to confirm it, so a call that
	 * would be refused anyway (e.g. a write outside the allowed packages) does
	 * not show a confirmation first.
	 *
	 * @return the reason for refusing the call, or empty to go on
	 */
	default Optional<String> refuse(String remoteName, JsonObject input, CancelToken cancel) {
		return Optional.empty();
	}
}

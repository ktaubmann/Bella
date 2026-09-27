package de.kiliantaubmann.bella.core.tools;

import java.util.Optional;

import com.google.gson.JsonObject;

/**
 * Hook that runs before a tool executes. The UI installs one that redirects
 * source writes aimed at an object that is open in an editor into that
 * editor's buffer instead of the SAP system.
 */
public interface WriteGuard {

	WriteGuard NONE = (tool, input) -> Optional.empty();

	/** @return a result to use instead of executing the tool, or empty to execute normally */
	Optional<ToolResult> intercept(ToolSpec tool, JsonObject input);
}

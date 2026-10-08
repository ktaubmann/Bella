package de.kiliantaubmann.bella.core.tools;

import java.util.Objects;

import com.google.gson.JsonObject;

/**
 * A tool the model may call.
 *
 * @param name        name exposed to the model, {@code ^[a-zA-Z0-9_-]{1,64}$}
 * @param description what the tool does and when to use it
 * @param inputSchema JSON schema of the input object
 * @param capability  optional capability tag (see {@link Capability}) used to
 *                    de-duplicate tools that do the same thing
 * @param kind        read-only or mutating; drives the default policy. It says whether the call changes
 *                    the system, not which HTTP method it uses: a syntax check, a pretty print or a quick
 *                    fix preview send POST requests and still change nothing, so they are {@code READ}
 * @param providerId  id of the {@link ToolProvider} that executes it
 * @param remoteName  the name the provider knows the tool by
 */
public record ToolSpec(String name, String description, JsonObject inputSchema, String capability, Kind kind,
		String providerId, String remoteName) {

	public enum Kind {
		READ, WRITE, UNKNOWN
	}

	public ToolSpec {
		Objects.requireNonNull(name);
		Objects.requireNonNull(inputSchema);
		description = description == null ? "" : description;
		kind = kind == null ? Kind.UNKNOWN : kind;
		remoteName = remoteName == null ? name : remoteName;
	}

	public static ToolSpec of(String name, String description, JsonObject inputSchema, String capability, Kind kind) {
		return new ToolSpec(name, description, inputSchema, capability, kind, null, name);
	}

	public ToolSpec withProvider(String id, String exposedName) {
		return new ToolSpec(exposedName, description, inputSchema, capability, kind, id, remoteName);
	}
}

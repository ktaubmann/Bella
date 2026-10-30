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
 * @param deferred    rarely needed: a provider that supports it loads the tool only when the model
 *                    searches for it, so its definition costs no tokens until then
 */
public record ToolSpec(String name, String description, JsonObject inputSchema, String capability, Kind kind,
		String providerId, String remoteName, boolean deferred) {

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

	/**
	 * An object schema from {@code name, type, description} triples; the
	 * names in {@code required} must be given.
	 */
	public static JsonObject schema(String[] required, String... props) {
		JsonObject p = new JsonObject();
		for (int i = 0; i + 2 < props.length; i += 3) {
			JsonObject prop = new JsonObject();
			prop.addProperty("type", props[i + 1]);
			prop.addProperty("description", props[i + 2]);
			p.add(props[i], prop);
		}
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", p);
		com.google.gson.JsonArray req = new com.google.gson.JsonArray();
		for (String r : required) {
			req.add(r);
		}
		s.add("required", req);
		return s;
	}

	public static ToolSpec of(String name, String description, JsonObject inputSchema, String capability, Kind kind) {
		return new ToolSpec(name, description, inputSchema, capability, kind, null, name, false);
	}

	public ToolSpec withProvider(String id, String exposedName) {
		return new ToolSpec(exposedName, description, inputSchema, capability, kind, id, remoteName, deferred);
	}

	/** The same tool, loaded only on demand (see {@code deferred}). */
	public ToolSpec asDeferred() {
		return new ToolSpec(name, description, inputSchema, capability, kind, providerId, remoteName, true);
	}
}

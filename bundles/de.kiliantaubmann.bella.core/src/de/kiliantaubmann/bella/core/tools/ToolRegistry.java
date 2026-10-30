package de.kiliantaubmann.bella.core.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Merges the tools of all registered providers into the single list the
 * model sees. Tool names are prefixed per provider so they never collide
 * ({@code adt_…} for Bella's own tools, {@code mcp_<server>_…} for MCP
 * servers). Tools that share a capability tag are de-duplicated in favour of
 * the preferred provider.
 */
public final class ToolRegistry {

	public static final String ADT_PROVIDER_ID = "adt";
	/** Bella's style check; its tool keeps its own name like the ADT tools. */
	public static final String LINT_PROVIDER_ID = "lint";
	/** The ABAP debugger in Eclipse; its tools keep their names ({@code debug_…}). */
	public static final String DEBUG_PROVIDER_ID = "debug";
	/** Questions with options to the developer in the chat; its tool keeps its name ({@code ask_user}). */
	public static final String ASK_PROVIDER_ID = "ask";

	private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();
	private volatile String preferredProviderId = ADT_PROVIDER_ID;
	private volatile Map<String, ToolSpec> tools = Map.of();
	private volatile Map<String, ToolProvider> providerByTool = Map.of();
	/** The last tool list of each provider, used while it fails, so the list the model sees stays the same. */
	private final Map<String, List<ToolSpec>> lastListed = new java.util.concurrent.ConcurrentHashMap<>();

	public void addProvider(ToolProvider provider) {
		providers.removeIf(p -> p.id().equals(provider.id()));
		providers.add(provider);
	}

	public void removeProvider(String id) {
		providers.removeIf(p -> p.id().equals(id));
		lastListed.remove(id);
	}

	public List<ToolProvider> providers() {
		return List.copyOf(providers);
	}

	/** @param providerIdPrefix {@code adt} or {@code mcp} */
	public void setPreferredProvider(String providerIdPrefix) {
		this.preferredProviderId = providerIdPrefix;
	}

	/**
	 * Re-reads the tool lists. Providers that fail (server down, not logged
	 * on) are reported to {@code onError} and keep the tools they listed last
	 * (calls then fail with the provider's error): the tools come first in a
	 * request, so a changed list would make the whole chat miss the prompt cache.
	 * The order is stable for the same reason.
	 */
	public void refresh(Consumer<String> onError) {
		Map<String, ToolSpec> merged = new LinkedHashMap<>();
		Map<String, ToolProvider> owners = new LinkedHashMap<>();
		Map<String, String> capabilityOwner = new LinkedHashMap<>();
		List<ToolProvider> ordered = new ArrayList<>(providers);
		ordered.sort((a, b) -> Boolean.compare(!isPreferred(a), !isPreferred(b)));
		for (ToolProvider provider : ordered) {
			List<ToolSpec> specs;
			try {
				specs = provider.listTools();
				lastListed.put(provider.id(), List.copyOf(specs));
			} catch (Exception e) {
				onError.accept(provider.displayName() + ": " + e.getMessage());
				specs = lastListed.get(provider.id());
				if (specs == null) {
					continue;
				}
			}
			for (ToolSpec spec : specs) {
				String cap = spec.capability();
				if (cap != null && capabilityOwner.containsKey(cap)
						&& !capabilityOwner.get(cap).equals(provider.id())) {
					continue;
				}
				String exposed = exposedName(provider, spec.remoteName());
				ToolSpec registered = spec.withProvider(provider.id(), exposed);
				merged.put(exposed, registered);
				owners.put(exposed, provider);
				if (cap != null) {
					capabilityOwner.putIfAbsent(cap, provider.id());
				}
			}
		}
		this.tools = java.util.Collections.unmodifiableMap(merged);
		this.providerByTool = java.util.Collections.unmodifiableMap(owners);
	}

	private boolean isPreferred(ToolProvider p) {
		return p.id().equals(preferredProviderId) || p.id().startsWith(preferredProviderId + ":");
	}

	public List<ToolSpec> tools() {
		return List.copyOf(tools.values());
	}

	public Optional<ToolSpec> find(String exposedName) {
		return Optional.ofNullable(tools.get(exposedName));
	}

	public Optional<ToolProvider> providerOf(String exposedName) {
		return Optional.ofNullable(providerByTool.get(exposedName));
	}

	/**
	 * Builds the name the model sees. Bella's own tools keep their names
	 * ({@code adt_…}, {@code abap_lint}, {@code ask_user}); MCP tools get {@code mcp_<server>_}. The result is
	 * cut to the API's 64 character limit.
	 */
	static String exposedName(ToolProvider provider, String remoteName) {
		String name;
		if (provider.id().equals(ADT_PROVIDER_ID) || provider.id().equals(LINT_PROVIDER_ID)
				|| provider.id().equals(DEBUG_PROVIDER_ID) || provider.id().equals(ASK_PROVIDER_ID)) {
			name = remoteName;
		} else {
			String server = provider.id().startsWith("mcp:") ? provider.id().substring(4) : provider.id();
			name = "mcp_" + sanitize(server) + "_" + sanitize(remoteName);
		}
		return name.length() > 64 ? name.substring(0, 64) : name;
	}

	static String sanitize(String s) {
		return s.replaceAll("[^a-zA-Z0-9_-]", "_");
	}
}

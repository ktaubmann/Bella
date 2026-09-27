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

	private final List<ToolProvider> providers = new CopyOnWriteArrayList<>();
	private volatile String preferredProviderId = ADT_PROVIDER_ID;
	private volatile Map<String, ToolSpec> tools = Map.of();
	private volatile Map<String, ToolProvider> providerByTool = Map.of();

	public void addProvider(ToolProvider provider) {
		providers.removeIf(p -> p.id().equals(provider.id()));
		providers.add(provider);
	}

	public void removeProvider(String id) {
		providers.removeIf(p -> p.id().equals(id));
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
	 * on) are skipped and reported to {@code onError}.
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
			} catch (Exception e) {
				onError.accept(provider.displayName() + ": " + e.getMessage());
				continue;
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
		this.tools = Map.copyOf(merged);
		this.providerByTool = Map.copyOf(owners);
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
	 * Builds the name the model sees. Bella's own tools already carry the
	 * {@code adt_} prefix; MCP tools get {@code mcp_<server>_}. The result is
	 * cut to the API's 64 character limit.
	 */
	static String exposedName(ToolProvider provider, String remoteName) {
		String name;
		if (provider.id().equals(ADT_PROVIDER_ID)) {
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

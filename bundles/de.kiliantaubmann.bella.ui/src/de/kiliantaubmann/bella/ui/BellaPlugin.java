package de.kiliantaubmann.bella.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtToolProvider;
import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.OpenAiCompatibleProvider;
import de.kiliantaubmann.bella.core.mcp.McpClient;
import de.kiliantaubmann.bella.core.mcp.McpToolProvider;
import de.kiliantaubmann.bella.core.mcp.StdioTransport;
import de.kiliantaubmann.bella.core.mcp.StreamableHttpTransport;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.ui.prefs.McpServerConfig;
import de.kiliantaubmann.bella.ui.prefs.Prefs;
import de.kiliantaubmann.bella.ui.prefs.SecureStore;

/** Activator and central access point of the Bella UI bundle. */
public class BellaPlugin extends AbstractUIPlugin {

	public static final String ID = "de.kiliantaubmann.bella.ui";
	public static final String VERSION = "0.1.0";

	private static BellaPlugin plugin;

	private final HttpTransport http = HttpTransport.jdk();
	private final ToolRegistry tools = new ToolRegistry();
	private final List<McpToolProvider> mcpProviders = new ArrayList<>();
	private ServiceTracker<AdtBackend, AdtBackend> adtTracker;
	private volatile String activeDestination;

	public static BellaPlugin getDefault() {
		return plugin;
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		adtTracker = new ServiceTracker<>(context, AdtBackend.class, null) {
			@Override
			public AdtBackend addingService(org.osgi.framework.ServiceReference<AdtBackend> reference) {
				AdtBackend backend = super.addingService(reference);
				tools.addProvider(new AdtToolProvider(backend, () -> activeDestination));
				return backend;
			}

			@Override
			public void removedService(org.osgi.framework.ServiceReference<AdtBackend> reference, AdtBackend service) {
				tools.removeProvider(ToolRegistry.ADT_PROVIDER_ID);
				super.removedService(reference, service);
			}
		};
		adtTracker.open();
		getPreferenceStore().addPropertyChangeListener(e -> {
			if (Prefs.MCP_SERVERS.equals(e.getProperty())) {
				reconnectMcpServers();
			} else if (Prefs.UI_LANGUAGE.equals(e.getProperty())) {
				Messages.reload();
			}
		});
		reconnectMcpServers();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		closeMcpServers();
		if (adtTracker != null) {
			adtTracker.close();
		}
		plugin = null;
		super.stop(context);
	}

	// ---- images ----------------------------------------------------------------

	public static final String IMG_BELLA = "bella";

	private static final String[] IMAGES = { IMG_BELLA, "explain", "generate", "rewrite", "method", "insert",
			"replace", "copy", "send", "stop", "new_chat", "tool", "activate", "refactor", "test", "settings" };

	@Override
	protected void initializeImageRegistry(ImageRegistry reg) {
		for (String key : IMAGES) {
			reg.put(key, descriptor(key));
		}
	}

	public static ImageDescriptor descriptor(String key) {
		return ResourceLocator.imageDescriptorFromBundle(ID, "icons/" + key + ".png").orElse(null);
	}

	public static Image image(String key) {
		return getDefault().getImageRegistry().get(key);
	}

	// ---- logging -----------------------------------------------------------------

	public static void log(String message, Throwable t) {
		ILog.of(BellaPlugin.class).log(new Status(IStatus.ERROR, ID, message, t));
	}

	// ---- configuration -----------------------------------------------------------

	public IPreferenceStore prefs() {
		return getPreferenceStore();
	}

	public boolean usesAnthropic() {
		return !OpenAiCompatibleProvider.ID.equals(prefs().getString(Prefs.PROVIDER));
	}

	public LlmProvider provider() {
		IPreferenceStore s = prefs();
		if (usesAnthropic()) {
			return new AnthropicProvider(() -> SecureStore.get(SecureStore.ANTHROPIC_KEY),
					s.getString(Prefs.ANTHROPIC_BASE_URL), s.getBoolean(Prefs.REFUSAL_FALLBACK), http);
		}
		return new OpenAiCompatibleProvider(() -> SecureStore.get(SecureStore.OPENAI_KEY),
				s.getString(Prefs.OPENAI_BASE_URL), http);
	}

	public String chatModel() {
		return prefs().getString(usesAnthropic() ? Prefs.CHAT_MODEL : Prefs.OPENAI_CHAT_MODEL).trim();
	}

	public String completionModel() {
		String m = prefs().getString(usesAnthropic() ? Prefs.COMPLETION_MODEL : Prefs.OPENAI_COMPLETION_MODEL).trim();
		return m.isEmpty() ? chatModel() : m;
	}

	public ChatSession.Settings chatSettings() {
		String effort = prefs().getString(Prefs.EFFORT);
		return new ChatSession.Settings(chatModel(), Math.max(1024, prefs().getInt(Prefs.MAX_TOKENS)),
				effort == null || effort.isBlank() ? null : effort);
	}

	public AbapPrompts prompts() {
		String answer = prefs().getString(Prefs.ANSWER_LANGUAGE);
		String answerName = switch (answer == null ? "" : answer) {
		case "question" -> null;
		case "", "ui" -> Languages.englishName(Languages.uiTag());
		default -> Languages.englishName(answer);
		};
		return new AbapPrompts(answerName, Languages.englishName(prefs().getString(Prefs.COMMENT_LANGUAGE)));
	}

	public ToolPolicy policy() {
		return new ToolPolicy(ToolPolicy.parseRules(prefs().getString(Prefs.POLICY_RULES)));
	}

	public ToolRegistry tools() {
		tools.setPreferredProvider(prefs().getString(Prefs.PREFERRED_TOOLS));
		return tools;
	}

	public AdtBackend adt() {
		return adtTracker == null ? null : adtTracker.getService();
	}

	/** Destination of the ABAP object in the active editor; default system for tools. */
	public void setActiveDestination(String destinationId) {
		this.activeDestination = destinationId;
	}

	// ---- MCP servers ---------------------------------------------------------------

	public synchronized void reconnectMcpServers() {
		closeMcpServers();
		for (McpServerConfig cfg : McpServerConfig.parse(prefs().getString(Prefs.MCP_SERVERS))) {
			if (!cfg.enabled() || cfg.id() == null || cfg.id().isBlank()) {
				continue;
			}
			try {
				McpToolProvider p = createMcpProvider(cfg);
				mcpProviders.add(p);
				tools.addProvider(p);
			} catch (Exception e) {
				log("Cannot start MCP server " + cfg.name(), e);
			}
		}
	}

	public McpToolProvider createMcpProvider(McpServerConfig cfg) throws java.io.IOException {
		String token = SecureStore.get(SecureStore.mcpTokenKey(cfg.id()));
		McpClient client;
		if (cfg.http()) {
			client = new McpClient(new StreamableHttpTransport(URI.create(cfg.url().trim()), token, http));
		} else {
			// A stdio server brings its own SAP connection settings (environment or config file).
			client = new McpClient(new StdioTransport(StdioTransport.splitCommand(cfg.command()), Map.of(), 120));
		}
		return new McpToolProvider(cfg.id(), cfg.name(), client, VERSION);
	}

	private synchronized void closeMcpServers() {
		for (McpToolProvider p : mcpProviders) {
			tools.removeProvider(p.id());
			p.close();
		}
		mcpProviders.clear();
	}
}

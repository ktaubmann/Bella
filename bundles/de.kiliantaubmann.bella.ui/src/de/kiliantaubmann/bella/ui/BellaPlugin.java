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
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCli;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeProvider;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeSession;
import de.kiliantaubmann.bella.core.claudecode.ProcessLauncher;
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.OpenAiCompatibleProvider;
import de.kiliantaubmann.bella.core.mcp.McpClient;
import de.kiliantaubmann.bella.core.mcp.McpToolProvider;
import de.kiliantaubmann.bella.core.mcp.StdioTransport;
import de.kiliantaubmann.bella.core.mcp.StreamableHttpTransport;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.WriteGuard;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.ui.prefs.McpServerConfig;
import de.kiliantaubmann.bella.ui.prefs.Prefs;
import de.kiliantaubmann.bella.ui.prefs.SecureStore;

/** Activator and central access point of the Bella UI bundle. */
public class BellaPlugin extends AbstractUIPlugin {

	public static final String ID = "de.kiliantaubmann.bella.ui";
	public static final String VERSION = "0.2.0";

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

	/** {@code anthropic}, {@code claude-code} or {@code openai}. */
	public String providerId() {
		String id = prefs().getString(Prefs.PROVIDER);
		return ClaudeCodeProvider.ID.equals(id) || OpenAiCompatibleProvider.ID.equals(id) ? id : AnthropicProvider.ID;
	}

	/** Claude through the developer's subscription (Claude Code CLI) instead of an API key. */
	public boolean usesClaudeCode() {
		return ClaudeCodeProvider.ID.equals(providerId());
	}

	public boolean usesAnthropic() {
		return AnthropicProvider.ID.equals(providerId());
	}

	public LlmProvider provider() {
		IPreferenceStore s = prefs();
		if (usesClaudeCode()) {
			return new ClaudeCodeProvider(claudeCli());
		}
		if (usesAnthropic()) {
			return new AnthropicProvider(() -> SecureStore.get(SecureStore.ANTHROPIC_KEY),
					s.getString(Prefs.ANTHROPIC_BASE_URL), s.getBoolean(Prefs.REFUSAL_FALLBACK), http);
		}
		return new OpenAiCompatibleProvider(() -> SecureStore.get(SecureStore.OPENAI_KEY),
				s.getString(Prefs.OPENAI_BASE_URL), http);
	}

	/** The Claude Code CLI as configured; {@code executable} and {@code token} override the stored values. */
	public ClaudeCli claudeCli(String executable, String token) {
		java.nio.file.Path workDir = getStateLocation().append("claude-code").toPath();
		return new ClaudeCli(new ClaudeCli.Config(executable, token), ProcessLauncher.SYSTEM, workDir);
	}

	public ClaudeCli claudeCli() {
		return claudeCli(prefs().getString(Prefs.CC_EXECUTABLE), SecureStore.get(SecureStore.CLAUDE_CODE_TOKEN));
	}

	/**
	 * A new chat for the configured provider. With the subscription the CLI
	 * runs the tool loop and calls Bella's tools through a private MCP server;
	 * confirmation and the open-editor router apply either way.
	 */
	public Conversation newConversation(ToolExecutor.Confirmer confirmer, WriteGuard writeGuard) {
		if (usesClaudeCode()) {
			return new ClaudeCodeSession(claudeCli(), this::chatSettings, prompts().chatSystem(),
					new ToolExecutor(tools(), this::policy, confirmer, writeGuard), VERSION);
		}
		return new ChatSession(this::provider, this::chatSettings, prompts().chatSystem(), tools(), this::policy,
				confirmer::confirm, writeGuard);
	}

	public String chatModel() {
		String key = usesClaudeCode() ? Prefs.CC_CHAT_MODEL : usesAnthropic() ? Prefs.CHAT_MODEL : Prefs.OPENAI_CHAT_MODEL;
		return prefs().getString(key).trim();
	}

	/** Model for the status line, e.g. "opus (subscription)". */
	public String chatModelLabel() {
		return usesClaudeCode() ? Messages.fmt("chat.status.subscription", chatModel()) : chatModel();
	}

	public String completionModel() {
		String key = usesClaudeCode() ? Prefs.CC_COMPLETION_MODEL
				: usesAnthropic() ? Prefs.COMPLETION_MODEL : Prefs.OPENAI_COMPLETION_MODEL;
		String m = prefs().getString(key).trim();
		return m.isEmpty() ? chatModel() : m;
	}

	/**
	 * Suggestions while typing. Off with the subscription: every suggestion
	 * starts the CLI, which takes seconds; the shortcut still works.
	 */
	public boolean autoCompletion() {
		return prefs().getBoolean(Prefs.AUTO_COMPLETION) && !usesClaudeCode();
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

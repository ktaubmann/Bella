package de.kiliantaubmann.bella.ui;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.core.adt.AdtToolProvider;
import de.kiliantaubmann.bella.core.adt.DevScope;
import de.kiliantaubmann.bella.core.debug.DebugToolProvider;
import de.kiliantaubmann.bella.core.lint.LintToolProvider;
import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.agent.LoggingConversation;
import de.kiliantaubmann.bella.core.mask.Masker;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCli;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeProvider;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeSession;
import de.kiliantaubmann.bella.core.copilot.CopilotCli;
import de.kiliantaubmann.bella.core.copilot.CopilotProvider;
import de.kiliantaubmann.bella.core.copilot.CopilotSession;
import de.kiliantaubmann.bella.core.util.ProcessLauncher;
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.llm.LlmProvider;
import de.kiliantaubmann.bella.core.llm.LoggingProvider;
import de.kiliantaubmann.bella.core.llm.OpenAiCompatibleProvider;
import de.kiliantaubmann.bella.core.mcp.McpClient;
import de.kiliantaubmann.bella.core.mcp.McpToolProvider;
import de.kiliantaubmann.bella.core.mcp.StdioTransport;
import de.kiliantaubmann.bella.core.mcp.StreamableHttpTransport;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.conventions.ProjectConventions;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.WriteGuard;
import de.kiliantaubmann.bella.core.util.HttpTransport;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.internal.LogFile;
import de.kiliantaubmann.bella.ui.debug.EclipseDebugBackend;
import de.kiliantaubmann.bella.ui.prefs.McpServerConfig;
import de.kiliantaubmann.bella.ui.prefs.Prefs;
import de.kiliantaubmann.bella.ui.prefs.SecureStore;

/** Activator and central access point of the Bella UI bundle. */
public class BellaPlugin extends AbstractUIPlugin {

	public static final String ID = "de.kiliantaubmann.bella.ui";
	public static final String VERSION = "0.8.0";

	private static BellaPlugin plugin;

	private final HttpTransport http = HttpTransport.jdk();
	private final ToolRegistry tools = new ToolRegistry();
	private final List<McpToolProvider> mcpProviders = new ArrayList<>();
	private ServiceTracker<AdtBackend, AdtBackend> adtTracker;
	private volatile String activeDestination;
	private final DevScope devScope = new DevScope();
	/** Bella's SAP tools while the ADT integration is available. */
	private volatile AdtToolProvider adtTools;
	private LogFile logFile;
	private final Masker masker = new Masker(this::maskSettings);
	private volatile List<AdtSystem> maskSystems = List.of();
	private volatile long maskSystemsAt;

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
				Log.info("bella", "ADT integration available");
				adtTools = new AdtToolProvider(backend, () -> activeDestination,
						() -> prefs().getString(Prefs.WRITE_PACKAGES), BellaPlugin.this::atcVariant,
						() -> conventions(activeDestination).naming(), devScope);
				tools.addProvider(adtTools);
				return backend;
			}

			@Override
			public void removedService(org.osgi.framework.ServiceReference<AdtBackend> reference, AdtBackend service) {
				tools.removeProvider(ToolRegistry.ADT_PROVIDER_ID);
				adtTools = null;
				Log.info("bella", "ADT integration removed");
				super.removedService(reference, service);
			}
		};
		adtTracker.open();
		tools.addProvider(new LintToolProvider(() -> conventions(activeDestination).naming()));
		tools.addProvider(new DebugToolProvider(new EclipseDebugBackend()));
		Log.mask(masker::mask);
		configureLog();
		getPreferenceStore().addPropertyChangeListener(e -> {
			if (Prefs.LOG_ENABLED.equals(e.getProperty()) || Prefs.LOG_DETAIL.equals(e.getProperty())) {
				configureLog();
			} else if (Prefs.MCP_SERVERS.equals(e.getProperty())) {
				reconnectMcpServers();
			} else if (Prefs.UI_LANGUAGE.equals(e.getProperty())) {
				Messages.reload();
				refreshLabels();
			}
		});
		reconnectMcpServers();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		Log.info("bella", "Bella stops");
		closeMcpServers();
		if (adtTracker != null) {
			adtTracker.close();
		}
		Log.configure(null, Log.Level.INFO);
		Log.mask(null);
		synchronized (this) {
			if (logFile != null) {
				logFile.close();
			}
		}
		plugin = null;
		super.stop(context);
	}

	/** Menus are rebuilt when opened; the toolbar tooltip and the chat tab title need a nudge. */
	private void refreshLabels() {
		if (!org.eclipse.ui.PlatformUI.isWorkbenchRunning()) {
			return;
		}
		org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
			org.eclipse.ui.commands.ICommandService commands = org.eclipse.ui.PlatformUI.getWorkbench()
					.getService(org.eclipse.ui.commands.ICommandService.class);
			if (commands != null) {
				commands.refreshElements(de.kiliantaubmann.bella.ui.handlers.OpenChatHandler.COMMAND, null);
			}
			de.kiliantaubmann.bella.ui.views.ChatView.find().ifPresent(v -> v.updateTitle());
		});
	}

	// ---- images ----------------------------------------------------------------

	public static final String IMG_BELLA = "bella";

	private static final String[] IMAGES = { IMG_BELLA, "explain", "generate", "rewrite", "method", "insert",
			"replace", "copy", "send", "stop", "new_chat", "tool", "activate", "refactor", "test", "settings", "plan" };

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
		Log.error("ui", message, t);
	}

	/** Bella's log file, whether or not logging is on. */
	public synchronized LogFile logFile() {
		if (logFile == null) {
			logFile = new LogFile(getStateLocation().append("bella.log").toFile().toPath());
		}
		return logFile;
	}

	/** Switches the log on or off as the preferences say; writes a header when it starts. */
	public synchronized void configureLog() {
		boolean on = prefs().getBoolean(Prefs.LOG_ENABLED);
		boolean detail = prefs().getBoolean(Prefs.LOG_DETAIL);
		boolean wasOn = Log.enabled(Log.Level.ERROR);
		if (!on) {
			if (wasOn) {
				Log.info("bella", "logging switched off");
			}
			Log.configure(null, Log.Level.INFO);
			if (logFile != null) {
				logFile.close();
			}
			return;
		}
		Log.configure(logFile(), detail ? Log.Level.DEBUG : Log.Level.INFO);
		if (!wasOn) {
			Log.info("bella", header());
		} else {
			Log.info("bella", "detail level " + (detail ? "on" : "off"));
		}
	}

	/** Empties the log file; while logging is on, it starts again with the header. */
	public synchronized void clearLog() {
		logFile().clear();
		if (Log.enabled(Log.Level.ERROR)) {
			Log.info("bella", header());
		}
	}

	/** Versions and settings that help to understand a problem; no secrets. */
	String header() {
		StringBuilder sb = new StringBuilder("===== Bella ").append(VERSION).append(" log started");
		org.osgi.framework.Bundle platform = org.eclipse.core.runtime.Platform.getBundle("org.eclipse.platform");
		org.osgi.framework.Bundle adtCore = org.eclipse.core.runtime.Platform.getBundle("com.sap.adt.tools.core");
		sb.append("\nEclipse: ").append(platform == null ? "?" : platform.getVersion())
				.append(System.getProperty("eclipse.buildId") == null ? "" : " (build " + System.getProperty("eclipse.buildId") + ")")
				.append("\nJava: ").append(System.getProperty("java.version")).append(' ')
				.append(System.getProperty("java.vendor"))
				.append("\nOS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
				.append(' ').append(System.getProperty("os.arch"))
				.append("\nLanguage: ").append(Languages.uiTag()).append(", answers ")
				.append(prefs().getString(Prefs.ANSWER_LANGUAGE))
				.append("\nProvider: ").append(providerId()).append(", chat model ").append(chatModelLabel())
				.append("\nADT: ").append(adt() == null ? "not available" : "available")
				.append(adtCore == null ? "" : " (com.sap.adt.tools.core " + adtCore.getVersion() + ")")
				.append("\nPreferred tools: ").append(prefs().getString(Prefs.PREFERRED_TOOLS))
				.append(", SAP definitions for editor actions: ").append(prefs().getBoolean(Prefs.EDITOR_SAP_CONTEXT))
				.append("\nLog masking: ").append(masker.active() ? "on" : "off")
				.append("\nMCP servers:");
		List<McpServerConfig> servers = McpServerConfig.parse(prefs().getString(Prefs.MCP_SERVERS));
		if (servers.isEmpty()) {
			sb.append(" none");
		}
		for (McpServerConfig cfg : servers) {
			sb.append("\n  - ").append(cfg.name()).append(" (").append(cfg.http() ? "HTTP" : "stdio")
					.append(cfg.enabled() ? ", enabled" : ", disabled").append(')');
		}
		sb.append("\nDetail level: ").append(prefs().getBoolean(Prefs.LOG_DETAIL) ? "on" : "off");
		return sb.toString();
	}

	// ---- configuration -----------------------------------------------------------

	public IPreferenceStore prefs() {
		return getPreferenceStore();
	}

	/** {@code anthropic}, {@code claude-code} or {@code openai}. */
	public String providerId() {
		String id = prefs().getString(Prefs.PROVIDER);
		return ClaudeCodeProvider.ID.equals(id) || CopilotProvider.ID.equals(id) || OpenAiCompatibleProvider.ID.equals(id)
				? id
				: AnthropicProvider.ID;
	}

	/** Models from the developer's GitHub Copilot subscription through the Copilot CLI. */
	public boolean usesCopilot() {
		return CopilotProvider.ID.equals(providerId());
	}

	/** Provider that runs through a local CLI (seconds per request, no suggestions while typing). */
	public boolean usesCli() {
		return usesClaudeCode() || usesCopilot();
	}

	/** Claude through the developer's subscription (Claude Code CLI) instead of an API key. */
	public boolean usesClaudeCode() {
		return ClaudeCodeProvider.ID.equals(providerId());
	}

	public boolean usesAnthropic() {
		return AnthropicProvider.ID.equals(providerId());
	}

	/** The configured model provider, writing its requests to Bella's log. */
	public LlmProvider provider() {
		return LoggingProvider.wrap(plainProvider());
	}

	/** Replaces confidential data in Bella's log with placeholders; the model gets the real data. */
	public Masker masker() {
		return masker;
	}

	/** Masking as set in the preferences, with the system data of the ABAP projects in the workspace. */
	private Masker.Settings maskSettings() {
		IPreferenceStore s = prefs();
		if (!s.getBoolean(Prefs.MASK_ENABLED)) {
			return Masker.Settings.OFF;
		}
		List<String> system = new ArrayList<>();
		List<String> users = new ArrayList<>();
		if (s.getBoolean(Prefs.MASK_SYSTEM)) {
			for (AdtSystem sys : maskSystems()) {
				system.add(sys.projectName());
				system.add(sys.destinationId());
				if (sys.systemId() != null) {
					system.add(sys.systemId());
				}
				if (sys.user() != null) {
					users.add(sys.user());
				}
			}
		}
		return new Masker.Settings(true, s.getBoolean(Prefs.MASK_OBJECTS), s.getBoolean(Prefs.MASK_PERSONAL),
				Masker.Settings.parseTerms(s.getString(Prefs.MASK_TERMS)), system, users);
	}

	/**
	 * The ABAP projects, read at most every ten seconds: masking runs on every
	 * log entry. Never logs itself, since it runs while an entry is written.
	 */
	private List<AdtSystem> maskSystems() {
		AdtBackend backend = adt();
		long now = System.currentTimeMillis();
		if (backend == null || now - maskSystemsAt <= 10_000) {
			return maskSystems;
		}
		maskSystemsAt = now;
		try {
			maskSystems = List.copyOf(backend.systems());
		} catch (RuntimeException e) {
			// keep the last list
		}
		return maskSystems;
	}

	private LlmProvider plainProvider() {
		IPreferenceStore s = prefs();
		if (usesClaudeCode()) {
			return new ClaudeCodeProvider(claudeCli());
		}
		if (usesCopilot()) {
			return new CopilotProvider(copilotCli());
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

	/** The Copilot CLI as configured; {@code executable} and {@code token} override the stored values. */
	public CopilotCli copilotCli(String executable, String token) {
		java.nio.file.Path workDir = getStateLocation().append("copilot").toPath();
		return new CopilotCli(new CopilotCli.Config(executable, token), ProcessLauncher.SYSTEM, workDir);
	}

	public CopilotCli copilotCli() {
		return copilotCli(prefs().getString(Prefs.CP_EXECUTABLE), SecureStore.get(SecureStore.COPILOT_TOKEN));
	}

	/**
	 * A new chat for the configured provider. With the subscription the CLI
	 * runs the tool loop and calls Bella's tools through a private MCP server;
	 * confirmation and the open-editor router apply either way.
	 */
	public Conversation newConversation(ToolExecutor.Confirmer confirmer, WriteGuard writeGuard) {
		return newConversation(confirmer, writeGuard, () -> ChatMode.NORMAL);
	}

	/**
	 * @param mode the chat's mode at the time of each tool call (plan mode,
	 *             suggest, ask, read data, activate, Automode)
	 */
	public Conversation newConversation(ToolExecutor.Confirmer confirmer, WriteGuard writeGuard,
			Supplier<ChatMode> mode) {
		return LoggingConversation.wrap(plainConversation(confirmer, writeGuard, () -> policy().withMode(mode.get())));
	}

	private Conversation plainConversation(ToolExecutor.Confirmer confirmer, WriteGuard writeGuard,
			Supplier<ToolPolicy> policy) {
		if (usesClaudeCode()) {
			return new ClaudeCodeSession(claudeCli(), this::chatSettings, prompts().chatSystem(),
					new ToolExecutor(tools(), policy, confirmer, writeGuard), VERSION);
		}
		if (usesCopilot()) {
			return new CopilotSession(copilotCli(), this::chatSettings, prompts().chatSystem(),
					new ToolExecutor(tools(), policy, confirmer, writeGuard), VERSION);
		}
		return new ChatSession(this::provider, this::chatSettings, prompts().chatSystem(), tools(), policy,
				confirmer::confirm, writeGuard);
	}

	public String chatModel() {
		String key = usesClaudeCode() ? Prefs.CC_CHAT_MODEL
				: usesCopilot() ? Prefs.CP_CHAT_MODEL : usesAnthropic() ? Prefs.CHAT_MODEL : Prefs.OPENAI_CHAT_MODEL;
		return prefs().getString(key).trim();
	}

	/** Conversation class the current provider needs; a chat of another type has to start over. */
	public Class<? extends Conversation> conversationType() {
		return usesClaudeCode() ? ClaudeCodeSession.class : usesCopilot() ? CopilotSession.class : ChatSession.class;
	}

	/** Model for the status line, e.g. "opus (subscription)". */
	public String chatModelLabel() {
		if (usesCopilot()) {
			String m = chatModel();
			return Messages.fmt("chat.status.copilot", m.isEmpty() ? Messages.get("chat.status.copilotDefault") : m);
		}
		return usesClaudeCode() ? Messages.fmt("chat.status.subscription", chatModel()) : chatModel();
	}

	public String completionModel() {
		String key = usesClaudeCode() ? Prefs.CC_COMPLETION_MODEL
				: usesCopilot() ? Prefs.CP_COMPLETION_MODEL
						: usesAnthropic() ? Prefs.COMPLETION_MODEL : Prefs.OPENAI_COMPLETION_MODEL;
		String m = prefs().getString(key).trim();
		return m.isEmpty() ? chatModel() : m;
	}

	/**
	 * Suggestions while typing. Off with the Claude subscription and GitHub
	 * Copilot: every suggestion starts a CLI, which takes seconds (and costs a
	 * premium request with Copilot); the shortcut still works.
	 */
	public boolean autoCompletion() {
		return prefs().getBoolean(Prefs.AUTO_COMPLETION) && !usesCli();
	}

	public ChatSession.Settings chatSettings() {
		String effort = prefs().getString(Prefs.EFFORT);
		return new ChatSession.Settings(chatModel(), Math.max(1024, prefs().getInt(Prefs.MAX_TOKENS)),
				effort == null || effort.isBlank() ? null : effort);
	}

	/**
	 * The conventions for a system: those for all systems joined with the
	 * system's own (its naming rules win per kind).
	 *
	 * @param destinationId ADT destination, or {@code null} for the general ones only
	 */
	public ProjectConventions conventions(String destinationId) {
		ProjectConventions general = ProjectConventions.of(
				prefs().getString(Prefs.CONVENTIONS_TEXT + Prefs.CONVENTIONS_GLOBAL),
				prefs().getString(Prefs.CONVENTIONS_NAMING + Prefs.CONVENTIONS_GLOBAL));
		if (destinationId == null || destinationId.isBlank()) {
			return general;
		}
		return ProjectConventions.merge(general, ProjectConventions.of(
				prefs().getString(Prefs.CONVENTIONS_TEXT + destinationId),
				prefs().getString(Prefs.CONVENTIONS_NAMING + destinationId)));
	}

	/** The conventions for the system of the active editor. */
	public ProjectConventions activeConventions() {
		return conventions(activeDestination);
	}

	/**
	 * The ATC check variant for a system: its own, else the one for all
	 * systems, else empty for the system default.
	 */
	public String atcVariant(String destinationId) {
		String own = destinationId == null || destinationId.isBlank() ? ""
				: prefs().getString(Prefs.ATC_VARIANT + destinationId).trim();
		return own.isEmpty() ? prefs().getString(Prefs.ATC_VARIANT + Prefs.CONVENTIONS_GLOBAL).trim() : own;
	}

	public AbapPrompts prompts() {
		String answer = prefs().getString(Prefs.ANSWER_LANGUAGE);
		String answerName = switch (answer == null ? "" : answer) {
		case "question" -> null;
		case "", "ui" -> Languages.englishName(Languages.uiTag());
		default -> Languages.englishName(answer);
		};
		return new AbapPrompts(answerName, Languages.englishName(prefs().getString(Prefs.COMMENT_LANGUAGE)),
				conventions(activeDestination));
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

	/** Development package and transport request of the chat, which the SAP tools keep to. */
	public DevScope devScope() {
		return devScope;
	}

	/** Destination of the ABAP object in the active editor; {@code null} before one was active. */
	public String activeDestination() {
		return activeDestination;
	}

	/** Bella's SAP tools, empty without the ADT integration. */
	public Optional<AdtToolProvider> adtTools() {
		return Optional.ofNullable(adtTools);
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

package de.kiliantaubmann.bella.ui.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.mcp.McpServer;
import de.kiliantaubmann.bella.core.mcp.McpSetupCommands;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.OpenEditorRouter;
import de.kiliantaubmann.bella.ui.prefs.Prefs;
import de.kiliantaubmann.bella.ui.prefs.SecureStore;
import de.kiliantaubmann.bella.ui.views.ToolConfirmDialog;

/**
 * Bella's MCP server opened for other programs on the developer's machine
 * (Claude Code in a terminal, the Copilot CLI, VS Code …): loopback only, a
 * fixed port and a token kept in secure storage. It offers Bella's own tools
 * (SAP, style check, debugger), runs them in the mode set for it, with the
 * chat's development package and request, and asks in Eclipse like the chat.
 */
public final class ExternalMcp {

	/** Providers whose tools are offered: not ask_user (the chat's) and no MCP servers Bella itself uses. */
	static final Set<String> PROVIDERS = Set.of(ToolRegistry.ADT_PROVIDER_ID, ToolRegistry.LINT_PROVIDER_ID,
			ToolRegistry.DEBUG_PROVIDER_ID);
	/** Modes another program may use; none writes without asking. */
	public static final Set<ChatMode> MODES = Set.of(ChatMode.PLAN, ChatMode.SUGGEST, ChatMode.NORMAL,
			ChatMode.READ_DATA);

	/** Where the token is kept: Eclipse secure storage, in tests memory. */
	public interface TokenStore {
		String get();

		void put(String token);

		/** Eclipse secure storage (encrypted, per user). */
		TokenStore SECURE = new TokenStore() {
			@Override
			public String get() {
				return SecureStore.get(SecureStore.MCP_EXTERNAL_TOKEN);
			}

			@Override
			public void put(String token) {
				SecureStore.put(SecureStore.MCP_EXTERNAL_TOKEN, token);
			}
		};
	}

	private final IPreferenceStore prefs;
	private final TokenStore tokens;
	private final ToolRegistry tools;
	private final Supplier<ToolPolicy> policy;
	private final Path stateDir;
	private final String version;
	private McpServer server;
	private int serverPort;
	private String serverToken;
	private volatile String problem;

	public ExternalMcp(IPreferenceStore prefs, TokenStore tokens, ToolRegistry tools, Supplier<ToolPolicy> policy,
			Path stateDir, String version) {
		this.prefs = prefs;
		this.tokens = tokens;
		this.tools = tools;
		this.policy = policy;
		this.stateDir = stateDir;
		this.version = version;
	}

	/** Starts, restarts or stops the server as the preferences say. */
	public synchronized void apply() {
		if (!prefs.getBoolean(Prefs.MCP_EXTERNAL_ENABLED)) {
			stop();
			problem = null;
			return;
		}
		int port = port();
		String token = token();
		if (server != null && server.isRunning() && port == serverPort && token.equals(serverToken)) {
			return;
		}
		stop();
		McpServer s = new McpServer(new ToolExecutor(tools, () -> policy.get().withMode(mode()), new Confirmer(),
				new OpenEditorRouter()), version, token, port, t -> PROVIDERS.contains(t.providerId()),
				"Tools of the Bella Eclipse plug-in. adt_* tools act on the SAP system through the developer's ADT "
						+ "logon in Eclipse. Changes are confirmed by the developer in Eclipse and stay in the "
						+ "development package and transport request of Bella's chat (adt_dev_package).");
		s.refreshToolsWith(() -> tools.refresh(e -> Log.warn("mcp", "Tool list for another program: " + e)));
		try {
			s.start();
			server = s;
			serverPort = port;
			serverToken = token;
			problem = null;
			Log.info("mcp", "Bella's MCP server for other programs listens on " + url(port));
		} catch (IOException e) {
			s.close();
			problem = Messages.fmt("mcpx.status.failed", port, e.getMessage() == null ? e.toString() : e.getMessage());
			Log.warn("mcp", "Bella's MCP server for other programs could not start on port " + port + ": " + e);
		}
	}

	public synchronized void close() {
		stop();
	}

	private void stop() {
		if (server != null) {
			server.close();
			server = null;
			Log.info("mcp", "Bella's MCP server for other programs stopped");
		}
	}

	public synchronized boolean running() {
		return server != null && server.isRunning();
	}

	/** Why the server is not running although switched on; {@code null} otherwise. */
	public String problem() {
		return problem;
	}

	public int port() {
		return prefs.getInt(Prefs.MCP_EXTERNAL_PORT);
	}

	public static String url(int port) {
		return "http://127.0.0.1:" + port + McpServer.PATH;
	}

	/** The mode tool calls run in; one not allowed for other programs falls back to asking before changes. */
	public ChatMode mode() {
		try {
			ChatMode m = ChatMode.valueOf(prefs.getString(Prefs.MCP_EXTERNAL_MODE));
			return MODES.contains(m) ? m : ChatMode.NORMAL;
		} catch (IllegalArgumentException e) {
			return ChatMode.NORMAL;
		}
	}

	/** The token, created on first use. */
	public synchronized String token() {
		String t = tokens.get();
		if (t == null || t.isBlank()) {
			t = McpServer.newToken();
			tokens.put(t);
		}
		return t;
	}

	/** Replaces the token; programs set up with the old one are refused from now on. */
	public synchronized void newToken() {
		tokens.put(McpServer.newToken());
		apply();
	}

	/** Writes the file the Copilot command names (only the developer may read it) and returns it. */
	public Path writeCopilotFile(int port) throws IOException {
		Path file = stateDir.resolve("mcp").resolve(McpSetupCommands.SERVER_NAME + "-copilot.json");
		writePrivate(file, McpSetupCommands.copilotFile(url(port), token()));
		return file;
	}

	/** Adds or replaces the server in the developer's Copilot configuration and returns the file. */
	public Path addToCopilotConfig(int port) throws IOException {
		Path file = McpSetupCommands.copilotHome(System.getenv()).resolve("mcp-config.json");
		String existing = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		String updated;
		try {
			updated = McpSetupCommands.withCopilotServer(existing, url(port), token());
		} catch (IllegalArgumentException e) {
			throw new IOException(file + ": " + e.getMessage(), e);
		}
		writePrivate(file, updated);
		return file;
	}

	private static void writePrivate(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		if (!Files.exists(file) && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		}
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	/** Asks in Eclipse, naming the program that called. */
	private final class Confirmer implements ToolExecutor.Confirmer {

		@Override
		public boolean confirm(ToolSpec tool, JsonObject input) {
			return confirm(tool, input, CancelToken.NONE);
		}

		@Override
		public boolean confirm(ToolSpec tool, JsonObject input, CancelToken cancel) {
			if (!PlatformUI.isWorkbenchRunning()) {
				return false;
			}
			McpServer s;
			synchronized (ExternalMcp.this) {
				s = server;
			}
			String client = s == null ? null : s.clientName();
			String caller = Objects.requireNonNullElse(client, Messages.get("mcpx.unknownClient"));
			AtomicBoolean ok = new AtomicBoolean();
			PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
				Shell shell = shell();
				if (shell != null && !cancel.isCancelled()) {
					shell.forceActive();
					ok.set(ToolConfirmDialog.ask(shell, tool, input, cancel, caller));
				}
			});
			return ok.get();
		}

		/** The active workbench window, else the first one: the developer may be in a terminal. UI thread. */
		private static Shell shell() {
			IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (w == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
				w = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
			}
			return w == null || w.getShell() == null || w.getShell().isDisposed() ? null : w.getShell();
		}
	}
}

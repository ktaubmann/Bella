package de.kiliantaubmann.bella.ui.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
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
import de.kiliantaubmann.bella.core.tools.ToolProvider;
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

	/**
	 * Runs the token's reads and writes on the thread that may show secure
	 * storage's master password prompt (Eclipse's UI thread; in tests the caller).
	 * Only that thread ever loads or saves the token, so no other thread waits for
	 * it while it waits for the prompt.
	 */
	public interface TokenThread {
		<T> T call(Supplier<T> task);

		/** On the calling thread. */
		TokenThread CALLER = Supplier::get;
	}

	private final IPreferenceStore prefs;
	private final TokenStore tokens;
	private final TokenThread tokenThread;
	private final ToolRegistry tools;
	private final Supplier<ToolPolicy> policy;
	private final Path stateDir;
	private final String version;
	private volatile McpServer server;
	private final Object tokenLock = new Object();
	private int serverPort;
	private String serverToken;
	/** The token in use; kept here as well, so that it stays the same when secure storage fails. */
	private volatile String token;
	/** Whether {@link #token} could not be saved: it then holds only until Eclipse restarts. */
	private volatile boolean tokenNotSaved;
	private volatile String problem;

	public ExternalMcp(IPreferenceStore prefs, TokenStore tokens, TokenThread tokenThread, ToolRegistry tools,
			Supplier<ToolPolicy> policy, Path stateDir, String version) {
		this.prefs = prefs;
		this.tokens = tokens;
		this.tokenThread = tokenThread;
		this.tools = tools;
		this.policy = policy;
		this.stateDir = stateDir;
		this.version = version;
	}

	/**
	 * Starts, restarts or stops the server as the preferences say; never throws.
	 * The token is read before the lock is taken: reading it may wait for secure
	 * storage's password prompt on the UI thread, which may itself be waiting for
	 * this lock.
	 */
	public void apply() {
		try {
			String token = prefs.getBoolean(Prefs.MCP_EXTERNAL_ENABLED) ? token() : null;
			synchronized (this) {
				applyPrefs(token);
			}
		} catch (RuntimeException e) {
			synchronized (this) {
				stop();
			}
			problem = Messages.fmt("mcpx.status.failed", port(), e.getMessage() == null ? e.toString() : e.getMessage());
			Log.warn("mcp", "Bella's MCP server for other programs could not start: " + e);
		}
	}

	private void applyPrefs(String token) {
		if (token == null || !prefs.getBoolean(Prefs.MCP_EXTERNAL_ENABLED)) {
			stop();
			problem = null;
			return;
		}
		int port = port();
		if (server != null && server.isRunning() && port == serverPort && token.equals(serverToken)) {
			return;
		}
		stop();
		// its own registry over the same providers: a refresh for another program never changes the tool list
		// a running chat sends, which must stay the same for the prompt cache
		ToolRegistry own = new ToolRegistry();
		McpServer s = new McpServer(new ToolExecutor(own,
				() -> policy.get().withMode(mode()).confirmingChanges(), new Confirmer(),
				new OpenEditorRouter()), version, token, port, t -> PROVIDERS.contains(t.providerId()),
				"Tools of the Bella Eclipse plug-in. adt_* tools act on the SAP system through the developer's ADT "
						+ "logon in Eclipse. Changes are confirmed by the developer in Eclipse and stay in the "
						+ "development package and transport request of Bella's chat (adt_dev_package).");
		s.refreshToolsWith(() -> refresh(own));
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

	/**
	 * Takes over the offered providers the chat's registry has now (the ADT
	 * provider comes and goes with the ADT bundle) and re-reads their tools:
	 * no requests to the MCP servers Bella itself uses.
	 */
	private void refresh(ToolRegistry own) {
		synchronized (own) {
			List<ToolProvider> offered = tools.providers().stream()
					.filter(p -> PROVIDERS.contains(p.id())).toList();
			for (var p : own.providers()) {
				if (offered.stream().noneMatch(o -> o == p)) {
					own.removeProvider(p.id());
				}
			}
			offered.forEach(own::addProvider);
			own.refresh(e -> Log.warn("mcp", "Tool list for another program: " + e));
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

	/** Lock-free, so the preference page never waits for a start in progress. */
	public boolean running() {
		McpServer s = server;
		return s != null && s.isRunning();
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

	/**
	 * The token, created on first use. Read from the store once; if a new one
	 * cannot be saved there, it is kept in memory, so the server and the copied
	 * commands still agree until Eclipse restarts (see {@link #tokenNotSaved()}).
	 */
	public String token() {
		String t = token;
		return t != null ? t : tokenThread.call(this::loadToken);
	}

	/** Runs only on {@link #tokenThread}; its own lock, never the server's. */
	private String loadToken() {
		synchronized (tokenLock) {
			return loadTokenLocked();
		}
	}

	private String loadTokenLocked() {
		if (token == null) {
			String stored = read();
			if (stored == null || stored.isBlank()) {
				save(McpServer.newToken());
			} else {
				token = stored;
				tokenNotSaved = false;
			}
		}
		return token;
	}

	/** Replaces the token; programs set up with the old one are refused from now on. */
	public void newToken() {
		tokenThread.call(() -> {
			synchronized (tokenLock) {
				save(McpServer.newToken());
			}
			return null;
		});
		apply();
	}

	/** Whether the token could not be saved in secure storage and holds only until Eclipse restarts. */
	public boolean tokenNotSaved() {
		return tokenNotSaved;
	}

	private String read() {
		try {
			return tokens.get();
		} catch (RuntimeException e) {
			Log.warn("mcp", "Cannot read the token of Bella's MCP server for other programs: " + e);
			return null;
		}
	}

	private void save(String t) {
		token = t;
		try {
			tokens.put(t);
			tokenNotSaved = !t.equals(tokens.get());
		} catch (RuntimeException e) {
			Log.warn("mcp", "Cannot save the token of Bella's MCP server for other programs: " + e);
			tokenNotSaved = true;
		}
		if (tokenNotSaved) {
			Log.warn("mcp", "The token of Bella's MCP server for other programs is not in secure storage; it is "
					+ "valid until Eclipse restarts");
		}
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

	/**
	 * Writes a file with the token that only the developer may read: also a
	 * file that exists already, and before the token is in it.
	 */
	static void writePrivate(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		if (!Files.exists(file)) {
			Files.createFile(file);
		}
		restrictToOwner(file);
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	/** POSIX: {@code rw-------}; Windows: one ACL entry for the owner, nothing inherited. */
	private static void restrictToOwner(Path file) throws IOException {
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			return;
		}
		AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
		if (acl == null) {
			return;
		}
		UserPrincipal owner = acl.getOwner();
		acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
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
				throw new ToolExecutor.ConfirmationUnavailable("Eclipse is not running");
			}
			String caller = Objects.requireNonNullElse(McpServer.callerName(), Messages.get("mcpx.unknownClient"));
			AtomicBoolean ok = new AtomicBoolean();
			AtomicBoolean shown = new AtomicBoolean();
			PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
				Shell shell = shell();
				if (shell != null && !cancel.isCancelled()) {
					shown.set(true);
					shell.forceActive();
					ok.set(ToolConfirmDialog.ask(shell, tool, input, cancel, caller));
				}
			});
			if (!shown.get() && !cancel.isCancelled()) {
				throw new ToolExecutor.ConfirmationUnavailable("no Eclipse window to show the dialog in");
			}
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

package de.kiliantaubmann.bella.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.layout.GridData;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.adt.AdtToolProvider;
import de.kiliantaubmann.bella.core.adt.AdtTransportRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Markdown;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.Shortcuts;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.editor.EditorTracker;
import de.kiliantaubmann.bella.ui.editor.OpenEditorRouter;
import de.kiliantaubmann.bella.ui.prefs.Prefs;

/** Bella's chat window. */
public class ChatView extends ViewPart {

	public static final String ID = "de.kiliantaubmann.bella.ui.chatView";

	private Browser browser;
	private Text input;
	private Button send;
	private Button stop;
	private Button withContext;
	private Combo modeChoice;
	/** The modes the drop-down offers; plan mode has its own button. */
	static final List<ChatMode> MODES = java.util.Arrays.stream(ChatMode.values()).filter(m -> m != ChatMode.PLAN)
			.toList();
	private Button planning;
	/** Accept, change or cancel, shown below a plan. */
	private Composite planBar;
	/** Plan mode for the running planning turn, on top of the drop-down's mode. */
	private volatile ChatMode turnOverride;
	/** The next message from the input changes the last plan. */
	private boolean revisingPlan;
	/** The Planning Mode button is pressed: from its click until the plan is accepted or cancelled. */
	private boolean planActive;
	private int lastAnswerId;
	/** Read on the job thread at every tool call, so unticking takes effect at once. */
	private volatile ChatMode mode = ChatMode.NORMAL;
	private Label status;
	private Conversation session;
	private volatile CancelToken running;
	private int nextMessageId = 1;
	/** Tool problems last shown in this chat; the same problem is not repeated on every question. */
	private volatile List<String> shownToolErrors = List.of();
	/** "messageId_segment" → code blocks of that text segment. */
	private final Map<String, List<String>> codeBlocks = new HashMap<>();
	/** Scripts issued before the page finished loading. */
	private final List<String> pendingScripts = new ArrayList<>();
	private boolean pageLoaded;
	/** Development package and transport request above the chat. */
	private Composite scopeBar;
	private Combo packageChoice;
	private Button packageSearch;
	private Combo transportChoice;
	/** Request ids in the order of {@link #transportChoice}; "" for the placeholder. */
	private List<String> transportIds = List.of();
	/** Package and request last shown, {@code null} without SAP or before the first look. */
	private AdtToolProvider.Scope shownScope;
	/** Package and request last announced in the chat, "" while none is set. */
	private String announcedScope = "";
	/** The developer chose package or request above the chat; the next message tells the model. */
	private boolean scopeChosen;
	/** Reads package, request and the developer's open requests in the background. */
	private Job scopeJob;
	private final Runnable scopeListener = () -> {
		Job j = scopeJob;
		if (j != null) {
			j.schedule(50);
		}
	};
	/** Packages kept in the drop-down. */
	static final int PACKAGE_HISTORY = 10;

	/** The chat view if it is open, without opening it. UI thread. */
	public static Optional<ChatView> find() {
		var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null || window.getActivePage() == null) {
			return Optional.empty();
		}
		return Optional.ofNullable((ChatView) window.getActivePage().findView(ID));
	}

	/** Tab title in Bella's UI language (plugin.xml only knows the system language). */
	public void updateTitle() {
		setPartName(Messages.plugin("view.chat"));
	}

	/** Shows the chat view and returns it. UI thread. */
	public static Optional<ChatView> open() {
		try {
			IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
			return Optional.of((ChatView) page.showView(ID));
		} catch (PartInitException e) {
			BellaPlugin.log("Cannot open Bella chat", e);
			return Optional.empty();
		}
	}

	@Override
	public void createPartControl(Composite parent) {
		EditorTracker.install();
		updateTitle();
		GridLayoutFactory.fillDefaults().spacing(0, 0).applyTo(parent);
		createScopeBar(parent);
		try {
			browser = new Browser(parent, SWT.NONE);
		} catch (org.eclipse.swt.SWTError e) {
			// No browser engine (e.g. Linux without WebKitGTK): explain instead of failing.
			Label missing = new Label(parent, SWT.WRAP);
			missing.setText(Messages.fmt("chat.noBrowser", e.getMessage()));
			GridDataFactory.fillDefaults().grab(true, true).applyTo(missing);
			browser = null;
		}
		if (browser != null) {
			createBrowser(parent);
		}
		createInput(parent);
	}

	private void createBrowser(Composite parent) {
		GridDataFactory.fillDefaults().grab(true, true).applyTo(browser);
		browser.setJavascriptEnabled(true);
		browser.setText(template());
		browser.addProgressListener(org.eclipse.swt.browser.ProgressListener.completedAdapter(e -> {
			if (pageLoaded) {
				return;
			}
			pageLoaded = true;
			browser.execute("init(" + Json.GSON.toJson(texts()) + "," + isDark(parent) + ")");
			pendingScripts.forEach(browser::execute);
			pendingScripts.clear();
		}));
		browser.addLocationListener(new LocationAdapter() {
			@Override
			public void changing(LocationEvent event) {
				if (!"about:blank".equals(event.location)) {
					event.doit = false;
				}
			}
		});
		new BrowserFunction(browser, "bellaAction") {
			@Override
			public Object function(Object[] args) {
				onCodeAction((String) args[0], ((Number) args[1]).intValue(), ((Number) args[2]).intValue(),
						((Number) args[3]).intValue());
				return null;
			}
		};
		new BrowserFunction(browser, "bellaOpen") {
			@Override
			public Object function(Object[] args) {
				String url = String.valueOf(args[0]);
				if (url.startsWith("https://") || url.startsWith("http://")) {
					org.eclipse.swt.program.Program.launch(url);
				}
				return null;
			}
		};

	}

	private void createInput(Composite parent) {
		createPlanBar(parent);
		Composite bottom = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(bottom);
		GridLayoutFactory.swtDefaults().numColumns(3).margins(6, 6).applyTo(bottom);
		input = new Text(bottom, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		input.setMessage(Messages.get("chat.inputHint"));
		GridDataFactory.fillDefaults().grab(true, false).span(1, 2).hint(SWT.DEFAULT, 64).applyTo(input);
		input.addListener(SWT.KeyDown, e -> {
			if ((e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) && (e.stateMask & SWT.SHIFT) == 0) {
				e.doit = false;
				sendFromInput();
			}
		});
		send = new Button(bottom, SWT.PUSH);
		send.setImage(BellaPlugin.image("send"));
		send.setToolTipText(Messages.get("chat.send"));
		send.addListener(SWT.Selection, e -> sendFromInput());
		stop = new Button(bottom, SWT.PUSH);
		stop.setImage(BellaPlugin.image("stop"));
		stop.setToolTipText(Messages.get("chat.stop"));
		stop.setEnabled(false);
		stop.addListener(SWT.Selection, e -> cancel());
		planning = new Button(bottom, SWT.TOGGLE);
		planning.setImage(BellaPlugin.image("plan"));
		planning.setText(Messages.get("chat.plan.button"));
		planning.setToolTipText(Messages.get("chat.plan.buttonTip"));
		// as wide as Send and Stop together, normal button height
		GridDataFactory.swtDefaults().span(2, 1).align(SWT.FILL, SWT.BEGINNING).applyTo(planning);
		planning.addListener(SWT.Selection, e -> {
			if (planning.getSelection()) {
				setPlanning(true);
				sendFromInput(); // plans what is typed; with an empty input the next message is planned
			} else {
				if (running != null && turnOverride == ChatMode.PLAN) {
					cancel(); // unpressed while Bella plans: stop planning
				}
				cancelPlan();
			}
		});
		// context, mode and status in one row below, so the button columns stay narrow
		Composite options = new Composite(bottom, SWT.NONE);
		GridDataFactory.fillDefaults().span(3, 1).grab(true, false).applyTo(options);
		GridLayoutFactory.fillDefaults().numColumns(3).spacing(12, 0).applyTo(options);
		withContext = new Button(options, SWT.CHECK);
		withContext.setText(Messages.get("chat.withContext"));
		withContext.setToolTipText(Messages.get("chat.withContextTip"));
		withContext.setSelection(true);
		modeChoice = new Combo(options, SWT.READ_ONLY | SWT.DROP_DOWN);
		for (ChatMode m : MODES) {
			modeChoice.add(Messages.get(modeKey(m)));
		}
		modeChoice.setVisibleItemCount(MODES.size());
		modeChoice.addListener(SWT.Selection, e -> {
			ChatMode chosen = MODES.get(Math.max(0, modeChoice.getSelectionIndex()));
			if (chosen.writesWithoutAsking() && chosen != mode && !MessageDialog.openConfirm(getSite().getShell(),
					Messages.get(modeKey(chosen)), Messages.get(modeKey(chosen) + ".confirm"))) {
				setMode(mode); // back to the previous choice
				return;
			}
			setMode(chosen);
		});
		status = new Label(options, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(status);

		IToolBarManager tb = getViewSite().getActionBars().getToolBarManager();
		Action newChat = new Action(Messages.get("chat.new"), BellaPlugin.descriptor("new_chat")) {
			@Override
			public void run() {
				newChat();
			}
		};
		Action settings = new Action(Messages.get("chat.settings"), BellaPlugin.descriptor("settings")) {
			@Override
			public void run() {
				PreferenceDialog d = PreferencesUtil.createPreferenceDialogOn(getSite().getShell(),
						"de.kiliantaubmann.bella.ui.prefs.main", null, null);
				d.open();
				updateStatus();
			}
		};
		tb.add(newChat);
		tb.add(settings);
		Action openLog = new Action(Messages.get("chat.openLog")) {
			@Override
			public void run() {
				java.nio.file.Path file = BellaPlugin.getDefault().logFile().path();
				if (java.nio.file.Files.exists(file)) {
					de.kiliantaubmann.bella.ui.prefs.LogViewer.openInEditor(file);
				} else {
					// no log yet: show where to switch it on
					PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), "de.kiliantaubmann.bella.ui.prefs.log",
							null, null).open();
				}
			}
		};
		getViewSite().getActionBars().getMenuManager().add(openLog);
		newSession();
		setMode(ChatMode.NORMAL);
		BellaPlugin.getDefault().devScope().addListener(scopeListener);
		scopeJob.schedule();
	}

	/** Selects a mode in the drop-down and uses it from the next tool call on. UI thread. */
	public void setMode(ChatMode newMode) {
		if (!MODES.contains(newMode)) {
			throw new IllegalArgumentException(newMode + " is not a drop-down mode; use ask(…, true) to plan");
		}
		mode = newMode;
		modeChoice.select(MODES.indexOf(newMode));
		modeChoice.setToolTipText(Messages.get(modeKey(newMode) + ".tip"));
		updateStatus();
	}

	/** Accept, change or cancel below a plan; hidden until a planning turn has answered. */
	private void createPlanBar(Composite parent) {
		planBar = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).exclude(true).applyTo(planBar);
		GridLayoutFactory.swtDefaults().numColumns(4).margins(6, 4).applyTo(planBar);
		planBar.setVisible(false);
		Label question = new Label(planBar, SWT.NONE);
		question.setText(Messages.get("chat.plan.question"));
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(question);
		Button accept = new Button(planBar, SWT.PUSH);
		accept.setText(Messages.get("chat.plan.accept"));
		accept.addListener(SWT.Selection, e -> acceptPlan());
		Button change = new Button(planBar, SWT.PUSH);
		change.setText(Messages.get("chat.plan.change"));
		change.addListener(SWT.Selection, e -> changePlan());
		Button discard = new Button(planBar, SWT.PUSH);
		discard.setText(Messages.get("chat.plan.cancel"));
		discard.addListener(SWT.Selection, e -> cancelPlan());
	}

	private void showPlanBar(boolean show) {
		if (planBar == null || planBar.isDisposed() || planBar.getVisible() == show) {
			return;
		}
		planBar.setVisible(show);
		((GridData) planBar.getLayoutData()).exclude = !show;
		planBar.getParent().layout(true, true);
	}

	/** Whether the accept/change/cancel choice below a plan is shown. */
	public boolean isPlanPending() {
		return planBar != null && planBar.getVisible();
	}

	/** Whether the Planning Mode button is pressed. */
	public boolean isPlanning() {
		return planActive;
	}

	/** Presses or releases the Planning Mode button; while pressed, messages from the input are planned. UI thread. */
	public void setPlanning(boolean active) {
		planActive = active;
		if (!active) {
			revisingPlan = false;
			showPlanBar(false);
		}
		planning.setSelection(active);
		input.setMessage(Messages.get(!active ? "chat.inputHint"
				: revisingPlan ? "chat.plan.changeHint" : "chat.plan.inputHint"));
		updateStatus();
	}

	/** Carries the plan out in the mode chosen in the drop-down. UI thread. */
	public void acceptPlan() {
		setPlanning(false);
		ask(Messages.get("chat.plan.accept"), ACCEPT_PLAN, false);
	}

	/** The next message from the input revises the plan, again in plan mode. UI thread. */
	public void changePlan() {
		showPlanBar(false);
		revisingPlan = true;
		setPlanning(true);
		input.setFocus();
	}

	/** Drops the plan and releases the Planning Mode button; nothing is carried out. UI thread. */
	public void cancelPlan() {
		boolean hadPlan = isPlanPending() || revisingPlan;
		setPlanning(false);
		if (hadPlan) {
			js("notice(" + lastAnswerId + ",\"warn\"," + str(Markdown.escape(Messages.get("chat.plan.cancelled")))
					+ ")");
		}
	}

	static final String ACCEPT_PLAN = "The developer accepted the plan above. Carry it out now, step by step and in "
			+ "the order of the plan, and report what you did.";

	static final String REVISE_PLAN = "The developer wants the plan changed. Give the complete revised plan.\n\n"
			+ "Change request: ";

	/** Message key of a mode's label, e.g. {@code chat.mode.read_data}. */
	static String modeKey(ChatMode m) {
		return "chat.mode." + m.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The chat's current mode. */
	public ChatMode mode() {
		return mode;
	}

	private static boolean isDark(Composite c) {
		RGB bg = c.getBackground().getRGB();
		return (bg.red * 299 + bg.green * 587 + bg.blue * 114) / 1000 < 128;
	}

	private static String template() {
		try (InputStream in = ChatView.class.getResourceAsStream("/html/chat.html")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException | NullPointerException e) {
			return "<html><body>chat.html missing</body></html>";
		}
	}

	private static Map<String, String> texts() {
		Map<String, String> t = new HashMap<>();
		for (String k : List.of("welcomeTitle", "welcomeText", "tip1", "tip2", "tip3", "youInitial", "working",
				"thinking", "insert", "insertTip", "replace", "replaceTip", "copyTip", "insertAtCursor",
				"replaceSelection", "replaceMethod", "copy")) {
			t.put(k, Markdown.escape(Messages.get("chat.js." + k)));
		}
		t.put("tip3", Markdown.escape(Messages.fmt("chat.js.tip3", Shortcuts.completion())));
		return t;
	}

	private void newSession() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		if (session != null) {
			session.close();
		}
		session = plugin.newConversation(this::confirmTool, new OpenEditorRouter(),
				() -> turnOverride != null ? turnOverride : mode);
		// a new conversation knows no package yet; the developer names it again
		plugin.devScope().reset();
		shownToolErrors = List.of();
	}

	private void newChat() {
		cancel();
		setPlanning(false);
		newSession();
		codeBlocks.clear();
		announcedScope = "";
		scopeChosen = false;
		js("showEmpty()");
		updateStatus();
	}

	private void updateStatus() {
		if (status == null || status.isDisposed()) {
			return;
		}
		BellaPlugin plugin = BellaPlugin.getDefault();
		String system = EditorBridge.activeTextEditor().isPresent()
				? Optional.ofNullable(EditorBridge.systemLabel(activeEditor())).orElse("")
				: "";
		String text = Messages.fmt("chat.status", plugin.chatModelLabel(), system.isEmpty() ? "–" : system);
		if (planActive || turnOverride == ChatMode.PLAN) {
			text += " · " + Messages.get("chat.plan.button");
		} else if (mode != ChatMode.NORMAL) {
			text += " · " + Messages.get(modeKey(mode));
		}
		status.setText(text);
		status.getParent().layout();
	}

	private static IEditorPart activeEditor() {
		return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
	}

	@Override
	public void setFocus() {
		input.setFocus();
	}

	private void js(String script) {
		if (browser == null || browser.isDisposed()) {
			return;
		}
		if (!pageLoaded) {
			pendingScripts.add(script);
			return;
		}
		browser.execute(script);
	}

	private static String str(String s) {
		return Json.GSON.toJson(s);
	}

	// ---- development package and transport request ------------------------------------

	/** Package and request of the chat, above it; chosen here or by Bella when the developer names them. */
	private void createScopeBar(Composite parent) {
		scopeBar = new Composite(parent, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(scopeBar);
		GridLayoutFactory.swtDefaults().numColumns(5).margins(6, 4).applyTo(scopeBar);
		Label pkgLabel = new Label(scopeBar, SWT.NONE);
		pkgLabel.setText(Messages.get("chat.scope.package"));
		packageChoice = new Combo(scopeBar, SWT.DROP_DOWN);
		packageChoice.setToolTipText(Messages.get("chat.scope.packageTip"));
		GridDataFactory.fillDefaults().grab(true, false).hint(140, SWT.DEFAULT).applyTo(packageChoice);
		// Enter in the field or a package from the list
		packageChoice.addListener(SWT.DefaultSelection, e -> choosePackage(packageChoice.getText()));
		packageChoice.addListener(SWT.Selection, e -> choosePackage(packageChoice.getText()));
		packageSearch = new Button(scopeBar, SWT.PUSH);
		packageSearch.setText("…");
		packageSearch.setToolTipText(Messages.get("chat.scope.searchTip"));
		packageSearch.addListener(SWT.Selection, e -> searchPackage());
		Label trLabel = new Label(scopeBar, SWT.NONE);
		trLabel.setText(Messages.get("chat.scope.transport"));
		transportChoice = new Combo(scopeBar, SWT.READ_ONLY | SWT.DROP_DOWN);
		transportChoice.setToolTipText(Messages.get("chat.scope.transportTip"));
		GridDataFactory.fillDefaults().grab(true, false).hint(160, SWT.DEFAULT).applyTo(transportChoice);
		transportChoice.addListener(SWT.Selection, e -> {
			int i = transportChoice.getSelectionIndex();
			if (i >= 0 && i < transportIds.size() && !transportIds.get(i).isEmpty()
					&& (shownScope == null || !transportIds.get(i).equals(shownScope.transport()))) {
				choose(null, transportIds.get(i));
			}
		});
		scopeJob = Job.create(Messages.get("chat.scope.jobName"), monitor -> {
			readScope();
			return Status.OK_STATUS;
		});
		scopeJob.setSystem(true);
		showScope(null, List.of(), null);
	}

	/** Job thread: package, request and, for a transported package, the developer's open requests. */
	private void readScope() {
		Optional<AdtToolProvider> tools = BellaPlugin.getDefault().adtTools();
		AdtToolProvider.Scope scope = null;
		List<AdtTransportRequest> requests = List.of();
		String problem = null;
		if (tools.isEmpty()) {
			problem = Messages.get("chat.scope.noAdt");
		} else {
			try {
				scope = tools.get().scope(CancelToken.NONE);
				if (scope.pkg() != null && !scope.local()) {
					requests = tools.get().openTransports(CancelToken.NONE);
				}
			} catch (IOException | RuntimeException e) {
				problem = e.getMessage() == null ? e.toString() : e.getMessage();
			}
		}
		AdtToolProvider.Scope s = scope;
		List<AdtTransportRequest> r = requests;
		String p = problem;
		Display.getDefault().asyncExec(() -> showScope(s, r, p));
	}

	/** UI thread. {@code scope} is {@code null} when it cannot be read; {@code problem} says why. */
	private void showScope(AdtToolProvider.Scope scope, List<AdtTransportRequest> requests, String problem) {
		if (scopeBar == null || scopeBar.isDisposed()) {
			return;
		}
		shownScope = scope;
		String pkg = scope == null || scope.pkg() == null ? "" : scope.pkg();
		packageChoice.setItems(packageHistory().toArray(String[]::new));
		packageChoice.setText(pkg);
		packageChoice.setToolTipText(scope == null ? problem
				: scope.editorObject() != null ? Messages.fmt("chat.scope.locked", scope.editorObject())
						: Messages.get("chat.scope.packageTip"));
		List<String> ids = new ArrayList<>();
		transportChoice.removeAll();
		if (scope == null || scope.pkg() == null) {
			ids.add("");
			transportChoice.add(Messages.get("chat.scope.packageFirst"));
		} else if (scope.local()) {
			ids.add("");
			transportChoice.add(Messages.get("chat.scope.local"));
		} else {
			String current = scope.transport();
			if (current == null) {
				ids.add("");
				transportChoice.add(Messages.get("chat.scope.chooseTransport"));
			}
			for (AdtTransportRequest t : requests) {
				ids.add(t.id().toUpperCase(java.util.Locale.ROOT));
				transportChoice.add(t.id().toUpperCase(java.util.Locale.ROOT)
						+ (t.description().isBlank() ? "" : "  " + t.description()));
			}
			if (current != null && !ids.contains(current)) {
				ids.add(0, current);
				transportChoice.add(current, 0);
			}
		}
		transportIds = ids;
		transportChoice.select(scope == null || scope.transport() == null ? 0 : ids.indexOf(scope.transport()));
		if (scope != null && scope.pkg() != null) {
			remember(scope.pkg());
		}
		enableScope();
		announceScope(scope);
		scopeBar.layout(true, true);
	}

	/** Locked while Bella answers, without SAP and, for the package, while an editor object binds it. */
	private void enableScope() {
		if (scopeBar == null || scopeBar.isDisposed()) {
			return;
		}
		boolean idle = running == null && shownScope != null;
		boolean ownPackage = idle && shownScope.editorObject() == null;
		packageChoice.setEnabled(ownPackage);
		packageSearch.setEnabled(ownPackage);
		transportChoice.setEnabled(idle && shownScope.pkg() != null && !shownScope.local());
	}

	/** A note in the chat whenever package or request change, whoever changed them. */
	private void announceScope(AdtToolProvider.Scope scope) {
		if (scope == null || scope.pkg() == null) {
			announcedScope = "";
			return;
		}
		String key = scope.pkg() + "|" + scope.transport();
		if (key.equals(announcedScope)) {
			return;
		}
		announcedScope = key;
		String text = scope.local() || scope.transport() == null ? Messages.fmt("chat.scope.notice", scope.pkg())
				: Messages.fmt("chat.scope.noticeTransport", scope.pkg(), scope.transport());
		if (scope.editorObject() != null) {
			text += " " + Messages.fmt("chat.scope.noticeEditor", scope.editorObject());
		}
		js("notice(0,\"info\"," + str(Markdown.escape(text)) + ")");
	}

	private void choosePackage(String name) {
		String pkg = name == null ? "" : name.trim().toUpperCase(java.util.Locale.ROOT);
		if (pkg.isEmpty() || shownScope == null || pkg.equals(shownScope.pkg())) {
			return;
		}
		choose(pkg, null);
	}

	private void searchPackage() {
		Optional<AdtToolProvider> tools = BellaPlugin.getDefault().adtTools();
		if (tools.isEmpty()) {
			return;
		}
		PackageDialog dialog = new PackageDialog(getSite().getShell(), tools.get(), packageChoice.getText());
		if (dialog.open() == PackageDialog.OK && dialog.result() != null) {
			packageChoice.setText(dialog.result());
			choosePackage(dialog.result());
		}
	}

	/**
	 * Records the developer's choice with the checks of adt_dev_package; a
	 * refusal is shown and the bar returns to what is set. {@code null} keeps
	 * a value.
	 */
	private void choose(String pkg, String transport) {
		Optional<AdtToolProvider> tools = BellaPlugin.getDefault().adtTools();
		if (tools.isEmpty()) {
			return;
		}
		packageChoice.setEnabled(false);
		transportChoice.setEnabled(false);
		Job job = Job.create(Messages.get("chat.scope.jobName"), monitor -> {
			Optional<String> refused = tools.get().choose(pkg, transport, CancelToken.NONE);
			Display.getDefault().asyncExec(() -> {
				if (refused.isPresent()) {
					if (!scopeBar.isDisposed()) {
						MessageDialog.openError(getSite().getShell(), Messages.get("chat.scope.title"), refused.get());
					}
				} else {
					scopeChosen = true;
					if (pkg != null) {
						remember(pkg);
					}
				}
				// a recorded choice reaches the bar through the scope listener as well
				if (scopeJob != null) {
					scopeJob.schedule();
				}
			});
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	/** The model reads package and request with adt_dev_package; this note tells it they were chosen here. */
	private String withScopeNote(String prompt) {
		if (!scopeChosen) {
			return prompt;
		}
		scopeChosen = false;
		return prompt + "\n\n" + SCOPE_NOTE;
	}

	static final String SCOPE_NOTE = "<dev_scope>The developer chose the development package and/or the transport "
			+ "request above the chat. Read them with adt_dev_package; do not ask for them again.</dev_scope>";

	private static List<String> packageHistory() {
		String stored = BellaPlugin.getDefault().prefs().getString(Prefs.PACKAGE_HISTORY);
		return stored == null || stored.isBlank() ? List.of() : List.of(stored.split(","));
	}

	/** Puts {@code pkg} first in the drop-down's history. */
	private static void remember(String pkg) {
		List<String> history = new ArrayList<>(packageHistory());
		if (!history.isEmpty() && history.get(0).equals(pkg)) {
			return;
		}
		history.remove(pkg);
		history.add(0, pkg);
		BellaPlugin.getDefault().prefs().setValue(Prefs.PACKAGE_HISTORY,
				String.join(",", history.subList(0, Math.min(PACKAGE_HISTORY, history.size()))));
	}

	// ---- sending ------------------------------------------------------------------------

	/** Sends the input; while the Planning Mode button is pressed, as a planning turn. */
	private void sendFromInput() {
		String text = input.getText().trim();
		if (text.isEmpty() || running != null) {
			input.setFocus();
			return;
		}
		boolean planning = planActive;
		input.setText("");
		if (revisingPlan) {
			revisingPlan = false;
			input.setMessage(Messages.get("chat.plan.inputHint"));
			text = REVISE_PLAN + text;
		}
		String prompt = text;
		if (withContext.getSelection()) {
			IEditorPart part = activeEditor();
			Optional<ITextEditor> editor = EditorBridge.textEditor(part);
			if (editor.isPresent()) {
				prompt = withEditorContext(text, EditorBridge.context(part, editor.get()));
				// working on an editor object binds the chat to its package
				EditorBridge.adtObject(part).ifPresent(BellaPlugin.getDefault().devScope()::editorObject);
			}
		}
		ask(text.startsWith(REVISE_PLAN) ? text.substring(REVISE_PLAN.length()) : text, prompt, planning);
	}

	static String withEditorContext(String question, EditorContext ctx) {
		StringBuilder sb = new StringBuilder(question).append("\n\n<editor_context>\nObject: ")
				.append(ctx.describeObject()).append('\n');
		if (!ctx.selection().isEmpty()) {
			sb.append("Selected code:\n```abap\n").append(ctx.selection()).append("\n```\n");
		}
		String src = ctx.source();
		if (src.length() > 60_000) {
			src = src.substring(0, 60_000) + "\n* …";
		}
		sb.append("Source in the editor (may contain unsaved changes):\n```abap\n").append(src).append("\n```\n")
				.append("</editor_context>");
		return sb.toString();
	}

	/**
	 * Sends a prompt; the chat shows {@code display} as the user's message.
	 * UI thread.
	 */
	public void ask(String display, String prompt) {
		ask(display, prompt, false);
	}

	/**
	 * @param planning plan mode for this turn only: Bella reads and answers with
	 *                 a plan, then offers to accept, change or cancel it
	 */
	public void ask(String display, String prompt, boolean planning) {
		if (running != null) {
			return;
		}
		showPlanBar(false);
		if (!planning && planActive) {
			// e.g. an editor action sent to the chat: another task, so planning ends
			setPlanning(false);
		}
		if (!BellaPlugin.getDefault().conversationType().isInstance(session.unwrap())) {
			newSession(); // provider switched in the preferences: the old chat cannot continue
			updateStatus();
		}
		int userId = nextMessageId++;
		int botId = nextMessageId++;
		js("addUser(" + userId + "," + str(display) + ")");
		js("startAssistant(" + botId + ")");
		CancelToken cancel = new CancelToken();
		ChatMode turnMode = planning ? ChatMode.PLAN : mode;
		turnOverride = planning ? ChatMode.PLAN : null;
		lastAnswerId = botId;
		AtomicBoolean answered = new AtomicBoolean();
		running = cancel;
		setBusy(true);
		updateStatus();
		Renderer renderer = new Renderer(botId);
		String sent = withScopeNote(prompt);
		Job job = Job.create(Messages.get("chat.jobName"), (IProgressMonitor monitor) -> {
			try {
				List<String> toolErrors = new ArrayList<>();
				BellaPlugin.getDefault().tools().refresh(toolErrors::add);
				if (!toolErrors.equals(shownToolErrors)) {
					toolErrors.forEach(err -> renderer.notice("warn", Markdown.escape(err)));
				}
				shownToolErrors = List.copyOf(toolErrors);
				session.ask(turnMode.apply(sent), renderer, cancel);
				answered.set(!cancel.isCancelled() && !renderer.incomplete);
			} catch (CancelToken.CancelledException e) {
				renderer.notice("warn", Messages.get("chat.cancelled"));
			} catch (Exception e) {
				renderer.notice("error", Markdown.escape(e.getMessage() == null ? e.toString() : e.getMessage()));
			} finally {
				renderer.flush();
				Display.getDefault().asyncExec(() -> {
					js("endAssistant(" + botId + ")");
					running = null;
					turnOverride = null;
					setBusy(false);
					updateStatus();
					if (planning && answered.get()) {
						showPlanBar(true);
					}
				});
			}
			return Status.OK_STATUS;
		});
		job.setUser(false);
		job.schedule();
	}

	private void setBusy(boolean busy) {
		if (send.isDisposed()) {
			return;
		}
		send.setEnabled(!busy);
		planning.setEnabled(!busy || planActive); // releasing it stops a running plan
		stop.setEnabled(busy);
		enableScope();
	}

	private void cancel() {
		CancelToken c = running;
		if (c != null) {
			c.cancel();
		}
	}

	@Override
	public void dispose() {
		BellaPlugin.getDefault().devScope().removeListener(scopeListener);
		scopeJob = null;
		cancel();
		if (session != null) {
			session.close();
		}
		super.dispose();
	}

	// ---- tool confirmation ---------------------------------------------------------------

	private boolean confirmTool(ToolSpec tool, JsonObject input) {
		AtomicBoolean ok = new AtomicBoolean();
		Display.getDefault().syncExec(() -> ok.set(ToolConfirmDialog.ask(getSite().getShell(), tool, input)));
		return ok.get();
	}

	// ---- code block actions ----------------------------------------------------------------

	private void onCodeAction(String action, int messageId, int segment, int index) {
		List<String> blocks = codeBlocks.get(messageId + "_" + segment);
		if (blocks == null || index < 0 || index >= blocks.size()) {
			return;
		}
		String code = blocks.get(index);
		switch (action) {
		case "insert" -> CodeActions.apply(CodeActions.Target.CURSOR, code);
		case "replace" -> CodeActions.apply(CodeActions.Target.SELECTION, code);
		case "method" -> CodeActions.apply(CodeActions.Target.METHOD, code);
		case "copy" -> {
			Clipboard cb = new Clipboard(Display.getDefault());
			cb.setContents(new Object[] { code }, new Transfer[] { TextTransfer.getInstance() });
			cb.dispose();
		}
		default -> {
			// unknown action
		}
		}
	}

	// ---- streaming into the browser ----------------------------------------------------------

	/** Turns session callbacks into throttled browser updates. Called on the job thread. */
	/** Notices after which the answer is no complete plan (declined, cut off, limit, error). */
	static final java.util.Set<String> INCOMPLETE = java.util.Set.of("refusal", "max_tokens", "max_tokens_tool",
			"max_rounds", "cc_limit", "cc_error", "cc_login", "cp_limit", "cp_error", "cp_login");

	private final class Renderer implements ConversationListener {
		/** The answer was declined, cut off or ended by an error notice. */
		volatile boolean incomplete;
		private final int id;
		private int segment;
		private String kind = "";
		private final StringBuilder buffer = new StringBuilder();
		private boolean scheduled;
		private final List<Runnable> pending = new ArrayList<>();

		Renderer(int id) {
			this.id = id;
		}

		private synchronized void segmentOf(String k) {
			if (!kind.equals(k)) {
				flushLocked();
				segment++;
				kind = k;
				buffer.setLength(0);
			}
		}

		@Override
		public void onText(String delta) {
			synchronized (this) {
				segmentOf("text");
				buffer.append(delta);
			}
			schedule();
		}

		@Override
		public void onThinking(String delta) {
			synchronized (this) {
				segmentOf("thinking");
				buffer.append(delta);
			}
			schedule();
		}

		@Override
		public void onFallback(String fromModel, String toModel) {
			notice("warn", Messages.fmt("chat.fallback", Markdown.escape(String.valueOf(fromModel)),
					Markdown.escape(String.valueOf(toModel))));
		}

		@Override
		public void onToolCall(ToolSpec tool, ToolCall call) {
			int seg;
			synchronized (this) {
				segmentOf("tool:" + call.id());
				seg = segment;
			}
			String in = abbreviate(Json.PRETTY.toJson(call.input()), 4000);
			ui(() -> js("addTool(" + id + "," + seg + "," + str(tool.name()) + "," + str(in) + ")"));
		}

		@Override
		public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
			int seg;
			synchronized (this) {
				seg = segment;
			}
			String out = abbreviate(result.content() == null ? "" : result.content(), 6000);
			boolean denied = result.isError() && result.content() != null
					&& (result.content().contains("declined") || result.content().contains("Refused"));
			String state = !result.isError() ? "ok" : denied ? "denied" : "error";
			String label = Messages.get("chat.tool." + state);
			ui(() -> js("finishTool(" + id + "," + seg + "," + str(state) + "," + str(label) + "," + str(out) + ")"));
		}

		@Override
		public void onNotice(String message) {
			// "key" or "key:detail", e.g. "refusal:…" or "cc_limit:…"
			int colon = message.indexOf(':');
			String bare = colon < 0 ? message : message.substring(0, colon);
			if (INCOMPLETE.contains(bare)) {
				incomplete = true;
			}
			String key = "chat.notice." + bare;
			String detail = colon < 0 ? "" : message.substring(colon + 1);
			notice("warn", Markdown.escape(Messages.fmt(key, detail)));
		}

		@Override
		public void onTurnEnd(ChatResult last) {
			flush();
		}

		void notice(String kind, String html) {
			flush();
			ui(() -> js("notice(" + id + "," + str(kind) + "," + str(html) + ")"));
		}

		private void schedule() {
			synchronized (this) {
				if (scheduled) {
					return;
				}
				scheduled = true;
			}
			Display.getDefault().asyncExec(() -> Display.getDefault().timerExec(80, this::flush));
		}

		void flush() {
			synchronized (this) {
				flushLocked();
			}
		}

		private void flushLocked() {
			scheduled = false;
			if (buffer.isEmpty()) {
				return;
			}
			String content = buffer.toString();
			int seg = segment;
			if (kind.equals("thinking")) {
				ui(() -> js("setThinking(" + id + "," + seg + "," + str(content) + ")"));
			} else if (kind.equals("text")) {
				Markdown.Rendered r = Markdown.render(content,
						(i, lang) -> "<div class=\"code-bar\" data-lang=\"" + Markdown.escape(lang) + "\"></div>");
				ui(() -> {
					codeBlocks.put(id + "_" + seg, r.codeBlocks());
					js("setText(" + id + "," + seg + "," + str(r.html()) + ")");
				});
			}
		}

		private void ui(Runnable r) {
			Display d = Display.getDefault();
			if (Display.getCurrent() == d) {
				r.run();
			} else {
				d.asyncExec(r);
			}
		}
	}

	private static String abbreviate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "\n…";
	}

	/** Used by the status line to pick up preference changes. */
	public IStatus refresh() {
		updateStatus();
		return Status.OK_STATUS;
	}
}

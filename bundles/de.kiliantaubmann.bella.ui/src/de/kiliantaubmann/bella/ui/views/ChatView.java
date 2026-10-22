package de.kiliantaubmann.bella.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatRecord;
import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.ChatStore;
import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.agent.TurnUsage;
import de.kiliantaubmann.bella.core.adt.AdtToolProvider;
import de.kiliantaubmann.bella.core.adt.AdtTransportRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.tools.AskUserToolProvider;
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
	/** Tokens of the shown chat so far, for the status line. */
	private Usage chatTokens = Usage.NONE;
	/** Tokens of the chat's last answer, {@code null} before the first. */
	private TurnUsage lastUsage;
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
	/** The developer's open requests, read again after this time or for another system. */
	static final long REQUESTS_TTL_MILLIS = 60_000;
	/** A choice from a drop-down waits this long, so arrow keys or the mouse wheel do not set every entry. */
	static final int CHOICE_DELAY_MILLIS = 700;
	/** Open requests last read, for the system in {@link #requestsSystem}; job thread. */
	private volatile List<AdtTransportRequest> cachedRequests = List.of();
	private volatile String requestsSystem;
	private volatile long requestsAt;
	/** The drop-down choice waiting for {@link #CHOICE_DELAY_MILLIS}, {@code null} if none. */
	private Runnable pendingChoice;
	/** The chat as the chat history keeps it. */
	private ChatRecord record = new ChatRecord();
	/**
	 * The conversation so far for a model that did not see it (a chat opened from the history, or the provider
	 * changed); goes with the next message. {@code null} if the model knows the chat.
	 */
	private String earlierChat;
	/** The session's model history is the whole chat, so the history may keep it for an exact restore. */
	private boolean exactHistory = true;
	/** The renderer of the running answer, {@code null} between answers. */
	private volatile Renderer renderer;
	/** The open question menu ("messageId_segment"), its questions and the answer the tool waits for. */
	private String askKey;
	private int askMessage;
	private int askSegment;
	private List<AskUserToolProvider.Question> askQuestions;
	private CompletableFuture<Optional<List<AskUserToolProvider.Answer>>> askAnswer;
	/** Counts the chats of this view; a turn of an earlier chat must not leave its package in the new one. */
	private int chatGeneration;

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
			browser.execute("showUsage(" + BellaPlugin.getDefault().prefs().getBoolean(Prefs.SHOW_USAGE) + ")");
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
		new BrowserFunction(browser, "bellaAnswer") {
			@Override
			public Object function(Object[] args) {
				onAnswer(String.valueOf(args[0]), String.valueOf(args[1]));
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
		// Send and Stop as wide as the text buttons below them
		GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(send);
		stop = new Button(bottom, SWT.PUSH);
		stop.setImage(BellaPlugin.image("stop"));
		stop.setToolTipText(Messages.get("chat.stop"));
		stop.setEnabled(false);
		stop.addListener(SWT.Selection, e -> cancel());
		GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(stop);
		planning = new Button(bottom, SWT.TOGGLE);
		planning.setImage(BellaPlugin.image("plan"));
		planning.setText(Messages.get("chat.plan.button"));
		planning.setToolTipText(Messages.get("chat.plan.buttonTip"));
		GridDataFactory.swtDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(planning);
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
		Button newChatButton = new Button(bottom, SWT.PUSH);
		newChatButton.setImage(BellaPlugin.image("new_chat"));
		newChatButton.setText(Messages.get("chat.new"));
		newChatButton.setToolTipText(Messages.get("chat.newTip"));
		GridDataFactory.swtDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(newChatButton);
		newChatButton.addListener(SWT.Selection, e -> newChat());
		// context, mode and status in one row below, so the button columns stay narrow
		Composite options = new Composite(bottom, SWT.NONE);
		GridDataFactory.fillDefaults().span(3, 1).grab(true, false).applyTo(options);
		GridLayoutFactory.fillDefaults().numColumns(4).spacing(12, 0).applyTo(options);
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
		Button chats = new Button(options, SWT.PUSH);
		chats.setImage(BellaPlugin.image("history"));
		chats.setText(Messages.get("chat.history") + " \u25BE");
		chats.setToolTipText(Messages.get("chat.historyTip"));
		chats.addListener(SWT.Selection, e -> showHistoryMenu(chats));

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
		Action history = new Action(Messages.get("chat.history"), BellaPlugin.descriptor("history")) {
			@Override
			public void run() {
				openHistoryDialog();
			}
		};
		history.setToolTipText(Messages.get("chat.historyTip"));
		tb.add(newChat);
		tb.add(history);
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
			record.notice(lastAnswerId, "warn", Markdown.escape(Messages.get("chat.plan.cancelled")));
			js("notice(" + lastAnswerId + ",\"warn\"," + str(Markdown.escape(Messages.get("chat.plan.cancelled")))
					+ ")");
			saveChat();
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
				"replaceSelection", "replaceMethod", "copy", "askOther", "askHint", "askHintMulti", "askNext",
				"askSubmit", "askDismiss", "askDismissed")) {
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
		resetChat();
		js("showEmpty()");
		updateStatus();
	}

	/** Stops and saves the chat and starts an empty one. UI thread. */
	private void resetChat() {
		chatGeneration++;
		cancel();
		setPlanning(false);
		saveChat();
		newSession();
		codeBlocks.clear();
		announcedScope = "";
		scopeChosen = false;
		record = new ChatRecord();
		chatTokens = Usage.NONE;
		lastUsage = null;
		earlierChat = null;
		exactHistory = true;
	}

	// ---- chat history ----------------------------------------------------------------------

	/** Longest part of an earlier chat handed to a model that did not see it. */
	static final int EARLIER_CHAT_CHARS = 60_000;
	/** Chats in the menu of the Chats button; the rest are in the dialog. */
	static final int MENU_CHATS = 15;

	/** Conversation type and model; a saved model history is only taken over by the same. */
	private static String providerKey() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		return plugin.conversationType().getSimpleName() + ":" + plugin.chatModel();
	}

	/** The message with the chat so far for a model that did not see it. */
	static String withEarlierChat(String prompt, String earlier) {
		if (earlier == null || earlier.isBlank()) {
			return prompt;
		}
		return "<earlier_chat>\nThe developer continues an earlier chat. The conversation so far, for context "
				+ "(tool results are not included; read again what you need):\n" + earlier + "\n</earlier_chat>\n\n"
				+ prompt;
	}

	/** Saves the chat in the background, unless the history is off or the chat is empty. UI thread. */
	private void saveChat() {
		saveChat(false);
	}

	/** @param now write before returning, e.g. when Eclipse closes and background jobs may not run any more */
	private void saveChat(boolean now) {
		BellaPlugin plugin = BellaPlugin.getDefault();
		ChatRecord chat = record;
		if (!plugin.keepsChatHistory() || chat.isEmpty()) {
			return;
		}
		AdtToolProvider.Scope scope = shownScope;
		chat.context(providerKey(), scope == null ? null : scope.pkg(), scope == null ? null : scope.transport(),
				mode.name());
		Conversation c = session == null ? null : session.unwrap();
		chat.modelHistory(exactHistory && c instanceof ChatSession cs ? cs.history() : List.of());
		ChatStore store = plugin.chatStore();
		int keep = plugin.prefs().getInt(Prefs.CHAT_HISTORY_MAX);
		Runnable save = () -> {
			try {
				store.save(chat);
				store.prune(keep);
			} catch (IOException | RuntimeException e) {
				BellaPlugin.log("Cannot save the chat", e);
			}
		};
		if (now) {
			save.run();
			return;
		}
		Job job = Job.create(Messages.get("chat.history.saving"), monitor -> {
			save.run();
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	/** Shows a saved chat and continues it. UI thread. */
	public void openChat(String id) {
		if (id.equals(record.id()) && running == null) {
			return;
		}
		Optional<ChatRecord> loaded = BellaPlugin.getDefault().chatStore().load(id);
		if (loaded.isEmpty()) {
			MessageDialog.openError(getSite().getShell(), Messages.get("chat.history.title"),
					Messages.get("chat.history.unreadable"));
			return;
		}
		resetChat();
		ChatRecord chat = loaded.get();
		record = chat;
		// fresh message ids: a turn of the chat before, still stopping, must not write into the shown one
		int offset = nextMessageId;
		nextMessageId = offset + chat.lastMessageId() + 1;
		replay(chat, offset);
		Conversation c = session.unwrap();
		if (c instanceof ChatSession cs && !chat.modelHistory().isEmpty() && chat.provider().equals(providerKey())) {
			cs.restore(chat.modelHistory());
			exactHistory = true;
		} else {
			earlierChat = chat.transcript(EARLIER_CHAT_CHARS);
			exactHistory = false;
		}
		String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
				.format(new java.util.Date(chat.updated()));
		js("notice(0,\"info\"," + str(Markdown.escape(Messages.fmt("chat.history.continued", when))) + ")");
		restoreMode(chat.mode());
		restoreScope(chat.devPackage(), chat.transport());
		updateStatus();
		input.setFocus();
	}

	/**
	 * Shows the entries of a saved chat with the page's own functions; {@code offset} is added to the saved
	 * message ids. UI thread.
	 */
	private void replay(ChatRecord chat, int offset) {
		js("showEmpty()");
		List<Integer> answers = new ArrayList<>();
		for (JsonObject e : chat.entries()) {
			int id = Json.integer(e, "id", 0) + offset;
			int seg = Json.integer(e, "seg", 0);
			String type = String.valueOf(Json.str(e, "t"));
			switch (type) {
			case "user" -> js("addUser(" + id + "," + str(nz(Json.str(e, "text"))) + ")");
			case "bella" -> {
				js("startAssistant(" + id + ")");
				answers.add(id);
				lastAnswerId = id;
			}
			case "text" -> showText(id, seg, renderText(nz(Json.str(e, "md"))));
			case "thinking" -> js("setThinking(" + id + "," + seg + "," + str(nz(Json.str(e, "text"))) + ")");
			case "tool" -> {
				String state = Json.str(e, "state");
				js("addTool(" + id + "," + seg + "," + str(nz(Json.str(e, "name"))) + ","
						+ str(nz(Json.str(e, "input"))) + ")");
				String shown = state == null ? "denied" : state;
				String label = Messages.get(state == null ? "chat.tool.stopped" : "chat.tool." + state);
				js("finishTool(" + id + "," + seg + "," + str(shown) + "," + str(label) + ","
						+ str(nz(Json.str(e, "output"))) + ")");
			}
			case "ask" -> {
				JsonObject questions = Json.obj(e, "questions");
				var answered = e.get("answers");
				js("askDone(" + str(id + "_" + seg) + "," + Json.GSON.toJson(questions == null ? new JsonObject()
						: questions) + "," + (answered == null ? "null" : Json.GSON.toJson(answered)) + ")");
			}
			case "notice" -> js("notice(" + id + "," + str(nz(Json.str(e, "kind"))) + ","
					+ str(nz(Json.str(e, "html"))) + ")");
			case "usage" -> countUsage(id, ChatRecord.usageOf(e));
			default -> {
				// an entry of a newer Bella
			}
			}
		}
		answers.forEach(id -> js("endAssistant(" + id + ")"));
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	/** The saved chat's mode; modes that write without asking are not taken over unasked. */
	private void restoreMode(String name) {
		ChatMode saved;
		try {
			saved = ChatMode.valueOf(name);
		} catch (IllegalArgumentException | NullPointerException e) {
			return;
		}
		if (MODES.contains(saved) && !saved.writesWithoutAsking()) {
			setMode(saved);
		}
	}

	/** Sets the saved chat's package and request again, with the checks of adt_dev_package. UI thread. */
	private void restoreScope(String pkg, String transport) {
		Optional<AdtToolProvider> tools = BellaPlugin.getDefault().adtTools();
		if (pkg == null || pkg.isBlank() || tools.isEmpty()) {
			return;
		}
		String request = transport == null || transport.isBlank() ? null : transport;
		int generation = chatGeneration;
		Job job = Job.create(Messages.get("chat.scope.jobName"), monitor -> {
			Optional<String> refused = tools.get().choose(pkg, request, CancelToken.NONE);
			String problem = null;
			boolean chosen = refused.isEmpty();
			if (refused.isPresent() && request != null) {
				// e.g. the request was released in the meantime: keep at least the package
				Optional<String> packageOnly = tools.get().choose(pkg, null, CancelToken.NONE);
				chosen = packageOnly.isEmpty();
				problem = chosen ? Messages.fmt("chat.history.requestLost", request, refused.get())
						: Messages.fmt("chat.history.scopeLost", pkg, packageOnly.get());
			} else if (refused.isPresent()) {
				problem = Messages.fmt("chat.history.scopeLost", pkg, refused.get());
			}
			showScopeProblem(generation, problem, chosen);
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	/** Job thread: after restoring package and request, a problem goes into the chat. */
	private void showScopeProblem(int generation, String problem, boolean chosen) {
		Display.getDefault().asyncExec(() -> {
			if (generation != chatGeneration || browser == null || browser.isDisposed()) {
				return;
			}
			if (chosen) {
				scopeChosen = true;
			}
			if (problem != null) {
				js("notice(0,\"warn\"," + str(Markdown.escape(problem)) + ")");
			}
			if (scopeJob != null) {
				scopeJob.schedule();
			}
		});
	}

	/** The last chats and "All chats…" below {@code control}. UI thread. */
	private void showHistoryMenu(org.eclipse.swt.widgets.Control control) {
		org.eclipse.swt.widgets.Menu menu = new org.eclipse.swt.widgets.Menu(control.getShell(), SWT.POP_UP);
		List<ChatStore.Summary> chats = BellaPlugin.getDefault().chatStore().list();
		if (chats.isEmpty()) {
			org.eclipse.swt.widgets.MenuItem none = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH);
			none.setText(Messages.get("chat.history.empty"));
			none.setEnabled(false);
		}
		for (ChatStore.Summary c : chats.subList(0, Math.min(MENU_CHATS, chats.size()))) {
			org.eclipse.swt.widgets.MenuItem item = new org.eclipse.swt.widgets.MenuItem(menu, SWT.CHECK);
			item.setText(menuText(c));
			item.setSelection(c.id().equals(record.id()));
			item.addListener(SWT.Selection, e -> openChat(c.id()));
		}
		new org.eclipse.swt.widgets.MenuItem(menu, SWT.SEPARATOR);
		org.eclipse.swt.widgets.MenuItem all = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH);
		all.setText(Messages.get("chat.history.all"));
		all.addListener(SWT.Selection, e -> openHistoryDialog());
		if (!BellaPlugin.getDefault().keepsChatHistory()) {
			org.eclipse.swt.widgets.MenuItem off = new org.eclipse.swt.widgets.MenuItem(menu, SWT.PUSH);
			off.setText(Messages.get("chat.history.off"));
			off.setEnabled(false);
		}
		org.eclipse.swt.graphics.Rectangle b = control.getBounds();
		menu.setLocation(control.getParent().toDisplay(b.x, b.y + b.height));
		menu.setVisible(true);
	}

	/** Title and age; {@code &} would be taken for a mnemonic. */
	static String menuText(ChatStore.Summary c) {
		String title = c.title().isBlank() ? Messages.get("chat.history.untitled") : c.title();
		return title.replace("&", "&&") + "\t" + ChatHistoryDialog.age(c.updated(), System.currentTimeMillis());
	}

	private void openHistoryDialog() {
		ChatHistoryDialog d = new ChatHistoryDialog(getSite().getShell(), BellaPlugin.getDefault().chatStore(),
				record.id());
		if (d.open() == ChatHistoryDialog.OK && d.result() != null) {
			openChat(d.result());
		}
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
		if (!record.title().isEmpty()) {
			text = ChatHistoryDialog.clip(record.title(), 32) + " · " + text;
		}
		if (planActive || turnOverride == ChatMode.PLAN) {
			text += " · " + Messages.get("chat.plan.button");
		} else if (mode != ChatMode.NORMAL) {
			text += " · " + Messages.get(modeKey(mode));
		}
		boolean showUsage = plugin.prefs().getBoolean(Prefs.SHOW_USAGE);
		js("showUsage(" + showUsage + ")");
		String tip = null;
		if (showUsage && !chatTokens.isEmpty()) {
			text += " · " + Messages.fmt("chat.usage.status", compact(chatTokens.promptTokens()
					+ chatTokens.outputTokens()));
			tip = Messages.fmt("chat.usage.chat", chatTokens.promptTokens(), chatTokens.cacheReadTokens(),
					chatTokens.outputTokens());
			if (lastUsage != null && lastUsage.contextPercent() >= 0) {
				text += " · " + Messages.fmt("chat.usage.statusContext", lastUsage.contextPercent());
			}
			if (lastUsage != null && lastUsage.contextTokens() > 0) {
				tip += "\n" + contextText(lastUsage);
			}
		}
		status.setText(text);
		status.setToolTipText(tip);
		status.getParent().layout();
	}

	/** Adds the tokens of an answer of the shown chat and shows them below it. UI thread. */
	private void countUsage(int id, TurnUsage u) {
		chatTokens = chatTokens.plus(u.total());
		if (u.contextTokens() > 0) {
			lastUsage = u;
		}
		showUsage(id, u);
	}

	private void showUsage(int id, TurnUsage u) {
		js("usage(" + id + "," + str(usageText(u)) + ")");
	}

	/** The line below an answer: tokens in and out, and how full the context window is. */
	static String usageText(TurnUsage u) {
		Usage t = u.total();
		String text = Messages.fmt("chat.usage.turn", t.promptTokens(), t.outputTokens());
		if (t.cacheReadTokens() > 0) {
			text += " " + Messages.fmt("chat.usage.cached", t.cacheReadTokens());
		}
		if (u.contextTokens() > 0) {
			text += " · " + contextText(u);
		}
		return text;
	}

	private static String contextText(TurnUsage u) {
		return u.contextPercent() >= 0
				? Messages.fmt("chat.usage.context", u.contextTokens(), u.contextWindow(), u.contextPercent())
				: Messages.fmt("chat.usage.contextNoWindow", u.contextTokens());
	}

	/** A token count for the status line, e.g. 1.2M. */
	static String compact(int tokens) {
		NumberFormat f = NumberFormat.getCompactNumberInstance(Messages.locale(), NumberFormat.Style.SHORT);
		f.setMaximumFractionDigits(1);
		return f.format(tokens);
	}

	private static IEditorPart activeEditor() {
		return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActiveEditor();
	}

	/**
	 * Working on an ABAP object in an editor binds the chat to its package
	 * (see {@code DevScope}), so its customer objects can be read. Editor
	 * actions call this before {@link #ask(String, String)}; {@code null} or
	 * a non-ABAP editor binds nothing.
	 */
	public static void bindEditor(IEditorPart part) {
		EditorBridge.adtObject(part).ifPresent(BellaPlugin.getDefault().devScope()::editorObject);
	}

	/** {@link #bindEditor} for the active editor. UI thread. */
	public static void bindActiveEditor() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		bindEditor(page == null ? null : page.getActiveEditor());
	}

	@Override
	public void setFocus() {
		input.setFocus();
		refreshScope(); // e.g. logged on to the system in the meantime
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
		// Enter in the field at once, a package from the list once the developer stopped scrolling through it
		packageChoice.addListener(SWT.DefaultSelection, e -> {
			cancelPendingChoice();
			choosePackage(packageChoice.getText());
		});
		packageChoice.addListener(SWT.Selection, e -> chooseLater(() -> choosePackage(packageChoice.getText())));
		packageSearch = new Button(scopeBar, SWT.PUSH);
		packageSearch.setText("…");
		packageSearch.setToolTipText(Messages.get("chat.scope.searchTip"));
		packageSearch.addListener(SWT.Selection, e -> searchPackage());
		Label trLabel = new Label(scopeBar, SWT.NONE);
		trLabel.setText(Messages.get("chat.scope.transport"));
		transportChoice = new Combo(scopeBar, SWT.READ_ONLY | SWT.DROP_DOWN);
		transportChoice.setToolTipText(Messages.get("chat.scope.transportTip"));
		GridDataFactory.fillDefaults().grab(true, false).hint(160, SWT.DEFAULT).applyTo(transportChoice);
		transportChoice.addListener(SWT.Selection, e -> chooseLater(this::chooseTransport));
		scopeJob = Job.create(Messages.get("chat.scope.jobName"), monitor -> {
			readScope();
			return Status.OK_STATUS;
		});
		scopeJob.setSystem(true);
		showScope(null, List.of(), null, null);
	}

	/** Job thread: package, request and, for a transported package, the developer's open requests. */
	private void readScope() {
		Optional<AdtToolProvider> tools = BellaPlugin.getDefault().adtTools();
		AdtToolProvider.Scope scope = null;
		List<AdtTransportRequest> requests = List.of();
		String problem = null;
		String requestsProblem = null;
		if (tools.isEmpty()) {
			problem = Messages.get("chat.scope.noAdt");
		} else {
			try {
				scope = tools.get().scope(CancelToken.NONE);
			} catch (IOException | RuntimeException e) {
				problem = message(e);
			}
			if (scope != null && scope.pkg() != null && !scope.local()) {
				try {
					requests = openRequests(tools.get(), scope.system());
				} catch (IOException | RuntimeException e) {
					requestsProblem = message(e);
				}
			}
		}
		AdtToolProvider.Scope s = scope;
		List<AdtTransportRequest> r = requests;
		String p = problem;
		String rp = requestsProblem;
		Display.getDefault().asyncExec(() -> showScope(s, r, p, rp));
	}

	/** The developer's open requests on {@code system}, read again only after a while; job thread. */
	private List<AdtTransportRequest> openRequests(AdtToolProvider tools, String system) throws IOException {
		if (!system.equals(requestsSystem) || System.currentTimeMillis() - requestsAt > REQUESTS_TTL_MILLIS) {
			cachedRequests = tools.openTransports(CancelToken.NONE);
			requestsSystem = system;
			requestsAt = System.currentTimeMillis();
		}
		return cachedRequests;
	}

	private static String message(Exception e) {
		return e.getMessage() == null ? e.toString() : e.getMessage();
	}

	/** Reads package and request again, e.g. after the developer logged on. UI thread. */
	public void refreshScope() {
		Job j = scopeJob;
		if (j != null) {
			j.schedule();
		}
	}

	/**
	 * UI thread. {@code scope} is {@code null} when it cannot be read;
	 * {@code problem} says why, {@code requestsProblem} why the open requests
	 * could not be read.
	 */
	private void showScope(AdtToolProvider.Scope scope, List<AdtTransportRequest> requests, String problem,
			String requestsProblem) {
		if (scopeBar == null || scopeBar.isDisposed()) {
			return;
		}
		String before = shownScope == null || shownScope.pkg() == null ? "" : shownScope.pkg();
		shownScope = scope;
		String pkg = scope == null || scope.pkg() == null ? "" : scope.pkg();
		String typed = packageChoice.getText().trim();
		packageChoice.setItems(packageHistory().toArray(String[]::new));
		// a name the developer is still typing stays; a refresh (e.g. on activating the view) must not wipe it
		boolean editing = packageChoice.isFocusControl() && !typed.isEmpty() && !typed.equalsIgnoreCase(before);
		packageChoice.setText(editing ? typed : pkg);
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
				transportChoice.add(Messages.get(requestsProblem == null ? "chat.scope.chooseTransport"
						: "chat.scope.requestsFailed"));
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
		transportChoice.setToolTipText(requestsProblem == null ? Messages.get("chat.scope.transportTip")
				: Messages.fmt("chat.scope.requestsFailedTip", requestsProblem));
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

	/** Runs {@code choice} once the drop-down has rested for {@link #CHOICE_DELAY_MILLIS}. UI thread. */
	private void chooseLater(Runnable choice) {
		cancelPendingChoice();
		pendingChoice = () -> {
			pendingChoice = null;
			if (scopeBar != null && !scopeBar.isDisposed()) {
				choice.run();
			}
		};
		Display.getCurrent().timerExec(CHOICE_DELAY_MILLIS, pendingChoice);
	}

	private void cancelPendingChoice() {
		if (pendingChoice != null) {
			Display.getCurrent().timerExec(-1, pendingChoice);
			pendingChoice = null;
		}
	}

	private void chooseTransport() {
		int i = transportChoice.getSelectionIndex();
		if (i >= 0 && i < transportIds.size() && !transportIds.get(i).isEmpty()
				&& (shownScope == null || !transportIds.get(i).equals(shownScope.transport()))) {
			choose(null, transportIds.get(i));
		}
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
		if (tools.isEmpty() || running != null) {
			return; // e.g. a delayed drop-down choice after Send: the scope stays as the turn found it
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
					scopeChosen = true; // the refresh below puts the package into the history
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
				bindEditor(part);
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
			// provider switched in the preferences: a new session, which reads the chat so far with the next message
			newSession();
			if (!record.isEmpty()) {
				earlierChat = record.transcript(EARLIER_CHAT_CHARS);
				exactHistory = false;
			}
			updateStatus();
		}
		int userId = nextMessageId++;
		int botId = nextMessageId++;
		js("addUser(" + userId + "," + str(display) + ")");
		js("startAssistant(" + botId + ")");
		ChatRecord chat = record;
		chat.user(userId, display);
		chat.answer(botId);
		CancelToken cancel = new CancelToken();
		ChatMode turnMode = planning ? ChatMode.PLAN : mode;
		turnOverride = planning ? ChatMode.PLAN : null;
		lastAnswerId = botId;
		AtomicBoolean answered = new AtomicBoolean();
		running = cancel;
		setBusy(true);
		updateStatus();
		Renderer renderer = new Renderer(botId, chat);
		this.renderer = renderer;
		String earlier = earlierChat;
		String sent = withEarlierChat(withScopeNote(prompt), earlier);
		int generation = chatGeneration;
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
					if (this.renderer == renderer) {
						this.renderer = null;
					}
					closeQuestions();
					running = null;
					turnOverride = null;
					if (generation != chatGeneration) {
						// New chat during this turn: a tool still running may have set package or request after
						// the reset; nothing of the new chat could, since the bar and Send wait for this turn
						BellaPlugin.getDefault().devScope().reset();
					}
					setBusy(false);
					updateStatus();
					refreshScope();
					if (planning && answered.get()) {
						showPlanBar(true);
					}
					if (answered.get() && earlier != null && earlierChat == earlier) {
						earlierChat = null; // the model knows the chat now
					}
					if (generation == chatGeneration) {
						saveChat();
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
		closeQuestions();
	}

	// ---- questions with options (ask_user) -------------------------------------------------

	/**
	 * Shows the questions of {@code ask_user} as a menu in the running answer; {@code answer} completes when the
	 * developer answers or dismisses them. UI thread.
	 *
	 * @return {@code false} if there is no running answer or a menu is already open
	 */
	boolean showQuestions(List<AskUserToolProvider.Question> questions,
			CompletableFuture<Optional<List<AskUserToolProvider.Answer>>> answer) {
		Renderer r = renderer;
		if (r == null || browser == null || browser.isDisposed() || askAnswer != null) {
			return false;
		}
		int seg = r.questionSegment();
		askKey = r.id + "_" + seg;
		askMessage = r.id;
		askSegment = seg;
		askQuestions = questions;
		askAnswer = answer;
		js("askUser(" + r.id + "," + seg + "," + Json.GSON.toJson(AskUserToolProvider.toJson(questions)) + ")");
		browser.setFocus();
		return true;
	}

	/** Closes an open question menu as not answered, e.g. on Stop. UI thread. */
	void closeQuestions() {
		CompletableFuture<Optional<List<AskUserToolProvider.Answer>>> f = askAnswer;
		if (f == null) {
			return;
		}
		recordQuestions(Optional.empty());
		askAnswer = null;
		askKey = null;
		askQuestions = null;
		f.complete(Optional.empty());
		js("closeAsks()");
	}

	private void onAnswer(String key, String json) {
		CompletableFuture<Optional<List<AskUserToolProvider.Answer>>> f = askAnswer;
		if (f == null || !key.equals(askKey)) {
			return;
		}
		Optional<List<AskUserToolProvider.Answer>> answers;
		try {
			answers = AskUserToolProvider.answersFromJson(Json.parseStrict(json));
		} catch (RuntimeException e) {
			answers = Optional.empty();
		}
		recordQuestions(answers);
		askAnswer = null;
		askKey = null;
		askQuestions = null;
		f.complete(answers);
		input.setFocus();
	}

	private void recordQuestions(Optional<List<AskUserToolProvider.Answer>> answers) {
		if (askQuestions != null) {
			record.questions(askMessage, askSegment, AskUserToolProvider.toJson(askQuestions),
					AskUserToolProvider.answersToJson(answers));
		}
	}

	@Override
	public void dispose() {
		saveChat(true);
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
		/** The chat this answer belongs to, for the chat history. */
		private final ChatRecord chat;

		Renderer(int id, ChatRecord chat) {
			this.id = id;
			this.chat = chat;
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
			if (AskUserToolProvider.TOOL.equals(tool.name())) {
				return; // the questions show as a menu in this segment, see showQuestions
			}
			String in = abbreviate(Json.PRETTY.toJson(call.input()), 4000);
			chat.tool(id, seg, tool.name(), in, null, null);
			ui(() -> js("addTool(" + id + "," + seg + "," + str(tool.name()) + "," + str(in) + ")"));
		}

		@Override
		public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
			int seg;
			synchronized (this) {
				seg = segment;
			}
			if (AskUserToolProvider.TOOL.equals(tool.name()) && !result.isError()) {
				return; // the answered menu stays in its place
			}
			if (AskUserToolProvider.TOOL.equals(tool.name())) {
				String err = abbreviate(result.content() == null ? "" : result.content(), 6000);
				chat.tool(id, seg, tool.name(), "", "error", err);
				ui(() -> {
					js("addTool(" + id + "," + seg + "," + str(tool.name()) + ",\"\")");
					js("finishTool(" + id + "," + seg + ",\"error\"," + str(Messages.get("chat.tool.error")) + ","
							+ str(err) + ")");
				});
				return;
			}
			String out = abbreviate(result.content() == null ? "" : result.content(), 6000);
			boolean denied = result.isError() && result.content() != null
					&& (result.content().contains("declined") || result.content().contains("Refused"));
			String state = !result.isError() ? "ok" : denied ? "denied" : "error";
			String label = Messages.get("chat.tool." + state);
			chat.tool(id, seg, tool.name(), abbreviate(Json.PRETTY.toJson(call.input()), 4000), state, out);
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
		public void onUsage(TurnUsage usage) {
			chat.usage(id, usage);
			ui(() -> {
				if (chat == record) {
					countUsage(id, usage);
					updateStatus();
				}
			});
		}

		@Override
		public void onTurnEnd(ChatResult last) {
			flush();
		}

		void notice(String kind, String html) {
			flush();
			chat.notice(id, kind, html);
			ui(() -> js("notice(" + id + "," + str(kind) + "," + str(html) + ")"));
		}

		/** The segment of the running {@code ask_user} call, where its menu goes. */
		synchronized int questionSegment() {
			return segment;
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
				chat.thinking(id, seg, content);
				ui(() -> js("setThinking(" + id + "," + seg + "," + str(content) + ")"));
			} else if (kind.equals("text")) {
				chat.text(id, seg, content);
				Markdown.Rendered r = renderText(content);
				ui(() -> showText(id, seg, r));
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

	private static Markdown.Rendered renderText(String markdown) {
		return Markdown.render(markdown,
				(i, lang) -> "<div class=\"code-bar\" data-lang=\"" + Markdown.escape(lang) + "\"></div>");
	}

	/** A text segment of an answer, with the code blocks its buttons insert. UI thread. */
	private void showText(int id, int seg, Markdown.Rendered r) {
		codeBlocks.put(id + "_" + seg, r.codeBlocks());
		js("setText(" + id + "," + seg + "," + str(r.html()) + ")");
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

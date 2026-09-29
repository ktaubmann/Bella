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
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
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
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
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

/** Bella's chat window. */
public class ChatView extends ViewPart {

	public static final String ID = "de.kiliantaubmann.bella.ui.chatView";

	private Browser browser;
	private Text input;
	private Button send;
	private Button stop;
	private Button withContext;
	private Label status;
	private Conversation session;
	private volatile CancelToken running;
	private int nextMessageId = 1;
	/** "messageId_segment" → code blocks of that text segment. */
	private final Map<String, List<String>> codeBlocks = new HashMap<>();
	/** Scripts issued before the page finished loading. */
	private final List<String> pendingScripts = new ArrayList<>();
	private boolean pageLoaded;

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
		GridDataFactory.fillDefaults().span(2, 1).applyTo(new Label(bottom, SWT.NONE));
		withContext = new Button(bottom, SWT.CHECK);
		withContext.setText(Messages.get("chat.withContext"));
		withContext.setToolTipText(Messages.get("chat.withContextTip"));
		withContext.setSelection(true);
		status = new Label(bottom, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(status);

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
		newSession();
		updateStatus();
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
		session = plugin.newConversation(this::confirmTool, new OpenEditorRouter());
	}

	private void newChat() {
		cancel();
		newSession();
		codeBlocks.clear();
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
		status.setText(Messages.fmt("chat.status", plugin.chatModelLabel(), system.isEmpty() ? "–" : system));
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

	// ---- sending ------------------------------------------------------------------------

	private void sendFromInput() {
		String text = input.getText().trim();
		if (text.isEmpty() || running != null) {
			return;
		}
		input.setText("");
		String prompt = text;
		if (withContext.getSelection()) {
			IEditorPart part = activeEditor();
			Optional<ITextEditor> editor = EditorBridge.textEditor(part);
			if (editor.isPresent()) {
				prompt = withEditorContext(text, EditorBridge.context(part, editor.get()));
			}
		}
		ask(text, prompt);
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
		if (running != null) {
			return;
		}
		if (!BellaPlugin.getDefault().conversationType().isInstance(session)) {
			newSession(); // provider switched in the preferences: the old chat cannot continue
			updateStatus();
		}
		int userId = nextMessageId++;
		int botId = nextMessageId++;
		js("addUser(" + userId + "," + str(display) + ")");
		js("startAssistant(" + botId + ")");
		CancelToken cancel = new CancelToken();
		running = cancel;
		setBusy(true);
		Renderer renderer = new Renderer(botId);
		Job job = Job.create(Messages.get("chat.jobName"), (IProgressMonitor monitor) -> {
			try {
				BellaPlugin.getDefault().tools().refresh(err -> renderer.notice("warn", Markdown.escape(err)));
				session.ask(prompt, renderer, cancel);
			} catch (CancelToken.CancelledException e) {
				renderer.notice("warn", Messages.get("chat.cancelled"));
			} catch (Exception e) {
				renderer.notice("error", Markdown.escape(e.getMessage() == null ? e.toString() : e.getMessage()));
			} finally {
				renderer.flush();
				Display.getDefault().asyncExec(() -> {
					js("endAssistant(" + botId + ")");
					running = null;
					setBusy(false);
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
		stop.setEnabled(busy);
	}

	private void cancel() {
		CancelToken c = running;
		if (c != null) {
			c.cancel();
		}
	}

	@Override
	public void dispose() {
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
	private final class Renderer implements ConversationListener {
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
			String key = "chat.notice." + (colon < 0 ? message : message.substring(0, colon));
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

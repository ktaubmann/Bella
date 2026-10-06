package de.kiliantaubmann.bella.ui.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.agent.Conversation;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeProvider;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeSession;
import de.kiliantaubmann.bella.core.copilot.CopilotProvider;
import de.kiliantaubmann.bella.core.copilot.CopilotSession;
import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ChatMode;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.Shortcuts;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.internal.BellaMenuItems;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.editor.OpenEditorRouter;
import de.kiliantaubmann.bella.ui.prefs.BellaPreferencePage;
import de.kiliantaubmann.bella.ui.prefs.ConventionsPreferencePage;
import de.kiliantaubmann.bella.ui.prefs.LogPreferencePage;
import de.kiliantaubmann.bella.ui.prefs.MaskingPreferencePage;
import de.kiliantaubmann.bella.ui.prefs.Prefs;
import de.kiliantaubmann.bella.ui.prefs.ToolsPreferencePage;
import de.kiliantaubmann.bella.ui.views.ChatView;

/** Starts a real workbench and exercises Bella's UI without a model or SAP system. */
class WorkbenchSmokeTest {

	private static final String SOURCE = """
			CLASS zcl_demo DEFINITION PUBLIC FINAL CREATE PUBLIC.
			  PUBLIC SECTION.
			    METHODS total RETURNING VALUE(rv) TYPE i.
			ENDCLASS.

			CLASS zcl_demo IMPLEMENTATION.
			  METHOD total.
			    rv = 0.
			  ENDMETHOD.
			ENDCLASS.
			""";

	private static IWorkbenchPage page() {
		return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
	}

	private static void pump() {
		Display d = Display.getCurrent();
		while (d.readAndDispatch()) {
			// process pending UI events
		}
	}

	private static IEditorPart openDemoEditor() throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("bella-smoke");
		if (!project.exists()) {
			project.create(null);
		}
		project.open(null);
		IFile file = project.getFile("ZCL_DEMO.txt");
		if (file.exists()) {
			file.setContents(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true, false, null);
		} else {
			file.create(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true, null);
		}
		return IDE.openEditor(page(), file, "org.eclipse.ui.DefaultTextEditor");
	}

	@AfterEach
	void closeEditors() {
		page().closeAllEditors(false);
	}

	@Test
	void pluginStartsAndCommandsAreDefined() {
		assertNotNull(BellaPlugin.getDefault());
		ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
		for (String id : List.of("openChat", "explain", "refactor", "unitTest", "generate", "rewrite",
				"implementMethod", "complete", "atcFix", "reviewTransport", "reviewCode")) {
			Command c = commands.getCommand("de.kiliantaubmann.bella.ui." + id);
			assertTrue(c.isDefined(), id);
		}
		assertNotNull(BellaPlugin.image(BellaPlugin.IMG_BELLA));
		assertFalse(Messages.get("chat.send").startsWith("!"));
	}

	@Test
	void chatViewOpens() {
		ChatView view = ChatView.open().orElseThrow();
		pump();
		assertNotNull(view);
		page().hideView(view);
	}

	@Test
	void chatHasPlanModeAndGodmode() {
		ChatView view = ChatView.open().orElseThrow();
		pump();
		List<Button> buttons = find(view.getSite().getShell(), Button.class);
		Button plan = buttons.stream().filter(b -> b.getText().equals(Messages.get("chat.planMode"))).findFirst()
				.orElseThrow();
		Button god = buttons.stream().filter(b -> b.getText().equals(Messages.get("chat.godMode"))).findFirst()
				.orElseThrow();
		assertEquals(ChatMode.NORMAL, view.mode());
		assertFalse(god.getSelection(), "Godmode is never on when the chat opens");
		plan.setSelection(true);
		plan.notifyListeners(SWT.Selection, new Event());
		assertEquals(ChatMode.PLAN, view.mode());
		plan.setSelection(false);
		plan.notifyListeners(SWT.Selection, new Event());
		assertEquals(ChatMode.NORMAL, view.mode());
		page().hideView(view);
	}

	@Test
	void preferencePagesRender() {
		Shell shell = new Shell(Display.getCurrent());
		try {
			for (PreferencePage p : List.<PreferencePage>of(new BellaPreferencePage(), new ToolsPreferencePage(),
					new ConventionsPreferencePage(), new MaskingPreferencePage(), new LogPreferencePage())) {
				((org.eclipse.ui.IWorkbenchPreferencePage) p).init(PlatformUI.getWorkbench());
				p.createControl(shell);
				assertTrue(p.performOk());
			}
		} finally {
			shell.dispose();
		}
	}

	@Test
	void maskingIsOnByDefaultAndRoundTrips() {
		var masker = BellaPlugin.getDefault().masker();
		assertTrue(masker.active());
		String masked = masker.mask("Fix ZCL_SMOKE_SECRET");
		assertFalse(masked.contains("SMOKE"), masked);
		assertEquals("Fix ZCL_SMOKE_SECRET", masker.unmask(masked));
	}

	@Test
	void conventionsAreSavedPerScopeAndCheckedInThePreview() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		Shell shell = new Shell(Display.getCurrent());
		try {
			ConventionsPreferencePage page = new ConventionsPreferencePage();
			page.init(PlatformUI.getWorkbench());
			page.createControl(shell);
			List<Text> areas = find((Composite) page.getControl(), Text.class);
			areas.get(0).setText("Packages: ZSD_* per process.");
			areas.get(1).setText("local_data = lv_*");
			assertTrue(page.performOk());
			areas.get(1).setText("nonsense = x");
			assertFalse(page.isValid(), "invalid rules block saving");
			assertFalse(page.performOk());
		} finally {
			shell.dispose();
		}
		try {
			assertEquals("Packages: ZSD_* per process.", plugin.conventions(null).text());
			assertEquals("local_data = lv_*\n", plugin.conventions("unknown-destination").naming().describe());
			assertTrue(plugin.prompts().chatSystem().contains("Packages: ZSD_* per process."));
			List<AbapLint.Finding> f = AbapLint.check("METHOD m.\n  DATA count TYPE i.\nENDMETHOD.",
					plugin.conventions(null).naming());
			assertTrue(CodeActions.previewNotes(List.of(), f).contains("naming: local data \"count\" should match lv_*"),
					CodeActions.previewNotes(List.of(), f));
		} finally {
			plugin.prefs().setValue(Prefs.CONVENTIONS_TEXT + Prefs.CONVENTIONS_GLOBAL, "");
			plugin.prefs().setValue(Prefs.CONVENTIONS_NAMING + Prefs.CONVENTIONS_GLOBAL, "");
		}
	}

	@Test
	void implementsMethodIntoEditorWithoutSaving() throws Exception {
		BellaPlugin.getDefault().prefs().setValue(Prefs.DIFF_PREVIEW, false);
		IEditorPart part = openDemoEditor();
		ITextEditor editor = EditorBridge.textEditor(part).orElseThrow();
		IDocument doc = EditorBridge.document(editor);
		editor.selectAndReveal(doc.get().indexOf("rv = 0."), 0);
		CodeActions.apply(part, editor, CodeActions.Target.METHOD, "rv = 42.");
		pump();
		assertTrue(doc.get().contains("  METHOD total.\n    rv = 42.\n  ENDMETHOD."), doc.get());
		assertTrue(editor.isDirty(), "the change stays unsaved in the editor");
	}

	@Test
	void atcFixReplacesTheWholeSourceWithoutSaving() throws Exception {
		BellaPlugin.getDefault().prefs().setValue(Prefs.DIFF_PREVIEW, false);
		IEditorPart part = openDemoEditor();
		ITextEditor editor = EditorBridge.textEditor(part).orElseThrow();
		IDocument doc = EditorBridge.document(editor);
		String fixed = doc.get().replace("rv = 0.", "rv = 1.").stripTrailing();
		CodeActions.apply(part, editor, CodeActions.Target.DOCUMENT, fixed);
		pump();
		assertTrue(doc.get().contains("rv = 1."), doc.get());
		assertTrue(doc.get().endsWith("\n"), "the final line break is kept");
		assertTrue(editor.isDirty(), "the change stays unsaved in the editor");
		assertFalse(Messages.get("atc.fix").startsWith("!"));
	}

	@Test
	void styleCheckIsRegisteredAndFeedsTheDiffPreview() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		plugin.tools().refresh(e -> {
		});
		ToolSpec lint = plugin.tools().find("abap_lint").orElseThrow();
		assertEquals(ToolSpec.Kind.READ, lint.kind());
		assertTrue(plugin.prefs().getDefaultBoolean(Prefs.EDITOR_SAP_CONTEXT));
		String notes = CodeActions.previewNotes(List.of("MARA", "ZCL_LOG"),
				AbapLint.check("SELECT * FROM mara WHERE matnr IN @r INTO TABLE @DATA(lt).\nBREAK-POINT."));
		assertTrue(notes.contains(Messages.fmt("diff.definitions", "MARA, ZCL_LOG")), notes);
		assertTrue(notes.contains(Messages.fmt("diff.lint", 2)), notes);
		assertTrue(notes.contains("Line 1 [warning] select_star"), notes);
		assertEquals("", CodeActions.previewNotes(List.of(), List.of()));
	}

	@Test
	void logFileCanBeSwitchedOnAndOff() throws Exception {
		BellaPlugin plugin = BellaPlugin.getDefault();
		IPreferenceStore prefs = plugin.prefs();
		assertFalse(prefs.getDefaultBoolean(Prefs.LOG_ENABLED), "logging is off by default");
		assertFalse(prefs.getDefaultBoolean(Prefs.LOG_DETAIL));
		java.nio.file.Path file = plugin.logFile().path();
		try {
			plugin.clearLog();
			prefs.setValue(Prefs.LOG_ENABLED, true);
			Log.info("test", "hello with x-api-key: sk-ant-secret123456");
			Log.debug("test", () -> "detail entry");
			String text = java.nio.file.Files.readString(file);
			assertTrue(text.contains("===== Bella " + BellaPlugin.VERSION + " log started"), text);
			assertTrue(text.contains("Provider: "), text);
			assertTrue(text.contains("INFO  [test] hello with x-api-key: ***"), text);
			assertFalse(text.contains("secret123456"), text);
			assertFalse(text.contains("detail entry"), text);

			prefs.setValue(Prefs.LOG_DETAIL, true);
			Log.debug("test", () -> "detail entry");
			assertTrue(java.nio.file.Files.readString(file).contains("DEBUG [test] detail entry"));

			plugin.clearLog();
			text = java.nio.file.Files.readString(file);
			assertFalse(text.contains("hello"), text);
			assertTrue(text.contains("log started"), text);

			prefs.setValue(Prefs.LOG_ENABLED, false);
			Log.info("test", "after switching off");
			assertFalse(java.nio.file.Files.readString(file).contains("after switching off"));
			assertFalse(Log.enabled(Log.Level.ERROR));
		} finally {
			prefs.setToDefault(Prefs.LOG_DETAIL);
			prefs.setToDefault(Prefs.LOG_ENABLED);
			plugin.clearLog();
		}
	}

	@Test
	void routerWritesSourceIntoOpenEditor() throws Exception {
		BellaPlugin.getDefault().prefs().setValue(Prefs.DIFF_PREVIEW, false);
		IEditorPart part = openDemoEditor();
		ToolSpec write = ToolSpec.of("adt_write_source", "", new JsonObject(), Capability.WRITE_SOURCE,
				ToolSpec.Kind.WRITE);
		JsonObject input = new JsonObject();
		input.addProperty("name", "ZCL_DEMO");
		input.addProperty("source", SOURCE.replace("rv = 0.", "rv = 7."));
		ToolResult result = new OpenEditorRouter().intercept(write, input).orElseThrow();
		pump();
		assertFalse(result.isError(), result.content());
		ITextEditor editor = EditorBridge.textEditor(part).orElseThrow();
		assertTrue(EditorBridge.document(editor).get().contains("rv = 7."));
		assertTrue(editor.isDirty());
		JsonObject other = new JsonObject();
		other.addProperty("name", "ZCL_OTHER");
		other.addProperty("source", "x");
		assertTrue(new OpenEditorRouter().intercept(write, other).isEmpty(), "closed objects are not intercepted");

		JsonObject method = new JsonObject();
		method.addProperty("name", "ZCL_DEMO");
		method.addProperty("method", "total");
		method.addProperty("source", "rv = 8.");
		ToolResult m = new OpenEditorRouter().intercept(write, method).orElseThrow();
		pump();
		assertFalse(m.isError(), m.content());
		assertTrue(EditorBridge.document(editor).get().contains("  METHOD total.\n    rv = 8.\n  ENDMETHOD."),
				EditorBridge.document(editor).get());
	}

	private static <T extends Control> List<T> find(Composite root, Class<T> type) {
		List<T> out = new ArrayList<>();
		for (Control c : root.getChildren()) {
			if (type.isInstance(c)) {
				out.add(type.cast(c));
			}
			if (c instanceof Composite comp) {
				out.addAll(find(comp, type));
			}
		}
		return out;
	}

	@Test
	void providerDropdownOffersTheSubscriptionWithCompletionHint() {
		IPreferenceStore prefs = BellaPlugin.getDefault().prefs();
		prefs.setValue(Prefs.UI_LANGUAGE, "en");
		Shell shell = new Shell(Display.getCurrent());
		try {
			BellaPreferencePage page = new BellaPreferencePage();
			page.init(PlatformUI.getWorkbench());
			page.createControl(shell);
			Composite root = (Composite) page.getControl();
			Combo provider = find(root, Combo.class).stream()
					.filter(c -> List.of(c.getItems()).contains(Messages.get("prefs.provider.claudeCode"))).findFirst()
					.orElseThrow();
			assertEquals(List.of(Messages.get("prefs.provider.anthropic"), Messages.get("prefs.provider.claudeCode"),
					Messages.get("prefs.provider.copilot"), Messages.get("prefs.provider.openai")),
					List.of(provider.getItems()));
			Group copilotGroup = find(root, Group.class).stream()
					.filter(g -> g.getText().equals(Messages.get("prefs.cp"))).findFirst().orElseThrow();
			Group subscription = find(root, Group.class).stream()
					.filter(g -> g.getText().equals(Messages.get("prefs.cc"))).findFirst().orElseThrow();
			Group apiKey = find(root, Group.class).stream()
					.filter(g -> g.getText().equals(Messages.get("prefs.claude"))).findFirst().orElseThrow();
			Button auto = find(root, Button.class).stream()
					.filter(b -> b.getText().equals(Messages.get("prefs.autoCompletion"))).findFirst().orElseThrow();

			provider.select(1);
			provider.notifyListeners(SWT.Selection, new Event());
			assertTrue(subscription.getVisible());
			assertFalse(apiKey.getVisible());
			assertFalse(auto.getEnabled(), "automatic suggestions are off with the subscription");
			String shortcut = Shortcuts.completion();
			List<String> hints = find(root, Label.class).stream().map(Label::getText)
					.filter(t -> t.contains("2–5")).toList();
			assertEquals(2, hints.size(), "hint under the dropdown and in the editor group");
			assertTrue(hints.get(0).contains(shortcut), hints.get(0));

			provider.select(2);
			provider.notifyListeners(SWT.Selection, new Event());
			assertTrue(copilotGroup.getVisible());
			assertFalse(subscription.getVisible());
			assertFalse(auto.getEnabled(), "automatic suggestions are off with GitHub Copilot");
			assertTrue(find(root, Label.class).stream().map(Label::getText)
					.anyMatch(t -> t.contains("premium request") && t.contains(shortcut)));

			provider.select(0);
			provider.notifyListeners(SWT.Selection, new Event());
			assertFalse(copilotGroup.getVisible());
			assertFalse(subscription.getVisible());
			assertTrue(apiKey.getVisible());
			assertTrue(auto.getEnabled());
			assertTrue(find(root, Label.class).stream().map(Label::getText)
					.anyMatch(t -> t.contains(shortcut) && t.contains("Tab")));
		} finally {
			shell.dispose();
			prefs.setValue(Prefs.UI_LANGUAGE, "");
		}
	}

	@Test
	void newConversationFollowsTheProvider() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		IPreferenceStore prefs = plugin.prefs();
		try {
			prefs.setValue(Prefs.PROVIDER, ClaudeCodeProvider.ID);
			prefs.setValue(Prefs.AUTO_COMPLETION, true);
			try (Conversation c = plugin.newConversation((tool, input) -> false, null)) {
				assertTrue(c.unwrap() instanceof ClaudeCodeSession);
			}
			assertTrue(plugin.provider().unwrap() instanceof ClaudeCodeProvider);
			assertEquals("opus", plugin.chatModel());
			assertEquals("haiku", plugin.completionModel());
			assertFalse(plugin.autoCompletion());

			prefs.setValue(Prefs.PROVIDER, CopilotProvider.ID);
			try (Conversation c = plugin.newConversation((tool, input) -> false, null)) {
				assertTrue(c.unwrap() instanceof CopilotSession);
				assertTrue(plugin.conversationType().isInstance(c.unwrap()));
			}
			assertTrue(plugin.provider().unwrap() instanceof CopilotProvider);
			assertFalse(plugin.autoCompletion());
			assertEquals(Messages.fmt("chat.status.copilot", Messages.get("chat.status.copilotDefault")),
					plugin.chatModelLabel());

			prefs.setValue(Prefs.PROVIDER, AnthropicProvider.ID);
			try (Conversation c = plugin.newConversation((tool, input) -> false, null)) {
				assertTrue(c.unwrap() instanceof ChatSession);
				assertTrue(plugin.conversationType().isInstance(c.unwrap()));
			}
			assertTrue(plugin.autoCompletion());
		} finally {
			prefs.setToDefault(Prefs.PROVIDER);
			prefs.setToDefault(Prefs.AUTO_COMPLETION);
		}
	}

	@Test
	void completionShortcutWinsInTextEditors() throws Exception {
		openDemoEditor();
		pump();
		IContextService contexts = PlatformUI.getWorkbench().getService(IContextService.class);
		assertTrue(contexts.getActiveContextIds().contains(Shortcuts.EDITOR_CONTEXT),
				contexts.getActiveContextIds().toString());
		IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
		Binding match = bindings.getPerfectMatch(KeySequence.getInstance(Shortcuts.defaultCompletion()));
		assertNotNull(match, "no conflict with Scroll Line Up");
		assertEquals(Shortcuts.COMPLETE_COMMAND, match.getParameterizedCommand().getId());
		assertFalse(Shortcuts.completion().isBlank());
		assertFalse(bindings.isPerfectMatch(KeySequence.getInstance("M1+M3+SPACE")),
				"Ctrl+Alt+Space is left to the Claude desktop app");
	}

	@Test
	void menusFollowBellasLanguageNotTheSystemLanguage() {
		IPreferenceStore prefs = BellaPlugin.getDefault().prefs();
		Shell shell = new Shell(Display.getCurrent());
		try {
			for (String[] lang : new String[][] {
					{ "en", "What happens here?", "Bella Chat", "Check with ATC and fix…", "Review transport request…",
							"Review code (chat)" },
					{ "de", "Was passiert hier?", "Bella-Chat", "Mit ATC prüfen und beheben…", "Transportauftrag reviewen…",
							"Code prüfen (Chat)" } }) {
				prefs.setValue(Prefs.UI_LANGUAGE, lang[0]);
				Menu menu = new Menu(shell, SWT.POP_UP);
				BellaMenuItems items = new BellaMenuItems(BellaMenuItems.POPUP_ID);
				items.initialize(PlatformUI.getWorkbench());
				items.fill(menu, 0);
				List<String> labels = java.util.Arrays.stream(menu.getItems()).map(MenuItem::getText)
						.filter(t -> !t.isEmpty()).toList();
				assertTrue(labels.get(0).startsWith(lang[1]), labels.toString());
				assertEquals(11, labels.size(), labels.toString());
				assertTrue(labels.contains(lang[3]), labels.toString());
				assertTrue(labels.contains(lang[5]), labels.toString());
				assertTrue(labels.contains(lang[4]), labels.toString());
				menu.dispose();
				ChatView view = ChatView.open().orElseThrow();
				pump();
				assertEquals(lang[2], view.getPartName());
			}
		} finally {
			prefs.setValue(Prefs.UI_LANGUAGE, "");
			shell.dispose();
		}
	}

	@Test
	void languageOverrideSwitchesTexts() {
		BellaPlugin.getDefault().prefs().setValue(Prefs.UI_LANGUAGE, "de");
		try {
			assertEquals("Senden", Messages.get("chat.send"));
			BellaPlugin.getDefault().prefs().setValue(Prefs.UI_LANGUAGE, "ja");
			assertEquals("送信", Messages.get("chat.send"));
		} finally {
			BellaPlugin.getDefault().prefs().setValue(Prefs.UI_LANGUAGE, "");
		}
	}
}

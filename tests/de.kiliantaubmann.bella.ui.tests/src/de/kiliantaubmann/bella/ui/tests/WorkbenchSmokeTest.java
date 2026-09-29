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
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.Shortcuts;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.internal.BellaMenuItems;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.editor.OpenEditorRouter;
import de.kiliantaubmann.bella.ui.prefs.BellaPreferencePage;
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
				"implementMethod", "complete")) {
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
	void preferencePagesRender() {
		Shell shell = new Shell(Display.getCurrent());
		try {
			for (PreferencePage p : List.<PreferencePage>of(new BellaPreferencePage(), new ToolsPreferencePage())) {
				((org.eclipse.ui.IWorkbenchPreferencePage) p).init(PlatformUI.getWorkbench());
				p.createControl(shell);
				assertTrue(p.performOk());
			}
		} finally {
			shell.dispose();
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
				assertTrue(c instanceof ClaudeCodeSession);
			}
			assertTrue(plugin.provider() instanceof ClaudeCodeProvider);
			assertEquals("opus", plugin.chatModel());
			assertEquals("haiku", plugin.completionModel());
			assertFalse(plugin.autoCompletion());

			prefs.setValue(Prefs.PROVIDER, CopilotProvider.ID);
			try (Conversation c = plugin.newConversation((tool, input) -> false, null)) {
				assertTrue(c instanceof CopilotSession);
				assertTrue(plugin.conversationType().isInstance(c));
			}
			assertTrue(plugin.provider() instanceof CopilotProvider);
			assertFalse(plugin.autoCompletion());
			assertEquals(Messages.fmt("chat.status.copilot", Messages.get("chat.status.copilotDefault")),
					plugin.chatModelLabel());

			prefs.setValue(Prefs.PROVIDER, AnthropicProvider.ID);
			try (Conversation c = plugin.newConversation((tool, input) -> false, null)) {
				assertTrue(c instanceof ChatSession);
				assertTrue(plugin.conversationType().isInstance(c));
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
			for (String[] lang : new String[][] { { "en", "What happens here?", "Bella Chat" },
					{ "de", "Was passiert hier?", "Bella-Chat" } }) {
				prefs.setValue(Prefs.UI_LANGUAGE, lang[0]);
				Menu menu = new Menu(shell, SWT.POP_UP);
				BellaMenuItems items = new BellaMenuItems(BellaMenuItems.POPUP_ID);
				items.initialize(PlatformUI.getWorkbench());
				items.fill(menu, 0);
				List<String> labels = java.util.Arrays.stream(menu.getItems()).map(MenuItem::getText)
						.filter(t -> !t.isEmpty()).toList();
				assertTrue(labels.get(0).startsWith(lang[1]), labels.toString());
				assertEquals(8, labels.size(), labels.toString());
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

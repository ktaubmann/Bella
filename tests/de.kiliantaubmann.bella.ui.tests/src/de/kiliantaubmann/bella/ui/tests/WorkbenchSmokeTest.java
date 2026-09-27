package de.kiliantaubmann.bella.ui.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.Capability;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
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

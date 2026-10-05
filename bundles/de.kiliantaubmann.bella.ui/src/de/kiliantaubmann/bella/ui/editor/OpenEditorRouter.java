package de.kiliantaubmann.bella.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapEdit;
import de.kiliantaubmann.bella.core.abap.AbapSlices;
import de.kiliantaubmann.bella.core.abap.ObjectTarget;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.tools.WriteGuard;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * Redirects source writes aimed at an object that is open in an editor into
 * that editor. The object is locked by the editor anyway; the developer
 * reviews the change there and saves and activates it in ADT.
 */
public final class OpenEditorRouter implements WriteGuard {

	@Override
	public Optional<ToolResult> intercept(ToolSpec tool, JsonObject input) {
		List<ObjectTarget> activated = ObjectTarget.activationTargets(tool, input);
		if (!activated.isEmpty()) {
			AtomicReference<Optional<ToolResult>> result = new AtomicReference<>(Optional.empty());
			Display.getDefault().syncExec(() -> result.set(unsavedBeforeActivation(activated)));
			return result.get();
		}
		if (!ObjectTarget.isSourceWrite(tool, input)) {
			return Optional.empty();
		}
		Optional<ObjectTarget> target = ObjectTarget.fromToolInput(input);
		if (target.isEmpty()) {
			return Optional.empty();
		}
		String source = ObjectTarget.sourceFromToolInput(input);
		String include = Json.str(input, "include");
		String method = Json.str(input, "method");
		AtomicReference<Optional<ToolResult>> result = new AtomicReference<>(Optional.empty());
		Display.getDefault().syncExec(() -> result.set(writeIntoOpenEditor(target.get(), include, method, source)));
		return result.get();
	}

	private static Optional<ToolResult> writeIntoOpenEditor(ObjectTarget target, String include, String method,
			String source) {
		IEditorPart part = findOpenEditor(target);
		if (part == null) {
			return Optional.empty();
		}
		if (include != null && !include.isBlank() && !include.equalsIgnoreCase("main")) {
			return Optional.of(ToolResult.error(Messages.fmt("router.includeOpen", target.name())));
		}
		Optional<ITextEditor> editor = EditorBridge.textEditor(part);
		if (editor.isEmpty()) {
			return Optional.of(ToolResult.error(Messages.fmt("router.noTextEditor", target.name())));
		}
		IDocument doc = EditorBridge.document(editor.get());
		String before = doc.get();
		if (method != null && !method.isBlank()) {
			Optional<String> updated = AbapEdit.replaceMethod(before, method, source);
			if (updated.isEmpty()) {
				return Optional.of(ToolResult.error("Method " + method.toUpperCase(Locale.ROOT)
						+ " is not implemented in the open editor of " + target.name() + ". Implemented methods: "
						+ String.join(", ", AbapSlices.methodNames(before))));
			}
			source = updated.get();
		}
		if (before.equals(source)) {
			return Optional.of(ToolResult.ok("The open editor of " + target.name() + " already contains this source."));
		}
		part.getSite().getPage().activate(part);
		if (!DiffPreview.confirm(part.getSite().getShell(), Messages.fmt("diff.titleObject", target.name()), before,
				source)) {
			return Optional.of(ToolResult.error("The developer discarded the proposed change to " + target.name()
					+ " in the diff preview."));
		}
		try {
			EditorBridge.replace(editor.get(), 0, doc.getLength(), source);
		} catch (Exception e) {
			return Optional.of(ToolResult.error("Could not write into the editor: " + e.getMessage()));
		}
		return Optional.of(ToolResult.ok(target.name()
				+ " is open in the developer's editor, so the new source was written into the editor buffer. "
				+ "It is NOT saved and NOT activated; the developer reviews it and saves/activates in ADT."));
	}

	/**
	 * Activation works on the saved version. If an object is open with unsaved
	 * changes (e.g. code Bella just wrote into the editor), activating would
	 * activate and test the old code, so the call is stopped.
	 */
	private static Optional<ToolResult> unsavedBeforeActivation(List<ObjectTarget> targets) {
		List<String> unsaved = new ArrayList<>();
		for (ObjectTarget t : targets) {
			IEditorPart part = findOpenEditor(t);
			if (part != null && part.isDirty()) {
				unsaved.add(t.name());
			}
		}
		if (unsaved.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(ToolResult.error(String.join(", ", unsaved) + " is open in the developer's editor with "
				+ "unsaved changes; activating now would activate (and test) the last saved version. Not activated. "
				+ "Ask the developer to save (Ctrl+S) and activate (Ctrl+F3) in the editor."));
	}

	/** An open editor showing the target object, searching all windows and pages. */
	static IEditorPart findOpenEditor(ObjectTarget target) {
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				for (IEditorReference ref : page.getEditorReferences()) {
					IEditorPart part = ref.getEditor(false);
					if (part == null) {
						continue;
					}
					Optional<AdtEditorObject> adt = EditorBridge.adtObject(part);
					boolean matches = adt.isPresent() ? target.matches(adt.get().name(), adt.get().type())
							: target.matches(EditorBridge.objectName(part), null);
					if (matches) {
						return part;
					}
				}
			}
		}
		return null;
	}
}

package de.kiliantaubmann.bella.ui.editor;

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
		if (!ObjectTarget.isSourceWrite(tool, input)) {
			return Optional.empty();
		}
		Optional<ObjectTarget> target = ObjectTarget.fromToolInput(input);
		if (target.isEmpty()) {
			return Optional.empty();
		}
		String source = ObjectTarget.sourceFromToolInput(input);
		String include = Json.str(input, "include");
		AtomicReference<Optional<ToolResult>> result = new AtomicReference<>(Optional.empty());
		Display.getDefault().syncExec(() -> result.set(writeIntoOpenEditor(target.get(), include, source)));
		return result.get();
	}

	private static Optional<ToolResult> writeIntoOpenEditor(ObjectTarget target, String include, String source) {
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

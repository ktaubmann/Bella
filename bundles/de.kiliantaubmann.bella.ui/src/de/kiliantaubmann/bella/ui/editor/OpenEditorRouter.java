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
import de.kiliantaubmann.bella.core.abap.ClassSurgery;
import de.kiliantaubmann.bella.core.abap.CodeEdits;
import de.kiliantaubmann.bella.core.abap.ObjectTarget;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtSystemInfo;
import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.tools.WriteGuard;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.ui.BellaPlugin;
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
		if ("adt_edit_code".equals(tool.name())) {
			Optional<ObjectTarget> target = ObjectTarget.fromToolInput(input);
			if (target.isEmpty()) {
				return Optional.empty();
			}
			AtomicReference<Optional<ToolResult>> result = new AtomicReference<>(Optional.empty());
			Display.getDefault().syncExec(() -> result.set(editInOpenEditor(target.get(), input)));
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
		String written = source;
		if (method != null && !method.isBlank()) {
			Optional<String> updated = AbapEdit.replaceMethod(before, method, source);
			if (updated.isEmpty()) {
				return Optional.of(ToolResult.error("Method " + method.toUpperCase(Locale.ROOT)
						+ " is not implemented in the open editor of " + target.name() + ". Implemented methods: "
						+ String.join(", ", AbapSlices.methodNames(before))));
			}
			source = updated.get();
		}
		return replaceBuffer(part, editor.get(), doc, target, before, source)
				.or(() -> Optional.of(ToolResult.ok(target.name()
						+ " is open in the developer's editor, so the new source was written into the editor buffer. "
						+ "It is NOT saved and NOT activated; the developer reviews it and saves/activates in ADT."
						+ styleCheck(written, method != null && !method.isBlank(), lintTarget(part)))));
	}

	/** adt_edit_code on an open object: the same change, applied to the editor buffer. */
	private static Optional<ToolResult> editInOpenEditor(ObjectTarget target, JsonObject input) {
		IEditorPart part = findOpenEditor(target);
		if (part == null) {
			return Optional.empty();
		}
		Optional<ITextEditor> editor = EditorBridge.textEditor(part);
		if (editor.isEmpty()) {
			return Optional.of(ToolResult.error(Messages.fmt("router.noTextEditor", target.name())));
		}
		IDocument doc = EditorBridge.document(editor.get());
		String before = doc.get();
		String after;
		try {
			after = CodeEdits.apply(before, target.name(), input);
		} catch (ClassSurgery.SurgeryException e) {
			return Optional.of(ToolResult.error(e.getMessage()));
		}
		return replaceBuffer(part, editor.get(), doc, target, before, after)
				.or(() -> Optional.of(ToolResult.ok(target.name() + " is open in the developer's editor, so the change "
						+ "was made in the editor buffer. It is NOT saved and NOT activated; the developer reviews it and "
						+ "saves/activates in ADT.")));
	}

	/**
	 * Shows the diff preview and replaces the buffer; empty when the change
	 * went into the buffer, otherwise the result to report instead.
	 */
	private static Optional<ToolResult> replaceBuffer(IEditorPart part, ITextEditor editor, IDocument doc,
			ObjectTarget target, String before, String source) {
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
			EditorBridge.replace(editor, 0, doc.getLength(), source);
		} catch (Exception e) {
			return Optional.of(ToolResult.error("Could not write into the editor: " + e.getMessage()));
		}
		return Optional.empty();
	}

	/** Release of the editor's system if Bella knows it already; this runs on the UI thread, so it never asks SAP. */
	private static AbapLint.Target lintTarget(IEditorPart part) {
		try {
			return EditorBridge.adtObject(part).flatMap(o -> AdtSystemInfo.known(o.destinationId()))
					.map(AdtClient.SystemInfo::lintTarget).orElse(AbapLint.Target.UNKNOWN);
		} catch (RuntimeException | LinkageError e) {
			return AbapLint.Target.UNKNOWN;
		}
	}

	/** Bella's style check of the code written, as the ADT tools add it to a write in the SAP system. */
	private static String styleCheck(String written, boolean methodBody, AbapLint.Target target) {
		BellaPlugin plugin = BellaPlugin.getDefault();
		List<AbapLint.Finding> findings = AbapLint.check(written,
				plugin == null ? NamingRules.NONE : plugin.activeConventions().naming(), target);
		if (findings.isEmpty()) {
			return "";
		}
		return "\n\nBella's style check of the code written" + (methodBody ? " (line numbers count within the method body)" : "")
				+ "; fix the findings that apply:\n" + AbapLint.format(findings);
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

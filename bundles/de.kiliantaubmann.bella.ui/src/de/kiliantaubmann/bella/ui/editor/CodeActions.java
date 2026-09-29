package de.kiliantaubmann.bella.ui.editor;

import java.util.List;
import java.util.Optional;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.abap.AbapEdit;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * Puts generated code into the active editor: at the cursor, over the
 * selection, or as the body of the method around the cursor. Always via the
 * diff preview (unless switched off), never saved or activated.
 */
public final class CodeActions {

	public enum Target {
		CURSOR, SELECTION, METHOD
	}

	private CodeActions() {
	}

	/** UI thread. */
	public static void apply(Target target, String code) {
		IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		IEditorPart part = page.getActiveEditor();
		Shell shell = page.getWorkbenchWindow().getShell();
		Optional<ITextEditor> editor = EditorBridge.textEditor(part);
		if (editor.isEmpty()) {
			MessageDialog.openInformation(shell, Messages.get("app.name"), Messages.get("editor.none"));
			return;
		}
		apply(part, editor.get(), target, code);
	}

	public static void apply(IEditorPart part, ITextEditor editor, Target target, String code) {
		apply(part, editor, target, code, null);
	}

	/** @param notes shown above the diff (definitions used, style findings); may be {@code null} */
	public static void apply(IEditorPart part, ITextEditor editor, Target target, String code, String notes) {
		Shell shell = part.getSite().getShell();
		IDocument doc = EditorBridge.document(editor);
		ITextSelection sel = EditorBridge.selection(editor);
		if (doc == null || sel == null) {
			return;
		}
		String before = doc.get();
		int offset;
		int length;
		String text;
		switch (target) {
		case CURSOR -> {
			offset = sel.getOffset() + sel.getLength();
			length = 0;
			String indent = AbapEdit.indentationOfLine(before, offset);
			boolean lineIsBlank = before.substring(AbapEdit.lineStart(before, offset), offset).isBlank();
			text = lineIsBlank ? AbapEdit.indent(code, indent).stripLeading() : code;
		}
		case SELECTION -> {
			if (sel.getLength() == 0) {
				MessageDialog.openInformation(shell, Messages.get("app.name"), Messages.get("editor.noSelection"));
				return;
			}
			offset = sel.getOffset();
			length = sel.getLength();
			String indent = AbapEdit.indentationOfLine(before, offset);
			text = AbapEdit.indent(code, indent);
			if (offset == AbapEdit.lineStart(before, offset)) {
				// selection starts at column 0: keep the indentation inside the replacement
			} else {
				text = text.stripLeading();
			}
		}
		case METHOD -> {
			Optional<AbapStructureScanner.Block> routine = AbapStructureScanner.routineAt(before, sel.getOffset());
			if (routine.isEmpty()) {
				MessageDialog.openInformation(shell, Messages.get("app.name"), Messages.get("editor.noMethod"));
				return;
			}
			AbapEdit.Replacement r = AbapEdit.replaceBody(before, routine.get(), stripFrame(code));
			offset = r.offset();
			length = r.length();
			text = r.text();
		}
		default -> throw new IllegalStateException();
		}
		String after = before.substring(0, offset) + text + before.substring(offset + length);
		if (!DiffPreview.confirm(shell, Messages.fmt("diff.titleObject", EditorBridge.objectName(part)), before, after,
				notes)) {
			return;
		}
		try {
			EditorBridge.replace(editor, offset, length, text);
		} catch (Exception e) {
			BellaPlugin.log("Cannot write into editor", e);
			MessageDialog.openError(shell, Messages.get("app.name"), e.getMessage());
		}
	}

	/** Style findings shown in the diff preview at most. */
	private static final int MAX_NOTED_FINDINGS = 6;

	/** Text for the diff preview: which definitions were used and what the style check found. */
	public static String previewNotes(List<String> usedDefinitions, List<AbapLint.Finding> findings) {
		return previewNotes(usedDefinitions, null, findings);
	}

	/** @param definitionsError why the SAP definitions could not be loaded, or {@code null} */
	public static String previewNotes(List<String> usedDefinitions, String definitionsError,
			List<AbapLint.Finding> findings) {
		StringBuilder sb = new StringBuilder();
		if (definitionsError != null) {
			sb.append(Messages.fmt("diff.definitions.failed", definitionsError)).append('\n');
		}
		if (!usedDefinitions.isEmpty()) {
			sb.append(Messages.fmt("diff.definitions", String.join(", ", usedDefinitions))).append('\n');
		}
		if (!findings.isEmpty()) {
			sb.append(Messages.fmt("diff.lint", findings.size())).append('\n');
			for (AbapLint.Finding f : findings.subList(0, Math.min(MAX_NOTED_FINDINGS, findings.size()))) {
				sb.append("  ").append(f.format()).append('\n');
			}
			if (findings.size() > MAX_NOTED_FINDINGS) {
				sb.append("  ").append(Messages.fmt("diff.lint.more", findings.size() - MAX_NOTED_FINDINGS)).append('\n');
			}
		}
		return sb.toString();
	}

	/** Removes METHOD/ENDMETHOD lines if the model returned the full method anyway. */
	static String stripFrame(String code) {
		Optional<AbapStructureScanner.Block> block = AbapStructureScanner.blocks(code).stream()
				.filter(b -> b.kind().isRoutine()).findFirst();
		return block.map(b -> b.body(code)).orElse(code);
	}
}

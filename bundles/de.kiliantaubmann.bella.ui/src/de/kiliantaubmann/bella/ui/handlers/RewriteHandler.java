package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.abap.AbapEdit;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;

/** "Rework selection…": replaces the selection in the editor buffer. */
public class RewriteHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		if (ctx.selection().isBlank()) {
			MessageDialog.openInformation(part.getSite().getShell(), Messages.get("app.name"),
					Messages.get("editor.noSelection"));
			return;
		}
		ctx = toWholeLines(part, editor, ctx);
		String instruction = InstructionDialog.ask(part.getSite().getShell(), Messages.get("rewrite.title"),
				Messages.get("rewrite.message"), "rewrite", presets("rewrite.preset.", 5), false);
		if (instruction == null) {
			return;
		}
		generateInto(part, editor, BellaPlugin.getDefault().prompts().rewriteSelection(ctx, instruction),
				CodeActions.Target.SELECTION, ctx.selection(), instruction);
	}

	/** Selects whole lines where a multi-line selection starts or ends inside code (see {@link AbapEdit#wholeLines}). */
	private static EditorContext toWholeLines(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		ITextSelection sel = EditorBridge.selection(editor);
		IDocument doc = EditorBridge.document(editor);
		if (sel == null || doc == null) {
			return ctx;
		}
		int[] range = AbapEdit.wholeLines(doc.get(), sel.getOffset(), sel.getLength());
		if (range[0] == sel.getOffset() && range[1] == sel.getLength()) {
			return ctx;
		}
		editor.selectAndReveal(range[0], range[1]);
		return EditorBridge.context(part, editor);
	}
}

package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;

/** "Rework selection…": replaces the selection in the editor buffer. */
public class RewriteHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		if (ctx.selection().isBlank()) {
			MessageDialog.openInformation(part.getSite().getShell(), Messages.get("app.name"),
					Messages.get("editor.noSelection"));
			return;
		}
		String instruction = InstructionDialog.ask(part.getSite().getShell(), Messages.get("rewrite.title"),
				Messages.get("rewrite.message"), "rewrite", presets("rewrite.preset.", 5), false);
		if (instruction == null) {
			return;
		}
		generateInto(part, editor, BellaPlugin.getDefault().prompts().rewriteSelection(ctx, instruction),
				CodeActions.Target.SELECTION);
	}
}

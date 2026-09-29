package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;

/** "Generate code here…": writes code at the cursor into the editor buffer. */
public class GenerateHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		String instruction = InstructionDialog.ask(part.getSite().getShell(), Messages.get("generate.title"),
				Messages.fmt("generate.message", ctx.objectName()), "generate", presets("generate.preset.", 4), false);
		if (instruction == null) {
			return;
		}
		generateInto(part, editor, BellaPlugin.getDefault().prompts().generateAtCursor(ctx, instruction),
				CodeActions.Target.CURSOR, codeAround(ctx), instruction);
	}
}

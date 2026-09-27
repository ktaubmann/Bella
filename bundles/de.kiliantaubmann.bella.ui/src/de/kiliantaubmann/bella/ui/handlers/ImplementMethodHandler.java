package de.kiliantaubmann.bella.ui.handlers;

import java.util.Optional;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;

/** "Implement method": writes the body of the METHOD/FORM/FUNCTION at the cursor. */
public class ImplementMethodHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		String src = ctx.source();
		Optional<AbapStructureScanner.Block> routine = AbapStructureScanner.routineAt(src, ctx.selectionOffset());
		if (routine.isEmpty()) {
			MessageDialog.openInformation(part.getSite().getShell(), Messages.get("app.name"),
					Messages.get("editor.noMethod"));
			return;
		}
		String instruction = InstructionDialog.ask(part.getSite().getShell(),
				Messages.fmt("implement.title", routine.get().name()), Messages.get("implement.message"), "method",
				presets("implement.preset.", 3), true);
		if (instruction == null) {
			return;
		}
		String className = AbapStructureScanner.classAt(src, ctx.selectionOffset()).map(AbapStructureScanner.Block::name)
				.orElse(null);
		String declaration = className == null ? null
				: AbapStructureScanner.methodDeclaration(src, className, routine.get().name()).orElse(null);
		String definition = className == null ? null
				: AbapStructureScanner.classDefinition(src, className).orElse(null);
		generateInto(part, editor, BellaPlugin.getDefault().prompts().implementRoutine(ctx, routine.get(), declaration,
				definition, instruction), CodeActions.Target.METHOD);
	}
}

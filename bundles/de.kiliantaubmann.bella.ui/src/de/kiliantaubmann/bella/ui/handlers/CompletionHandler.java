package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.completion.GhostTextController;

/** Requests an inline completion at the cursor. */
public class CompletionHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		GhostTextController c = GhostTextController.of(part, editor);
		if (c != null) {
			c.trigger();
		}
	}
}

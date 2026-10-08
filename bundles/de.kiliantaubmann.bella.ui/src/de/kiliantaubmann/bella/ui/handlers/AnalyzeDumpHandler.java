package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.views.ChatView;

/** "Analyse newest short dump": Bella reads the dump, finds the cause and proposes a fix. */
public class AnalyzeDumpHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		String object = editor == null ? "" : EditorBridge.objectName(editor);
		String display = object == null || object.isBlank() ? Messages.get("chat.display.analyzeDump")
				: Messages.fmt("chat.display.analyzeDumpFor", object);
		ChatView.open().ifPresent(v -> v.ask(display, BellaPlugin.getDefault().prompts().analyzeDump(object).user()));
		return null;
	}
}

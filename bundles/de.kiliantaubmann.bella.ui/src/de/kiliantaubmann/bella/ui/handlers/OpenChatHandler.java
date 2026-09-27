package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import de.kiliantaubmann.bella.ui.views.ChatView;

/** Opens the Bella chat. */
public class OpenChatHandler extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) {
		ChatView.open().ifPresent(ChatView::setFocus);
		return null;
	}
}

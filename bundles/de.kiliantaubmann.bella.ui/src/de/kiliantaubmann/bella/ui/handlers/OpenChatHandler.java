package de.kiliantaubmann.bella.ui.handlers;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;

import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.views.ChatView;

/** Opens the Bella chat. */
public class OpenChatHandler extends AbstractHandler implements IElementUpdater {

	public static final String COMMAND = "de.kiliantaubmann.bella.ui.openChat";

	@Override
	public Object execute(ExecutionEvent event) {
		ChatView.open().ifPresent(ChatView::setFocus);
		return null;
	}

	/** Tooltip of the toolbar button in Bella's UI language (plugin.xml only knows the system language). */
	@Override
	public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
		element.setTooltip(Messages.plugin("cmd.openChat.desc"));
	}
}

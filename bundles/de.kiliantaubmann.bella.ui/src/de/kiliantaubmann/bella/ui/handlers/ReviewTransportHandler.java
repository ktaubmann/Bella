package de.kiliantaubmann.bella.ui.handlers;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.views.ChatView;

/**
 * "Review transport request…": the developer picks one of their requests
 * (or enters a number) and Bella reviews it in the chat. Read only.
 */
public class ReviewTransportHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		Shell shell = HandlerUtil.getActiveShell(event);
		AdtBackend adt = BellaPlugin.getDefault().adt();
		List<AdtSystem> systems;
		try {
			systems = adt == null ? List.of() : adt.systems().stream().filter(AdtSystem::loggedOn).toList();
		} catch (RuntimeException | LinkageError e) {
			systems = List.of();
		}
		if (systems.isEmpty()) {
			MessageDialog.openInformation(shell, Messages.get("tr.title"), Messages.get("tr.noSystem"));
			return null;
		}
		Optional<TransportPickerDialog.Choice> choice = TransportPickerDialog.choose(shell, adt, systems,
				activeDestination());
		choice.ifPresent(c -> ChatView.open().ifPresent(v -> v.ask(
				Messages.fmt("chat.display.reviewTransport", c.request(), c.system().label()),
				BellaPlugin.getDefault().prompts().reviewTransport(c.request(), c.system().projectName()))));
		return null;
	}

	/** Destination of the ABAP object in the active editor, or {@code null}. */
	private static String activeDestination() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null || page.getActiveEditor() == null) {
			return null;
		}
		return EditorBridge.adtObject(page.getActiveEditor()).map(AdtEditorObject::destinationId).orElse(null);
	}
}

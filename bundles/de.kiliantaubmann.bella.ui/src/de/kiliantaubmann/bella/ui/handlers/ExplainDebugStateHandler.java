package de.kiliantaubmann.bella.ui.handlers;

import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import de.kiliantaubmann.bella.core.debug.DebugSnapshot;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.debug.EclipseDebugBackend;
import de.kiliantaubmann.bella.ui.views.ChatView;

/**
 * "Explain debugger state": sends the state of the stopped ABAP debug
 * session to the chat. The variables are read in the background, since each
 * one may be a round trip to the SAP system.
 */
public class ExplainDebugStateHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		Shell shell = HandlerUtil.getActiveShell(event);
		Job job = Job.create(Messages.get("debug.reading"), monitor -> {
			Optional<DebugSnapshot> snapshot;
			try {
				snapshot = new EclipseDebugBackend().snapshot();
			} catch (Exception e) {
				Log.warn("debug", "reading the debug session failed: " + e);
				snapshot = Optional.empty();
			}
			Optional<DebugSnapshot> s = snapshot;
			Display.getDefault().asyncExec(() -> {
				if (s.isEmpty()) {
					MessageDialog.openInformation(shell, Messages.get("debug.title"), Messages.get("debug.noSession"));
				} else {
					ask(s.get());
				}
			});
			return Status.OK_STATUS;
		});
		job.setUser(false);
		job.schedule();
		return null;
	}

	/** Asks the chat about a snapshot; UI thread. */
	public static void ask(DebugSnapshot snapshot) {
		String where = snapshot.object() + (snapshot.line() > 0 ? " (" + snapshot.line() + ")" : "");
		ChatView.open().ifPresent(v -> v.ask(Messages.fmt("chat.display.debugState", where),
				BellaPlugin.getDefault().prompts().explainDebugState(snapshot.format(), null).user()));
	}
}

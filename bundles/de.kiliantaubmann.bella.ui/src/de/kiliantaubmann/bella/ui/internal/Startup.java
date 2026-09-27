package de.kiliantaubmann.bella.ui.internal;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import de.kiliantaubmann.bella.ui.editor.EditorTracker;

/** Starts tracking editors early so inline completion and the default system work before the chat is opened. */
public class Startup implements IStartup {

	@Override
	public void earlyStartup() {
		PlatformUI.getWorkbench().getDisplay().asyncExec(EditorTracker::install);
	}
}

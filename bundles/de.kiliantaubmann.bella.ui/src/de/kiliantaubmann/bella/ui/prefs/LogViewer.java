package de.kiliantaubmann.bella.ui.prefs;

import java.nio.file.Path;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

import de.kiliantaubmann.bella.ui.BellaPlugin;

/** Opens Bella's log file in an Eclipse text editor. */
public final class LogViewer {

	private LogViewer() {
	}

	/** UI thread. */
	public static void openInEditor(Path file) {
		IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		try {
			IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toUri()));
		} catch (PartInitException e) {
			BellaPlugin.log("Cannot open the log file", e);
		}
	}
}

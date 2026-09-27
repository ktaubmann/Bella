package de.kiliantaubmann.bella.ui.editor;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.completion.GhostTextController;

/**
 * Follows the active editor: remembers its SAP system as default for the
 * tools and installs inline completion into ABAP editors.
 */
public final class EditorTracker implements IPartListener2, IWindowListener {

	private static EditorTracker instance;

	public static synchronized void install() {
		if (instance != null) {
			return;
		}
		instance = new EditorTracker();
		PlatformUI.getWorkbench().addWindowListener(instance);
		for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			instance.windowOpened(w);
		}
	}

	private void onEditor(IWorkbenchPart part) {
		if (!(part instanceof IEditorPart editor)) {
			return;
		}
		EditorBridge.adtObject(editor).map(AdtEditorObject::destinationId)
				.ifPresent(BellaPlugin.getDefault()::setActiveDestination);
		EditorBridge.textEditor(editor).ifPresent(te -> GhostTextController.installIfAbap(editor, te));
	}

	@Override
	public void partActivated(IWorkbenchPartReference ref) {
		onEditor(ref.getPart(false));
	}

	@Override
	public void partOpened(IWorkbenchPartReference ref) {
		onEditor(ref.getPart(false));
	}

	@Override
	public void windowOpened(IWorkbenchWindow window) {
		window.getPartService().addPartListener(this);
		if (window.getActivePage() != null && window.getActivePage().getActiveEditor() != null) {
			onEditor(window.getActivePage().getActiveEditor());
		}
	}

	@Override
	public void windowActivated(IWorkbenchWindow window) {
	}

	@Override
	public void windowDeactivated(IWorkbenchWindow window) {
	}

	@Override
	public void windowClosed(IWorkbenchWindow window) {
		window.getPartService().removePartListener(this);
	}
}

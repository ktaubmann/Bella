package de.kiliantaubmann.bella.ui.editor;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;

import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Shortcuts;
import de.kiliantaubmann.bella.ui.completion.GhostTextController;

/**
 * Follows the active editor: remembers its SAP system as default for the
 * tools and installs inline completion into ABAP editors.
 */
public final class EditorTracker implements IPartListener2, IWindowListener {

	private static EditorTracker instance;

	/** Editors whose site already has Bella's key binding context. */
	private final Map<IEditorPart, Boolean> withContext = Collections.synchronizedMap(new WeakHashMap<>());

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
		EditorBridge.textEditor(editor).ifPresent(te -> {
			activateContext(editor);
			GhostTextController.installIfAbap(editor, te);
		});
	}

	/**
	 * Activates Bella's editor context on the editor's own site: it is only
	 * active while that editor has focus and ends when the editor closes.
	 */
	private void activateContext(IEditorPart editor) {
		if (withContext.containsKey(editor) || editor.getSite() == null) {
			return;
		}
		IContextService contexts = editor.getSite().getService(IContextService.class);
		if (contexts != null) {
			contexts.activateContext(Shortcuts.EDITOR_CONTEXT);
			withContext.put(editor, Boolean.TRUE);
		}
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

package de.kiliantaubmann.bella.core.adt;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The package a chat develops in and the transport request the developer
 * chose for it. Customer objects (Z*, Y*) of other packages are ignored, and
 * every write needs the package and, outside local packages, the request.
 * With an object from the editor in the chat, its package is the development
 * package and cannot be changed.
 * <p>
 * Thread safe; one instance belongs to the chat and is reset with it.
 * Listeners hear of every change, e.g. to show package and request in the
 * chat window; they run on the thread that made the change.
 */
public final class DevScope {

	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private AdtEditorObject editor;
	private String editorPackage;
	private String destination;
	private String pkg;
	private String transport;

	/** {@code listener} runs after each change of package, request or editor object. */
	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public void removeListener(Runnable listener) {
		listeners.remove(listener);
	}

	private void changed() {
		listeners.forEach(Runnable::run);
	}

	/** Forgets package, transport and editor object, e.g. for a new chat. */
	public void reset() {
		synchronized (this) {
			editor = null;
			editorPackage = null;
			destination = null;
			pkg = null;
			transport = null;
		}
		changed();
	}

	/** The system the chat is bound to, {@code null} while no package or editor object binds it. */
	public synchronized String destination() {
		return destination;
	}

	/**
	 * The chat works on this editor object; its package becomes the
	 * development package. Only the first one binds the chat: later editor
	 * objects (another include, another class) keep the package and the
	 * transport request, and writes to objects of other packages are refused.
	 * A package the developer named before stays: the editor object is then
	 * only context and binds nothing.
	 */
	public void editorObject(AdtEditorObject object) {
		synchronized (this) {
			if (object == null || editor != null || pkg != null) {
				return;
			}
			editor = object;
			editorPackage = null;
			destination = object.destinationId();
			pkg = null;
			transport = null;
		}
		changed();
	}

	public synchronized AdtEditorObject editorObject() {
		return editor;
	}

	/** The package of the editor object, {@code null} until resolved. */
	synchronized String editorPackage(AdtEditorObject object) {
		return object.equals(editor) ? editorPackage : null;
	}

	void editorPackage(AdtEditorObject object, String resolved) {
		synchronized (this) {
			if (!object.equals(editor)) {
				return;
			}
			editorPackage = resolved.toUpperCase(Locale.ROOT);
		}
		changed();
	}

	/** Sets the package the developer named; a different package drops the transport request. */
	public void developerPackage(String destinationId, String name) {
		synchronized (this) {
			String p = name.trim().toUpperCase(Locale.ROOT);
			if (!p.equals(pkg) || !destinationId.equals(destination)) {
				transport = null;
			}
			destination = destinationId;
			pkg = p;
		}
		changed();
	}

	/** The package the developer named for this system, {@code null} if none. */
	public synchronized String developerPackage(String destinationId) {
		return destinationId.equals(destination) ? pkg : null;
	}

	public void transport(String destinationId, String request) {
		synchronized (this) {
			if (!destinationId.equals(destination)) {
				return;
			}
			transport = request.trim().toUpperCase(Locale.ROOT);
		}
		changed();
	}

	/** The request the developer chose for this system, {@code null} if none. */
	public synchronized String transport(String destinationId) {
		return destinationId.equals(destination) ? transport : null;
	}

	/** Customer objects by SAP's naming: Z* and Y*. */
	public static boolean customer(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		char c = Character.toUpperCase(name.strip().charAt(0));
		return c == 'Z' || c == 'Y';
	}

	/** Local packages ($TMP, $ZDEV …) record no changes in transport requests. */
	public static boolean local(String pkg) {
		return pkg != null && pkg.startsWith("$");
	}
}

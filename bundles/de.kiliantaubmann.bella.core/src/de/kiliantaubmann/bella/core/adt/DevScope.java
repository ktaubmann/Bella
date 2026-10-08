package de.kiliantaubmann.bella.core.adt;

import java.util.Locale;

/**
 * The package a chat develops in and the transport request the developer
 * chose for it. Customer objects (Z*, Y*) of other packages are ignored, and
 * every write needs the package and, outside local packages, the request.
 * With an object from the editor in the chat, its package is the development
 * package and cannot be changed.
 * <p>
 * Thread safe; one instance belongs to the chat and is reset with it.
 */
public final class DevScope {

	private AdtEditorObject editor;
	private String editorPackage;
	private String destination;
	private String pkg;
	private String transport;

	/** Forgets package, transport and editor object, e.g. for a new chat. */
	public synchronized void reset() {
		editor = null;
		editorPackage = null;
		destination = null;
		pkg = null;
		transport = null;
	}

	/**
	 * The chat works on this editor object; its package becomes the
	 * development package. Another object than before drops the chosen
	 * package and transport request.
	 */
	public synchronized void editorObject(AdtEditorObject object) {
		if (object == null || object.equals(editor)) {
			return;
		}
		editor = object;
		editorPackage = null;
		destination = object.destinationId();
		pkg = null;
		transport = null;
	}

	public synchronized AdtEditorObject editorObject() {
		return editor;
	}

	/** The package of the editor object, {@code null} until resolved. */
	synchronized String editorPackage(AdtEditorObject object) {
		return object.equals(editor) ? editorPackage : null;
	}

	synchronized void editorPackage(AdtEditorObject object, String resolved) {
		if (object.equals(editor)) {
			editorPackage = resolved.toUpperCase(Locale.ROOT);
		}
	}

	/** Sets the package the developer named; a different package drops the transport request. */
	public synchronized void developerPackage(String destinationId, String name) {
		String p = name.trim().toUpperCase(Locale.ROOT);
		if (!p.equals(pkg) || !destinationId.equals(destination)) {
			transport = null;
		}
		destination = destinationId;
		pkg = p;
	}

	/** The package the developer named for this system, {@code null} if none. */
	public synchronized String developerPackage(String destinationId) {
		return destinationId.equals(destination) ? pkg : null;
	}

	public synchronized void transport(String destinationId, String request) {
		if (destinationId.equals(destination)) {
			transport = request.trim().toUpperCase(Locale.ROOT);
		}
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

package de.kiliantaubmann.bella.core.adt;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * The package a chat develops in and the transport request the developer
 * chose for it. Customer objects (Z*, Y*) of other packages are ignored, and
 * every write needs the package and, outside local packages, the request.
 * With an object from the editor in the chat, its package is the development
 * package and cannot be changed. Once a transport request was reviewed on a
 * system, the chat reads customer objects of every package there; writes stay
 * in the development package. Customer objects of another package are read
 * when the developer allows it for that package ({@link ReadAsker}); the
 * answer holds for the rest of the chat, and writes stay in the development
 * package.
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
	private String reviewed;
	/** Packages the developer allowed or refused for reading, as {@code destination|PACKAGE}. */
	private final Set<String> readAllowed = new HashSet<>();
	private final Set<String> readRefused = new HashSet<>();
	private final Object asking = new Object();
	private volatile ReadAsker readAsker;

	/** Asks the developer whether Bella may read customer objects of a package outside the development package. */
	public interface ReadAsker {
		/**
		 * Blocks until the developer answered. {@link Answer#NONE} when the
		 * question could not be shown: nothing is recorded then, and a later
		 * read asks again.
		 *
		 * @param object the object Bella is about to read
		 * @param pkg    its package
		 * @param dev    the development package, {@code null} while none is set
		 * @return whether objects of {@code pkg} may be read in this chat
		 */
		Answer allowRead(String destinationId, String object, String pkg, String dev);
	}

	/** What the developer said to a {@link ReadAsker}; {@code NONE} if the question never reached them. */
	public enum Answer {
		YES, NO, NONE
	}

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
			reviewed = null;
			readAllowed.clear();
			readRefused.clear();
		}
		changed();
	}

	/** Who asks the developer before a package outside the development package is read; {@code null} never asks. */
	public void readAsker(ReadAsker asker) {
		readAsker = asker;
	}

	private static String key(String destinationId, String pkg) {
		return destinationId + "|" + pkg.trim().toUpperCase(Locale.ROOT);
	}

	/** Customer objects of {@code pkg} may be read: after a transport review or because the developer allowed it. */
	public synchronized boolean readsPackage(String destinationId, String pkg) {
		return readsEveryPackage(destinationId)
				|| pkg != null && !pkg.isBlank() && readAllowed.contains(key(destinationId, pkg));
	}

	/** The packages the developer allowed for reading on this system, upper case. */
	public synchronized Set<String> readPackages(String destinationId) {
		Set<String> out = new HashSet<>();
		String prefix = destinationId + "|";
		readAllowed.stream().filter(k -> k.startsWith(prefix)).forEach(k -> out.add(k.substring(prefix.length())));
		return out;
	}

	/** The developer declined reading {@code pkg} in this chat. */
	public synchronized boolean readRefused(String destinationId, String pkg) {
		return pkg != null && readRefused.contains(key(destinationId, pkg));
	}

	/** The packages the developer declined for reading on this system, upper case. */
	public synchronized Set<String> refusedPackages(String destinationId) {
		Set<String> out = new HashSet<>();
		String prefix = destinationId + "|";
		readRefused.stream().filter(k -> k.startsWith(prefix)).forEach(k -> out.add(k.substring(prefix.length())));
		return out;
	}

	/**
	 * Whether {@code object} of {@code pkg} may be read, asking the developer
	 * once per package and chat. Without an asker, or after a no, it may not.
	 * One question at a time: parallel reads of the same package ask once.
	 * A cancelled run does not ask, and a question that could not be shown
	 * counts as no for this read only.
	 */
	public boolean mayRead(String destinationId, String object, String pkg, String dev, CancelToken cancel) {
		if (pkg == null || pkg.isBlank()) {
			return false;
		}
		if (readsPackage(destinationId, pkg)) {
			return true; // without waiting for a question about another package
		}
		synchronized (asking) {
			ReadAsker asker = readAsker;
			if (readsPackage(destinationId, pkg)) {
				return true;
			}
			if (asker == null || readRefused(destinationId, pkg) || cancel != null && cancel.isCancelled()) {
				return false;
			}
			Answer answer = asker.allowRead(destinationId, object, pkg.trim().toUpperCase(Locale.ROOT), dev);
			if (answer == null || answer == Answer.NONE) {
				return false;
			}
			synchronized (this) {
				(answer == Answer.YES ? readAllowed : readRefused).add(key(destinationId, pkg));
			}
			return answer == Answer.YES;
		}
	}

	/** Whether the developer can be asked to allow reading another package. */
	public boolean asksForReads() {
		return readAsker != null;
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

	/**
	 * A transport request of this system was reviewed: its objects come from
	 * any package, so reading is no longer limited to the development package.
	 */
	public synchronized void transportReviewed(String destinationId) {
		reviewed = destinationId;
	}

	/** Customer objects of every package may be read on this system (after a transport review). */
	public synchronized boolean readsEveryPackage(String destinationId) {
		return destinationId.equals(reviewed);
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

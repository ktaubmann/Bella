package de.kiliantaubmann.bella.adt;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.sap.adt.destinations.logon.AdtLogonServiceFactory;
import com.sap.adt.tools.core.project.IAbapProject;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.core.adt.AdtTransport;
import de.kiliantaubmann.bella.core.util.Log;

/**
 * {@link AdtBackend} on top of SAP's ADT SDK. Uses the ABAP projects of the
 * workspace and their existing logon; Bella never opens a logon dialog.
 *
 * Only API usage seen in public ADT-based plug-ins is compiled against
 * directly. Optional details (client, user, object reference of an editor)
 * are read reflectively, so a changed ADT version degrades gracefully.
 */
public final class SdkAdtBackend implements AdtBackend {

	@Override
	public List<AdtSystem> systems() {
		List<AdtSystem> systems = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (!project.isOpen()) {
				continue;
			}
			IAbapProject abap = project.getAdapter(IAbapProject.class);
			if (abap == null) {
				continue;
			}
			String destination = abap.getDestinationId();
			Object data = Reflect.call(abap, "getDestinationData");
			systems.add(new AdtSystem(destination, project.getName(), abap.getSystemId(),
					Reflect.string(data, "getClient"), Reflect.string(data, "getUser"), loggedOn(destination),
					Reflect.string(data, "getLanguage")));
		}
		return systems;
	}

	private static boolean loggedOn(String destinationId) {
		try {
			return AdtLogonServiceFactory.createLogonService().isLoggedOn(destinationId);
		} catch (RuntimeException e) {
			Log.warn("adt", "cannot ask the logon service for " + destinationId + ": " + e);
			return false;
		}
	}

	@Override
	public AdtTransport stateless(String destinationId) {
		return new SdkTransport(destinationId, null);
	}

	@Override
	public AdtTransport.Session stateful(String destinationId) throws IOException {
		return new SdkTransport(destinationId, SdkTransport.openStatefulSession(destinationId));
	}

	@Override
	public Optional<AdtEditorObject> editorObject(Object editorInput) {
		if (editorInput == null) {
			return Optional.empty();
		}
		IFile file = Adapters.adapt(editorInput, IFile.class);
		IProject project = file != null ? file.getProject() : Adapters.adapt(editorInput, IProject.class);
		IAbapProject abap = project == null ? null : project.getAdapter(IAbapProject.class);
		if (abap == null) {
			return Optional.empty();
		}
		Object ref = adaptToObjectReference(editorInput);
		if (ref == null && file != null) {
			ref = adaptToObjectReference(file);
		}
		if (ref == null) {
			Reflect.once("no object reference for editor input " + editorInput.getClass().getName()
					+ " in ABAP project " + project.getName());
			return Optional.empty();
		}
		Object uri = Reflect.call(ref, "getUri");
		String name = Reflect.string(ref, "getName");
		String type = Reflect.string(ref, "getType");
		String path = uri == null ? null : uri instanceof URI u ? u.getPath() : String.valueOf(uri);
		AdtEditorObject obj = AdtEditorObject.of(abap.getDestinationId(), path, name, type);
		if (obj == null) {
			Reflect.once("object reference " + ref.getClass().getName() + " without URI or name");
		}
		return Optional.ofNullable(obj);
	}

	/**
	 * Opens the object with ADT's navigation service, as public ADT-based
	 * plug-ins do; read reflectively because it lives in ADT's UI bundle.
	 */
	@Override
	public boolean openInEditor(String destinationId, String objectUri) {
		IProject project = project(destinationId);
		if (project == null) {
			return false;
		}
		Object service = navigationService();
		if (service == null) {
			return false;
		}
		IAbapProject abap = project.getAdapter(IAbapProject.class);
		for (Method m : service.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			try {
				if (m.getName().equals("navigate") && p.length == 3 && p[0].isAssignableFrom(IProject.class)
						&& p[1] == String.class && p[2] == boolean.class) {
					// the service class itself may be internal
					m.setAccessible(true);
					m.invoke(service, project, objectUri, Boolean.TRUE);
					return true;
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				Log.warn("adt", "opening " + objectUri + " failed: " + e);
				return false;
			}
		}
		for (Method m : service.getClass().getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			try {
				if (m.getName().equals("navigateWithExternalLink") && p.length == 2 && p[0] == String.class
						&& p[1].isAssignableFrom(IProject.class)) {
					m.setAccessible(true);
					m.invoke(service, "adt://" + abap.getSystemId() + objectUri, project);
					return true;
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				Log.warn("adt", "opening " + objectUri + " failed: " + e);
				return false;
			}
		}
		Reflect.once("no navigate method on " + service.getClass().getName());
		return false;
	}

	private static IProject project(String destinationId) {
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			IAbapProject abap = project.isOpen() ? project.getAdapter(IAbapProject.class) : null;
			if (abap != null && abap.getDestinationId().equals(destinationId)) {
				return project;
			}
		}
		return null;
	}

	private static Object navigationService() {
		String factory = "com.sap.adt.tools.core.ui.navigation.AdtNavigationServiceFactory";
		for (String bundleName : new String[] { "com.sap.adt.tools.core.ui", "com.sap.adt.tools.core" }) {
			Bundle bundle = Platform.getBundle(bundleName);
			if (bundle == null) {
				continue;
			}
			try {
				return bundle.loadClass(factory).getMethod("createNavigationService").invoke(null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				// try the next bundle
			}
		}
		Reflect.once(factory + " not available");
		return null;
	}

	/** ADT exposes the repository object of an editor as an IAdtObjectReference adapter. */
	private static Object adaptToObjectReference(Object adaptable) {
		for (String cls : new String[] { "com.sap.adt.tools.core.IAdtObjectReference",
				"com.sap.adt.tools.core.model.adtcore.IAdtObjectReference" }) {
			Class<?> type = Reflect.load(cls);
			if (type != null) {
				Object ref = Adapters.adapt(adaptable, type);
				if (ref != null) {
					return ref;
				}
			}
		}
		return null;
	}

	/** Small reflection helpers that never throw. */
	static final class Reflect {

		private Reflect() {
		}

		static Class<?> load(String name) {
			try {
				return Class.forName(name, false, SdkAdtBackend.class.getClassLoader());
			} catch (ClassNotFoundException | LinkageError e) {
				return null;
			}
		}

		static Object call(Object target, String method) {
			if (target == null) {
				return null;
			}
			try {
				Method m = target.getClass().getMethod(method);
				m.setAccessible(true);
				return m.invoke(target);
			} catch (ReflectiveOperationException | RuntimeException e) {
				once(target.getClass().getName() + "." + method + "() not available: " + e);
				return null;
			}
		}

		private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

		/** Logs a reflection problem once per session, so repeated calls do not flood the log. */
		static void once(String message) {
			if (REPORTED.add(message)) {
				Log.info("adt", "ADT SDK: " + message);
			}
		}

		static String string(Object target, String method) {
			Object v = call(target, method);
			return v == null ? null : String.valueOf(v);
		}
	}
}

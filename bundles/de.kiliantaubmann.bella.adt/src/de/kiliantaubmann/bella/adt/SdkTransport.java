package de.kiliantaubmann.bella.adt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.sap.adt.communication.message.AbstractMessageBody;
import com.sap.adt.communication.message.HeadersFactory;
import com.sap.adt.communication.message.IHeaders;
import com.sap.adt.communication.message.IMessageBody;
import com.sap.adt.communication.message.IResponse;
import com.sap.adt.communication.resources.AdtRestResourceFactory;
import com.sap.adt.communication.resources.IRestResource;
import com.sap.adt.communication.resources.ResourceException;
import com.sap.adt.communication.session.AdtSystemSessionFactory;
import com.sap.adt.communication.session.ISystemSession;

import de.kiliantaubmann.bella.core.adt.AdtRequest;
import de.kiliantaubmann.bella.core.adt.AdtResponse;
import de.kiliantaubmann.bella.core.adt.AdtTransport;
import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Sends raw ADT REST requests through the ADT communication layer, so they
 * carry the project's logon (including SSO). A stateful session keeps the
 * ABAP session, which the lock/write/unlock sequence needs.
 */
final class SdkTransport implements AdtTransport.Session {

	private final String destinationId;
	private final ISystemSession session;

	SdkTransport(String destinationId, ISystemSession session) {
		this.destinationId = destinationId;
		this.session = session;
	}

	static ISystemSession openStatefulSession(String destinationId) throws IOException {
		Object factory = AdtSystemSessionFactory.createSystemSessionFactory();
		try {
			Method m = factory.getClass().getMethod("createStatefulSession", String.class);
			return (ISystemSession) m.invoke(factory, destinationId);
		} catch (ReflectiveOperationException | ClassCastException e) {
			throw new IOException("This ADT version does not offer stateful sessions to plug-ins ("
					+ e.getClass().getSimpleName() + "). Writing via Bella's ADT tools is not available; "
					+ "use ARC-1 or apply the change in the editor.", e);
		}
	}

	private IRestResource resource(URI uri) throws IOException {
		if (session == null) {
			return AdtRestResourceFactory.createRestResourceFactory().createResourceWithStatelessSession(uri,
					destinationId);
		}
		Object factory = AdtRestResourceFactory.createRestResourceFactory();
		for (Method m : factory.getClass().getMethods()) {
			if (m.getName().equals("createRestResource") && m.getParameterCount() == 2
					&& m.getParameterTypes()[0] == URI.class
					&& m.getParameterTypes()[1].isAssignableFrom(session.getClass())) {
				try {
					return (IRestResource) m.invoke(factory, uri, session);
				} catch (ReflectiveOperationException e) {
					throw new IOException("Cannot create ADT resource: " + e.getMessage(), e);
				}
			}
		}
		throw new IOException("This ADT version cannot create resources for a stateful session.");
	}

	@Override
	public AdtResponse send(AdtRequest request, CancelToken cancel) throws IOException {
		URI uri = URI.create(request.path());
		IRestResource resource = resource(uri);
		IHeaders headers = HeadersFactory.newHeaders();
		for (Map.Entry<String, String> h : request.headers().entrySet()) {
			headers.addField(HeadersFactory.newField(h.getKey(), h.getValue()));
		}
		IMessageBody body = request.body() == null ? null
				: new StringBody(request.contentType() == null ? "text/plain" : request.contentType(), request.body());
		IProgressMonitor monitor = new NullProgressMonitor() {
			@Override
			public boolean isCanceled() {
				return cancel.isCancelled();
			}
		};
		try {
			IResponse response = switch (request.method()) {
			case "GET" -> resource.get(monitor, headers, IResponse.class);
			case "POST" -> invoke(resource, "post", monitor, headers, body);
			case "PUT" -> invoke(resource, "put", monitor, headers, body);
			case "DELETE" -> {
				resource.delete(monitor);
				yield null;
			}
			default -> throw new IOException("Unsupported method " + request.method());
			};
			return toResponse(response, 200);
		} catch (ResourceException e) {
			return toResponse(e.getResponse(), 500);
		} catch (RuntimeException e) {
			throw new IOException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * POST/PUT with headers and a raw body. Resolved reflectively because the
	 * overloads differ between ADT releases: {@code (monitor, headers, type, body)}.
	 */
	private static IResponse invoke(IRestResource resource, String name, IProgressMonitor monitor, IHeaders headers,
			IMessageBody body) throws IOException {
		for (Method m : IRestResource.class.getMethods()) {
			Class<?>[] p = m.getParameterTypes();
			if (m.getName().equals(name) && p.length == 4 && p[0] == IProgressMonitor.class && p[1] == IHeaders.class
					&& p[2] == Class.class) {
				try {
					return (IResponse) m.invoke(resource, monitor, headers, IResponse.class, body);
				} catch (InvocationTargetException e) {
					if (e.getCause() instanceof ResourceException re) {
						throw re;
					}
					throw new IOException(String.valueOf(e.getCause()), e.getCause());
				} catch (IllegalAccessException e) {
					throw new IOException(e);
				}
			}
		}
		throw new IOException("This ADT version has no " + name + "(monitor, headers, type, body) method.");
	}

	private static AdtResponse toResponse(IResponse response, int fallbackStatus) throws IOException {
		if (response == null) {
			return new AdtResponse(fallbackStatus == 500 ? 500 : 204, "", "");
		}
		Object status = SdkAdtBackend.Reflect.call(response, "getStatus");
		int code = status instanceof Number n ? n.intValue() : fallbackStatus;
		IMessageBody body = response.getBody();
		String text = "";
		String contentType = "";
		if (body != null) {
			contentType = String.valueOf(SdkAdtBackend.Reflect.call(body, "getContentType"));
			try (InputStream in = body.getContent()) {
				text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		return new AdtResponse(code, contentType, text);
	}

	@Override
	public void close() {
		if (session == null) {
			return;
		}
		for (String m : new String[] { "destroy", "close", "dispose" }) {
			try {
				Method method = session.getClass().getMethod(m, IProgressMonitor.class);
				method.invoke(session, new NullProgressMonitor());
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				// try the next variant
			}
			if (SdkAdtBackend.Reflect.call(session, m) != null) {
				return;
			}
		}
	}

	/** Raw request body. */
	private static final class StringBody extends AbstractMessageBody {
		private final byte[] bytes;

		StringBody(String contentType, String text) {
			super(contentType);
			this.bytes = text.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public InputStream getContent() {
			return new ByteArrayInputStream(bytes);
		}
	}
}

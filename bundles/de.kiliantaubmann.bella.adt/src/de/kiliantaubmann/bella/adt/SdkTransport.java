package de.kiliantaubmann.bella.adt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import de.kiliantaubmann.bella.core.adt.AdtConnectionException;
import de.kiliantaubmann.bella.core.adt.AdtRequest;
import de.kiliantaubmann.bella.core.adt.AdtResponse;
import de.kiliantaubmann.bella.core.adt.AdtTransport;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.core.util.Reflection;

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

	@Override
	public boolean isStateful() {
		return session != null;
	}

	static ISystemSession openStatefulSession(String destinationId) throws IOException {
		Object factory = AdtSystemSessionFactory.createSystemSessionFactory();
		try {
			Method m = factory.getClass().getMethod("createStatefulSession", String.class);
			return (ISystemSession) m.invoke(factory, destinationId);
		} catch (ReflectiveOperationException | ClassCastException e) {
			Log.warn("adt", "no stateful ADT session for " + destinationId + " (factory "
					+ factory.getClass().getName() + "): " + e);
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
					Log.warn("adt", "createRestResource(URI, " + session.getClass().getName() + ") failed: " + e);
					throw new IOException("Cannot create ADT resource: " + e.getMessage(), e);
				}
			}
		}
		Log.warn("adt", "no createRestResource(URI, session) method for " + session.getClass().getName() + " in "
				+ factory.getClass().getName());
		throw new IOException("This ADT version cannot create resources for a stateful session.");
	}

	/**
	 * Sends the request. A broken connection becomes an
	 * {@link AdtConnectionException} with a short message; a stateless GET is
	 * tried once more first, since the communication layer opens a new
	 * connection then. Writes are never repeated.
	 */
	@Override
	public AdtResponse send(AdtRequest request, CancelToken cancel) throws IOException {
		try {
			return sendOnce(request, cancel);
		} catch (IOException first) {
			if (!connectionLost(first)) {
				throw first;
			}
			IOException last = first;
			if (session == null && "GET".equals(request.method()) && !cancel.isCancelled()) {
				Log.info("adt", "connection to " + destinationId + " lost, trying " + request.path() + " once more");
				try {
					return sendOnce(request, cancel);
				} catch (IOException second) {
					if (!connectionLost(second)) {
						throw second;
					}
					last = second;
				}
			}
			throw new AdtConnectionException(destinationId, AdtConnectionException.shortDetail(rootMessage(last)));
		}
	}

	/** The ADT communication layer reports a broken connection as a CommunicationException. */
	static boolean connectionLost(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t.getClass().getName().endsWith("CommunicationException")) {
				return true;
			}
		}
		return false;
	}

	private static String rootMessage(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t.getClass().getName().endsWith("CommunicationException") && t.getMessage() != null) {
				return t.getMessage();
			}
		}
		return e.getMessage();
	}

	private AdtResponse sendOnce(AdtRequest request, CancelToken cancel) throws IOException {
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
			case "PATCH" -> patch(resource, uri.getPath(), monitor, headers, body);
			// Bella's own ADT deletes send no headers and keep the plain call
			case "DELETE" -> request.headers().isEmpty() ? delete(resource, monitor) : delete(resource, monitor, headers);
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
	 * POST/PUT with headers and a raw body. The overloads differ between ADT
	 * releases, e.g. {@code (monitor, headers, type, body)} or with trailing
	 * query parameters, so the method is chosen by parameter types.
	 */
	private static IResponse invoke(IRestResource resource, String name, IProgressMonitor monitor, IHeaders headers,
			IMessageBody body) throws IOException {
		Optional<Reflection.Call> call = Reflection.bestMatch(IRestResource.class, name, monitor, headers,
				IResponse.class, body);
		if (call.isEmpty()) {
			List<String> available = Reflection.signatures(IRestResource.class, name);
			Log.warn("adt", "IRestResource has no usable " + name + " overload in this ADT version; available: "
					+ available);
			throw new IOException("Bella cannot " + name.toUpperCase(java.util.Locale.ROOT)
					+ " through this ADT version (available: " + available
					+ "). Change the code in the editor instead, or use ARC-1.");
		}
		Log.debug("adt", () -> "using " + call.get().method());
		try {
			return (IResponse) call.get().invoke(resource);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof ResourceException re) {
				throw re;
			}
			throw new IOException(String.valueOf(e.getCause()), e.getCause());
		} catch (ReflectiveOperationException | IllegalArgumentException e) {
			throw new IOException(e);
		}
	}

	/**
	 * PATCH where the ADT release offers it. Otherwise, for SAP Gateway
	 * (OData) only, a POST with {@code X-HTTP-Method: PATCH}, which Gateway
	 * reads as PATCH; other handlers may ignore the header and would run a
	 * POST, so they are refused.
	 */
	private static IResponse patch(IRestResource resource, String path, IProgressMonitor monitor, IHeaders headers,
			IMessageBody body) throws IOException {
		if (Reflection.bestMatch(IRestResource.class, "patch", monitor, headers, IResponse.class, body).isPresent()) {
			return invoke(resource, "patch", monitor, headers, body);
		}
		String p = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
		if (!p.startsWith("/sap/opu/odata/") && !p.startsWith("/sap/opu/odata4/")) {
			throw new IOException("This ADT version cannot send PATCH, and " + path + " is no SAP Gateway (OData) "
					+ "service that would read a POST with X-HTTP-Method: PATCH as one. Nothing was sent; use PUT "
					+ "if the service accepts it.");
		}
		headers.addField(HeadersFactory.newField("X-HTTP-Method", "PATCH"));
		return invoke(resource, "post", monitor, headers, body);
	}

	/**
	 * DELETE with headers and the answer where the ADT release offers such an
	 * overload. The plain {@code delete(monitor)} would drop the headers
	 * (If-Match, the CSRF token), so without the overload nothing is sent.
	 */
	private static IResponse delete(IRestResource resource, IProgressMonitor monitor, IHeaders headers)
			throws IOException {
		Optional<Reflection.Call> call = Reflection.bestMatch(IRestResource.class, "delete", monitor, headers,
				IResponse.class);
		if (call.isEmpty() || call.get().method().getParameterCount() <= 1) {
			throw new IOException("This ADT version cannot send a DELETE with headers (available: "
					+ Reflection.signatures(IRestResource.class, "delete") + "). Nothing was sent.");
		}
		try {
			Object r = call.get().invoke(resource);
			return r instanceof IResponse response ? response : null;
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof ResourceException re) {
				throw re;
			}
			throw new IOException(String.valueOf(e.getCause()), e.getCause());
		} catch (ReflectiveOperationException | IllegalArgumentException e) {
			throw new IOException(e);
		}
	}

	private static IResponse delete(IRestResource resource, IProgressMonitor monitor) {
		resource.delete(monitor);
		return null;
	}

	/** Response headers Bella reads; cookies are never passed on. */
	private static final String[] HEADERS = { "ETag", "x-csrf-token", "Location", "Content-Type", "sap-message",
			"sap-statistics", "sap-perf-fesrec", "DataServiceVersion", "OData-Version", "OData-EntityId" };

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
				byte[] bytes = in == null ? new byte[0] : in.readAllBytes();
				text = textual(contentType) ? new String(bytes, StandardCharsets.UTF_8)
						: "[" + bytes.length + " bytes " + contentType + "]";
			}
		}
		Map<String, String> headers = new LinkedHashMap<>();
		for (String name : HEADERS) {
			String v = header(response, name);
			if (v != null) {
				headers.put(name, v);
			}
		}
		return new AdtResponse(code, contentType, text, headers);
	}

	/** Whether a body of this type is text; images, PDFs and archives would be garbled as UTF-8. */
	static boolean textual(String contentType) {
		String t = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT).trim();
		return !(t.startsWith("image/") || t.startsWith("audio/") || t.startsWith("video/")
				|| t.startsWith("application/pdf") || t.startsWith("application/zip")
				|| t.startsWith("application/octet-stream") || t.startsWith("application/vnd.openxmlformats"))
				|| t.contains("svg");
	}

	/**
	 * A response header, read via reflection because the header API differs
	 * between ADT releases; {@code null} if absent or not readable.
	 */
	static String header(Object response, String name) {
		Object headers = SdkAdtBackend.Reflect.call(response, "getHeaders");
		if (headers == null) {
			return null;
		}
		boolean readable = false;
		for (String getter : new String[] { "getField", "getFirstField", "getHeader", "get" }) {
			Object field;
			try {
				Method m = headers.getClass().getMethod(getter, String.class);
				m.setAccessible(true);
				field = m.invoke(headers, name);
				readable = true;
			} catch (ReflectiveOperationException | RuntimeException e) {
				continue;
			}
			if (field instanceof String value) {
				return value;
			}
			for (String value : new String[] { "getValue", "getContent", "getFieldValue" }) {
				String v = field == null ? null : SdkAdtBackend.Reflect.string(field, value);
				if (v != null) {
					return v;
				}
			}
		}
		if (!readable) {
			SdkAdtBackend.Reflect.once("response headers not readable from " + headers.getClass().getName());
		}
		return null;
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

		/** The SDK prints the body into the request dump of its exceptions. */
		@Override
		public String toString() {
			String text = new String(bytes, StandardCharsets.UTF_8);
			return text.length() <= 2_000 ? text : text.substring(0, 2_000) + "…";
		}
	}
}

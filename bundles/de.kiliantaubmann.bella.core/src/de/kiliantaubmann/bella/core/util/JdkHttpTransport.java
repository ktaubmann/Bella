package de.kiliantaubmann.bella.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** {@link HttpTransport} on top of {@link java.net.http.HttpClient}. */
final class JdkHttpTransport implements HttpTransport {

	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	@Override
	public Response post(URI uri, Map<String, String> headers, String body, CancelToken cancel) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		headers.forEach(builder::header);
		long start = System.nanoTime();
		try {
			// Asynchronous, so Stop also ends a request that is still waiting for the response headers.
			CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(builder.build(),
					HttpResponse.BodyHandlers.ofInputStream());
			cancel.onCancel(() -> pending.cancel(true));
			HttpResponse<InputStream> response;
			try {
				response = pending.get();
			} catch (CancellationException e) {
				throw new IOException("cancelled", e);
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
			}
			log("POST", uri, response.statusCode(), start);
			InputStream in = response.body();
			cancel.onCancel(in);
			return new Response() {
				@Override
				public int status() {
					return response.statusCode();
				}

				@Override
				public Optional<String> header(String name) {
					return response.headers().firstValue(name);
				}

				@Override
				public InputStream body() {
					return in;
				}

				@Override
				public void close() throws IOException {
					in.close();
				}
			};
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		} catch (IOException e) {
			Log.warn(AREA, "POST " + display(uri) + " failed after " + Log.millisSince(start) + " ms: " + e);
			throw e;
		}
	}

	@Override
	public void delete(URI uri, Map<String, String> headers) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).DELETE();
		headers.forEach(builder::header);
		long start = System.nanoTime();
		try {
			log("DELETE", uri, client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode(), start);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}

	private static final String AREA = "http";

	private static void log(String method, URI uri, int status, long start) {
		String line = method + " " + display(uri) + " -> " + status + " (" + Log.millisSince(start) + " ms)";
		if (status >= 400) {
			Log.warn(AREA, line);
		} else {
			Log.info(AREA, line);
		}
	}

	/** Scheme, host and path; user info and query may carry secrets and are left out. */
	static String display(URI uri) {
		return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
				+ (uri.getPath() == null ? "" : uri.getPath());
	}
}

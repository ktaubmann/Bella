package de.kiliantaubmann.bella.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
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

	/**
	 * Redirects are followed by hand: the requests carry API keys and bearer
	 * tokens, which must never go to another host.
	 */
	private final HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	static final int MAX_REDIRECTS = 5;

	@Override
	public Response post(URI uri, Map<String, String> headers, String body, CancelToken cancel) throws IOException {
		long start = System.nanoTime();
		URI target = uri;
		try {
			HttpResponse<InputStream> response = send(target, headers, body, cancel);
			for (int hops = 0; isRedirect(response.statusCode()); hops++) {
				URI next = location(target, response);
				response.body().close();
				if (next == null || hops >= MAX_REDIRECTS) {
					throw new IOException("HTTP " + response.statusCode() + " redirect from " + display(target)
							+ (next == null ? " without a valid Location" : ": too many redirects"));
				}
				if (!sameOrigin(target, next)) {
					throw new IOException("Refused a redirect from " + display(target) + " to " + display(next)
							+ ": API keys and tokens are only sent to the configured host.");
				}
				if (response.statusCode() != 307 && response.statusCode() != 308) {
					throw new IOException("HTTP " + response.statusCode() + " redirect from " + display(target)
							+ " to " + display(next) + ": correct the configured URL.");
				}
				Log.info(AREA, "POST " + display(target) + " redirected to " + display(next));
				target = next;
				response = send(target, headers, body, cancel);
			}
			log("POST", target, response.statusCode(), start);
			HttpResponse<InputStream> last = response;
			InputStream in = response.body();
			cancel.onCancel(in);
			return new Response() {
				@Override
				public int status() {
					return last.statusCode();
				}

				@Override
				public Optional<String> header(String name) {
					return last.headers().firstValue(name);
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
		} catch (ConnectException e) {
			// a local server that is not started (ARC-1 on localhost) is no fault; the caller reports it once
			Log.info(AREA, "POST " + display(target) + ": connection refused");
			throw e;
		} catch (IOException e) {
			Log.warn(AREA, "POST " + display(target) + " failed after " + Log.millisSince(start) + " ms: " + e);
			throw e;
		}
	}

	/** Sends asynchronously, so Stop also ends a request that is still waiting for the response headers. */
	private HttpResponse<InputStream> send(URI uri, Map<String, String> headers, String body, CancelToken cancel)
			throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		headers.forEach(builder::header);
		CompletableFuture<HttpResponse<InputStream>> pending = client.sendAsync(builder.build(),
				HttpResponse.BodyHandlers.ofInputStream());
		cancel.onCancel(() -> pending.cancel(true));
		try {
			return pending.get();
		} catch (CancellationException e) {
			throw new IOException("cancelled", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
		}
	}

	static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static URI location(URI from, HttpResponse<?> response) {
		try {
			return response.headers().firstValue("location").map(from::resolve).orElse(null);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Same scheme, host and port; {@code http} to {@code https} on the same host counts too. */
	static boolean sameOrigin(URI a, URI b) {
		if (a.getHost() == null || b.getHost() == null || !a.getHost().equalsIgnoreCase(b.getHost())) {
			return false;
		}
		String sa = a.getScheme().toLowerCase(java.util.Locale.ROOT);
		String sb = b.getScheme().toLowerCase(java.util.Locale.ROOT);
		if (sa.equals("https") && !sb.equals("https")) {
			return false; // never downgrade
		}
		return port(a) == port(b) || (sa.equals("http") && sb.equals("https"));
	}

	private static int port(URI u) {
		return u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
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

	static String display(URI uri) {
		return HttpTransport.display(uri);
	}
}

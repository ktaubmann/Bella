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
		try {
			HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
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
		}
	}

	@Override
	public void delete(URI uri, Map<String, String> headers) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).DELETE();
		headers.forEach(builder::header);
		try {
			client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}
}

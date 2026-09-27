package de.kiliantaubmann.bella.core.testutil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.HttpTransport;

/** Replays canned HTTP responses and records the requests. */
public final class FakeHttp implements HttpTransport {

	public record Sent(URI uri, Map<String, String> headers, String body) {
	}

	public record Canned(int status, Map<String, String> headers, String body) {
	}

	public final List<Sent> sent = new ArrayList<>();
	private final Deque<Canned> responses = new ArrayDeque<>();

	public FakeHttp respond(int status, String contentType, String body) {
		responses.add(new Canned(status, Map.of("content-type", contentType), body));
		return this;
	}

	public FakeHttp respond(int status, Map<String, String> headers, String body) {
		responses.add(new Canned(status, headers, body));
		return this;
	}

	@Override
	public Response post(URI uri, Map<String, String> headers, String body, CancelToken cancel) {
		sent.add(new Sent(uri, Map.copyOf(headers), body));
		Canned c = responses.isEmpty() ? new Canned(500, Map.of(), "no canned response") : responses.poll();
		InputStream in = new ByteArrayInputStream(c.body().getBytes(StandardCharsets.UTF_8));
		return new Response() {
			@Override
			public int status() {
				return c.status();
			}

			@Override
			public Optional<String> header(String name) {
				return c.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
						.map(Map.Entry::getValue).findFirst();
			}

			@Override
			public InputStream body() {
				return in;
			}

			@Override
			public void close() {
			}
		};
	}

	/** Builds an SSE body from alternating event names and JSON data. */
	public static String sse(String... eventAndData) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < eventAndData.length; i += 2) {
			sb.append("event: ").append(eventAndData[i]).append('\n');
			sb.append("data: ").append(eventAndData[i + 1]).append("\n\n");
		}
		return sb.toString();
	}
}

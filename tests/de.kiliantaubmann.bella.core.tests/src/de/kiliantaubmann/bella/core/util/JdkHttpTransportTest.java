package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JdkHttpTransportTest {

	@Test
	void stopEndsARequestThatWaitsForTheResponseHeaders() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			Thread accept = new Thread(() -> {
				try (Socket s = silent.accept()) {
					Thread.sleep(10_000); // never answers
				} catch (IOException | InterruptedException e) {
					// test ended
				}
			});
			accept.setDaemon(true);
			accept.start();
			CancelToken cancel = new CancelToken();
			new Thread(() -> {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					return;
				}
				cancel.cancel();
			}).start();
			long start = System.nanoTime();
			assertThrows(IOException.class, () -> HttpTransport.jdk().post(
					URI.create("http://127.0.0.1:" + silent.getLocalPort() + "/v1/messages"), Map.of(), "{}", cancel));
			assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "Stop must not wait for the server");
		}
	}

	/** Answers each request with the next canned response; records request lines and headers. */
	private static ServerSocket server(List<String> seen, String... responses) throws IOException {
		ServerSocket ss = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
		Thread t = new Thread(() -> {
			for (String response : responses) {
				try (Socket s = ss.accept()) {
					var in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
							java.nio.charset.StandardCharsets.ISO_8859_1));
					int length = 0;
					String line;
					while ((line = in.readLine()) != null && !line.isEmpty()) {
						seen.add(line);
						if (line.toLowerCase().startsWith("content-length:")) {
							length = Integer.parseInt(line.substring(15).trim());
						}
					}
					char[] body = new char[length];
					int read = 0;
					while (read < length) {
						read += in.read(body, read, length - read);
					}
					s.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
					s.getOutputStream().flush();
				} catch (IOException e) {
					return;
				}
			}
		});
		t.setDaemon(true);
		t.start();
		return ss;
	}

	private static String redirect(int status, String location) {
		return "HTTP/1.1 " + status + " Redirect\r\nLocation: " + location + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
	}

	private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";

	@Test
	void followsTemporaryRedirectsOnTheSameHost() throws Exception {
		List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ServerSocket ss = server(seen, redirect(307, "/v2/messages"), OK)) {
			URI uri = URI.create("http://127.0.0.1:" + ss.getLocalPort() + "/v1/messages");
			try (HttpTransport.Response r = HttpTransport.jdk().post(uri, Map.of("x-api-key", "k"), "{}",
					CancelToken.NONE)) {
				assertEquals(200, r.status());
			}
			assertTrue(seen.contains("POST /v2/messages HTTP/1.1"), seen.toString());
		}
	}

	@Test
	void neverSendsTheKeyToAnotherHost() throws Exception {
		List<String> other = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ServerSocket elsewhere = server(other, OK);
				ServerSocket ss = server(new ArrayList<>(),
						redirect(307, "http://localhost:" + elsewhere.getLocalPort() + "/steal"))) {
			URI uri = URI.create("http://127.0.0.1:" + ss.getLocalPort() + "/v1/messages");
			IOException e = assertThrows(IOException.class,
					() -> HttpTransport.jdk().post(uri, Map.of("x-api-key", "secret-key"), "{}", CancelToken.NONE));
			assertTrue(e.getMessage().contains("Refused a redirect"), e.getMessage());
			assertTrue(other.isEmpty(), "the other host got: " + other);
		}
	}

	@Test
	void originRules() {
		assertTrue(JdkHttpTransport.sameOrigin(URI.create("https://a.example/x"), URI.create("https://A.example:443/y")));
		assertTrue(JdkHttpTransport.sameOrigin(URI.create("http://a.example/x"), URI.create("https://a.example/y")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.example/x"), URI.create("http://a.example/y")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.example/x"), URI.create("https://b.example/y")));
		assertFalse(JdkHttpTransport.sameOrigin(URI.create("https://a.example/x"), URI.create("https://a.example:8443/y")));
	}
}

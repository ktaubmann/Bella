package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
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
}

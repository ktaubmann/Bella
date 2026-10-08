package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.testutil.LogRecorder;

class LogTest {

	@AfterEach
	void off() {
		Log.configure(null, Log.Level.INFO);
	}

	@Test
	void offWritesNothingAndBuildsNoContent() {
		assertFalse(Log.enabled(Log.Level.ERROR));
		Log.info("x", "hello");
		Log.debug("x", () -> {
			throw new AssertionError("content must not be built while the log is off");
		});
	}

	@Test
	void levelsAreRespected() {
		try (LogRecorder r = LogRecorder.start(Log.Level.INFO)) {
			Log.info("adt", "one");
			Log.warn("adt", "two");
			Log.debug("adt", () -> {
				throw new AssertionError("detail is off");
			});
			Log.error("llm", "three", new IllegalStateException("boom"));
			assertEquals(3, r.lines.size());
			assertEquals("INFO [adt] one", r.lines.get(0));
			assertTrue(r.lines.get(2).contains("java.lang.IllegalStateException: boom"), r.lines.get(2));
			assertFalse(Log.detail());
		}
		try (LogRecorder r = LogRecorder.start(Log.Level.DEBUG)) {
			Log.debug("tool", () -> "input");
			assertEquals(List.of("DEBUG [tool] input"), r.lines);
			assertTrue(Log.detail());
		}
	}

	@Test
	void secretsAreRemoved() {
		String s = Log.redact("x-api-key: sk-ant-api03-abcDEF_123 and {\"Authorization\":\"Bearer 0123abcd\"} "
				+ "and ghp_abcdefghijklmnopqrstuvwxyz0123 and github_pat_11ABCDEFG0123456789_abcdefghij "
				+ "COPILOT_GITHUB_TOKEN=gho_abcdefghijklmnopqrstuvwxyz \"token\": \"secret1\" "
				+ "?sap-client=100&sap-password=Geheim1 openai sk-proj-abcdefghijklmnopqrstuvwx");
		for (String secret : List.of("abcDEF_123", "0123abcd", "ghp_abcdef", "github_pat_11", "gho_abcdef",
				"secret1", "Geheim1", "sk-proj-abcdef")) {
			assertFalse(s.contains(secret), secret + " in " + s);
		}
		assertTrue(s.contains("sap-client=100"), s);
		// ABAP code stays readable
		assertEquals("lv_token = get_token( ). DATA token TYPE string.",
				Log.redact("lv_token = get_token( ). DATA token TYPE string."));
		assertEquals("", Log.redact(null));
		String code = "me->authorization = authorization.\\n  ENDMETHOD.\\n ls_logon-password = lv_x. **Authorization:** checked";
		assertEquals(code, Log.redact(code));
		assertEquals("authorization = ***\\n  ENDMETHOD.", Log.redact("authorization = abc.\\n  ENDMETHOD."));
		assertEquals("Authorization: *** next", Log.redact("Authorization: Basic dXNlcjpwdw== next"));
	}

	@Test
	void hyphenatedHeadersAndOtherSchemesAreRedacted() {
		assertEquals("x-csrf-token: *** X-Auth-Token: *** Proxy-Authorization: ***",
				Log.redact("x-csrf-token: AbC123xyz X-Auth-Token: t0k3n Proxy-Authorization: Basic dXNlcjpwdw=="));
		assertEquals("Authorization: *** next", Log.redact("Authorization: Negotiate YIIGhgYGKwYBBQUCoIIGejCCBnag next"));
		assertEquals("password: ***", Log.redact("password: *abc"));
	}

	@Test
	void clipsLongContent() {
		assertEquals("abc", Log.clip("abc", 5));
		assertEquals("abcde … [3 more characters]", Log.clip("abcdefgh", 5));
		assertEquals("", Log.clip(null));
	}

	@Test
	void lineTapPassesThroughAndReportsLines() throws Exception {
		List<String> seen = new ArrayList<>();
		InputStream in = LineTap.in(new ByteArrayInputStream("a\r\nbc\nd".getBytes(StandardCharsets.UTF_8)), seen::add);
		assertEquals("a\r\nbc\nd", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		assertEquals(List.of("a", "bc"), seen);

		List<String> written = new ArrayList<>();
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		OutputStream out = LineTap.out(sink, written::add);
		out.write("{\"x\":1}\n{\"y\"".getBytes(StandardCharsets.UTF_8));
		out.write(':');
		out.write("2}\n".getBytes(StandardCharsets.UTF_8));
		assertEquals("{\"x\":1}\n{\"y\":2}\n", sink.toString(StandardCharsets.UTF_8));
		assertEquals(List.of("{\"x\":1}", "{\"y\":2}"), written);
	}

	@Test
	void processDescriptionsHideValues() {
		assertEquals("claude.cmd", ProcessLauncher.displayName(List.of("C:\\Windows\\cmd.exe", "/c",
				"C:\\Users\\k\\AppData\\Roaming\\npm\\claude.cmd", "-p")));
		assertEquals("copilot", ProcessLauncher.displayName(List.of("/usr/local/bin/copilot", "--acp")));
		java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
		env.put("GH_TOKEN", "ghp_secret");
		env.put("ANTHROPIC_API_KEY", null);
		env.put("COPILOT_GITHUB_TOKEN", "x");
		String summary = ProcessLauncher.envSummary(env);
		assertEquals("set [COPILOT_GITHUB_TOKEN, GH_TOKEN], removed [ANTHROPIC_API_KEY]", summary);
		assertFalse(summary.contains("secret"));
		assertEquals("", ProcessLauncher.envSummary(Map.of()));
	}

	@Test
	void httpUrlsLeaveOutQueryAndUserInfo() {
		assertEquals("https://api.anthropic.com/v1/messages",
				JdkHttpTransport.display(java.net.URI.create("https://user:pw@api.anthropic.com/v1/messages?key=x")));
		assertEquals("http://localhost:3000/mcp", JdkHttpTransport.display(java.net.URI.create("http://localhost:3000/mcp")));
	}
}

package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.testutil.LogRecorder;

class ProtocolLogTest {

	@Test
	void streamingFragmentsAreCountedNotLogged() {
		try (LogRecorder log = LogRecorder.start(Log.Level.DEBUG)) {
			ProtocolLog p = new ProtocolLog("claude");
			p.line("->", "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"}}");
			p.line("->", "{\"type\":\"system\",\"subtype\":\"thinking_tokens\",\"estimated_tokens\":50}");
			p.line("->", "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"update\":{\"sessionUpdate\":\"agent_message_chunk\"}}}");
			p.line("->", "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"thinking\",\"signature\":\"CAQSmgYKEAgSGAI4AUIIdGhpbmtpbmc=\"},{\"type\":\"text\",\"text\":\"done\"}]}}");
			p.line("<-", "{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}");
			p.finish();
			assertEquals(3, log.lines.size(), log.all());
			assertTrue(log.lines.get(0).startsWith("DEBUG [cli] claude -> {\"type\":\"assistant\""), log.all());
			assertTrue(log.lines.get(0).contains("\"signature\":\"[…]\""), log.all());
			assertFalse(log.all().contains("CAQSmgYK"), log.all());
			assertTrue(log.lines.get(1).startsWith("DEBUG [cli] claude <- {\"type\":\"user\""), log.all());
			assertEquals("DEBUG [cli] claude: 3 streaming events not logged (their content is in the full messages)",
					log.lines.get(2));
		}
	}

	@Test
	void nothingIsCountedWithoutTheDetailLevel() {
		try (LogRecorder log = LogRecorder.start(Log.Level.INFO)) {
			ProtocolLog p = new ProtocolLog("copilot");
			p.line("->", "{\"type\":\"stream_event\"}");
			p.line("->", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
			p.finish();
			assertTrue(log.lines.isEmpty(), log.all());
		}
	}
}

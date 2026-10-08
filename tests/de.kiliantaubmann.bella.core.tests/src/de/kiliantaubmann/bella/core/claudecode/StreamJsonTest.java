package de.kiliantaubmann.bella.core.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.agent.ConversationListener;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StopReason;

class StreamJsonTest {

	static final class Recorder implements ConversationListener {
		final StringBuilder text = new StringBuilder();
		final StringBuilder thinking = new StringBuilder();
		final List<String> toolStarts = new ArrayList<>();

		@Override
		public void onText(String delta) {
			text.append(delta);
		}

		@Override
		public void onThinking(String delta) {
			thinking.append(delta);
		}

		@Override
		public void onToolUseStart(String id, String name) {
			toolStarts.add(name);
		}
	}

	@Test
	void streamsDeltasWithoutRepeatingTheFinalMessage() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		assertFalse(s.accept(FakeCli.init()));
		assertEquals("connected", s.mcpStatus());
		s.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
				+ "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}}");
		s.accept(FakeCli.delta("Die Methode "));
		s.accept(FakeCli.delta("liest Aufträge."));
		s.accept(FakeCli.assistantText("Die Methode liest Aufträge."));
		assertTrue(s.accept(FakeCli.success("Die Methode liest Aufträge.")));
		assertEquals("Die Methode liest Aufträge.", r.text.toString());
		assertEquals("hmm", r.thinking.toString());
		assertNull(s.errorNotice());
		ChatResult result = s.toResult("fallback");
		assertEquals("claude-opus-5", result.model());
		assertEquals(StopReason.END_TURN, result.stopReason());
		assertEquals(12, result.usage().inputTokens());
		assertEquals(3, result.usage().cacheReadTokens());
	}

	@Test
	void streamedTextBlocksAreSeparateParagraphs() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		String textStart = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,"
				+ "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}}";
		s.accept(textStart);
		s.accept(FakeCli.delta("I'm retrying that now."));
		s.accept(FakeCli.assistantText("I'm retrying that now."));
		s.accept(textStart);
		s.accept(FakeCli.delta("The API is not finished."));
		s.accept(FakeCli.assistantText("The API is not finished."));
		s.accept(FakeCli.success("The API is not finished."));
		assertEquals("I'm retrying that now.\n\nThe API is not finished.", r.text.toString());
		assertEquals("I'm retrying that now.\n\nThe API is not finished.", s.text());
	}

	@Test
	void showsCompleteMessagesWhenNothingWasStreamed() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		s.accept(FakeCli.assistantText("Erster Teil."));
		s.accept(FakeCli.assistantText("Zweiter Teil."));
		s.accept(FakeCli.success("Zweiter Teil."));
		assertEquals("Erster Teil.\n\nZweiter Teil.", r.text.toString());
	}

	@Test
	void usesResultTextAsLastResort() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		s.accept(FakeCli.success("Nur im Result."));
		assertEquals("Nur im Result.", r.text.toString());
	}

	@Test
	void reportsToolStartsWithBellaNames() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		s.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":1,"
				+ "\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"mcp__bella__adt_read_source\",\"input\":{}}}}");
		assertEquals(List.of("adt_read_source"), r.toolStarts);
	}

	@Test
	void ignoresNoiseSubAgentsAndUnknownTypes() {
		Recorder r = new Recorder();
		StreamJson s = new StreamJson(r);
		s.accept("npm WARN something");
		s.accept("");
		s.accept("[1,2]");
		s.accept("{\"type\":\"rate_limit_event\",\"info\":{}}");
		s.accept("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"x\"}]}}");
		s.accept("{\"type\":\"stream_event\",\"parent_tool_use_id\":\"t9\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"sub\"}}}");
		assertEquals("", r.text.toString());
		assertFalse(s.done());
	}

	@Test
	void classifiesErrors() {
		StreamJson limit = new StreamJson(new Recorder());
		limit.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Claude AI usage limit reached|1760000000\"}");
		assertTrue(limit.errorNotice().startsWith("cc_limit:"));
		assertEquals(StopReason.OTHER, limit.toResult("m").stopReason());

		StreamJson login = new StreamJson(new Recorder());
		login.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key · Please run /login\"}");
		assertEquals("cc_login", login.errorNotice());

		StreamJson other = new StreamJson(new Recorder());
		other.accept("{\"type\":\"result\",\"subtype\":\"error_max_turns\"}");
		assertEquals("cc_error:error_max_turns", other.errorNotice());
	}

	@Test
	void keepsControlRequestsForAnAnswer() {
		StreamJson s = new StreamJson(new Recorder());
		s.accept("{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\"}}");
		assertEquals("r1", s.takeControlRequest().get("request_id").getAsString());
		assertNull(s.takeControlRequest());
	}
}

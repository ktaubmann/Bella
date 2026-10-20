package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.AskUserToolProvider.Answer;
import de.kiliantaubmann.bella.core.tools.AskUserToolProvider.Question;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AskUserToolProviderTest {

	static final String TWO_QUESTIONS = """
			{"questions":[
			 {"question":"Which package?","header":"Development package","options":[
			  {"label":"$TMP (Recommended)","description":"local, no transport"},
			  {"label":"ZSD","description":"sales"}]},
			 {"question":"Which checks?","header":"Checks","multiSelect":true,"options":[
			  {"label":"ATC","description":""},{"label":"ABAP Unit","description":""},{"label":"Lint"}]}]}""";

	static final class FakeAsker implements AskUserToolProvider.Asker {
		final List<List<Question>> asked = new ArrayList<>();
		Optional<List<Answer>> answer = Optional.empty();

		@Override
		public Optional<List<Answer>> ask(List<Question> questions, CancelToken cancel) {
			asked.add(questions);
			return answer;
		}
	}

	@Test
	void questionsReachTheChatAndTheAnswersTheModel() throws Exception {
		FakeAsker asker = new FakeAsker();
		asker.answer = Optional.of(List.of(new Answer(List.of("ZSD"), ""),
				new Answer(List.of("ATC", "Lint"), "and a syntax check")));
		ToolResult r = new AskUserToolProvider(asker).call("ask_user", Json.parseObject(TWO_QUESTIONS),
				CancelToken.NONE);
		assertFalse(r.isError(), r.content());
		assertEquals(1, asker.asked.size());
		Question first = asker.asked.get(0).get(0);
		assertEquals("Which package?", first.question());
		assertEquals("Development…", first.header()); // cut to 12 characters like Claude Code's chips
		assertFalse(first.multiSelect());
		assertTrue(asker.asked.get(0).get(1).multiSelect());
		assertEquals("", asker.asked.get(0).get(1).options().get(2).description());
		assertTrue(r.content().contains("\"Which package?\" = \"ZSD\""), r.content());
		assertTrue(r.content().contains("\"Which checks?\" = \"ATC\", \"Lint\", own answer: \"and a syntax check\""),
				r.content());
	}

	@Test
	void dismissedQuestionsAreNoError() throws Exception {
		ToolResult r = new AskUserToolProvider(new FakeAsker()).call("ask_user", Json.parseObject(TWO_QUESTIONS),
				CancelToken.NONE);
		assertFalse(r.isError());
		assertTrue(r.content().contains("dismissed"));
	}

	@Test
	void withoutAChatTheModelIsToldToAskInItsAnswer() throws Exception {
		ToolResult r = new AskUserToolProvider(null).call("ask_user", Json.parseObject(TWO_QUESTIONS),
				CancelToken.NONE);
		assertTrue(r.isError());
		assertTrue(r.content().contains("Ask in your answer"));
	}

	@Test
	void invalidQuestionsAreRefusedWithAReason() throws Exception {
		FakeAsker asker = new FakeAsker();
		AskUserToolProvider p = new AskUserToolProvider(asker);
		String oneOption = "{\"questions\":[{\"question\":\"Q?\",\"header\":\"H\",\"options\":[{\"label\":\"A\"}]}]}";
		String twice = "{\"questions\":[{\"question\":\"Q?\",\"header\":\"H\",\"options\":[{\"label\":\"A\"},"
				+ "{\"label\":\"a\"}]}]}";
		String none = "{\"questions\":[]}";
		for (String bad : List.of(oneOption, twice, none)) {
			ToolResult r = p.call("ask_user", Json.parseObject(bad), CancelToken.NONE);
			assertTrue(r.isError(), bad);
		}
		assertTrue(asker.asked.isEmpty());
	}

	@Test
	void questionsAndAnswersSurviveTheTripThroughThePage() {
		List<Question> qs = AskUserToolProvider.parse(Json.parseObject(TWO_QUESTIONS));
		assertEquals(qs, AskUserToolProvider.fromJson(Json.parseObject(Json.GSON.toJson(AskUserToolProvider.toJson(qs)))));
		Optional<List<Answer>> answers = Optional.of(List.of(new Answer(List.of("ZSD"), ""),
				new Answer(List.of(), "own")));
		assertEquals(answers, AskUserToolProvider.answersFromJson(AskUserToolProvider.answersToJson(answers)));
		assertEquals(Optional.empty(), AskUserToolProvider.answersFromJson(Json.parseStrict("null")));
		assertEquals(null, AskUserToolProvider.answersToJson(Optional.empty()));
	}

	@Test
	void runsWithoutConfirmationAlsoInPlanMode() {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(new AskUserToolProvider(new FakeAsker()));
		registry.refresh(e -> {
		});
		ToolSpec spec = registry.find("ask_user").orElseThrow();
		JsonObject input = Json.parseObject(TWO_QUESTIONS);
		for (ChatMode mode : ChatMode.values()) {
			assertEquals(ToolPolicy.Decision.AUTO, new ToolPolicy(List.of()).withMode(mode).decide(spec, input),
					mode.name());
		}
	}
}

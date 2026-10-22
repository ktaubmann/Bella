package de.kiliantaubmann.bella.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelCapsTest {

	@Test
	void contextWindowByModelFamily() {
		assertEquals(1_000_000, ModelCaps.contextWindow("claude-opus-5-5"));
		assertEquals(1_000_000, ModelCaps.contextWindow("claude-sonnet-4-6"));
		assertEquals(1_000_000, ModelCaps.contextWindow("us.anthropic.claude-haiku-5-5"));
		assertEquals(200_000, ModelCaps.contextWindow("claude-haiku-4-5"));
		assertEquals(200_000, ModelCaps.contextWindow("claude-sonnet-4-5-20250929"));
		assertEquals(200_000, ModelCaps.contextWindow("claude-sonnet-4-20250514"));
		assertEquals(0, ModelCaps.contextWindow("gpt-5"));
		assertEquals(0, ModelCaps.contextWindow(null));
	}
}

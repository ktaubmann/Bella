package de.kiliantaubmann.bella.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void haiku45ClearsToolResultsButHasNoToolSearch() {
		assertFalse(ModelCaps.toolSearch("claude-haiku-4-5"));
		assertTrue(ModelCaps.contextEditing("claude-haiku-4-5"));
		assertTrue(ModelCaps.toolSearch("claude-haiku-5-5"));
		assertTrue(ModelCaps.toolSearch("claude-opus-5-5"));
		assertTrue(ModelCaps.contextEditing("claude-sonnet-4-5-20250929"));
		assertFalse(ModelCaps.contextEditing("claude-sonnet-4-20250514"));
	}
}

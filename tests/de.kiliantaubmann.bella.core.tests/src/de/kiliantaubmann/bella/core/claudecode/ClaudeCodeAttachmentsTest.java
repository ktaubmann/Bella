package de.kiliantaubmann.bella.core.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.Attachment;
import de.kiliantaubmann.bella.core.util.Json;

class ClaudeCodeAttachmentsTest {

	@Test
	void streamJsonUserMessageCarriesImageBlocks() {
		Attachment png = new Attachment("image/png", "iVBORw0KGgo=");
		JsonObject msg = ClaudeCodeSession.userMessage("What is this dump?", List.of(png));
		assertEquals("user", Json.str(msg, "type"));
		JsonObject message = Json.obj(msg, "message");
		assertEquals("user", Json.str(message, "role"));
		JsonArray content = Json.arr(message, "content");
		assertEquals(2, content.size());
		assertEquals(png.anthropicBlock(), content.get(0));
		assertEquals("What is this dump?", Json.str(content.get(1).getAsJsonObject(), "text"));

		JsonArray plain = Json.arr(Json.obj(ClaudeCodeSession.userMessage("Hi", List.of()), "message"), "content");
		assertEquals(1, plain.size());
	}
}

package de.kiliantaubmann.bella.core.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.Attachment;
import de.kiliantaubmann.bella.core.util.Json;

class CopilotAttachmentsTest {

	private static List<String> types(JsonArray prompt) {
		List<String> out = new ArrayList<>();
		prompt.forEach(e -> out.add(Json.str(e.getAsJsonObject(), "type")));
		return out;
	}

	@Test
	void imagesFollowTheQuestionAndPrecedeTheInstructions() {
		Attachment png = new Attachment("image/png", "iVBORw0KGgo=");
		JsonArray prompt = Json.arr(
				CopilotSession.promptParams("s1", "<instructions>\nBe Bella\n</instructions>\n\nWhat is this?",
						List.of(png)),
				"prompt");
		assertEquals(List.of("text", "image", "text"), types(prompt));
		assertEquals("What is this?", Json.str(prompt.get(0).getAsJsonObject(), "text"));
		JsonObject image = prompt.get(1).getAsJsonObject();
		assertEquals("image/png", Json.str(image, "mimeType"));
		assertEquals("iVBORw0KGgo=", Json.str(image, "data"));

		assertEquals(List.of("text", "image"),
				types(Json.arr(CopilotSession.promptParams("s1", "Hi", List.of(png)), "prompt")));

		Attachment pdf = new Attachment("Spec.pdf", Attachment.PDF, "JVBERi0xLjQK");
		JsonArray withPdf = Json.arr(CopilotSession.promptParams("s1", "Hi", List.of(pdf)), "prompt");
		assertEquals(List.of("text", "resource"), types(withPdf));
		JsonObject resource = Json.obj(withPdf.get(1).getAsJsonObject(), "resource");
		assertEquals("file:///Spec.pdf", Json.str(resource, "uri"));
		assertEquals(Attachment.PDF, Json.str(resource, "mimeType"));
		assertEquals("JVBERi0xLjQK", Json.str(resource, "blob"));
	}

	@Test
	void imageCapabilityComesFromInitialize() {
		Acp acp = new Acp(new FakeAcp().start(List.of("copilot"), java.util.Map.of(), null), (m, p) -> {
		}, (m, p) -> null);
		try {
			acp.initialized(Json.parseObject("{\"agentCapabilities\":{\"mcpCapabilities\":{\"http\":true}}}"));
			assertFalse(acp.imagePrompts());
			acp.initialized(Json.parseObject("{\"agentCapabilities\":{\"promptCapabilities\":{\"image\":true}}}"));
			assertTrue(acp.imagePrompts());
			assertFalse(acp.resourcePrompts());
			acp.initialized(Json.parseObject("{\"agentCapabilities\":{\"promptCapabilities\":{\"embeddedContext\":true}}}"));
			assertTrue(acp.resourcePrompts());
		} finally {
			acp.close();
		}
	}
}

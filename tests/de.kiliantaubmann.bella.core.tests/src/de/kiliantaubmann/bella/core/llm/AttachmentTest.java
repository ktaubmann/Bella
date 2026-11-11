package de.kiliantaubmann.bella.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.agent.ChatRecord;
import de.kiliantaubmann.bella.core.agent.ChatSession;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AttachmentTest {

	static final Attachment PNG = new Attachment("image/png", "iVBORw0KGgo=");

	@Test
	void onlyFormatsClaudeReadsAndAtMostFiveMegabytes() {
		assertThrows(IllegalArgumentException.class, () -> new Attachment("image/bmp", "AAAA"));
		assertThrows(IllegalArgumentException.class, () -> new Attachment("image/png", ""));
		assertThrows(IllegalArgumentException.class,
				() -> new Attachment("image/png", "A".repeat(Attachment.MAX_IMAGE_BASE64 + 1)));
		assertEquals("data:image/png;base64,iVBORw0KGgo=", PNG.dataUrl());
	}

	@Test
	void anthropicBlockRoundTrips() {
		JsonObject block = PNG.anthropicBlock();
		assertEquals("image", Json.str(block, "type"));
		assertEquals("base64", Json.str(Json.obj(block, "source"), "type"));
		assertEquals(PNG, Attachment.fromAnthropicBlock(block));
		assertNull(Attachment.fromAnthropicBlock(Json.textBlock("x")));
	}

	@Test
	void imagesComeBeforeTheText() {
		JsonArray content = Json.arr(Json.userContent("What is wrong?", List.of(PNG, PNG)), "content");
		List<String> types = new ArrayList<>();
		content.forEach(e -> types.add(Json.str(e.getAsJsonObject(), "type")));
		assertEquals(List.of("image", "image", "text"), types);
		// an image alone needs no empty text block
		assertEquals(1, Json.arr(Json.userContent("", List.of(PNG)), "content").size());
	}

	@Test
	void openAiGetsImagesAsContentParts() {
		JsonArray out = new JsonArray();
		OpenAiCompatibleProvider.convertMessage(Json.userContent("Explain", List.of(PNG)), out);
		assertEquals(1, out.size());
		JsonArray parts = out.get(0).getAsJsonObject().getAsJsonArray("content");
		assertEquals("text", Json.str(parts.get(0).getAsJsonObject(), "type"));
		assertEquals("Explain", Json.str(parts.get(0).getAsJsonObject(), "text"));
		JsonObject image = parts.get(1).getAsJsonObject();
		assertEquals("image_url", Json.str(image, "type"));
		assertEquals(PNG.dataUrl(), Json.str(Json.obj(image, "image_url"), "url"));
	}

	@Test
	void sessionSendsImagesAndDropsThemWithAFailedQuestion() throws Exception {
		List<ChatRequest> requests = new ArrayList<>();
		boolean[] fail = { true };
		LlmProvider llm = new LlmProvider() {
			@Override
			public String id() {
				return "fake";
			}

			@Override
			public ChatResult chat(ChatRequest request, StreamListener listener, CancelToken cancel)
					throws LlmException {
				requests.add(request);
				if (fail[0]) {
					throw new LlmException(500, "down");
				}
				JsonArray content = new JsonArray();
				content.add(Json.textBlock("A dump."));
				return new ChatResult(Json.message("assistant", content), "A dump.", List.of(), StopReason.END_TURN,
						null, "m", Usage.NONE);
			}
		};
		ChatSession session = new ChatSession(() -> llm, () -> new ChatSession.Settings("m", 1000, null), "sys", null,
				ToolPolicy::defaults, (tool, input) -> true, null);
		assertThrows(LlmException.class, () -> session.send("What is this?", List.of(PNG), new ChatSession.Listener() {
		}, CancelToken.NONE));
		assertTrue(session.history().isEmpty(), "the failed question with its image is taken back");

		fail[0] = false;
		session.send("What is this?", List.of(PNG), new ChatSession.Listener() {
		}, CancelToken.NONE);
		JsonArray sent = Json.arr(requests.get(1).messages().get(0), "content");
		assertEquals("image", Json.str(sent.get(0).getAsJsonObject(), "type"));
		assertEquals(2, session.history().size());
	}

	@Test
	void chatRecordKeepsPreviewsAndTheTranscriptMentionsThem() {
		ChatRecord record = new ChatRecord();
		record.user(1, "Look at this", List.of("data:image/jpeg;base64,AAAA"), List.of("spec.md"));
		JsonObject entry = record.entries().get(0);
		assertEquals(List.of("data:image/jpeg;base64,AAAA"), ChatRecord.thumbnailsOf(entry));
		assertTrue(record.transcript(10_000).contains("[1 image attached, not available any more]"));

		ChatRecord again = ChatRecord.fromJson(record.toJson());
		assertEquals(List.of("data:image/jpeg;base64,AAAA"), ChatRecord.thumbnailsOf(again.entries().get(0)));
		assertEquals(List.of("spec.md"), ChatRecord.filesOf(again.entries().get(0)));
		assertTrue(record.transcript(10_000).contains("[attached spec.md, not available any more]"));
	}

	static final Attachment PDF = new Attachment("Spec.pdf", Attachment.PDF, "JVBERi0xLjQK");

	@Test
	void pdfBecomesADocumentBlockWithItsName() {
		JsonObject block = PDF.anthropicBlock();
		assertEquals("document", Json.str(block, "type"));
		assertEquals("Spec.pdf", Json.str(block, "title"));
		assertEquals(Attachment.PDF, Json.str(Json.obj(block, "source"), "media_type"));
		assertEquals(PDF, Attachment.fromAnthropicBlock(block));
		assertTrue(PDF.isPdf());
		// PDFs may be larger than images
		new Attachment("big.pdf", Attachment.PDF, "A".repeat(Attachment.MAX_IMAGE_BASE64 + 1));
	}

	@Test
	void openAiGetsAPdfAsFilePart() {
		JsonArray out = new JsonArray();
		OpenAiCompatibleProvider.convertMessage(Json.userContent("Summarise", List.of(PDF)), out);
		JsonArray parts = out.get(0).getAsJsonObject().getAsJsonArray("content");
		JsonObject file = parts.get(1).getAsJsonObject();
		assertEquals("file", Json.str(file, "type"));
		assertEquals("Spec.pdf", Json.str(Json.obj(file, "file"), "filename"));
		assertEquals(PDF.dataUrl(), Json.str(Json.obj(file, "file"), "file_data"));
	}

	@Test
	void exportsAnAnswerAndTheWholeChatAsMarkdown() {
		ChatRecord record = new ChatRecord();
		record.user(1, "Explain ZCL_ORDERS", List.of(), List.of("spec.md"));
		record.answer(2);
		record.text(2, 0, "It reads orders.");
		record.tool(2, 1, "adt_read_source", "{}", "ok", "…");
		record.text(2, 2, "## Details\n\n- one");

		assertEquals("It reads orders.\n\n## Details\n\n- one\n", record.answerMarkdown(2));
		String md = record.toMarkdown(new ChatRecord.MarkdownLabels("Developer", "Bella", "Attached", "Tool"));
		assertTrue(md.startsWith("# Explain ZCL_ORDERS\n"), md);
		assertTrue(md.contains("## Developer\n\nExplain ZCL_ORDERS\n\n*Attached: spec.md*\n"), md);
		assertTrue(md.contains("## Bella\n\nIt reads orders.\n"), md);
		assertTrue(md.contains("> Tool: `adt_read_source` (ok)\n"), md);
		assertTrue(md.contains("## Details\n\n- one\n"), md);
	}
}

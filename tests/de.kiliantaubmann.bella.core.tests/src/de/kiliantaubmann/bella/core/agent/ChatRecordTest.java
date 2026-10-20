package de.kiliantaubmann.bella.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

class ChatRecordTest {

	static ChatRecord sample() {
		ChatRecord r = new ChatRecord();
		r.user(1, "Explain   ZCL_ORDER\nplease");
		r.answer(2);
		r.text(2, 1, "It reads");
		r.text(2, 1, "It reads orders."); // the last state of a streamed segment replaces the first
		r.tool(2, 2, "adt_read_source", "{}", null, null);
		r.tool(2, 2, "adt_read_source", "{}", "ok", "CLASS zcl_order …");
		r.tool(2, 3, "adt_table_contents", "{\"table\":\"VBAK\"}", "ok", "0000004711 | Müller");
		r.questions(2, 4, Json.parseObject("{\"questions\":[{\"question\":\"Which package?\",\"header\":\"Package\","
				+ "\"options\":[{\"label\":\"ZSD\"},{\"label\":\"$TMP\"}]}]}"),
				(JsonArray) Json.parseStrict("[{\"chosen\":[\"ZSD\"],\"other\":\"\"}]"));
		r.notice(2, "warn", "Plan discarded");
		return r;
	}

	@Test
	void keepsWhatTheChatShowedWithoutBusinessData() {
		ChatRecord r = sample();
		assertEquals("Explain ZCL_ORDER please", r.title());
		List<JsonObject> e = r.entries();
		assertEquals(List.of("user", "bella", "text", "tool", "tool", "ask", "notice"),
				e.stream().map(x -> Json.str(x, "t")).toList());
		assertEquals("It reads orders.", Json.str(e.get(2), "md"));
		assertEquals("ok", Json.str(e.get(3), "state"));
		assertEquals(ChatRecord.NOT_STORED, Json.str(e.get(4), "output"));
		assertEquals(2, r.lastMessageId());
	}

	@Test
	void transcriptForAModelThatDidNotSeeTheChat() {
		String t = sample().transcript(10_000);
		assertTrue(t.startsWith("Developer: Explain   ZCL_ORDER"), t);
		assertTrue(t.contains("Bella:\nIt reads orders."), t);
		assertTrue(t.contains("[tool adt_read_source: ok]"), t);
		assertTrue(t.contains("[asked the developer: Which package? → ZSD]"), t);
		assertFalse(t.contains("Müller"), t);
		assertFalse(t.contains("Plan discarded"), t);
		String cut = sample().transcript(60);
		assertTrue(cut.startsWith("[… earlier part of the conversation left out]"), cut);
		assertTrue(cut.length() < 120, cut);
	}

	@Test
	void modelHistoryLosesDataToolResults() {
		ChatRecord r = new ChatRecord();
		JsonObject use = Json.parseObject("{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\","
				+ "\"name\":\"adt_table_contents\",\"input\":{}},{\"type\":\"tool_use\",\"id\":\"t2\","
				+ "\"name\":\"adt_read_source\",\"input\":{}}]}");
		JsonObject results = Json.parseObject("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
				+ "\"tool_use_id\":\"t1\",\"content\":\"4711 Müller\"},{\"type\":\"tool_result\",\"tool_use_id\":\"t2\","
				+ "\"content\":\"REPORT z.\"}]}");
		r.modelHistory(List.of(use, results));
		JsonArray kept = Json.arr(r.modelHistory().get(1), "content");
		assertEquals(ChatRecord.NOT_STORED, Json.str(kept.get(0).getAsJsonObject(), "content"));
		assertEquals("REPORT z.", Json.str(kept.get(1).getAsJsonObject(), "content"));
		assertEquals("4711 Müller", Json.str(Json.arr(results, "content").get(0).getAsJsonObject(), "content"));
	}

	@Test
	void storeSavesListsLoadsAndPrunes(@TempDir Path dir) throws Exception {
		ChatStore store = new ChatStore(dir.resolve("chats"));
		assertTrue(store.list().isEmpty());
		store.save(new ChatRecord()); // empty: not written
		assertTrue(store.list().isEmpty());

		ChatRecord a = sample();
		a.context("ChatSession:opus", "ZSD", "A4HK900012", "NORMAL");
		a.modelHistory(List.of(Json.userText("hi")));
		store.save(a);
		Thread.sleep(5);
		ChatRecord b = new ChatRecord();
		b.user(1, "second");
		store.save(b);
		Files.writeString(dir.resolve("chats/broken.json"), "{not json");
		Files.writeString(dir.resolve("chats/00000000-0000-0000-0000-000000000000.json"), "{\"id\":\"other\"}");

		List<ChatStore.Summary> list = store.list();
		assertEquals(List.of(b.id(), a.id()), list.stream().map(ChatStore.Summary::id).toList());
		assertEquals("second", list.get(0).title());

		ChatRecord loaded = store.load(a.id()).orElseThrow();
		assertEquals(Json.GSON.toJson(a.toJson()), Json.GSON.toJson(loaded.toJson()));
		assertEquals("ZSD", loaded.devPackage());
		assertEquals("A4HK900012", loaded.transport());
		assertEquals(1, loaded.modelHistory().size());
		assertTrue(store.load("../escape").isEmpty());

		store.prune(1);
		assertEquals(List.of(b.id()), store.list().stream().map(ChatStore.Summary::id).toList());
		store.deleteAll();
		assertTrue(store.list().isEmpty());
	}
}

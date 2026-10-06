package de.kiliantaubmann.bella.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

class MaskerTest {

	private static Masker.Settings all(List<String> terms, List<String> system, List<String> users) {
		return new Masker.Settings(true, true, true, terms, system, users);
	}

	private static Masker objectsOnly() {
		return new Masker(() -> all(List.of(), List.of(), List.of()));
	}

	@Test
	void customerObjectsBecomePlaceholdersAndComeBack() {
		Masker m = objectsOnly();
		String masked = m.mask("Read ZCL_ACME_ORDER and call zcl_acme_order=>get( ). Program ZACME_REPORT, table ZACME_T.");
		assertFalse(masked.toUpperCase().contains("ACME"), masked);
		assertTrue(masked.contains("ZCL_MASK1"), masked);
		assertTrue(masked.contains("zcl_mask1=>get"), "lower case code stays lower case: " + masked);
		assertTrue(masked.contains("ZMASK2"), masked);
		assertEquals("Read ZCL_ACME_ORDER and call zcl_acme_order=>get( ). Program ZACME_REPORT, table ZACME_T.",
				m.unmask(masked));
		assertEquals(masked, m.mask(m.unmask(masked)), "stable for the session");
	}

	@Test
	void standardObjectsAndWordsStay() {
		Masker m = objectsOnly();
		String text = "SELECT FROM mara, CL_ABAP_TYPEDESCR, YES or ZERO, Yoga and your zone.";
		assertEquals(text, m.mask(text));
	}

	@Test
	void lowerCaseNameWithoutUnderscoreIsMaskedOnceKnown() {
		Masker m = objectsOnly();
		assertEquals("REPORT zreport.", m.mask("REPORT zreport."), "unknown: could be a word");
		String masked = m.mask("Object: ZREPORT (PROG)\nREPORT zreport.");
		assertTrue(masked.contains("REPORT zmask1."), masked);
	}

	@Test
	void namesTheModelInventsAreNotMasked() {
		Masker m = objectsOnly();
		m.mask("ZCL_SECRET");
		String fromModel = m.unmask("I will create ZCL_NEW_HELPER next to ZCL_MASK1.");
		assertEquals("I will create ZCL_NEW_HELPER next to ZCL_SECRET.", fromModel);
		assertEquals("Created ZCL_NEW_HELPER.", m.mask("Created ZCL_NEW_HELPER."));
	}

	@Test
	void termsNamespacesSystemsUsersAndPersonalData() {
		Masker m = new Masker(() -> all(List.of("Acme GmbH", "/ACME/"), List.of("S4H", "S4H_100_dev"),
				List.of("MUELLER")));
		String masked = m.mask("""
				Customer acme gmbh, class /acme/cl_order, system S4H_100_dev (S4H/100), user MUELLER, \
				mail anna.schmidt@acme.de, IBAN DE89 3704 0044 0532 0130 00. The admin muellers.""");
		for (String secret : List.of("acme", "Acme", "S4H", "MUELLER", "anna.schmidt", "DE89")) {
			assertFalse(masked.contains(secret), secret + " in " + masked);
		}
		assertTrue(masked.contains("/mask"), "namespace keeps its shape: " + masked);
		assertTrue(masked.contains("muellers"), "only whole words: " + masked);
		String back = m.unmask(masked);
		assertTrue(back.contains("/acme/cl_order"), back);
		assertTrue(back.contains("anna.schmidt@acme.de"), back);
		assertTrue(back.contains("DE89 3704 0044 0532 0130 00"), back);
		assertTrue(back.contains("user MUELLER"), back);
	}

	@Test
	void offMeansNothingChangesButOldPlaceholdersStillResolve() {
		AtomicReference<Masker.Settings> s = new AtomicReference<>(all(List.of(), List.of(), List.of()));
		Masker m = new Masker(s::get);
		String masked = m.mask("ZCL_SECRET");
		s.set(Masker.Settings.OFF);
		assertFalse(m.active());
		assertEquals("ZCL_OTHER", m.mask("ZCL_OTHER"));
		assertEquals("ZCL_SECRET", m.unmask(masked));
	}

	@Test
	void streamedPlaceholdersSplitAcrossDeltas() {
		Masker m = objectsOnly();
		m.mask("ZCL_SECRET ZCL_OTHER");
		StringBuilder out = new StringBuilder();
		Masker.UnmaskStream stream = m.stream(out::append);
		for (String delta : List.of("Use ZCL_MA", "SK1=>run( ) and zcl_", "mask2.", " Done ZCL_MASK", "2")) {
			stream.accept(delta);
		}
		stream.flush();
		assertEquals("Use ZCL_SECRET=>run( ) and zcl_other. Done ZCL_OTHER", out.toString());
	}

	@Test
	void messagesKeepProtocolFields() {
		Masker m = objectsOnly();
		JsonObject msg = Json.parseObject("""
				{"role":"user","content":[{"type":"thinking","thinking":"ZCL_SECRET","signature":"ZZZ+ZCL_SECRET"},
				{"type":"tool_use","id":"toolu_1","name":"adt_read_source","input":{"name":"ZCL_SECRET","type":"CLAS"}}]}""");
		JsonObject masked = m.maskMessage(msg);
		String json = Json.GSON.toJson(masked);
		assertTrue(json.contains("\"signature\":\"ZZZ+ZCL_SECRET\""), json);
		assertTrue(json.contains("\"thinking\":\"ZCL_MASK1\""), json);
		assertTrue(json.contains("\"input\":{\"name\":\"ZCL_MASK1\""), json);
		assertEquals(Json.GSON.toJson(msg), Json.GSON.toJson(m.unmaskMessage(masked)));
	}

	@Test
	void parsesTerms() {
		assertEquals(List.of("Acme", "Project X", "/ACME/"),
				Masker.Settings.parseTerms("# comment\nAcme, Project X\n\n/ACME/"));
	}
}

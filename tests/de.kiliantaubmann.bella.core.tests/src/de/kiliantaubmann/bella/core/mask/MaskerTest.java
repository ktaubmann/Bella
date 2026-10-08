package de.kiliantaubmann.bella.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.testutil.LogRecorder;
import de.kiliantaubmann.bella.core.tools.ToolExecutor;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolRegistry;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;

class MaskerTest {

	private static Masker.Settings all(List<String> terms, List<String> system, List<String> users) {
		return new Masker.Settings(true, true, true, terms, system, users);
	}

	private static Masker objectsOnly() {
		return new Masker(() -> all(List.of(), List.of(), List.of()));
	}

	@Test
	void customerObjectsBecomeStablePlaceholders() {
		Masker m = objectsOnly();
		String masked = m.mask("Read ZCL_ACME_ORDER and call zcl_acme_order=>get( ). Program ZACME_REPORT, table ZACME_T.");
		assertFalse(masked.toUpperCase().contains("ACME"), masked);
		assertTrue(masked.contains("ZCL_MASK1"), masked);
		assertTrue(masked.contains("zcl_mask1=>get"), "lower case code stays lower case: " + masked);
		assertTrue(masked.contains("ZMASK2"), masked);
		assertEquals("ZCL_MASK1 again", m.mask("ZCL_ACME_ORDER again"), "same placeholder for the session");
		assertEquals(masked, m.mask(masked), "placeholders are not masked again");
	}

	@Test
	void cdsAnnotationsAreNoAddressesAndUrlsAreMasked() {
		Masker m = objectsOnly();
		String json = "\"source\":\"@AbapCatalog.viewEnhancementCategory: [#NONE]\\n@EndUserText.label: 'x'\\n"
				+ "define view entity ZI_X as select from mara { key matnr, amount@Semantics.amount }\"";
		String masked = m.mask(json);
		assertTrue(masked.contains("\\n@EndUserText.label"), masked);
		assertTrue(masked.contains("amount@Semantics.amount"), masked);
		assertFalse(masked.contains("example.invalid"), masked);
		assertEquals("GET /sap/bc/adt/textelements/programs/zsd_mask2/source/symbols",
				m.mask("GET /sap/bc/adt/textelements/programs/zsd_outb_delivery_delete/source/symbols"));
		assertTrue(m.mask("mail max.muster@firma.de").contains("maskmail"));
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
	}

	@Test
	void namesAfterEscapedLineBreaksInJsonAreMasked() {
		Masker m = new Masker(() -> all(List.of(), List.of("S4H"), List.of("MUELLER")));
		// a tool result as the CLI's stream-json carries it: line breaks escaped as \n
		String line = """
				{"text":"ZAPI_ACME_ONE (IWSG) package $TMP\\nZSD_ACME_DELETE (PROG/P)\\r\\nZ1ACME_MSG\\tS4H\\nMUELLER\\nanna@acme.de\\nDE89 3704 0044 0532 0130 00"}""";
		String masked = m.mask(line);
		for (String secret : List.of("ACME", "S4H", "MUELLER", "anna", "DE89")) {
			assertFalse(masked.contains(secret), secret + " in " + masked);
		}
		assertTrue(masked.contains("\\nZSD_MASK"), "the escape stays: " + masked);
		assertTrue(masked.contains("\\nmaskmail"), "the escape stays: " + masked);
	}

	@Test
	void encodedPathsTransportNumbersAndNamedUsersAreMasked() {
		Masker m = new Masker(() -> all(List.of(), List.of("S4H"), List.of("MUELLER")));
		String masked = m.mask("POST /usageReferences?uri=%2Fsap%2Fbc%2Fadt%2Fddic%2Fddl%2Fsources%2Fzc_acme_material "
				+ "with corrNr=S4HK903503: Object R3TR DDLS ZI_ACME is already locked in request S4HK902998 of user "
				+ "SCHMIDT; Benutzer MUELLER; user ID");
		for (String secret : List.of("acme", "ACME", "S4H", "SCHMIDT", "MUELLER")) {
			assertFalse(masked.contains(secret), secret + " in " + masked);
		}
		assertTrue(masked.contains("K903503"), "the request number keeps its shape: " + masked);
		assertTrue(masked.contains("user ID"), "too short for a user: " + masked);
		assertEquals(masked, m.mask(masked), "placeholders are not masked again");
		assertEquals("S4HANA and S4H2", m.mask("S4HANA and S4H2"), "no other words");
	}

	@Test
	void abapCodeAboutUsersStaysIntact() {
		Masker m = new Masker(() -> new Masker.Settings(true, false, true, List.of(), List.of(), List.of()));
		String code = "DATA user TYPE syuname. ls_x-user = me->user. user LIKE sy-uname. user VALUE 'X'.";
		assertEquals(code, m.mask(code));
		assertFalse(m.mask("locked by user SCHMIDT").contains("SCHMIDT"));
	}

	@Test
	void offChangesNothing() {
		AtomicReference<Masker.Settings> s = new AtomicReference<>(Masker.Settings.OFF);
		Masker m = new Masker(s::get);
		assertFalse(m.active());
		assertEquals("ZCL_SECRET anna@acme.de", m.mask("ZCL_SECRET anna@acme.de"));
	}

	@Test
	void parsesTerms() {
		assertEquals(List.of("Acme", "Project X", "/ACME/"),
				Masker.Settings.parseTerms("# comment\nAcme, Project X\n\n/ACME/"));
	}

	@Test
	void logIsMasked() {
		Masker masker = objectsOnly();
		Log.mask(masker::mask);
		try (LogRecorder r = LogRecorder.start(Log.Level.DEBUG)) {
			Log.debug("tool", () -> "adt_read_source input: {\"name\":\"ZCL_SECRET\"} owner anna@acme.de");
			Log.error("adt", "failed for zcl_secret", new IllegalStateException("ZCL_SECRET is locked"));
			String all = r.all();
			assertFalse(all.toLowerCase().contains("secret") || all.contains("anna@acme.de"), all);
			assertTrue(all.contains("ZCL_MASK") && all.contains("zcl_mask") && all.contains("maskmail"), all);
		} finally {
			Log.mask(null);
		}
	}

	@Test
	void onlyTheLogIsMaskedNotTheTool() {
		Masker masker = objectsOnly();
		List<String> received = new ArrayList<>();
		ToolProvider sap = new ToolProvider() {
			@Override
			public String id() {
				return ToolRegistry.ADT_PROVIDER_ID;
			}

			@Override
			public String displayName() {
				return "ADT";
			}

			@Override
			public List<ToolSpec> listTools() {
				return List.of(ToolSpec.of("adt_read_source", "", new JsonObject(), null, ToolSpec.Kind.READ));
			}

			@Override
			public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
				received.add(Json.str(input, "name"));
				return ToolResult.ok("CLASS zcl_secret DEFINITION.");
			}
		};
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(sap);
		registry.refresh(e -> {
		});
		Log.mask(masker::mask);
		try (LogRecorder r = LogRecorder.start(Log.Level.DEBUG)) {
			ToolResult result = new ToolExecutor(registry, ToolPolicy::defaults, (t, i) -> true, null).run(
					new ToolCall("1", "adt_read_source", Json.parseObject("{\"name\":\"ZCL_SECRET\"}"), "{}", null),
					ToolExecutor.Observer.NONE, CancelToken.NONE);
			assertEquals(List.of("ZCL_SECRET"), received);
			assertEquals("CLASS zcl_secret DEFINITION.", result.content(), "the model gets the real result");
			assertFalse(r.all().toLowerCase().contains("secret"), r.all());
		} finally {
			Log.mask(null);
		}
	}
}

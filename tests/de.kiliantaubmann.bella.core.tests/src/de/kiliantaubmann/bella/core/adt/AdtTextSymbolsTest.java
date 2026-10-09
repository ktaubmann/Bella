package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.util.CancelToken;

class AdtTextSymbolsTest {

	private static final AdtEditorObject PROGRAM = new AdtEditorObject("dev", "/sap/bc/adt/programs/programs/zrep",
			"ZREP", "PROG/P");

	@Test
	void symbolsAreFoundOutsideComments() {
		String code = """
				* MESSAGE 'Commented out'(009).
				  MESSAGE 'No entries found in MARA'(001) TYPE 'S' DISPLAY LIKE 'E'. " 'Not this'(008)
				  WRITE / 'It''s done'(a02).
				  WRITE / |Template '(003)|.
				  WRITE / 'Plain literal'.
				  WRITE / 'Other text'(001).
				""";
		assertEquals(Map.of("001", "No entries found in MARA", "A02", "It's done"), AdtTextSymbols.inCode(code));
	}

	@Test
	void planSplitsMissingAndDiffering() {
		AdtTextSymbols.Plan plan = AdtTextSymbols.plan("WRITE 'Kept'(001). WRITE 'New'(002). WRITE 'Changed'(003).",
				"@MaxLength:14\r\n001=Kept\r\n@MaxLength:20\r\n003=Original");
		assertEquals(Map.of("002", "New"), plan.missing());
		assertEquals(Map.of("003", "Original"), plan.differing());
		assertTrue(AdtTextSymbols.plan("WRITE TEXT-001.", "").isEmpty());
	}

	@Test
	void addedEntriesKeepThePool() {
		assertEquals("@MaxLength:14\n001=Kept\n002=New",
				AdtTextSymbols.withAdded("@MaxLength:14\r\n001=Kept\r\n", Map.of("002", "New")));
		assertEquals("001=New", AdtTextSymbols.withAdded("", Map.of("001", "New")));
	}

	@Test
	void ownPoolOnlyForProgramsAndClasses() {
		assertTrue(AdtTextSymbols.hasOwnPool(PROGRAM));
		assertTrue(AdtTextSymbols.hasOwnPool(new AdtEditorObject("dev", "u", "ZCL_A", "CLAS/OC")));
		assertFalse(AdtTextSymbols.hasOwnPool(new AdtEditorObject("dev", "u", "ZREP_TOP", "PROG/I")));
		assertFalse(AdtTextSymbols.hasOwnPool(new AdtEditorObject("dev", "u", "Z_FM", "FUGR/FF")));
	}

	@Test
	void addWritesAndActivatesThePool() throws Exception {
		List<String> puts = new ArrayList<>();
		String[] pool = { "@MaxLength:14\r\n001=Kept" };
		FakeAdt adt = new FakeAdt()
				.route("GET /sap/bc/adt/textelements/programs/zrep/source/symbols",
						r -> new AdtResponse(200, "text/plain", pool[0]))
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR>DEVK900001</CORRNR><IS_LOCAL></IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/textelements/programs/zrep/source/symbols", r -> {
					puts.add(r.body());
					pool[0] = r.body();
					return FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=UNLOCK", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/activation", r -> FakeAdt.ok(""));
		AdtClient client = new AdtClient(adt.stateless("dev"));
		String tr;
		try (AdtTransport.Session session = adt.stateful("dev")) {
			tr = AdtTextSymbols.add(session, client, PROGRAM, Map.of("001", "Other", "002", "No entries found"),
					CancelToken.NONE);
		}
		assertEquals("DEVK900001", tr);
		// 001 exists already and stays as it is
		assertEquals(List.of("@MaxLength:14\n001=Kept\n\n@MaxLength:26\n002=No entries found\n"), puts);
		assertTrue(adt.log.stream().anyMatch(l -> l.startsWith("POST /sap/bc/adt/activation")), adt.log.toString());
	}

	@Test
	void textsSapDoesNotKeepAreAnError() {
		FakeAdt adt = new FakeAdt()
				.route("GET /sap/bc/adt/textelements/programs/zrep/source/symbols",
						r -> new AdtResponse(200, "text/plain", ""))
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=LOCK", r -> FakeAdt.ok(
						"<DATA><LOCK_HANDLE>H</LOCK_HANDLE><CORRNR></CORRNR><IS_LOCAL>X</IS_LOCAL></DATA>"))
				.route("PUT /sap/bc/adt/textelements/programs/zrep/source/symbols", r -> FakeAdt.ok(""))
				.route("POST /sap/bc/adt/textelements/programs/zrep?_action=UNLOCK", r -> FakeAdt.ok(""));
		AdtClient client = new AdtClient(adt.stateless("dev"));
		AdtException e = assertThrows(AdtException.class, () -> {
			try (AdtTransport.Session session = adt.stateful("dev")) {
				AdtTextSymbols.add(session, client, PROGRAM, Map.of("001", "New"), CancelToken.NONE);
			}
		});
		assertTrue(e.getMessage().contains("001: wrote 'New', reads nothing"), e.getMessage());
	}
}

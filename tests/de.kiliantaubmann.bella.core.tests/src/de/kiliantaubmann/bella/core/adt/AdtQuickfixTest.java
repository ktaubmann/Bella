package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.abap.TextDeltas;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtQuickfixTest {

	static final String SOURCE = """
			CLASS zcl_a DEFINITION PUBLIC.
			  PUBLIC SECTION.
			    METHODS run.
			ENDCLASS.
			CLASS zcl_a IMPLEMENTATION.
			  METHOD run.
			    DATA lv_x TYPE i.
			    lv_y = 1.
			  ENDMETHOD.
			ENDCLASS.""";

	static final String PROPOSALS = "<qf:evaluationResults xmlns:qf=\"http://www.sap.com/adt/quickfixes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
			+ "<evaluationResult><adtcore:objectReference adtcore:uri=\"/sap/bc/adt/quickfixes/abap/declare_local\" "
			+ "adtcore:name=\"Declare local variable lv_y\" adtcore:description=\"Declare lv_y\"/>"
			+ "<userContent>ctx</userContent></evaluationResult></qf:evaluationResults>";

	static final String DELTAS = "<qf:proposalResult xmlns:qf=\"http://www.sap.com/adt/quickfixes\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
			+ "<deltas><unit><adtcore:objectReference adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=8,4;end=8,4\"/>"
			+ "<content>DATA lv_y TYPE i.\n    </content></unit></deltas></qf:proposalResult>";

	@Test
	void textDeltas() {
		assertEquals("ab-XY-ef", TextDeltas.apply("ab-cd-ef", List.of(new TextDeltas.Delta(1, 3, 1, 5, "XY"))));
		assertEquals("a\nX\nc", TextDeltas.apply("a\nb\nc", List.of(new TextDeltas.Delta(2, 0, 2, 1, "X"))));
		assertEquals("A\r\nb\r\nC", TextDeltas.apply("a\r\nb\r\nc",
				List.of(new TextDeltas.Delta(3, 0, 3, 1, "C"), new TextDeltas.Delta(1, 0, 1, 1, "A"))));
		assertThrows(IllegalArgumentException.class,
				() -> TextDeltas.apply("a", List.of(new TextDeltas.Delta(5, 0, 5, 0, "x"))));
		assertThrows(IllegalArgumentException.class, () -> TextDeltas.apply("abcdef",
				List.of(new TextDeltas.Delta(1, 0, 1, 3, "x"), new TextDeltas.Delta(1, 2, 1, 4, "y"))));
	}

	@Test
	void parsesProposalsAndDeltas() throws Exception {
		List<AdtQuickfix.Proposal> p = AdtQuickfix.parseProposals(PROPOSALS);
		assertEquals(1, p.size());
		assertEquals("/sap/bc/adt/quickfixes/abap/declare_local", p.get(0).uri());
		assertEquals("ctx", p.get(0).userContent());
		List<AdtQuickfix.Delta> d = AdtQuickfix.parseDeltas(DELTAS);
		assertEquals(new TextDeltas.Delta(8, 4, 8, 4, "DATA lv_y TYPE i.\n    "), d.get(0).delta());
		assertTrue(AdtQuickfix.sameSource(d.get(0).uri(), "/sap/bc/adt/oo/classes/zcl_a/source/main"));
	}

	private static FakeAdt adt(List<AdtRequest> requests) {
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEV", true));
		return adt.route("GET /sap/bc/adt/oo/classes/zcl_a/source/main", r -> new AdtResponse(200, "text/plain", SOURCE))
				.route("POST /sap/bc/adt/quickfixes/evaluation", r -> {
					requests.add(r);
					// only the token lv_y (column 4) has a fix
					return r.path().contains("%23start%3D8%2C4") ? FakeAdt.ok(PROPOSALS) : FakeAdt.ok("");
				})
				.route("POST /sap/bc/adt/quickfixes/abap/declare_local", r -> {
					requests.add(r);
					return FakeAdt.ok(DELTAS);
				})
				.route("POST /sap/bc/adt/abapsource/prettyprinter", r -> new AdtResponse(200, "text/plain",
						r.body().toUpperCase()))
				.route("GET /sap/bc/adt/abapsource/prettyprinter/settings", r -> FakeAdt.ok(
						"<abapformatter:PrettyPrinterSettings abapformatter:indentation=\"true\" abapformatter:style=\"keywordLower\" "
								+ "xmlns:abapformatter=\"http://www.sap.com/adt/prettyprintersettings\"/>"));
	}

	@Test
	void toolListsAndPreviewsAFix() {
		List<AdtRequest> requests = new ArrayList<>();
		AdtToolProvider p = new AdtToolProvider(adt(requests), () -> "dev");
		ToolResult list = p.call("adt_quickfix", Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"line\":8}"),
				CancelToken.NONE);
		assertFalse(list.isError(), list.content());
		assertTrue(list.content().startsWith("Quick fixes for ZCL_A line 8:4:\n1. Declare local variable lv_y"),
				list.content());
		assertEquals(SOURCE, requests.get(0).body());

		requests.clear();
		ToolResult preview = p.call("adt_quickfix", Json.parseObject(
				"{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"line\":8,\"column\":4,\"action\":\"preview\",\"proposal\":\"1\"}"),
				CancelToken.NONE);
		assertFalse(preview.isError(), preview.content());
		assertTrue(preview.content().contains("+    DATA lv_y TYPE i."), preview.content());
		assertTrue(preview.content().contains("Apply it with adt_write_source, 'method' RUN, and this body:"),
				preview.content());
		assertTrue(preview.content().contains("DATA lv_x TYPE i.\n    DATA lv_y TYPE i.\n    lv_y = 1."),
				preview.content());
		String apply = requests.get(1).body();
		assertTrue(apply.contains("<userContent>ctx</userContent>"), apply);
		assertTrue(apply.contains("adtcore:uri=\"/sap/bc/adt/oo/classes/zcl_a/source/main#start=8,4\""), apply);

		assertTrue(p.call("adt_quickfix", Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"line\":99}"),
				CancelToken.NONE).isError());
		assertEquals("SAP has no quick fix for ZCL_A line 1.", p.call("adt_quickfix",
				Json.parseObject("{\"name\":\"ZCL_A\",\"type\":\"CLAS\",\"line\":1}"), CancelToken.NONE).content());
	}

	@Test
	void refusesForeignProposalUris() {
		AdtQuickfix.Proposal evil = new AdtQuickfix.Proposal("/sap/bc/adt/cts/transportrequests/X/newreleasejobs", "x",
				"", null, null);
		assertThrows(AdtException.class, () -> AdtQuickfix.apply(new AdtClient(adt(new ArrayList<>()).stateless("dev")),
				evil, "/u", "s", 1, 0, CancelToken.NONE));
	}

	@Test
	void formatsWithThePrettyPrinter() {
		AdtToolProvider p = new AdtToolProvider(adt(new ArrayList<>()), () -> "dev");
		assertEquals("Formatted (not saved):\n```abap\nDATA X TYPE I.\n```",
				p.call("adt_format", Json.parseObject("{\"source\":\"data x type i.\"}"), CancelToken.NONE).content());
		assertEquals("Pretty printer: indentation on, keywords keywordLower.",
				p.call("adt_format", Json.parseObject("{\"action\":\"get_settings\"}"), CancelToken.NONE).content());
		assertTrue(p.call("adt_format_settings", Json.parseObject("{\"indentation\":true,\"style\":\"loud\"}"),
				CancelToken.NONE).isError());
	}
}

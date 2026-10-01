package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.adt.AdtRevisions.Baseline;
import de.kiliantaubmann.bella.core.adt.AdtRevisions.Pair;
import de.kiliantaubmann.bella.core.adt.AdtRevisions.Revision;
import de.kiliantaubmann.bella.core.adt.AdtRevisions.Selection;

class AdtRevisionsTest {

	/** Shape of a live 7.58 class feed (ARC-1 test data): versions 00002, 00000, 00001, unsorted. */
	static final String FEED = "<atom:feed xmlns:atom=\"http://www.w3.org/2005/Atom\" xmlns:adtcore=\"http://www.sap.com/adt/core\">"
			+ "<atom:title>Version List of ZCL_CALC (CLAS)</atom:title><atom:updated>2026-06-23T11:23:41Z</atom:updated>"
			+ entry("00002", "2026-06-23T11:23:41Z", "A4HK906291")
			+ entry("00000", "2026-06-23T11:22:09Z", null)
			+ entry("00001", "2026-06-23T09:34:43Z", "A4HK906289") + "</atom:feed>";

	static String entry(String number, String updated, String transport) {
		return "<atom:entry><atom:author><atom:name>MARIAN</atom:name></atom:author>"
				+ "<atom:content type=\"text/plain\" src=\"/sap/bc/adt/oo/classes/zcl_calc/includes/main/versions/20260623112341/"
				+ number + "/content\"/><atom:id>" + number + "</atom:id>"
				+ (transport == null ? ""
						: "<atom:link adtcore:name=\"" + transport + "\" href=\"/sap/bc/adt/cts/transportrequests/" + transport
								+ "\" rel=\"http://www.sap.com/adt/relations/transport/request\"/>")
				+ (updated == null ? "" : "<atom:updated>" + updated + "</atom:updated>") + "</atom:entry>";
	}

	static Revision rev(String number, String time, String transport) {
		return new Revision(number, "/x/versions/1/" + number + "/content", time, "U", transport == null ? "" : transport);
	}

	@Test
	void parsesTheFeed() throws Exception {
		List<Revision> r = AdtRevisions.parse(FEED);
		assertEquals(3, r.size());
		assertEquals("00002", r.get(0).number());
		assertEquals("A4HK906291", r.get(0).transport());
		assertEquals("", r.get(1).transport());
		assertEquals("2026-06-23T09:34:43Z", r.get(2).timestamp());
		assertEquals("MARIAN", r.get(2).author());
		assertEquals("00001", new Revision("urn:x", "/a/versions/9/00001/content", "", "", "").number());
	}

	@Test
	void releasedTransportDiffsAgainstThePreviousTransport() throws Exception {
		List<Revision> r = AdtRevisions.parse(FEED);
		Pair p = AdtRevisions.select(r, Set.of("A4HK906291", "A4HK906292"));
		assertEquals("00002", p.current().number());
		assertEquals("00001", p.previous().number());
		assertEquals(Selection.EXACT, p.selection());
		assertEquals(List.of("00000: work state, not a transported version"), p.skipped());
		assertEquals(Baseline.PRIOR, AdtRevisions.baseline(p, r));
	}

	@Test
	void openTransportUsesTheActiveStateItIsLockedIn() {
		List<Revision> r = List.of(rev("00001", "2026-01-01T10:00:00Z", "DEVK900001"),
				rev("00000", "2026-02-01T10:00:00Z", "DEVK900050"));
		Pair p = AdtRevisions.select(r, Set.of("DEVK900050"));
		assertEquals("00000", p.current().number());
		assertEquals("00001", p.previous().number());
		assertEquals(Baseline.PRIOR, AdtRevisions.baseline(p, r));
	}

	@Test
	void severalSavesInOneTransportDiffAgainstTheStateBeforeIt() {
		List<Revision> r = List.of(rev("00003", "2026-03-01T10:00:00Z", "T2"), rev("00002", "2026-03-01T10:00:00Z", "T2"),
				rev("00001", "2026-01-01T10:00:00Z", "T1"));
		Pair p = AdtRevisions.select(r, Set.of("T2"));
		assertEquals("00003", p.current().number(), "same second: the higher number is newer");
		assertEquals("00001", p.previous().number());
		assertEquals(List.of("00002: same transport"), p.skipped());
	}

	@Test
	void fallbacksAreLabelled() {
		List<Revision> other = List.of(rev("00002", "2026-03-01T10:00:00Z", "T9"), rev("00000", "2026-04-01T10:00:00Z", null),
				rev("00001", "2026-01-01T10:00:00Z", "T8"));
		Pair p = AdtRevisions.select(other, Set.of("T1"));
		assertEquals(Selection.LATEST_FALLBACK, p.selection());
		assertEquals("00002", p.current().number());
		assertEquals(Baseline.PRIOR_UNVERIFIED, AdtRevisions.baseline(p, other));

		List<Revision> activeOnly = List.of(rev("00000", "2026-04-01T10:00:00Z", null));
		Pair a = AdtRevisions.select(activeOnly, Set.of("T1"));
		assertEquals(Selection.ACTIVE_ONLY, a.selection());
		assertEquals(Baseline.AMBIGUOUS, AdtRevisions.baseline(a, activeOnly));

		List<Revision> created = List.of(rev("00001", "2026-04-01T10:00:00Z", "T1"));
		assertEquals(Baseline.CREATED, AdtRevisions.baseline(AdtRevisions.select(created, Set.of("T1")), created));

		Pair none = AdtRevisions.select(List.of(), Set.of("T1"));
		assertNull(none.current());
		assertEquals(Baseline.UNAVAILABLE, AdtRevisions.baseline(none, List.of()));
	}

	@Test
	void undatedVersionsRankAfterDatedOnes() {
		List<Revision> r = List.of(rev("00009", "", "T1"), rev("00002", "2026-03-01T10:00:00Z", "T1"),
				rev("00001", "2026-01-01T10:00:00Z", "T0"));
		Pair p = AdtRevisions.select(r, Set.of("T1"));
		assertEquals("00002", p.current().number());
		assertEquals("00001", p.previous().number());
	}
}

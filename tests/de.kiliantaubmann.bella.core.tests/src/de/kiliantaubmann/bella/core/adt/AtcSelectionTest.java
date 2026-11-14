package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class AtcSelectionTest {

	private static final String CLASS = "/sap/bc/adt/oo/classes/zcl_a";
	private static final String MAIN = CLASS + "/source/main";
	private static final String TESTS = CLASS + "/includes/testclasses";

	private static AdtClient.Message finding(String severity, String uri, int line) {
		return new AdtClient.Message(severity, "check: text", uri + "#start=" + line + ",0", line);
	}

	@Test
	void onlyFindingsOfTheEditorsSourceAreFixable() {
		assertTrue(AtcSelection.fixable(finding("Error", MAIN, 3), MAIN));
		assertTrue(AtcSelection.fixable(finding("Error", MAIN, 3), CLASS));
		assertFalse(AtcSelection.fixable(finding("Error", TESTS, 3), MAIN));
		assertTrue(AtcSelection.fixable(finding("Error", TESTS, 3), TESTS));
		assertFalse(AtcSelection.fixable(finding("Error", MAIN, 3), TESTS));
	}

	@Test
	void ticksAtMostTheLimitOfFixableErrorsAndWarnings() {
		List<AdtClient.Message> findings = new ArrayList<>();
		findings.add(finding("Error", TESTS, 1));
		for (int i = 1; i <= 100; i++) {
			findings.add(finding(i <= 60 ? "Warning" : "Info", MAIN, i));
		}
		List<Integer> ticked = AtcSelection.defaultTicks(findings, MAIN);
		assertEquals(AtcSelection.MAX_PER_RUN, ticked.size());
		assertEquals(1, ticked.get(0), "the finding of the test classes is not ticked");
		assertTrue(ticked.stream().allMatch(i -> findings.get(i).severity().equals("Warning")));
	}

	@Test
	void infosAreNotTicked() {
		assertEquals(List.of(1), AtcSelection.defaultTicks(
				List.of(finding("Info", MAIN, 1), finding("Error", MAIN, 2)), MAIN));
	}
}

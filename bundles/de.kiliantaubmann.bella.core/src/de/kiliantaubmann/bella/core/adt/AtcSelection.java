package de.kiliantaubmann.bella.core.adt;

import java.util.ArrayList;
import java.util.List;

/**
 * Which ATC findings "Check with ATC and fix" offers and ticks. The fix
 * rewrites the whole source of the editor in one answer, so a run takes only
 * a limited number of findings; after the save ATC runs again and offers the
 * rest.
 */
public final class AtcSelection {

	/** Findings fixed in one run at most. */
	public static final int MAX_PER_RUN = 20;

	private AtcSelection() {
	}

	/**
	 * Whether the finding is in the source the editor shows: a finding of
	 * another include (e.g. the test classes while the editor shows the main
	 * source) cannot be fixed there.
	 *
	 * @param editorUri URI of the editor's object or include
	 */
	public static boolean fixable(AdtClient.Message m, String editorUri) {
		return m.include().equals(new AdtClient.Message("", "", editorUri, 0).include());
	}

	/** Indexes of the findings ticked at first: fixable errors and warnings, at most {@link #MAX_PER_RUN}. */
	public static List<Integer> defaultTicks(List<AdtClient.Message> findings, String editorUri) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < findings.size() && out.size() < MAX_PER_RUN; i++) {
			AdtClient.Message m = findings.get(i);
			if (!m.severity().equals("Info") && fixable(m, editorUri)) {
				out.add(i);
			}
		}
		return out;
	}
}

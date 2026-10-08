package de.kiliantaubmann.bella.core.abap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies text replacements given as line and column ranges, as ADT returns
 * them for quick fixes: lines count from 1, columns from 0, and the end is
 * exclusive.
 */
public final class TextDeltas {

	/** Replace the text from (startLine, startColumn) to (endLine, endColumn) with {@code text}. */
	public record Delta(int startLine, int startColumn, int endLine, int endColumn, String text) {
	}

	private TextDeltas() {
	}

	/**
	 * The source with all deltas applied. Deltas are applied from the end of
	 * the source to its start, so their positions all refer to the original.
	 *
	 * @throws IllegalArgumentException if a delta lies outside the source or
	 *                                  two deltas overlap
	 */
	public static String apply(String source, List<Delta> deltas) {
		String nl = source.contains("\r\n") ? "\r\n" : "\n";
		String text = source.replace("\r\n", "\n");
		int[] starts = lineStarts(text);
		List<int[]> ranges = new ArrayList<>();
		List<Delta> sorted = new ArrayList<>(deltas);
		sorted.sort(Comparator.comparingInt(Delta::startLine).thenComparingInt(Delta::startColumn));
		for (Delta d : sorted) {
			int from = offset(text, starts, d.startLine(), d.startColumn());
			int to = offset(text, starts, d.endLine(), d.endColumn());
			if (to < from) {
				throw new IllegalArgumentException("Delta ends before it starts at line " + d.startLine());
			}
			if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] > from) {
				throw new IllegalArgumentException("Deltas overlap at line " + d.startLine());
			}
			ranges.add(new int[] { from, to });
		}
		StringBuilder sb = new StringBuilder(text);
		for (int i = sorted.size() - 1; i >= 0; i--) {
			sb.replace(ranges.get(i)[0], ranges.get(i)[1], sorted.get(i).text().replace("\r\n", "\n"));
		}
		return nl.equals("\n") ? sb.toString() : sb.toString().replace("\n", nl);
	}

	private static int offset(String text, int[] starts, int line, int column) {
		if (line < 1 || line > starts.length) {
			throw new IllegalArgumentException("Line " + line + " is outside the source (" + starts.length + " lines)");
		}
		int lineEnd = line < starts.length ? starts[line] - 1 : text.length();
		return Math.min(starts[line - 1] + Math.max(0, column), lineEnd);
	}

	private static int[] lineStarts(String s) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}
}

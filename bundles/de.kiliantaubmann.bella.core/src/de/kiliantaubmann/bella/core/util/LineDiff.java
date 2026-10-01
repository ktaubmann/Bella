package de.kiliantaubmann.bella.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based unified diff. Common leading and trailing lines are cut first;
 * the rest is compared with a longest-common-subsequence table, or, if that
 * would be too large, shown as one replaced block.
 */
public final class LineDiff {

	/** Diff text and the number of added and removed lines. */
	public record Result(String text, int added, int removed) {

		public boolean unchanged() {
			return added == 0 && removed == 0;
		}
	}

	/** Largest LCS table (cells) before falling back to one replaced block. */
	static final long MAX_CELLS = 4_000_000L;

	private enum Op {
		SAME, DEL, ADD
	}

	/** One line of the edit script with its line numbers (0-based) on both sides. */
	private record Line(Op op, int a, int b, String text) {
	}

	private LineDiff() {
	}

	static String[] lines(String s) {
		if (s == null || s.isEmpty()) {
			return new String[0];
		}
		String n = s.replace("\r\n", "\n");
		if (n.endsWith("\n")) {
			n = n.substring(0, n.length() - 1);
		}
		return n.split("\n", -1);
	}

	public static Result unified(String before, String after, String labelBefore, String labelAfter, int context) {
		String[] a = lines(before);
		String[] b = lines(after);
		List<Line> script = script(a, b);
		int added = 0;
		int removed = 0;
		for (Line l : script) {
			if (l.op() == Op.ADD) {
				added++;
			} else if (l.op() == Op.DEL) {
				removed++;
			}
		}
		if (added == 0 && removed == 0) {
			return new Result("", 0, 0);
		}
		StringBuilder sb = new StringBuilder("--- ").append(labelBefore).append("\n+++ ").append(labelAfter).append('\n');
		int i = 0;
		while (i < script.size()) {
			if (script.get(i).op() == Op.SAME) {
				i++;
				continue;
			}
			int start = Math.max(0, i - context);
			// extend the hunk while the next change is at most 2 * context lines away
			int last = i;
			for (int j = i; j < script.size() && j - last <= 2 * context; j++) {
				if (script.get(j).op() != Op.SAME) {
					last = j;
				}
			}
			int end = Math.min(script.size() - 1, last + context);
			hunk(sb, script.subList(start, end + 1));
			i = end + 1;
		}
		return new Result(sb.toString(), added, removed);
	}

	private static void hunk(StringBuilder sb, List<Line> lines) {
		int aStart = -1;
		int bStart = -1;
		int aLen = 0;
		int bLen = 0;
		for (Line l : lines) {
			if (l.op() != Op.ADD) {
				aLen++;
				if (aStart < 0) {
					aStart = l.a();
				}
			}
			if (l.op() != Op.DEL) {
				bLen++;
				if (bStart < 0) {
					bStart = l.b();
				}
			}
		}
		// for a pure insertion or deletion, the empty side points at the line before (unified diff convention)
		int aFrom = aLen == 0 ? lines.get(0).a() : aStart + 1;
		int bFrom = bLen == 0 ? lines.get(0).b() : bStart + 1;
		sb.append("@@ -").append(aFrom).append(',').append(aLen).append(" +").append(bFrom).append(',').append(bLen)
				.append(" @@\n");
		for (Line l : lines) {
			sb.append(switch (l.op()) {
			case SAME -> ' ';
			case DEL -> '-';
			case ADD -> '+';
			}).append(l.text()).append('\n');
		}
	}

	private static List<Line> script(String[] a, String[] b) {
		int pre = 0;
		while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
			pre++;
		}
		int suf = 0;
		while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
			suf++;
		}
		List<Line> out = new ArrayList<>();
		for (int i = 0; i < pre; i++) {
			out.add(new Line(Op.SAME, i, i, a[i]));
		}
		int n = a.length - pre - suf;
		int m = b.length - pre - suf;
		if ((long) (n + 1) * (m + 1) <= MAX_CELLS) {
			int[][] lcs = new int[n + 1][m + 1];
			for (int i = n - 1; i >= 0; i--) {
				for (int j = m - 1; j >= 0; j--) {
					lcs[i][j] = a[pre + i].equals(b[pre + j]) ? lcs[i + 1][j + 1] + 1
							: Math.max(lcs[i + 1][j], lcs[i][j + 1]);
				}
			}
			int i = 0;
			int j = 0;
			while (i < n || j < m) {
				if (i < n && j < m && a[pre + i].equals(b[pre + j])) {
					out.add(new Line(Op.SAME, pre + i, pre + j, a[pre + i]));
					i++;
					j++;
				} else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
					out.add(new Line(Op.DEL, pre + i, pre + j, a[pre + i]));
					i++;
				} else {
					out.add(new Line(Op.ADD, pre + i, pre + j, b[pre + j]));
					j++;
				}
			}
		} else {
			for (int i = 0; i < n; i++) {
				out.add(new Line(Op.DEL, pre + i, pre, a[pre + i]));
			}
			for (int j = 0; j < m; j++) {
				out.add(new Line(Op.ADD, pre + n, pre + j, b[pre + j]));
			}
		}
		for (int k = 0; k < suf; k++) {
			out.add(new Line(Op.SAME, a.length - suf + k, b.length - suf + k, a[a.length - suf + k]));
		}
		return out;
	}
}

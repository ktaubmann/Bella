package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LineDiffTest {

	@Test
	void changedLineWithContext() {
		LineDiff.Result r = LineDiff.unified("a\nb\nc\nd\ne\n", "a\nb\nC\nd\ne\n", "old", "new", 1);
		assertEquals("--- old\n+++ new\n@@ -2,3 +2,3 @@\n b\n-c\n+C\n d\n", r.text());
		assertEquals(1, r.added());
		assertEquals(1, r.removed());
	}

	@Test
	void insertionsDeletionsAndSeparateHunks() {
		String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10";
		String after = "1\nnew\n2\n3\n4\n5\n6\n7\n8\n10";
		LineDiff.Result r = LineDiff.unified(before, after, "a", "b", 1);
		assertEquals("--- a\n+++ b\n@@ -1,2 +1,3 @@\n 1\n+new\n 2\n@@ -8,3 +9,2 @@\n 8\n-9\n 10\n", r.text());
	}

	@Test
	void createdAndUnchanged() {
		LineDiff.Result created = LineDiff.unified("", "x\ny", "(none)", "v1", 3);
		assertEquals("--- (none)\n+++ v1\n@@ -0,0 +1,2 @@\n+x\n+y\n", created.text());
		assertTrue(LineDiff.unified("same\r\n", "same", "a", "b", 3).unchanged());
	}

	@Test
	void hugeChangeFallsBackToOneBlock() {
		StringBuilder a = new StringBuilder();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 2_100; i++) {
			a.append("a").append(i).append('\n');
			b.append("b").append(i).append('\n');
		}
		LineDiff.Result r = LineDiff.unified(a.toString(), b.toString(), "a", "b", 3);
		assertEquals(2_100, r.added());
		assertEquals(2_100, r.removed());
	}
}

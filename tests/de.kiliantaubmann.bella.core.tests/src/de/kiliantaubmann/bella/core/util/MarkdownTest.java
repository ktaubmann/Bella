package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownTest {

	@Test
	void rendersCodeBlocksAndCollectsThem() {
		Markdown.Rendered r = Markdown.render("Hi\n\n```abap\nDATA x TYPE i.\n```\ndone", (i, l) -> "<bar " + i + " " + l + ">");
		assertEquals(1, r.codeBlocks().size());
		assertEquals("DATA x TYPE i.", r.codeBlocks().get(0));
		assertTrue(r.html().contains("<bar 0 abap>"));
		assertTrue(r.html().contains("<p>done</p>"));
	}

	@Test
	void escapesHtmlAndFormatsInline() {
		String html = Markdown.render("a <b> **bold** `x<y` [link](https://sap.com) zcl_foo_bar", (i, l) -> "").html();
		assertTrue(html.contains("a &lt;b&gt; <strong>bold</strong> <code>x&lt;y</code>"));
		assertTrue(html.contains("<a href=\"https://sap.com\">link</a>"));
		assertTrue(html.contains("zcl_foo_bar"), html);
	}

	@Test
	void doesNotLinkJavascriptUrls() {
		assertFalse(Markdown.render("[x](javascript:alert(1))", (i, l) -> "").html().contains("href"));
	}

	@Test
	void unfinishedFenceStillRendersWhileStreaming() {
		Markdown.Rendered r = Markdown.render("```abap\nWRITE 'x'.", (i, l) -> "");
		assertEquals("WRITE 'x'.", r.codeBlocks().get(0));
	}

	@Test
	void listsAndTables() {
		String html = Markdown.render("- a\n- b\n\n| A | B |\n|---|---|\n| 1 | 2 |", (i, l) -> "").html();
		assertTrue(html.contains("<ul><li>a</li><li>b</li></ul>"));
		assertTrue(html.contains("<th>A</th><th>B</th>"));
		assertTrue(html.contains("<td>1</td><td>2</td>"));
	}

	@Test
	void firstCodeBlockFallsBackToWholeText() {
		assertEquals("WRITE 1.", Markdown.firstCodeBlock("  WRITE 1.  "));
		assertEquals("x", Markdown.firstCodeBlock("text\n```\nx\n```\n```\ny\n```"));
	}
}

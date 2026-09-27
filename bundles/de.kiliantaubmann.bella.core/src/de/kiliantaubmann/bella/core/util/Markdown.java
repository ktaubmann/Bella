package de.kiliantaubmann.bella.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free Markdown to HTML renderer for chat answers:
 * fenced code, headings, lists, block quotes, pipe tables, inline code,
 * bold, italics and http(s) links. All text is HTML-escaped.
 */
public final class Markdown {

	/** Rendered HTML plus the raw content of each fenced code block, by index. */
	public record Rendered(String html, List<String> codeBlocks) {
	}

	private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*([\\w+#-]*)\\s*$");
	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern UL = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
	private static final Pattern OL = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
	private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

	private Markdown() {
	}

	/**
	 * @param codeBar produces extra HTML placed above each code block (action
	 *                buttons), given the block index and language; may return ""
	 */
	public static Rendered render(String md, BiFunction<Integer, String, String> codeBar) {
		List<String> code = new ArrayList<>();
		StringBuilder html = new StringBuilder();
		String[] lines = md.replace("\r\n", "\n").split("\n", -1);
		int i = 0;
		while (i < lines.length) {
			String line = lines[i];
			Matcher fence = FENCE.matcher(line);
			if (fence.matches()) {
				String marker = fence.group(1);
				String lang = fence.group(2);
				StringBuilder body = new StringBuilder();
				i++;
				while (i < lines.length && !lines[i].trim().startsWith(marker)) {
					if (!body.isEmpty()) {
						body.append('\n');
					}
					body.append(lines[i]);
					i++;
				}
				i++; // closing fence (or end of an unfinished stream)
				int idx = code.size();
				code.add(body.toString());
				html.append("<div class=\"code\" data-idx=\"").append(idx).append("\">")
						.append(codeBar.apply(idx, lang))
						.append("<pre><code class=\"lang-").append(escape(lang.isEmpty() ? "text" : lang)).append("\">")
						.append(escape(body.toString())).append("</code></pre></div>\n");
				continue;
			}
			if (line.isBlank()) {
				i++;
				continue;
			}
			Matcher h = HEADING.matcher(line);
			if (h.matches()) {
				int level = Math.min(6, h.group(1).length() + 2);
				html.append("<h").append(level).append('>').append(inline(h.group(2))).append("</h").append(level)
						.append(">\n");
				i++;
				continue;
			}
			if (line.trim().startsWith("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
				html.append("<table><thead><tr>");
				for (String cell : cells(line)) {
					html.append("<th>").append(inline(cell)).append("</th>");
				}
				html.append("</tr></thead><tbody>");
				i += 2;
				while (i < lines.length && lines[i].trim().startsWith("|")) {
					html.append("<tr>");
					for (String cell : cells(lines[i])) {
						html.append("<td>").append(inline(cell)).append("</td>");
					}
					html.append("</tr>");
					i++;
				}
				html.append("</tbody></table>\n");
				continue;
			}
			if (UL.matcher(line).matches() || OL.matcher(line).matches()) {
				boolean ordered = OL.matcher(line).matches();
				Pattern p = ordered ? OL : UL;
				html.append(ordered ? "<ol>" : "<ul>");
				while (i < lines.length) {
					Matcher m = p.matcher(lines[i]);
					if (m.matches()) {
						html.append("<li>").append(inline(m.group(1)));
						i++;
						// continuation lines indented under the item
						while (i < lines.length && !lines[i].isBlank() && lines[i].startsWith("  ")
								&& !UL.matcher(lines[i]).matches() && !OL.matcher(lines[i]).matches()) {
							html.append(' ').append(inline(lines[i].trim()));
							i++;
						}
						html.append("</li>");
					} else {
						break;
					}
				}
				html.append(ordered ? "</ol>\n" : "</ul>\n");
				continue;
			}
			if (line.startsWith(">")) {
				StringBuilder quote = new StringBuilder();
				while (i < lines.length && lines[i].startsWith(">")) {
					quote.append(lines[i].substring(1).trim()).append(' ');
					i++;
				}
				html.append("<blockquote>").append(inline(quote.toString().trim())).append("</blockquote>\n");
				continue;
			}
			StringBuilder para = new StringBuilder(line.trim());
			i++;
			while (i < lines.length && !lines[i].isBlank() && !FENCE.matcher(lines[i]).matches()
					&& !HEADING.matcher(lines[i]).matches() && !UL.matcher(lines[i]).matches()
					&& !OL.matcher(lines[i]).matches() && !lines[i].startsWith(">")) {
				para.append('\n').append(lines[i].trim());
				i++;
			}
			html.append("<p>").append(inline(para.toString()).replace("\n", "<br>")).append("</p>\n");
		}
		return new Rendered(html.toString(), code);
	}

	private static List<String> cells(String row) {
		String r = row.trim();
		if (r.startsWith("|")) {
			r = r.substring(1);
		}
		if (r.endsWith("|")) {
			r = r.substring(0, r.length() - 1);
		}
		List<String> out = new ArrayList<>();
		for (String c : r.split("\\|", -1)) {
			out.add(c.trim());
		}
		return out;
	}

	private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
	private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])[*_]([^*_\\s][^*_]*?)[*_](?![\\w*])");
	private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");

	static String inline(String text) {
		// Protect inline code first so its content is not formatted.
		List<String> codes = new ArrayList<>();
		Matcher m = INLINE_CODE.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			codes.add(m.group(1));
			m.appendReplacement(sb, Matcher.quoteReplacement("\u0000" + (codes.size() - 1) + "\u0000"));
		}
		m.appendTail(sb);
		String s = escape(sb.toString());
		s = LINK.matcher(s).replaceAll("<a href=\"$2\">$1</a>");
		s = BOLD.matcher(s).replaceAll("<strong>$1</strong>");
		s = ITALIC.matcher(s).replaceAll("<em>$1</em>");
		for (int k = 0; k < codes.size(); k++) {
			s = s.replace("\u0000" + k + "\u0000", "<code>" + escape(codes.get(k)) + "</code>");
		}
		return s;
	}

	public static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (char c : s.toCharArray()) {
			switch (c) {
			case '<' -> sb.append("&lt;");
			case '>' -> sb.append("&gt;");
			case '&' -> sb.append("&amp;");
			case '"' -> sb.append("&quot;");
			case '\'' -> sb.append("&#39;");
			default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	/** The first fenced code block of a reply, or the whole reply if it has none. */
	public static String firstCodeBlock(String reply) {
		List<String> blocks = render(reply, (i, l) -> "").codeBlocks();
		return blocks.isEmpty() ? reply.trim() : blocks.get(0);
	}
}

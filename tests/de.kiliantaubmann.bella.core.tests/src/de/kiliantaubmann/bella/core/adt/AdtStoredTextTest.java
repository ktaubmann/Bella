package de.kiliantaubmann.bella.core.adt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class AdtStoredTextTest {

	// ---- building clusters as SAP stores them --------------------------------------------

	private static void int4(ByteArrayOutputStream out, int v) {
		out.writeBytes(ByteBuffer.allocate(4).putInt(v).array());
	}

	private static void utf16(ByteArrayOutputStream out, String s, int units) {
		out.writeBytes(String.format("%-" + units + "s", s).getBytes(StandardCharsets.UTF_16LE));
	}

	/** A TLINE table: segment, structure of two character fields, rows, end markers. */
	static byte[] payload(String[]... lines) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { 3, 14, 0 });
		int4(out, 268);
		int4(out, 0);
		out.write(5);
		out.writeBytes(new byte[20]);
		utf16(out, "TLINE", 5);
		out.writeBytes(new byte[] { (byte) 0xad, 0, 0 });
		int4(out, 268);
		out.writeBytes(new byte[] { (byte) 0xaa, 0, 0 });
		int4(out, 4);
		out.writeBytes(new byte[] { (byte) 0xaa, 0, 0 });
		int4(out, 264);
		out.writeBytes(new byte[] { (byte) 0xae, 0, 0 });
		int4(out, 268);
		out.write(0xbe);
		int4(out, 268);
		int4(out, lines.length);
		for (String[] l : lines) {
			out.write(0xbb);
			utf16(out, l[0], 2);
			utf16(out, l[1], 132);
			out.write(0xbd);
		}
		out.write(0xbf);
		out.write(4);
		return out.toByteArray();
	}

	private static byte[] header(int compression) {
		byte[] h = new byte[16];
		h[0] = (byte) 0xff;
		h[1] = 6;
		h[2] = 2;
		h[4] = (byte) compression;
		System.arraycopy("4103".getBytes(StandardCharsets.ISO_8859_1), 0, h, 8, 4);
		return h;
	}

	static byte[] plain(byte[] payload) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header(1));
		out.writeBytes(payload);
		return out.toByteArray();
	}

	/** Raw deflate with SAP's two-bit prefix in front, as the cluster holds it. */
	static byte[] compressed(byte[] payload) {
		Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		d.setInput(payload);
		d.finish();
		byte[] buf = new byte[payload.length + 64];
		int n = d.deflate(buf);
		d.end();
		byte[] shifted = new byte[n + 1];
		for (int i = 0; i <= n; i++) {
			int cur = i < n ? buf[i] & 0xff : 0;
			int prev = i > 0 ? buf[i - 1] & 0xff : 0;
			shifted[i] = (byte) ((cur << 2) | (prev >>> 6));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(header(2));
		out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
		out.writeBytes(new byte[] { 0x12, 0x1f, (byte) 0x9d, 0 });
		out.writeBytes(shifted);
		return out.toByteArray();
	}

	private static final String[][] LINES = { { "*", "Payment within 14 days" }, { "/:", "INCLUDE Z_FOOTER" },
			{ "", "net." } };

	@Test
	void decodesPlainAndCompressedClusters() throws Exception {
		byte[] p = payload(LINES);
		List<AdtStoredText.Line> expected = List.of(new AdtStoredText.Line("*", "Payment within 14 days"),
				new AdtStoredText.Line("/:", "INCLUDE Z_FOOTER"), new AdtStoredText.Line("", "net."));
		assertEquals(expected, AdtStoredText.decode(plain(p)));
		assertEquals(expected, AdtStoredText.decode(compressed(p)));
	}

	@Test
	void refusesOtherProfilesAndBrokenData() {
		byte[] other = plain(payload(LINES));
		other[8] = '1'; // code page 1103
		assertEquals(AdtStoredText.Code.UNSUPPORTED_PROFILE,
				assertThrows(AdtStoredText.TextReadException.class, () -> AdtStoredText.decode(other)).code);
		byte[] cut = java.util.Arrays.copyOf(plain(payload(LINES)), 200);
		assertEquals(AdtStoredText.Code.CORRUPT_CLUSTER,
				assertThrows(AdtStoredText.TextReadException.class, () -> AdtStoredText.decode(cut)).code);
		byte[] wrongLength = compressed(payload(LINES));
		wrongLength[16]++;
		assertEquals(AdtStoredText.Code.CORRUPT_CLUSTER,
				assertThrows(AdtStoredText.TextReadException.class, () -> AdtStoredText.decode(wrongLength)).code);
	}

	@Test
	void sqlKeepsBlanksAndEscapesLike() {
		assertEquals("SELECT A FROM STXH WHERE TDNAME = 'O''NEIL'",
				AdtStoredText.sql("STXH", List.of("A"), List.<String[]>of(new String[] { "TDNAME", "=", "O'NEIL" })));
		assertEquals("concat_with_space( 'A', 'B', 3 )", AdtStoredText.literal("A   B"));
		assertEquals("concat_with_space( ' ', 'B', 2 )", AdtStoredText.literal("  B"));
		assertEquals("SELECT * FROM STXH WHERE TDNAME LIKE 'Z#_PAY%' ESCAPE '#'", AdtStoredText.sql("STXH", null,
				List.<String[]>of(new String[] { "TDNAME", "STARTS_WITH", "Z_PAY" })));
		assertTrue(AdtStoredText.sql("STXH", null, List.<String[]>of(new String[] { "TDNAME", "STARTS_WITH", "A  B" }))
				.contains("left( TDNAME, 4 ) = concat_with_space( 'A', 'B', 2 )"));
	}

	// ---- through the tool ------------------------------------------------------------

	static String table(Map<String, List<String>> columns) {
		StringBuilder sb = new StringBuilder("<dataPreview:tableData xmlns:dataPreview=\"http://www.sap.com/adt/dataPreview\">");
		columns.forEach((name, values) -> {
			sb.append("<dataPreview:columns><dataPreview:metadata dataPreview:name=\"").append(name)
					.append("\"/><dataPreview:dataSet>");
			values.forEach(v -> sb.append("<dataPreview:data>").append(AdtXml.escape(v)).append("</dataPreview:data>"));
			sb.append("</dataPreview:dataSet></dataPreview:columns>");
		});
		return sb.append("</dataPreview:tableData>").toString();
	}

	private static Map<String, List<String>> row(String... pairs) {
		Map<String, List<String>> m = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			m.put(pairs[i], List.of(pairs[i + 1]));
		}
		return m;
	}

	private static String header(String name) {
		return table(row("MANDT", "100", "TDOBJECT", "TEXT", "TDNAME", name, "TDID", "ST", "TDSPRAS", "E", "TDTITLE",
				"Payment terms", "TDTEXTTYPE", "", "TDTXTLINES", "3", "TDVERSION", "1", "TDLDATE", "20260101", "TDLTIME",
				"120000", "TDREF", "", "TDREFOBJ", "", "TDREFNAME", "", "TDREFID", ""));
	}

	static FakeAdt system(byte[] cluster, List<String> sqls) {
		String hex = HexFormat.of().formatHex(java.util.Arrays.copyOf(cluster, 7902)).toUpperCase();
		FakeAdt adt = new FakeAdt();
		adt.systems.add(new AdtSystem("dev", "S4H_100", "S4H", "100", "DEVELOPER", true, "EN"));
		return adt.route("POST /sap/bc/adt/datapreview/freestyle", r -> {
			String sql = r.body();
			sqls.add(sql);
			String body;
			if (sql.contains("FROM TTXOB")) {
				body = table(row("MANDT", "100", "TDOBJECT", "TEXT"));
			} else if (sql.contains("FROM TTXID")) {
				body = table(row("TDOBJECT", "TEXT", "TDID", "ST"));
			} else if (sql.contains("FROM T002")) {
				body = table(row("SPRAS", "E"));
			} else if (sql.contains("FROM STXH") && sql.contains("LIKE")) {
				body = header("Z_PAYMENT_NOTICE");
			} else if (sql.contains("FROM STXH")) {
				body = sql.contains("'Z_PAYMENT_NOTICE'") ? header("Z_PAYMENT_NOTICE") : table(row());
			} else if (sql.contains("FROM STXL")) {
				body = table(row("MANDT", "100", "RELID", "TX", "TDOBJECT", "TEXT", "TDNAME", "Z_PAYMENT_NOTICE", "TDID",
						"ST", "TDSPRAS", "E", "SRTF2", "0", "CLUSTR", String.valueOf(cluster.length), "CLUSTD", hex));
			} else {
				body = table(row());
			}
			return FakeAdt.ok(body);
		});
	}

	private static ToolResult call(FakeAdt adt, String json) {
		return new AdtToolProvider(adt, () -> "dev").call("adt_table_contents", Json.parseObject(json), CancelToken.NONE);
	}

	private static final String KEY = "{\"object\":\"TEXT\",\"name\":\"Z_PAYMENT_NOTICE\",\"id\":\"ST\",\"language\":\"E\"}";

	@Test
	void discoverReadAndSearch() {
		List<String> sqls = new ArrayList<>();
		FakeAdt adt = system(compressed(payload(LINES)), sqls);
		ToolResult found = call(adt, "{\"action\":\"long_text\",\"mode\":\"discover\",\"object\":\"TEXT\","
				+ "\"name_prefix\":\"Z_PAY\"}");
		assertEquals("Saved long texts (object / name / ID / language):\n- TEXT / Z_PAYMENT_NOTICE / ST / E – Payment "
				+ "terms, 3 lines\nRead them with mode 'read' and these keys.", found.content());
		ToolResult read = call(adt, "{\"action\":\"long_text\",\"mode\":\"read\",\"keys\":[" + KEY + "]}");
		assertFalse(read.isError(), read.content());
		assertTrue(read.content().startsWith("TEXT / Z_PAYMENT_NOTICE / ST / E – Payment terms (3 lines)\n*  | Payment "
				+ "within 14 days\n/: | INCLUDE Z_FOOTER\n   | net.\nNote: ITF commands"), read.content());
		ToolResult search = call(adt, "{\"action\":\"long_text\",\"mode\":\"search\",\"phrase\":\"14 days\",\"keys\":["
				+ KEY + "]}");
		assertEquals("1 lines with '14 days':\nTEXT / Z_PAYMENT_NOTICE / ST / E line 1: *| Payment within 14 days",
				search.content());
		// header and chunks are read twice: a change in between is noticed
		assertEquals(4, sqls.stream().filter(s -> s.contains("FROM STXL")).count());
		assertTrue(sqls.stream().noneMatch(s -> s.contains(";")));
	}

	@Test
	void missingTextIsReportedPerKey() {
		ToolResult r = call(system(plain(payload(LINES)), new ArrayList<>()), "{\"action\":\"long_text\",\"mode\":"
				+ "\"read\",\"keys\":[{\"object\":\"TEXT\",\"name\":\"Z_OTHER\",\"id\":\"ST\",\"language\":\"E\"}]}");
		assertTrue(r.content().startsWith("TEXT / Z_OTHER / ST / E: NOT_FOUND, no header in classic storage."),
				r.content());
		assertTrue(call(system(plain(payload(LINES)), new ArrayList<>()), "{\"action\":\"long_text\",\"mode\":\"read\"}")
				.isError());
		assertTrue(call(system(plain(payload(LINES)), new ArrayList<>()), "{\"action\":\"other\"}").isError());
	}
}

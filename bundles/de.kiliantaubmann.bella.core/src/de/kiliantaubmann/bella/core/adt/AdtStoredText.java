package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Saved SAPscript long texts (SO10, READ_TEXT) from classic storage: the
 * header in STXH and the text lines as a cluster in STXL, read through ADT's
 * data preview and decoded here, without RFC or helpers in the system. Only
 * the verified profile is accepted (ITF texts, default persistence, container
 * FF06, Unicode 4103, plain or SAP-LZH compressed); anything else is reported,
 * never shown as an empty text. Port of ARC-1's {@code src/adt/stored-text}
 * (MIT), whose container research comes from aamelin1/sap-long-texts (MIT).
 */
final class AdtStoredText {

	/** Failure categories, as in ARC-1. */
	enum Code {
		INVALID_KEY("Use a configured object and ID and a one-character SAP language key (E, D …); discover exact keys first."),
		NOT_FOUND("No header for this exact key in classic storage. Discover IDs and languages; do not conclude the text "
				+ "does not exist from this alone."),
		UNSUPPORTED_PROFILE("This storage or container profile is not supported. Do not fall back to RFC or free SQL."),
		UNSUPPORTED_PERSISTENCE("The text uses another persistence; a released API of the application is needed."),
		NON_ITF_TEXT("Not an ITF text in STXL. Use the application's API; do not report it as missing."),
		REFERENCE_ERROR("The text refers to a missing or invalid text. Report the reference problem."),
		REFERENCE_CYCLE("The stored references form a cycle. Report it; do not retry."),
		REFERENCE_LIMIT("The chain of references is longer than eight. Report the limit."),
		INCONSISTENT_READ("The text changed while it was read. Read the same key once more."),
		CORRUPT_CLUSTER("The stored bytes do not match the supported profile. Report a decoding failure, not an empty text."),
		LIMIT_EXCEEDED("Ask for fewer or smaller texts, or a narrower name prefix.");

		final String hint;

		Code(String hint) {
			this.hint = hint;
		}
	}

	static final class TextReadException extends IOException {
		private static final long serialVersionUID = 1L;
		final Code code;

		TextReadException(Code code, String message) {
			super(message);
			this.code = code;
		}
	}

	/** Exact text identity: object (TDOBJECT), name (TDNAME), ID (TDID), language (TDSPRAS). */
	record Key(String object, String name, String id, String language) {

		String describe() {
			return object + " / " + name + " / " + id + " / " + language;
		}
	}

	record Line(String format, String text) {
	}

	record Header(Key key, String title, String textType, int storedLines, Key reference, boolean legacyReference,
			String revision) {
	}

	record Text(Key requested, Key resolved, Header header, List<Key> chain, List<Line> lines, List<String> warnings) {
	}

	static final int MAX_KEYS = 25;
	static final int MAX_LINES = 10_000;
	static final int MAX_INPUT = 2 * 1024 * 1024;
	static final int MAX_OUTPUT = 4 * 1024 * 1024;
	private static final int MAX_HOPS = 8;
	private static final int MAX_REQUESTS = 120;
	private static final int WINDOW = 32;
	private static final int COUNTERS = 256;
	private static final int CHUNK = 7902;
	private static final List<String> KEY_FIELDS = List.of("TDOBJECT", "TDNAME", "TDID", "TDSPRAS");
	private static final int[] KEY_LENGTHS = { 10, 70, 4, 1 };
	private static final List<String> HEADER_COLUMNS = List.of("MANDT", "TDOBJECT", "TDNAME", "TDID", "TDSPRAS",
			"TDTITLE", "TDTEXTTYPE", "TDTXTLINES", "TDVERSION", "TDLDATE", "TDLTIME", "TDREF", "TDREFOBJ", "TDREFNAME",
			"TDREFID");
	private static final List<String> CLUSTER_COLUMNS = List.of("MANDT", "RELID", "TDOBJECT", "TDNAME", "TDID",
			"TDSPRAS", "SRTF2", "CLUSTR", "CLUSTD");

	/** One read: the system's client, a request budget and the text configuration read so far. */
	static final class Session {
		private final AdtClient c;
		private final String client;
		private final CancelToken cancel;
		private final Map<String, Map<String, String>> config = new HashMap<>();
		private int requests;

		Session(AdtClient c, String client, CancelToken cancel) throws TextReadException {
			if (client == null || !client.matches("\\d{3}")) {
				throw new TextReadException(Code.INVALID_KEY, "the system's client is not known");
			}
			this.c = c;
			this.client = client;
			this.cancel = cancel;
		}

		List<Map<String, String>> query(String table, List<String> columns, List<String[]> where, int maxRows)
				throws IOException {
			if (requests++ >= MAX_REQUESTS) {
				throw new TextReadException(Code.LIMIT_EXCEEDED, "query limit of this read exceeded");
			}
			AdtClient.TableData data = c.tableContents(sql(table, columns, where), maxRows, cancel);
			if (data.rows().size() > maxRows) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid row count");
			}
			List<Map<String, String>> rows = new ArrayList<>();
			for (List<String> r : data.rows()) {
				Map<String, String> row = new LinkedHashMap<>();
				for (int i = 0; i < data.columns().size(); i++) {
					row.put(data.columns().get(i).toUpperCase(Locale.ROOT), i < r.size() ? r.get(i) : "");
				}
				rows.add(row);
			}
			return rows;
		}

		private Map<String, String> configuration(String table, List<String> columns, List<String[]> where,
				List<String> required) throws IOException {
			String cacheKey = table + where.stream().map(w -> w[0] + "=" + w[2]).toList();
			Map<String, String> row = config.get(cacheKey);
			if (row == null) {
				List<Map<String, String>> rows = query(table, columns, where, 2);
				if (rows.isEmpty()) {
					throw new TextReadException(Code.INVALID_KEY, "text object, ID or language is not configured");
				}
				if (rows.size() != 1 || !rows.get(0).keySet().containsAll(required)) {
					throw new TextReadException(Code.UNSUPPORTED_PERSISTENCE, "text configuration is ambiguous or unavailable");
				}
				row = rows.get(0);
				config.put(cacheKey, row);
			}
			return row;
		}

		/** The text object, ID and language exist and the text uses SAP's default persistence. */
		void checkSupported(Key key) throws IOException {
			Map<String, String> object = configuration("TTXOB", null, List.<String[]>of(eq("TDOBJECT", key.object())),
					List.of("TDOBJECT"));
			Map<String, String> id = configuration("TTXID", null,
					List.of(eq("TDOBJECT", key.object()), eq("TDID", key.id())), List.of("TDOBJECT", "TDID"));
			Map<String, String> language = configuration("T002", List.of("SPRAS"), List.<String[]>of(eq("SPRAS", key.language())),
					List.of("SPRAS"));
			if (!unpad(object.get("TDOBJECT")).equals(key.object()) || !unpad(id.get("TDOBJECT")).equals(key.object())
					|| !unpad(id.get("TDID")).equals(key.id()) || !unpad(language.get("SPRAS")).equals(key.language())) {
				throw new TextReadException(Code.UNSUPPORTED_PERSISTENCE, "text configuration does not match the key");
			}
			String handler = unpad(id.getOrDefault("HANDLER_CLASS", ""));
			if (handler.isEmpty()) {
				handler = unpad(object.getOrDefault("HANDLER_CLASS", ""));
			}
			if (!handler.isEmpty() && !handler.equals("CL_RSTXT_DEFAULT_PERSISTENCE")) {
				throw new TextReadException(Code.UNSUPPORTED_PERSISTENCE, "the text uses persistence " + handler);
			}
		}

		Header header(Key key) throws IOException {
			List<Map<String, String>> rows = query("STXH", HEADER_COLUMNS, keyFilters(key), 2);
			if (rows.size() > 1) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "duplicate text headers");
			}
			return rows.isEmpty() ? null : parseHeader(rows.get(0), client, key);
		}

		/** The cluster bytes of a text, from at most 256 chunks of 7902 bytes; {@code null} without chunks. */
		byte[] cluster(Key key) throws IOException {
			List<Map<String, String>> chunks = new ArrayList<>();
			for (int start = 0; start < COUNTERS; start += WINDOW) {
				List<String[]> where = new ArrayList<>();
				where.add(eq("RELID", "TX"));
				where.addAll(keyFilters(key));
				where.add(new String[] { "SRTF2", ">=", String.valueOf(start) });
				where.add(new String[] { "SRTF2", "<", String.valueOf(start + WINDOW) });
				List<Map<String, String>> rows = query("STXL", CLUSTER_COLUMNS, where, WINDOW + 1);
				if (start == 0 && rows.isEmpty()) {
					return null;
				}
				for (Map<String, String> row : rows) {
					int index = integer(row, "SRTF2");
					if (index < start || index >= start + WINDOW) {
						throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid chunk window");
					}
				}
				if (rows.size() > WINDOW) {
					throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid chunk window");
				}
				chunks.addAll(rows);
				if (rows.size() < WINDOW || start + WINDOW == COUNTERS) {
					return assemble(chunks, key, client);
				}
			}
			throw new TextReadException(Code.LIMIT_EXCEEDED, "chunk limit exceeded");
		}

		/** Text headers whose name starts with {@code prefix}, sorted; one more than {@code limit} tells of more. */
		List<Header> discover(String object, String prefix, String id, String language, int limit) throws IOException {
			if (prefix == null || prefix.isEmpty() || prefix.length() > 70 || prefix.chars().anyMatch(ch -> ch < 32)) {
				throw new TextReadException(Code.INVALID_KEY, "the name prefix has 1 to 70 characters");
			}
			key(object, "DISCOVERY", id == null ? "ST" : id, language == null ? "E" : language);
			List<String[]> where = new ArrayList<>();
			where.add(eq("TDOBJECT", object.strip()));
			where.add(new String[] { "TDNAME", "STARTS_WITH", prefix });
			if (id != null) {
				where.add(eq("TDID", id.strip()));
			}
			if (language != null) {
				where.add(eq("TDSPRAS", language.strip()));
			}
			List<Header> out = new ArrayList<>();
			Set<Key> seen = new HashSet<>();
			for (Map<String, String> row : query("STXH", HEADER_COLUMNS, where, limit + 1)) {
				Header h = parseHeader(row, client, null);
				if (!h.key().object().equals(object.strip()) || !h.key().name().startsWith(prefix)
						|| !seen.add(h.key())) {
					throw new TextReadException(Code.CORRUPT_CLUSTER, "discovery identity mismatch");
				}
				out.add(h);
			}
			out.sort((a, b) -> String.join("\u0000", a.key().object(), a.key().name(), a.key().id(), a.key().language())
					.compareTo(String.join("\u0000", b.key().object(), b.key().name(), b.key().id(), b.key().language())));
			return out;
		}

		/** Follows stored references in the same language, decodes the text and reads again to detect changes. */
		Text read(Key requested) throws IOException {
			Key key = requested;
			List<Header> headers = new ArrayList<>();
			Set<Key> visited = new HashSet<>();
			while (true) {
				if (!visited.add(key)) {
					throw new TextReadException(Code.REFERENCE_CYCLE, "stored reference cycle");
				}
				try {
					checkSupported(key);
				} catch (TextReadException e) {
					if (!headers.isEmpty() && e.code == Code.INVALID_KEY) {
						throw new TextReadException(Code.REFERENCE_ERROR, "the referenced text has an invalid key");
					}
					throw e;
				}
				Header h = header(key);
				if (h == null) {
					throw new TextReadException(headers.isEmpty() ? Code.NOT_FOUND : Code.REFERENCE_ERROR,
							headers.isEmpty() ? "no header in classic storage" : "the referenced text has no header");
				}
				if (!unpad(h.textType()).isEmpty()) {
					throw new TextReadException(Code.NON_ITF_TEXT, "text type " + unpad(h.textType()));
				}
				if (h.legacyReference()) {
					throw new TextReadException(Code.UNSUPPORTED_PROFILE, "incomplete reference metadata");
				}
				headers.add(h);
				if (h.reference() == null) {
					break;
				}
				if (headers.size() > MAX_HOPS) {
					throw new TextReadException(Code.REFERENCE_LIMIT, "more than eight references");
				}
				key = h.reference();
				if (!key.language().equals(requested.language())) {
					throw new TextReadException(Code.REFERENCE_ERROR, "a reference changes the language");
				}
			}
			byte[] bytes = cluster(key);
			List<Line> lines = bytes == null ? List.of() : decode(bytes);
			for (Header h : headers) {
				Header now = header(h.key());
				if (now == null || !now.revision().equals(h.revision())) {
					throw new TextReadException(Code.INCONSISTENT_READ, "the header changed during the read");
				}
			}
			if (!java.util.Arrays.equals(cluster(key), bytes)) {
				throw new TextReadException(Code.INCONSISTENT_READ, "the stored chunks changed during the read");
			}
			List<String> warnings = new ArrayList<>();
			if (headers.get(headers.size() - 1).storedLines() != lines.size()) {
				warnings.add("the header names " + headers.get(headers.size() - 1).storedLines() + " lines");
			}
			if (lines.stream().anyMatch(l -> l.format().equals("/:"))) {
				warnings.add("ITF commands (/:) are shown as stored, not executed");
			}
			if (lines.stream().anyMatch(l -> l.text().indexOf('�') >= 0)) {
				warnings.add("a character was cut in storage and is shown as �");
			}
			return new Text(requested, key, headers.get(0), headers.stream().map(Header::key).toList(), lines, warnings);
		}
	}

	// ---- keys and SQL ----------------------------------------------------------------

	/** A key with trailing blanks removed; each part is required and fits its field. */
	static Key key(String object, String name, String id, String language) throws TextReadException {
		String[] values = { object, name, id, language };
		for (int i = 0; i < values.length; i++) {
			String v = values[i];
			if (v == null || v.length() > KEY_LENGTHS[i] && unpad(v).length() > KEY_LENGTHS[i]
					|| v.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
				throw new TextReadException(Code.INVALID_KEY, "invalid " + KEY_FIELDS.get(i));
			}
			values[i] = unpad(v);
			if (values[i].isEmpty()) {
				throw new TextReadException(Code.INVALID_KEY, "empty " + KEY_FIELDS.get(i));
			}
		}
		return new Key(values[0], values[1], values[2], values[3]);
	}

	private static String[] eq(String field, String value) {
		return new String[] { field, "=", value };
	}

	private static List<String[]> keyFilters(Key k) {
		return List.of(eq("TDOBJECT", k.object()), eq("TDNAME", k.name()), eq("TDID", k.id()), eq("TDSPRAS", k.language()));
	}

	/**
	 * A static SELECT; values are quoted, and runs of blanks are kept with
	 * {@code concat_with_space}, since SAP condenses the statement's lines
	 * (as ARC-1's {@code buildTableQuerySql} with {@code preserveLiteralSpaces}).
	 */
	static String sql(String table, List<String> columns, List<String[]> where) {
		StringBuilder sb = new StringBuilder("SELECT ")
				.append(columns == null ? "*" : String.join(", ", columns)).append(" FROM ").append(table);
		List<String> clauses = new ArrayList<>();
		for (String[] w : where) {
			if (w[1].equals("STARTS_WITH")) {
				String prefix = w[2];
				if (prefix.contains("  ")) {
					// LIKE takes no expression: compare the prefix slice
					clauses.add("( left( " + w[0] + ", " + prefix.length() + " ) = " + literal(prefix) + " AND length( "
							+ w[0] + " ) >= " + prefix.length() + " )");
				} else {
					clauses.add(w[0] + " LIKE " + literal(prefix.replaceAll("[#%_]", "#$0") + "%") + " ESCAPE '#'");
				}
			} else {
				clauses.add(w[0] + " " + w[1] + " " + literal(w[2]));
			}
		}
		if (!clauses.isEmpty()) {
			sb.append(" WHERE ").append(String.join(" AND ", clauses));
		}
		return sb.toString();
	}

	static String literal(String value) {
		if (!value.contains("  ")) {
			return quote(value);
		}
		// texts between runs of blanks; ' ' stands for an empty text, as ABAP ignores trailing blanks
		List<String> texts = new ArrayList<>();
		List<Integer> blanks = new ArrayList<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(" +").matcher(value);
		int last = 0;
		while (m.find()) {
			texts.add(value.substring(last, m.start()));
			blanks.add(m.end() - m.start());
			last = m.end();
		}
		texts.add(value.substring(last));
		String expr = quote(texts.get(0).isEmpty() ? " " : texts.get(0));
		for (int i = 0; i < blanks.size(); i++) {
			String next = texts.get(i + 1);
			expr = "concat_with_space( " + expr + ", " + quote(next.isEmpty() ? " " : next) + ", " + blanks.get(i) + " )";
		}
		return expr;
	}

	private static String quote(String s) {
		return "'" + s.replace("'", "''") + "'";
	}

	static String unpad(String s) {
		return s == null ? "" : s.replaceAll(" +$", "");
	}

	private static String cell(Map<String, String> row, String field) throws TextReadException {
		String v = row.get(field);
		if (v == null) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "missing " + field);
		}
		return v;
	}

	private static int integer(Map<String, String> row, String field) throws TextReadException {
		String v = cell(row, field).trim();
		if (!v.matches("\\d{1,9}")) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid " + field);
		}
		return Integer.parseInt(v);
	}

	static Header parseHeader(Map<String, String> row, String client, Key expected) throws TextReadException {
		for (String f : HEADER_COLUMNS) {
			cell(row, f);
		}
		Key key;
		try {
			key = key(row.get("TDOBJECT"), row.get("TDNAME"), row.get("TDID"), row.get("TDSPRAS"));
		} catch (TextReadException e) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid stored text identity");
		}
		if (!unpad(row.get("MANDT")).equals(client) || expected != null && !key.equals(expected)) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "header identity mismatch");
		}
		String flag = unpad(row.get("TDREF"));
		if (!flag.isEmpty() && !flag.equals("X")) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid reference flag");
		}
		Key reference = null;
		String refObject = unpad(row.get("TDREFOBJ"));
		String refName = unpad(row.get("TDREFNAME"));
		String refId = unpad(row.get("TDREFID"));
		if (flag.equals("X") && !refObject.isEmpty() && !refName.isEmpty() && !refId.isEmpty()) {
			try {
				reference = key(refObject, refName, refId, key.language());
			} catch (TextReadException e) {
				throw new TextReadException(Code.REFERENCE_ERROR, "invalid reference target");
			}
		}
		String revision = String.join("\u0000", HEADER_COLUMNS.stream().map(row::get).toList());
		return new Header(key, row.get("TDTITLE"), row.get("TDTEXTTYPE"), integer(row, "TDTXTLINES"), reference,
				flag.equals("X") && reference == null, revision);
	}

	/** The chunks of STXL in order as one byte array; every chunk but the last is full. */
	static byte[] assemble(List<Map<String, String>> rows, Key key, String client) throws TextReadException {
		if (rows.isEmpty() || rows.size() > COUNTERS) {
			throw new TextReadException(Code.CORRUPT_CLUSTER, "missing or too many chunks");
		}
		byte[][] chunks = new byte[rows.size()][];
		for (Map<String, String> row : rows) {
			if (!unpad(cell(row, "MANDT")).equals(client) || !unpad(cell(row, "RELID")).equals("TX")
					|| !unpad(cell(row, "TDOBJECT")).equals(key.object()) || !unpad(cell(row, "TDNAME")).equals(key.name())
					|| !unpad(cell(row, "TDID")).equals(key.id()) || !unpad(cell(row, "TDSPRAS")).equals(key.language())) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "chunk identity mismatch");
			}
			int index = integer(row, "SRTF2");
			int length = integer(row, "CLUSTR");
			String hex = cell(row, "CLUSTD");
			if (index >= rows.size() || length < 1 || length > CHUNK || !hex.matches("[0-9A-Fa-f]{" + CHUNK * 2 + "}")) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "invalid or truncated chunk");
			}
			if (chunks[index] != null) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "duplicate chunk");
			}
			chunks[index] = HexFormat.of().parseHex(hex.substring(0, length * 2));
		}
		int total = 0;
		for (int i = 0; i < chunks.length; i++) {
			if (i < chunks.length - 1 && chunks[i].length != CHUNK) {
				throw new TextReadException(Code.CORRUPT_CLUSTER, "short chunk before the last");
			}
			total += chunks[i].length;
			if (total > MAX_INPUT) {
				throw new TextReadException(Code.LIMIT_EXCEEDED, "text larger than " + MAX_INPUT + " bytes");
			}
		}
		ByteBuffer out = ByteBuffer.allocate(total);
		for (byte[] chunk : chunks) {
			out.put(chunk);
		}
		return out.array();
	}

	// ---- decoder ---------------------------------------------------------------------

	private static final int HEADER_BYTES = 16;
	private static final int COMPRESSION_HEADER = 8;
	private static final int FORMAT_UNITS = 2;
	private static final int TEXT_UNITS = 132;
	private static final int ROW_BYTES = (FORMAT_UNITS + TEXT_UNITS) * 2;
	/** Residual bytes SAP leaves after the compressed stream; not a verified grammar. */
	private static final int MAX_TRAILING = 6;

	private static TextReadException corrupt(String message) {
		return new TextReadException(Code.CORRUPT_CLUSTER, message);
	}

	private static int uint(byte[] data, int offset, int size) throws TextReadException {
		if (offset < 0 || offset + size > data.length) {
			throw corrupt("truncated container at byte " + offset);
		}
		int v = 0;
		for (int i = 0; i < size; i++) {
			v = (v << 8) | (data[offset + i] & 0xff);
		}
		return v;
	}

	/** The TLINE rows of a cluster: container header, optional SAP-LZH compression, then the table. */
	static List<Line> decode(byte[] data) throws TextReadException {
		return parseTlines(unpack(data));
	}

	static byte[] unpack(byte[] data) throws TextReadException {
		if (data.length > MAX_INPUT) {
			throw new TextReadException(Code.LIMIT_EXCEEDED, "text larger than " + MAX_INPUT + " bytes");
		}
		if (data.length < HEADER_BYTES) {
			throw corrupt("truncated container");
		}
		if ((data[0] & 0xff) != 0xff || data[1] != 6 || data[2] != 2) {
			throw new TextReadException(Code.UNSUPPORTED_PROFILE, "unverified container signature, version or byte order");
		}
		String codePage = new String(data, 8, 4, StandardCharsets.ISO_8859_1);
		if (!codePage.equals("4103")) {
			throw new TextReadException(Code.UNSUPPORTED_PROFILE, "unverified code page " + codePage);
		}
		int compression = data[4];
		if (compression == 1) {
			if (data.length - HEADER_BYTES > MAX_OUTPUT) {
				throw new TextReadException(Code.LIMIT_EXCEEDED, "text larger than " + MAX_OUTPUT + " bytes");
			}
			return java.util.Arrays.copyOfRange(data, HEADER_BYTES, data.length);
		}
		if (compression != 2) {
			throw new TextReadException(Code.UNSUPPORTED_PROFILE, "compression flag " + compression);
		}
		if (data.length < HEADER_BYTES + COMPRESSION_HEADER + 1) {
			throw corrupt("truncated compression header");
		}
		if ((data[20] & 0xff) != 0x12 || (data[21] & 0xff) != 0x1f || (data[22] & 0xff) != 0x9d) {
			throw new TextReadException(Code.UNSUPPORTED_PROFILE, "unsupported compression header");
		}
		long expected = ByteBuffer.wrap(data, HEADER_BYTES, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
		if (expected < 1 || expected > MAX_OUTPUT) {
			throw new TextReadException(Code.LIMIT_EXCEEDED, "declared size " + expected);
		}
		byte[] reframed = reframe(java.util.Arrays.copyOfRange(data, HEADER_BYTES + COMPRESSION_HEADER, data.length));
		int blockType = ((reframed[0] & 0xff) >>> 1) & 3;
		if (blockType != 1 && blockType != 2) {
			throw new TextReadException(Code.UNSUPPORTED_PROFILE, "unsupported initial LZH block type");
		}
		Inflater inflater = new Inflater(true);
		try {
			inflater.setInput(reframed);
			byte[] out = new byte[(int) expected + 1];
			int n = 0;
			while (!inflater.finished() && n < out.length) {
				int got = inflater.inflate(out, n, out.length - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				n += got;
			}
			if (!inflater.finished()) {
				throw corrupt("invalid, truncated or oversized LZH stream");
			}
			if (n != expected) {
				throw corrupt("decompressed length mismatch");
			}
			long trailing = reframed.length - inflater.getBytesRead();
			if (inflater.getBytesRead() < 1 || trailing < 0 || trailing > MAX_TRAILING) {
				throw corrupt("excess bytes after the compressed stream");
			}
			return java.util.Arrays.copyOf(out, n);
		} catch (DataFormatException e) {
			throw corrupt("invalid LZH stream");
		} finally {
			inflater.end();
		}
	}

	/** Removes SAP's bit prefix so that the stream is plain deflate. */
	static byte[] reframe(byte[] source) {
		int shift = 2 + (source[0] & 3);
		byte[] out = new byte[source.length];
		for (int i = 0; i < source.length; i++) {
			int next = i + 1 < source.length ? source[i + 1] & 0xff : 0;
			out[i] = (byte) (((source[i] & 0xff) >>> shift) | (next << (8 - shift)));
		}
		return out;
	}

	private static String utf16(byte[] payload, int offset, int length) {
		return new String(payload, offset, length, StandardCharsets.UTF_16LE);
	}

	/** Parses one complete TLINE table; every marker checks the one before it. */
	static List<Line> parseTlines(byte[] p) throws TextReadException {
		List<Line> lines = new ArrayList<>();
		int pos = 0;
		boolean descriptor = false;
		boolean ended = false;
		boolean structureStarted = false;
		boolean structureEnded = false;
		boolean rowPending = false;
		boolean tableClosed = false;
		int rowBytes = 0;
		List<Integer> fields = new ArrayList<>();
		Integer declaredRows = null;
		int segmentLength = 0;
		while (pos < p.length) {
			int marker = p[pos] & 0xff;
			if (ended) {
				throw corrupt("bytes after the container end");
			}
			switch (marker) {
			case 3 -> { // segment
				if (descriptor) {
					throw corrupt("several tables in the container");
				}
				if (uint(p, pos + 1, 1) != 14 || uint(p, pos + 2, 1) != 0 || uint(p, pos + 3, 4) != ROW_BYTES) {
					throw corrupt("unexpected TLINE segment descriptor");
				}
				for (int i = pos + 12; i < pos + 32; i++) {
					if (uint(p, i, 1) != 0) {
						throw corrupt("unsupported segment header fields");
					}
				}
				segmentLength = uint(p, pos + 7, 4);
				int nameLength = uint(p, pos + 11, 1);
				if (pos + 32 + nameLength * 2 > p.length) {
					throw corrupt("truncated segment name");
				}
				if (!utf16(p, pos + 32, nameLength * 2).equals("TLINE")) {
					throw corrupt("the container does not hold TLINE");
				}
				pos += 32 + nameLength * 2;
				descriptor = true;
			}
			case 0xad -> { // structure start
				if (!descriptor || structureStarted || !fields.isEmpty() || declaredRows != null
						|| uint(p, pos + 3, 4) != ROW_BYTES) {
					throw corrupt("misplaced structure start");
				}
				structureStarted = true;
				pos += 7;
			}
			case 0xae -> { // structure end
				if (!structureStarted || structureEnded || fields.size() != 2 || declaredRows != null
						|| uint(p, pos + 3, 4) != ROW_BYTES) {
					throw corrupt("misplaced structure end");
				}
				structureEnded = true;
				pos += 7;
			}
			case 0xaa -> { // character field
				if (!structureStarted || structureEnded || fields.size() >= 2) {
					throw corrupt("unexpected character field");
				}
				int length = uint(p, pos + 3, 4);
				if (length != (fields.isEmpty() ? FORMAT_UNITS : TEXT_UNITS) * 2) {
					throw corrupt("unexpected TLINE field length");
				}
				fields.add(length);
				rowBytes += length;
				pos += 7;
			}
			case 0xbe -> { // table
				if (!structureEnded || declaredRows != null || !lines.isEmpty() || uint(p, pos + 1, 4) != rowBytes) {
					throw corrupt("misplaced table descriptor");
				}
				declaredRows = uint(p, pos + 5, 4);
				if (declaredRows > MAX_LINES) {
					throw new TextReadException(Code.LIMIT_EXCEEDED, "more than " + MAX_LINES + " lines");
				}
				pos += 9;
			}
			case 0xbc, 0xbb -> { // sized or fixed row
				int length = marker == 0xbc ? uint(p, pos + 1, 4) : rowBytes;
				int start = pos + (marker == 0xbc ? 5 : 1);
				if (tableClosed || rowPending || !descriptor || declaredRows == null || length != ROW_BYTES
						|| rowBytes != length || fields.size() != 2 || start + length > p.length) {
					throw corrupt("unexpected TLINE row");
				}
				if (lines.size() >= MAX_LINES) {
					throw new TextReadException(Code.LIMIT_EXCEEDED, "more than " + MAX_LINES + " lines");
				}
				lines.add(new Line(unpad(utf16(p, start, FORMAT_UNITS * 2)),
						unpad(utf16(p, start + FORMAT_UNITS * 2, TEXT_UNITS * 2))));
				rowPending = true;
				pos = start + length;
			}
			case 0xbd -> { // row end
				if (!rowPending || tableClosed) {
					throw corrupt("misplaced row end");
				}
				rowPending = false;
				pos++;
			}
			case 0xbf -> { // table end
				if (declaredRows == null || tableClosed || rowPending || declaredRows != lines.size()) {
					throw corrupt("misplaced table end");
				}
				tableClosed = true;
				pos++;
			}
			case 4 -> { // container end
				if (!tableClosed || segmentLength != 0 && segmentLength != pos) {
					throw corrupt("the container ended before the table");
				}
				ended = true;
				pos++;
			}
			default -> throw corrupt("unsupported container marker " + marker + " at " + pos);
			}
		}
		if (!descriptor || !ended) {
			throw corrupt("missing TLINE descriptor or end marker");
		}
		return lines;
	}

	// ---- output ----------------------------------------------------------------------

	/** Lines in ITF: the format column, a bar, the text. */
	static String format(Text t) {
		StringBuilder sb = new StringBuilder(t.requested().describe());
		String title = unpad(t.header().title());
		sb.append(title.isEmpty() ? "" : " – " + title).append(" (").append(t.lines().size()).append(" lines");
		if (!t.resolved().equals(t.requested())) {
			sb.append(", stored under ").append(t.resolved().describe());
		}
		sb.append(")\n");
		for (Line l : t.lines()) {
			sb.append(String.format("%-2s", l.format())).append(" | ").append(l.text()).append('\n');
		}
		t.warnings().forEach(w -> sb.append("Note: ").append(w).append('\n'));
		return sb.toString();
	}
}

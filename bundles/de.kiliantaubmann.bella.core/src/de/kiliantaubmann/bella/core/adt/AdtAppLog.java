package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * One saved application log (SLG1) read through SAP's own service
 * {@code APL_LOG_MANAGEMENT_SRV}, so SAP renders the messages and checks the
 * developer's authorization. Contract follows ARC-1
 * ({@code src/adt/application-log.ts}, MIT): an exact 20-digit log number,
 * pages of at most 100 messages, and an unexpected answer is refused as a
 * whole instead of shown in part.
 */
final class AdtAppLog {

	static final String SERVICE = "/sap/opu/odata/sap/APL_LOG_MANAGEMENT_SRV/";
	static final int MAX_ROWS = 100;
	static final int MAX_OFFSET = 5_000;

	private AdtAppLog() {
	}

	private static AdtException invalid() {
		return new AdtException(502, "SAP's application-log service answered unexpectedly; nothing of it is shown.");
	}

	private record Page(List<JsonObject> rows, boolean more) {
	}

	private static Page page(AdtClient c, String path, int maximum, CancelToken cancel) throws IOException {
		AdtResponse r = c.exchange(AdtRequest.get(SERVICE + path, "application/json"), cancel);
		if (r.status() == 404 && path.startsWith("ApplicationLogOverviewSet?")) {
			throw new AdtException(404, "This system has no application-log service (APL_LOG_MANAGEMENT_SRV); "
					+ "look at the log in SLG1.");
		}
		if (!r.ok()) {
			throw new AdtException(r.status(), AdtErrors.message(r));
		}
		JsonObject d;
		try {
			JsonElement root = JsonParser.parseString(r.body());
			d = root.isJsonObject() && root.getAsJsonObject().get("d") instanceof JsonObject o ? o : null;
		} catch (RuntimeException e) {
			throw invalid();
		}
		if (d == null || !(d.get("results") instanceof JsonArray results) || results.size() > maximum) {
			throw invalid();
		}
		List<JsonObject> rows = new ArrayList<>();
		for (JsonElement e : results) {
			if (!e.isJsonObject()) {
				throw invalid();
			}
			rows.add(e.getAsJsonObject());
		}
		JsonElement next = d.get("__next");
		if (next != null && (!next.isJsonPrimitive() || next.getAsString().isEmpty() || rows.isEmpty())) {
			throw invalid();
		}
		return new Page(rows, next != null);
	}

	private static String string(JsonObject row, String field) throws AdtException {
		JsonElement e = row.get(field);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw invalid();
		}
		return e.getAsString();
	}

	private static boolean bool(JsonObject row, String field) throws AdtException {
		JsonElement e = row.get(field);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw invalid();
		}
		return e.getAsBoolean();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/** Header and one page of messages of the log, as text for the model. */
	static String read(AdtClient c, String logNumber, int maxRows, int offset, CancelToken cancel) throws IOException {
		String number = logNumber == null ? "" : logNumber.trim();
		if (!number.matches("\\d{20}") || maxRows < 1 || maxRows > MAX_ROWS || offset < 0 || offset > MAX_OFFSET) {
			throw new AdtException(400, "Give the exact 20-digit 'log_number' (SLG1), 'max_rows' 1-" + MAX_ROWS
					+ " and 'offset' 0-" + MAX_OFFSET + ".");
		}
		// the log number filters the overview only; the message set has no such field
		Page overview = page(c, "ApplicationLogOverviewSet?" + "$filter=" + enc("LogNumber eq '" + number + "'")
				+ "&$top=2&$format=json", 2, cancel);
		if (overview.more() || overview.rows().size() > 1) {
			throw invalid();
		}
		if (overview.rows().isEmpty()) {
			return "No saved log " + number + " is visible to you. That does not prove it does not exist (it may be "
					+ "deleted, archived or not authorized).";
		}
		JsonObject header = overview.rows().get(0);
		if (!string(header, "LogNumber").equals(number)) {
			throw invalid();
		}
		String handle = string(header, "LogHandle");
		if (!handle.matches("[\\x21-\\x7e]{22}")) {
			throw invalid();
		}
		// the key SAP returned, never a URL from the answer; escaped for OData and the URL
		String key = enc(handle.replace("'", "''")).replace("'", "%27");
		Page messages = page(c, "ApplicationLogOverviewSet('" + key + "')/ApplicationLogMessageSet?$top="
				+ (maxRows + 1) + "&$skip=" + offset + "&$format=json", maxRows + 1, cancel);
		StringBuilder sb = new StringBuilder("Application log ").append(number).append(": object ")
				.append(string(header, "Object")).append(" / ").append(string(header, "SubObject"));
		String external = string(header, "ExternalNumber");
		sb.append(external.isBlank() ? "" : ", external number " + external).append(", severity ")
				.append(string(header, "Severity")).append(", created ").append(string(header, "CreatedAt")).append('\n');
		Set<String> numbers = new HashSet<>();
		int shown = 0;
		for (JsonObject m : messages.rows()) {
			String no = string(m, "MessageNumber");
			if (!string(m, "LogHandle").equals(handle) || !no.matches("\\d{6}") || !numbers.add(no)) {
				throw invalid();
			}
			String type = string(m, "Type");
			String text = string(m, "Text");
			String time = string(m, "Timestamp");
			boolean longText = bool(m, "HasLongText");
			boolean details = bool(m, "HasDetailText");
			if (shown++ < maxRows) {
				sb.append(no).append(' ').append(type).append(' ').append(time).append(": ").append(text)
						.append(longText ? " [long text in SLG1]" : "").append(details ? " [details in SLG1]" : "")
						.append('\n');
			}
		}
		boolean more = messages.rows().size() > maxRows || messages.more();
		int next = offset + Math.min(messages.rows().size(), maxRows);
		if (more) {
			sb.append(next <= MAX_OFFSET ? "More messages: read on with 'offset' " + next + "."
					: "More messages than " + MAX_OFFSET + "; look at the rest in SLG1.").append('\n');
		}
		return sb.append("Pages are read one by one; the log can change in between.").toString();
	}
}

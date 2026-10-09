package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * HTTP requests to services of the SAP system outside ADT (OData, REST, own
 * ICF nodes), sent through the developer's ADT logon. The OData probe with
 * {@code sap-statistics} follows ARC-1's {@code odata_perf}
 * ({@code src/adt/diagnostics.ts}, MIT).
 */
final class AdtHttp {

	static final List<String> SEND_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");
	/** Headers Bella or the logon sets; the model must not override them. */
	static final Set<String> FORBIDDEN_HEADERS = Set.of("authorization", "proxy-authorization", "cookie", "host",
			"content-length", "x-csrf-token", "x-sap-security-session");
	/** Response headers shown to the model; cookies and tokens stay out. */
	static final List<String> SHOWN_HEADERS = List.of("content-type", "location", "etag", "sap-message",
			"dataserviceversion", "odata-version", "odata-entityid");
	/** ADT itself and gCTS have their own tools, with package scope and policy rules. */
	private static final List<String> BLOCKED_PREFIXES = List.of("/sap/bc/adt", "/sap/bc/cts_abapvcs");
	private static final Pattern DOT_SEGMENT = Pattern.compile("(?:^|/)(?:\\.|%2e){1,2}(?:/|$)",
			Pattern.CASE_INSENSITIVE);
	/** Query parameters that would log on as another user. */
	private static final Set<String> LOGON_PARAMS = Set.of("sap-user", "sap-password", "sap-alias");
	/**
	 * What ICF strips from a path segment before it routes: URL mangling such
	 * as {@code /sap(bD1kZQ==)/} and matrix parameters such as {@code ;x=1}.
	 */
	private static final Pattern SEGMENT_DECORATION = Pattern.compile("\\([^/]*\\)|;[^/]*");
	private static final int MAX_URL = 4096;

	private AdtHttp() {
	}

	// ---- checks ----------------------------------------------------------------

	/**
	 * The url if it is a host-relative path on the connected system (as in
	 * ARC-1's {@code assertODataPerfUrl}); otherwise an
	 * {@link IllegalArgumentException} saying what is allowed.
	 *
	 * @param odataOnly only {@code /sap/opu/odata} and {@code /sap/opu/odata4}
	 */
	static String checkUrl(String url, boolean odataOnly) {
		String allowed = odataOnly
				? "'url' must be a host-relative OData path on the SAP system, e.g. /sap/opu/odata/sap/<SRV>/<EntitySet>?$top=20 "
						+ "or /sap/opu/odata4/sap/.../Entity?$filter=…; absolute URLs and other paths are not allowed."
				: "'url' must be a host-relative path on the SAP system, e.g. /sap/opu/odata/sap/<SRV>/<EntitySet> or "
						+ "/sap/bc/rest/…; absolute URLs are not allowed.";
		String u = url == null ? "" : url.trim();
		String rawPath = u.split("[?#]", 2)[0];
		if (u.isEmpty() || u.length() > MAX_URL || !u.startsWith("/") || u.startsWith("//") || u.contains("://")
				|| u.contains("\\") || u.contains("#") || rawPath.toLowerCase(Locale.ROOT).contains("%5c")
				|| DOT_SEGMENT.matcher(rawPath).find() || u.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
			throw new IllegalArgumentException(allowed);
		}
		String path;
		try {
			// decoded, so that an encoded letter cannot hide a blocked prefix
			URI parsed = new URI("https://bella.invalid" + rawPath);
			if (!"bella.invalid".equals(parsed.getHost()) || parsed.getPath() == null) {
				throw new IllegalArgumentException(allowed);
			}
			path = routedPath(parsed.getPath());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(allowed + " Encode special characters such as blanks (%20).");
		}
		if (DOT_SEGMENT.matcher(path).find()) {
			throw new IllegalArgumentException(allowed);
		}
		if (odataOnly && !isOData(path)) {
			throw new IllegalArgumentException(allowed);
		}
		for (String prefix : BLOCKED_PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				throw new IllegalArgumentException("ADT and gCTS paths (" + prefix
						+ ") are not allowed here; use Bella's ADT tools for them.");
			}
		}
		if (hasLogonParam(u)) {
			throw new IllegalArgumentException("Logon parameters (sap-user, sap-password, sap-alias) are not allowed; "
					+ "the request uses the developer's ADT logon.");
		}
		return u;
	}

	/**
	 * The path as ICF routes it, lower case: without URL mangling, matrix
	 * parameters and repeated slashes. OData keys such as
	 * {@code Orders('1')} lose their parentheses as well, which does not
	 * matter for the prefix checks.
	 */
	static String routedPath(String decodedPath) {
		return SEGMENT_DECORATION.matcher(decodedPath).replaceAll("").replaceAll("/{2,}", "/")
				.toLowerCase(Locale.ROOT);
	}

	/** Whether a query parameter name, decoded as ICF decodes it, is a logon parameter. */
	static boolean hasLogonParam(String url) {
		String[] parts = url.split("\\?", 2);
		if (parts.length < 2) {
			return false;
		}
		for (String pair : parts[1].split("[&;]")) {
			String name = pair.split("=", 2)[0];
			String decoded;
			try {
				decoded = URLDecoder.decode(name, StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				// a broken escape: refuse rather than guess what ICF makes of it
				return true;
			}
			if (LOGON_PARAMS.contains(decoded.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	static boolean isOData(String path) {
		return path.equals("/sap/opu/odata") || path.startsWith("/sap/opu/odata/") || path.equals("/sap/opu/odata4")
				|| path.startsWith("/sap/opu/odata4/");
	}

	/** The 'headers' argument as a map; refuses headers the logon or Bella sets. */
	static Map<String, String> headers(JsonObject in) {
		Map<String, String> out = new LinkedHashMap<>();
		JsonElement h = in == null ? null : in.get("headers");
		if (h == null || h.isJsonNull()) {
			return out;
		}
		if (!h.isJsonObject()) {
			throw new IllegalArgumentException("'headers' must be an object of header names and values.");
		}
		for (Map.Entry<String, JsonElement> e : h.getAsJsonObject().entrySet()) {
			String name = e.getKey().trim();
			if (name.isEmpty() || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
				throw new IllegalArgumentException("Invalid header name '" + name + "'.");
			}
			if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Header " + name
						+ " is set by the ADT logon or by Bella and cannot be given.");
			}
			String value = e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : String.valueOf(e.getValue());
			if (value.chars().anyMatch(ch -> ch == '\r' || ch == '\n')) {
				throw new IllegalArgumentException("Header " + name + " must not contain line breaks.");
			}
			out.put(name, value);
		}
		return out;
	}

	// ---- OData probe (read) --------------------------------------------------------

	static String withStatistics(String url) {
		return url + (url.contains("?") ? "&" : "?") + "sap-statistics=true";
	}

	/** The {@code sap-statistics} header ({@code k=v,k=v,…}) as numbers; unknown or broken pairs are skipped. */
	static Map<String, Double> statistics(String header) {
		Map<String, Double> out = new LinkedHashMap<>();
		if (header == null) {
			return out;
		}
		for (String pair : header.split(",")) {
			int i = pair.indexOf('=');
			if (i <= 0) {
				continue;
			}
			try {
				double v = Double.parseDouble(pair.substring(i + 1).trim());
				if (Double.isFinite(v)) {
					out.put(pair.substring(0, i).trim(), v);
				}
			} catch (NumberFormatException e) {
				// not a number
			}
		}
		return out;
	}

	/** Where the server time goes, so the model looks at the right deeper signal (after ARC-1). */
	static String verdict(Map<String, Double> m) {
		double total = m.getOrDefault("gwtotal", m.getOrDefault("total", 0d));
		if (total <= 0) {
			return "No Gateway timing in sap-statistics; the path may not be an OData/Gateway service.";
		}
		double db = m.getOrDefault("gwappdb", 0d);
		double app = Math.max(m.getOrDefault("gwapp", 0d) - db, 0);
		// older releases (e.g. 7.50) report only gwhub for the framework time
		double framework = Math.max(m.getOrDefault("gwfw", 0d), m.getOrDefault("gwhub", 0d));
		double auth = m.getOrDefault("icfauth", 0d);
		double top = Math.max(Math.max(db, app), Math.max(framework, auth));
		if (top <= 0) {
			return "Gateway total " + fmt(total) + " ms, but this release does not split it up (no gwappdb/gwapp/gwfw). "
					+ "Arm an ST05 SQL trace or an ABAP profiler trace (adt_trace_control) for the breakdown.";
		}
		if (top == db) {
			return "DB-bound: the CDS/SQL query dominates. Look at the CDS view or the SELECTs behind the service, "
					+ "or arm an ST05 SQL trace (adt_trace_control 'set_sql_trace').";
		}
		if (top == app) {
			return "ABAP-bound: application logic dominates, not the database ($expand N+1 is a classic cause). Find the "
					+ "implementation (DPC_EXT class or RAP behavior) and arm a profiler trace (adt_trace_control 'trace_start', "
					+ "process_type http), then read it with adt_diagnose 'traces'.";
		}
		if (top == framework) {
			return "Gateway-framework-bound: likely metadata or first-call (cold cache) cost. Send it again to compare.";
		}
		return "Auth-bound: ICF or DCL authorization checks dominate.";
	}

	/** The defaults, overridden by the given headers whatever their case. */
	static Map<String, String> withDefaults(Map<String, String> defaults, Map<String, String> headers) {
		Map<String, String> h = new LinkedHashMap<>(defaults);
		headers.forEach((k, v) -> {
			h.keySet().removeIf(k::equalsIgnoreCase);
			h.put(k, v);
		});
		return h;
	}

	/** GET of an OData path with {@code sap-statistics}: status, timing, verdict and the answer. */
	static String odataRequest(AdtClient c, String url, Map<String, String> headers, CancelToken cancel)
			throws IOException {
		String withStat = withStatistics(checkUrl(url, true));
		Map<String, String> h = withDefaults(Map.of("Accept", "application/json"), headers);
		long start = System.nanoTime();
		AdtResponse r = c.exchange(new AdtRequest("GET", withStat, h, null, null), cancel);
		long wall = (System.nanoTime() - start) / 1_000_000;
		Map<String, Double> stat = statistics(r.header("sap-statistics"));
		StringBuilder sb = new StringBuilder("GET ").append(url.trim()).append("\nHTTP ").append(r.status())
				.append(", ").append(wall).append(" ms wall clock\n");
		if (!stat.isEmpty()) {
			sb.append("sap-statistics (ms): ");
			stat.forEach((k, v) -> sb.append(k).append('=').append(fmt(v)).append(' '));
			sb.append('\n');
			Double gwtotal = stat.containsKey("gwtotal") ? stat.get("gwtotal") : stat.get("total");
			if (gwtotal != null) {
				long outside = Math.max(Math.round(wall - gwtotal), 0);
				sb.append("Outside SAP Gateway (network, queueing): ").append(outside).append(" ms");
				if (outside > gwtotal && outside > 1000) {
					sb.append(" – most of the time is not server time; cite gwtotal/gwappdb as the SAP figure");
				}
				sb.append('\n');
			}
			sb.append("Verdict: ").append(verdict(stat)).append('\n');
		}
		return sb.append(describe(r)).toString();
	}

	// ---- send (write) ----------------------------------------------------------------

	/** The method in upper case if it is one adt_http_send may use; else an {@link IllegalArgumentException}. */
	static String checkMethod(String method) {
		String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
		if (!SEND_METHODS.contains(m)) {
			throw new IllegalArgumentException("'method' is one of " + String.join(", ", SEND_METHODS)
					+ "; read with adt_diagnose 'odata_request'.");
		}
		return m;
	}

	/**
	 * Where to fetch the CSRF token: the service root for OData (its service
	 * document is small and runs no application logic), else the path itself.
	 * A GET on an entity set would read the whole set, a GET on a function
	 * import would run it.
	 */
	static String tokenPath(String url) {
		String raw = url.split("\\?", 2)[0];
		String[] seg = raw.split("/");
		// "", "sap", "opu", "odata", namespace, service[, …]
		String lower = raw.toLowerCase(Locale.ROOT);
		int keep = lower.startsWith("/sap/opu/odata4/") ? 10 // …/odata4/<ns>/<group>/srvd_a2x/<ns>/<srv>/<version>
				: lower.startsWith("/sap/opu/odata/") ? 6 : 0;
		if (keep == 0 || seg.length < keep) {
			return raw;
		}
		return String.join("/", java.util.Arrays.copyOf(seg, keep)) + "/";
	}

	/**
	 * Sends a changing request in one ABAP session: first a GET with
	 * {@code x-csrf-token: Fetch} on the service root, then the request with
	 * the token. Method and url must be checked ({@link #checkMethod},
	 * {@link #checkUrl}).
	 */
	static String send(AdtTransport.Session session, String method, String url, Map<String, String> headers,
			String body, String contentType, CancelToken cancel) throws IOException {
		String m = checkMethod(method);
		String u = url.trim();
		String tokenPath = tokenPath(u);
		AdtResponse tokenResponse = AdtClient.exchange(session,
				new AdtRequest("GET", tokenPath, Map.of("x-csrf-token", "Fetch", "Accept", "*/*"), null, null), cancel);
		String token = tokenResponse.header("x-csrf-token");
		if (token != null && (token.isBlank() || token.equalsIgnoreCase("Required"))) {
			token = null;
		}
		Map<String, String> h = withDefaults(Map.of("Accept", "application/json"), headers);
		// a Content-Type header is the body's type; sent once, as the body's
		String type = contentType == null || contentType.isBlank() ? null : contentType.trim();
		for (String k : List.copyOf(h.keySet())) {
			if (k.equalsIgnoreCase("Content-Type")) {
				String given = h.remove(k);
				if (type == null) {
					type = given;
				}
			}
		}
		if (type == null && body != null && !body.isBlank()) {
			type = "application/json";
		}
		String payload = body == null || body.isEmpty() ? null : body;
		long start = System.nanoTime();
		AdtResponse r = AdtClient.exchange(session, new AdtRequest(m, u, withToken(h, token), payload, type), cancel);
		boolean retried = false;
		if (token != null && csrfRefused(r)) {
			// the ADT layer may manage the session's token itself; then Bella's second one breaks it
			r = AdtClient.exchange(session, new AdtRequest(m, u, h, payload, type), cancel);
			retried = true;
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		StringBuilder sb = new StringBuilder(m).append(' ').append(u).append("\nHTTP ").append(r.status()).append(", ")
				.append(ms).append(" ms\n");
		if (token == null) {
			sb.append("No CSRF token came back from GET ").append(tokenPath).append(" (HTTP ")
					.append(tokenResponse.status()).append("); the request was sent without one.\n");
		}
		if (retried && !csrfRefused(r)) {
			sb.append("SAP refused Bella's CSRF token; the request went through with the session's own token.\n");
		}
		if (csrfRefused(r)) {
			sb.append("SAP refused the CSRF token (fetched from ").append(tokenPath)
					.append("). The service may not accept the ADT session; nothing was changed.\n");
		}
		return sb.append(describe(r)).toString();
	}

	private static Map<String, String> withToken(Map<String, String> headers, String token) {
		if (token == null) {
			return headers;
		}
		Map<String, String> h = new LinkedHashMap<>(headers);
		h.put("x-csrf-token", token);
		return h;
	}

	private static boolean csrfRefused(AdtResponse r) {
		return r.status() == 403 && "required".equalsIgnoreCase(String.valueOf(r.header("x-csrf-token")));
	}

	// ---- output ----------------------------------------------------------------------

	/** Selected headers and the body, JSON pretty-printed and cut; no cookies or tokens. */
	static String describe(AdtResponse r) {
		StringBuilder sb = new StringBuilder();
		for (String name : SHOWN_HEADERS) {
			String v = r.header(name);
			if (v != null && !v.isBlank()) {
				sb.append(name).append(": ").append(v).append('\n');
			}
		}
		String body = r.body() == null ? "" : r.body();
		if (body.isBlank()) {
			return sb.append(r.ok() ? "(no body)" : AdtErrors.message(r)).toString();
		}
		String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
		sb.append('\n');
		if (type.contains("json") || body.stripLeading().startsWith("{") || body.stripLeading().startsWith("[")) {
			sb.append(AdtDiagnostics.json(body));
		} else if (type.contains("html")) {
			sb.append(AdtDiagnostics.cut(body.replaceAll("(?is)<(script|style)\\b.*?</\\1\\s*>", "").replaceAll("<[^>]+>", " ")
					.replaceAll("&nbsp;", " ").replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim()));
		} else {
			sb.append(AdtDiagnostics.cut(body));
		}
		return sb.toString();
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}
}

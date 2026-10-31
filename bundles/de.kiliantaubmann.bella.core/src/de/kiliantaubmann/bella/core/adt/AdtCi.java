package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * ABAP Unit and ATC through SAP's public CI APIs ({@code /sap/bc/adt/api/…}):
 * a JUnit report of the tests and a Checkstyle report of the findings, with a
 * pass/fail verdict, as a CI pipeline would use them. Contract follows ARC-1
 * ({@code src/adt/aunit.ts} {@code runPublicAunit}, {@code src/adt/ci-quality.ts},
 * {@code src/adt/ci-quality-xml.ts}, MIT): only harmless tests run, and a run
 * whose result cannot be read completely never counts as passed.
 */
final class AdtCi {

	static final String AUNIT_RUNS = "/sap/bc/adt/api/abapunit/runs";
	static final String AUNIT_RESULTS = "/sap/bc/adt/api/abapunit/results/";
	static final String AUNIT_STATUS = "application/vnd.sap.adt.api.abapunit.run-status.v1+xml";
	static final String AUNIT_RUN = "application/vnd.sap.adt.api.abapunit.run.v2+xml";
	static final String JUNIT = "application/vnd.sap.adt.api.junit.run-result.v1+xml";
	static final String ATC_RUNS = "/sap/bc/adt/api/atc/runs";
	static final String ATC_RESULTS = "/sap/bc/adt/api/atc/results/";
	static final String ATC_RUN = "application/vnd.sap.atc.run.parameters.v1+xml; charset=utf-8";
	static final String ATC_STATUS = "application/vnd.sap.atc.run.v1+xml";
	static final String CHECKSTYLE = "application/vnd.sap.atc.checkstyle.v1+xml";
	/** The variant ARC-1 uses when neither the call nor the system names one. */
	static final String DEFAULT_VARIANT = "ABAP_CLOUD_DEVELOPMENT_DEFAULT";
	private static final String ZERO_RUN = "/00000000000000000000000000000000";
	static final long AUNIT_MAX_MILLIS = 300_000;
	static final long ATC_MAX_MILLIS = 600_000;
	/** Characters of a report handed to the model with 'include_report'. */
	static final int REPORT_CHARS = 40_000;
	/** Failures or findings listed one by one at most. */
	static final int LISTED = 100;

	private AdtCi() {
	}

	/** Object set of a run: one object, or a package with or without its subpackages. */
	record Target(String type, String name, boolean subpackages) {

		boolean isPackage() {
			return type.equals("DEVC");
		}

		String objectSet() {
			String ns = "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:osl=\"http://www.sap.com/api/osl\"";
			if (isPackage()) {
				return "<osl:objectSet " + ns + " xsi:type=\"osl:packageSet\"><osl:package name=\"" + AdtXml.escape(name)
						+ "\" includeSubpackages=\"" + subpackages + "\"/></osl:objectSet>";
			}
			return "<osl:objectSet " + ns + " xsi:type=\"osl:flatObjectSet\"><osl:object name=\"" + AdtXml.escape(name)
					+ "\" type=\"" + AdtXml.escape(type) + "\"/></osl:objectSet>";
		}
	}

	// ---- ABAP Unit -------------------------------------------------------------------

	/** One test of a JUnit report with its outcome; {@code message} empty when it passed. */
	record TestCase(String suite, String name, String outcome, String message) {
	}

	record JUnit(int tests, int failures, int errors, int skipped, List<TestCase> cases) {

		boolean passed() {
			return tests > skipped && failures == 0 && errors == 0;
		}
	}

	/** Runs the harmless tests of the target and returns the verdict and SAP's JUnit report. */
	static String unitTests(AdtClient c, Target t, boolean withReport, CancelToken cancel) throws IOException {
		if (!available(c, AUNIT_RUNS + ZERO_RUN, AUNIT_STATUS, cancel)) {
			return "This system has no ABAP Unit CI API (/sap/bc/adt/api/abapunit). Run adt_run_unit_tests without "
					+ "'format' for the usual result.";
		}
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><aunit:run xmlns:aunit=\"http://www.sap.com/adt/api/aunit\" "
				+ "title=\"Bella ABAP Unit\" context=\"Bella\"><aunit:options><aunit:measurements type=\"none\"/>"
				+ "<aunit:scope ownTests=\"true\" foreignTests=\"false\" addForeignTestsAsPreview=\"false\"/>"
				+ "<aunit:riskLevel harmless=\"true\" dangerous=\"false\" critical=\"false\"/>"
				+ "<aunit:duration short=\"true\" medium=\"true\" long=\"true\"/></aunit:options>" + t.objectSet()
				+ "</aunit:run>";
		AdtResponse created = ok(c.exchange(new AdtRequest("POST", AUNIT_RUNS, Map.of("Accept", AUNIT_STATUS), body,
				AUNIT_RUN), cancel), "starting the ABAP Unit run");
		String run = location(created, AUNIT_RUNS + "/");
		long end = System.currentTimeMillis() + AUNIT_MAX_MILLIS;
		long wait = 100;
		String status = "";
		String result = null;
		while (true) {
			String xml = ok(c.exchange(AdtRequest.get(run + (run.contains("?") ? "&" : "?") + "withLongPolling=true",
					AUNIT_STATUS), cancel), "reading the ABAP Unit run").body();
			Element root = AdtXml.parse(xml).getDocumentElement();
			List<Element> progress = AdtXml.elements(root, "progress");
			status = progress.isEmpty() ? "" : AdtXml.attr(progress.get(0), "status").trim();
			result = resultLink(root, AUNIT_RESULTS, "run-result", "junit.run-result");
			String s = status.toLowerCase(Locale.ROOT);
			if ((s.equals("completed") || s.equals("finished")) && result != null) {
				break;
			}
			if (s.isEmpty() || s.matches(".*(not created|failed|error|aborted|cancelled|canceled).*")) {
				return incomplete("ABAP Unit", "SAP did not complete the run (status " + (s.isEmpty() ? "empty" : status)
						+ ").", run);
			}
			if (System.currentTimeMillis() + wait > end) {
				return incomplete("ABAP Unit", "The run did not finish within " + AUNIT_MAX_MILLIS / 60_000
						+ " minutes (last status " + status + ").", run);
			}
			pause(wait, cancel);
			wait = Math.min(1_000, wait * 2);
		}
		String junit = ok(c.exchange(AdtRequest.get(result, JUNIT), cancel), "reading the JUnit report").body();
		JUnit report;
		try {
			report = parseJUnit(junit);
		} catch (IOException | RuntimeException e) {
			return incomplete("ABAP Unit", "SAP's JUnit report could not be read completely: " + e.getMessage(), run);
		}
		StringBuilder sb = new StringBuilder("ABAP Unit through the CI API (harmless tests of ")
				.append(t.isPackage() ? "package " + t.name() + (t.subpackages() ? " and its subpackages" : "")
						: t.type() + " " + t.name())
				.append("): ").append(report.tests()).append(" tests, ").append(report.failures()).append(" failures, ")
				.append(report.errors()).append(" errors, ").append(report.skipped()).append(" skipped. Verdict: ")
				.append(report.passed() ? "PASSED" : report.tests() == report.skipped() ? "FAILED (no test ran)" : "FAILED")
				.append('\n');
		int listed = 0;
		for (TestCase tc : report.cases()) {
			if (tc.outcome().equals("passed")) {
				continue;
			}
			if (listed++ == LISTED) {
				sb.append("… more in the report\n");
				break;
			}
			sb.append("- ").append(tc.outcome()).append(' ').append(tc.suite()).append(tc.suite().isEmpty() ? "" : ".")
					.append(tc.name()).append(tc.message().isEmpty() ? "" : ": " + tc.message().replaceAll("\\s+", " "))
					.append('\n');
		}
		return sb.append(report(junit, withReport, "JUnit")).toString();
	}

	/**
	 * Counters and test cases of a JUnit report; the counters must agree with the test cases. Only
	 * {@code tests} is required: many producers leave out {@code errors} or {@code skipped} on the root.
	 */
	static JUnit parseJUnit(String xml) throws IOException {
		Element root = AdtXml.parse(xml).getDocumentElement();
		if (!"testsuites".equals(AdtDiagnostics.local(root))) {
			throw new IOException("not a JUnit report");
		}
		int tests = count(root, "tests");
		Integer failures = optionalCount(root, "failures");
		Integer errors = optionalCount(root, "errors");
		Integer skipped = optionalCount(root, "skipped");
		List<TestCase> cases = new ArrayList<>();
		int f = 0;
		int e = 0;
		int s = 0;
		for (Element tc : AdtXml.elements(root, "testcase")) {
			String outcome = "passed";
			String message = "";
			for (String kind : List.of("failure", "error", "skipped")) {
				List<Element> found = AdtXml.elements(tc, kind);
				if (!found.isEmpty()) {
					if (!outcome.equals("passed")) {
						throw new IOException("a test case with contradictory outcomes");
					}
					outcome = kind;
					message = AdtXml.attr(found.get(0), "message");
					if (message.isEmpty()) {
						message = AdtXml.text(found.get(0)).trim();
					}
				}
			}
			switch (outcome) {
			case "failure" -> f++;
			case "error" -> e++;
			case "skipped" -> s++;
			default -> {
				// passed
			}
			}
			cases.add(new TestCase(AdtXml.attr(tc, "classname"), AdtXml.attr(tc, "name"), outcome, message));
		}
		if (cases.size() != tests || failures != null && f != failures || errors != null && e != errors
				|| skipped != null && s != skipped) {
			throw new IOException("its counters do not match its test cases");
		}
		return new JUnit(tests, f, e, s, cases);
	}

	/** A counter that may be missing (null); one that is there must be valid. */
	private static Integer optionalCount(Element e, String attr) throws IOException {
		return e.hasAttribute(attr) ? count(e, attr) : null;
	}

	private static int count(Element e, String attr) throws IOException {
		String v = AdtXml.attr(e, attr).trim();
		if (!v.matches("\\d{1,9}")) {
			throw new IOException("invalid '" + attr + "' counter");
		}
		return Integer.parseInt(v);
	}

	// ---- ATC -------------------------------------------------------------------------

	/** One Checkstyle finding. */
	record Finding(String file, int line, String severity, String message, String source) {
	}

	/**
	 * Runs ATC over a package and returns the verdict and SAP's Checkstyle
	 * report; the run fails on a finding of {@code failOn} or higher.
	 */
	static String atc(AdtClient c, Target t, String variant, String failOn, boolean withReport, CancelToken cancel)
			throws IOException {
		String threshold = failOn == null || failOn.isBlank() ? "error" : failOn.trim().toLowerCase(Locale.ROOT);
		if (!List.of("error", "warning", "info").contains(threshold)) {
			throw new AdtException(400, "'fail_on' is error, warning or info.");
		}
		if (!t.isPackage()) {
			throw new AdtException(400, "SAP's ATC CI API checks packages: give 'package' instead of 'name'.");
		}
		if (!available(c, ATC_RUNS + ZERO_RUN, ATC_STATUS, cancel)) {
			return "This system has no ATC CI API (/sap/bc/adt/api/atc). Run adt_atc_check without 'format' for the "
					+ "usual result.";
		}
		String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><atc:runparameters xmlns:atc=\"http://www.sap.com/adt/atc\" "
				+ "checkVariant=\"" + AdtXml.escape(variant) + "\">" + t.objectSet() + "</atc:runparameters>";
		AdtResponse created = ok(c.exchange(new AdtRequest("POST", ATC_RUNS + "?clientWait=false",
				Map.of("Accept", ATC_STATUS), body, ATC_RUN), cancel), "starting the ATC run");
		String run = location(created, ATC_RUNS + "/");
		long end = System.currentTimeMillis() + ATC_MAX_MILLIS;
		long wait = 500;
		String result;
		while (true) {
			Element root = AdtXml.parse(ok(c.exchange(AdtRequest.get(run, ATC_STATUS), cancel), "reading the ATC run")
					.body()).getDocumentElement();
			String status = "run".equals(AdtDiagnostics.local(root)) ? AdtXml.attr(root, "status").trim() : "";
			String s = status.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			if (s.equals("completed")) {
				result = resultLink(root, ATC_RESULTS, "/atc/result", "atc.checkstyle");
				if (result == null) {
					return incomplete("ATC", "The completed run names no Checkstyle result.", run);
				}
				break;
			}
			if (s.isEmpty() || List.of("not created", "failed", "cancelled", "canceled", "aborted", "error").contains(s)) {
				return incomplete("ATC", "SAP did not complete the run (status " + (s.isEmpty() ? "empty" : status) + ").",
						run);
			}
			if (System.currentTimeMillis() + wait > end) {
				return incomplete("ATC", "The run did not finish within " + ATC_MAX_MILLIS / 60_000
						+ " minutes (last status " + status + "). Look at it in SAP before starting another.", run);
			}
			pause(wait, cancel);
			wait = Math.min(5_000, wait * 2);
		}
		String xml = ok(c.exchange(AdtRequest.get(result, CHECKSTYLE), cancel), "reading the Checkstyle report").body();
		List<Finding> findings;
		try {
			findings = parseCheckstyle(xml);
		} catch (IOException | RuntimeException e) {
			return incomplete("ATC", "SAP's Checkstyle report could not be read completely: " + e.getMessage(), run);
		}
		long errors = findings.stream().filter(f -> f.severity().equals("error")).count();
		long warnings = findings.stream().filter(f -> f.severity().equals("warning")).count();
		long infos = findings.size() - errors - warnings;
		boolean fail = findings.stream().anyMatch(f -> rank(f.severity()) >= rank(threshold));
		StringBuilder sb = new StringBuilder("ATC through the CI API (package ").append(t.name())
				.append(t.subpackages() ? " and its subpackages" : "").append(", variant ").append(variant).append("): ")
				.append(findings.size()).append(" findings, ").append(errors).append(" errors, ").append(warnings)
				.append(" warnings, ").append(infos).append(" infos. Verdict: ").append(fail ? "FAILED" : "PASSED")
				.append(" (fails on ").append(threshold).append(threshold.equals("info") ? "" : " or higher").append(")\n");
		int listed = 0;
		for (Finding f : findings) {
			if (listed++ == LISTED) {
				sb.append("… more in the report\n");
				break;
			}
			sb.append("- ").append(f.severity()).append(' ').append(f.file()).append(f.line() > 0 ? " line " + f.line() : "")
					.append(": ").append(f.message().replaceAll("\\s+", " "))
					.append(f.source().isEmpty() ? "" : " [" + f.source() + "]").append('\n');
		}
		return sb.append(report(xml, withReport, "Checkstyle")).toString();
	}

	/** The findings of a Checkstyle report; anything but files with errors of a known severity is refused. */
	static List<Finding> parseCheckstyle(String xml) throws IOException {
		Element root = AdtXml.parse(xml).getDocumentElement();
		if (!"checkstyle".equals(AdtDiagnostics.local(root))) {
			throw new IOException("not a Checkstyle report");
		}
		List<Finding> out = new ArrayList<>();
		for (Element file : AdtXml.elements(root, "file")) {
			String name = AdtXml.attr(file, "name");
			if (name.isEmpty()) {
				throw new IOException("a file without a name");
			}
			for (Element error : AdtXml.elements(file, "error")) {
				String severity = AdtXml.attr(error, "severity");
				if (!List.of("error", "warning", "info").contains(severity)) {
					throw new IOException("invalid severity '" + severity + "'");
				}
				String message = AdtXml.attr(error, "message");
				if (message.isEmpty()) {
					throw new IOException("a finding without a message");
				}
				String line = AdtXml.attr(error, "line").trim();
				if (!line.isEmpty() && !line.matches("\\d{1,9}")) {
					throw new IOException("invalid line '" + line + "'");
				}
				out.add(new Finding(name, line.isEmpty() ? 0 : Integer.parseInt(line), severity, message,
						AdtXml.attr(error, "source")));
			}
		}
		return out;
	}

	private static int rank(String severity) {
		return switch (severity) {
		case "error" -> 3;
		case "warning" -> 2;
		default -> 1;
		};
	}

	// ---- shared ----------------------------------------------------------------------

	/** Whether the API answers on this system: 404, 405, 406 or 415 for an unknown run means it has none. */
	private static boolean available(AdtClient c, String probe, String accept, CancelToken cancel) throws IOException {
		return !List.of(404, 405, 406, 415).contains(c.exchange(AdtRequest.get(probe, accept), cancel).status());
	}

	private static AdtResponse ok(AdtResponse r, String what) throws AdtException {
		if (!r.ok()) {
			throw new AdtException(r.status(), "Failed " + what + ": " + AdtErrors.message(r));
		}
		return r;
	}

	/** The run's path from the Location header; only a path below {@code prefix} on the same system. */
	static String location(AdtResponse r, String prefix) throws AdtException {
		String loc = r.header("location");
		String path = canonical(loc, prefix);
		if (path == null) {
			throw new AdtException(502, "SAP started the run but named no usable location for it"
					+ (loc == null || loc.isBlank() ? "." : " (" + loc + ")."));
		}
		return path;
	}

	/** {@code uri} as a host-relative path below {@code prefix}, without dot segments; else {@code null}. */
	static String canonical(String uri, String prefix) {
		if (uri == null || uri.isBlank()) {
			return null;
		}
		String u = uri.trim();
		if (u.contains("://")) {
			try {
				URI parsed = new URI(u);
				u = parsed.getRawPath() + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
			} catch (URISyntaxException e) {
				return null;
			}
		}
		String path = u.split("\\?", 2)[0];
		if (!path.startsWith(prefix) || path.length() == prefix.length() || path.contains("..") || path.contains("//")
				|| path.contains("\\") || path.toLowerCase(Locale.ROOT).contains("%2e") || u.contains("#")) {
			return null;
		}
		return u;
	}

	/** The href of the run's result link, matched by rel or type. */
	private static String resultLink(Element root, String prefix, String rel, String type) {
		String found = null;
		for (Element link : AdtXml.elements(root, "link")) {
			if (AdtXml.attr(link, "rel").contains(rel) || AdtXml.attr(link, "type").contains(type)) {
				String href = canonical(AdtXml.attr(link, "href"), prefix);
				if (href != null) {
					if (found != null && !found.equals(href)) {
						return null; // two results: which one is meant is not clear
					}
					found = href;
				}
			}
		}
		return found;
	}

	private static String incomplete(String what, String reason, String run) {
		return what + " through the CI API: INCOMPLETE, which never counts as passed. " + reason + " Run: " + run;
	}

	private static String report(String xml, boolean withReport, String kind) {
		if (!withReport) {
			return "";
		}
		if (xml.length() > REPORT_CHARS) {
			return "\nThe " + kind + " report has " + xml.length() + " characters, more than " + REPORT_CHARS
					+ " shown; narrow the package.";
		}
		return "\n" + kind + " report:\n" + xml;
	}

	private static void pause(long millis, CancelToken cancel) throws IOException {
		if (cancel.isCancelled()) {
			throw new IOException("Cancelled; the run may still go on in SAP.");
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted; the run may still go on in SAP.");
		}
	}
}

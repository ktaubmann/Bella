package de.kiliantaubmann.bella.core.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bella's troubleshooting log. Off until the UI installs a {@link Sink}; then
 * {@link Level#INFO} records what happened (requests, tool calls, SAP calls,
 * CLI processes, durations, errors) and {@link Level#DEBUG} adds the content
 * (prompts, source code, tool input and output, protocol lines). Secrets such
 * as API keys and bearer tokens are removed from every entry, and confidential
 * names are masked like for the model (see {@link #mask(UnaryOperator)}).
 */
public final class Log {

	public enum Level {
		ERROR, WARN, INFO, DEBUG
	}

	/** Where entries go, e.g. a file. Implementations must be thread-safe. */
	public interface Sink {
		void write(Level level, String area, String message);
	}

	/** Longest content excerpt in a {@link Level#DEBUG} entry. */
	public static final int MAX_CONTENT = 8_000;

	private static volatile Sink sink;
	private static volatile Level threshold = Level.INFO;
	private static volatile UnaryOperator<String> mask = UnaryOperator.identity();

	private Log() {
	}

	/**
	 * @param newSink   {@code null} switches logging off
	 * @param threshold most detailed level written
	 */
	public static void configure(Sink newSink, Level newThreshold) {
		threshold = newThreshold == null ? Level.INFO : newThreshold;
		sink = newSink;
	}

	/**
	 * Masks every entry before it is written, e.g. with the masker that also
	 * prepares the model's input, so the file holds the same placeholders.
	 *
	 * @param masking {@code null} writes entries unmasked
	 */
	public static void mask(UnaryOperator<String> masking) {
		mask = masking == null ? UnaryOperator.identity() : masking;
	}

	public static boolean enabled(Level level) {
		return sink != null && level.ordinal() <= threshold.ordinal();
	}

	/** Whether content (prompts, code, tool results) is logged. */
	public static boolean detail() {
		return enabled(Level.DEBUG);
	}

	public static void error(String area, String message, Throwable t) {
		write(Level.ERROR, area, t == null ? message : message + "\n" + stackTrace(t));
	}

	public static void warn(String area, String message) {
		write(Level.WARN, area, message);
	}

	public static void info(String area, String message) {
		write(Level.INFO, area, message);
	}

	/** Content is only built when the detail level is on. */
	public static void debug(String area, Supplier<String> message) {
		if (enabled(Level.DEBUG)) {
			write(Level.DEBUG, area, message.get());
		}
	}

	private static void write(Level level, String area, String message) {
		Sink s = sink;
		if (s == null || level.ordinal() > threshold.ordinal()) {
			return;
		}
		try {
			s.write(level, area, redact(mask.apply(message)));
		} catch (RuntimeException e) {
			// logging must never break Bella
		}
	}

	private static final List<Pattern> SECRETS = List.of(
			// Authorization headers and similar JSON or header fields
			Pattern.compile("(?i)(\"?(?<![A-Za-z0-9_])(?:authorization|x-api-key|api[-_]?key|apikey|access[-_]?token|token|password|passwd|secret)\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+(?: [^\"\\s,}&]+)?)"),
			Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"),
			Pattern.compile("()(?<![A-Za-z0-9])sk-ant-[A-Za-z0-9_-]+"),
			Pattern.compile("()(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}"),
			Pattern.compile("()(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,}"),
			Pattern.compile("()(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{20,}"),
			// "sap-client=…&sap-password=…" style query parameters
			Pattern.compile("(?i)([?&](?:sap-)?password=)[^&\\s]+"));

	/** Replaces keys, tokens and passwords with {@code ***}. */
	public static String redact(String s) {
		if (s == null) {
			return "";
		}
		String out = s;
		for (Pattern p : SECRETS) {
			Matcher m = p.matcher(out);
			StringBuilder sb = new StringBuilder();
			while (m.find()) {
				m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "***"));
			}
			m.appendTail(sb);
			out = sb.toString();
		}
		return out;
	}

	/** At most {@code max} characters, with a note how much was left out. */
	public static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, max) + " … [" + (s.length() - max) + " more characters]";
	}

	/** {@link #clip(String, int)} with {@link #MAX_CONTENT}. */
	public static String clip(String s) {
		return clip(s, MAX_CONTENT);
	}

	/** Milliseconds since a {@link System#nanoTime()} value. */
	public static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	private static String stackTrace(Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		return w.toString().stripTrailing();
	}
}

package de.kiliantaubmann.bella.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.lint.AbapLint.Finding;
import de.kiliantaubmann.bella.core.lint.AbapLint.Severity;
import de.kiliantaubmann.bella.core.lint.AbapLint.Target;

/**
 * A few checks for CDS data definitions, after abaplint's CDS rules that
 * ARC-1 uses: obsolete DDIC-based views and association names without the
 * leading underscore. The syntax itself is left to SAP's syntax check.
 */
final class CdsLint {

	private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern START = Pattern.compile(
			"(?is)^\\s*(?:@|define\\s+(?:root\\s+)?(?:view|table\\s+function|abstract|custom|hierarchy|transient)\\b"
					+ "|extend\\s+view\\b)");
	private static final Pattern LEGACY_VIEW = Pattern.compile("(?i)\\bdefine\\s+(?:root\\s+)?view\\s++(?!entity\\b)");
	private static final Pattern ASSOCIATION = Pattern.compile(
			"(?i)\\b(association|composition)\\b(?:\\s*\\[[^\\]]*\\])?\\s+(?:to|of)\\s+(?:parent\\s+)?[\\w/]+\\s+as\\s+([\\w/]+)");

	private CdsLint() {
	}

	/** Whether the text is a CDS data definition rather than ABAP code. */
	static boolean isCds(String source) {
		return START.matcher(blankComments(source)).find();
	}

	static List<Finding> check(String source, Target target) {
		List<Finding> out = new ArrayList<>();
		String code = blankComments(source);
		int[] lineStarts = AbapLint.lineStarts(source);
		Matcher legacy = LEGACY_VIEW.matcher(code);
		if (legacy.find()) {
			int line = AbapLint.lineOf(lineStarts, legacy.start());
			if (target.cloud()) {
				out.add(new Finding(line, "cds_legacy_view", Severity.ERROR,
						"ABAP Cloud has no DDIC-based CDS views; use DEFINE VIEW ENTITY (without @AbapCatalog.sqlViewName)."));
			} else if (!target.below(755)) {
				out.add(new Finding(line, "cds_legacy_view", Severity.WARNING, "DDIC-based CDS views (DEFINE VIEW with "
						+ "@AbapCatalog.sqlViewName) are obsolete since 7.55; use DEFINE VIEW ENTITY."));
			}
		}
		Matcher a = ASSOCIATION.matcher(code);
		while (a.find()) {
			if (!a.group(2).startsWith("_")) {
				out.add(new Finding(AbapLint.lineOf(lineStarts, a.start()), "cds_association_name", Severity.INFO,
						"Name the " + a.group(1).toLowerCase(Locale.ROOT) + " _" + a.group(2)
								+ " (with a leading underscore), as SAP's VDM guidelines and abaplint expect."));
			}
		}
		return out;
	}

	/** Comments replaced by blanks of the same length, so offsets and line numbers stay right. */
	private static String blankComments(String source) {
		Matcher m = COMMENT.matcher(source);
		StringBuilder sb = new StringBuilder(source.length());
		int last = 0;
		while (m.find()) {
			sb.append(source, last, m.start());
			for (int i = m.start(); i < m.end(); i++) {
				sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
			}
			last = m.end();
		}
		return sb.append(source.substring(last)).toString();
	}
}

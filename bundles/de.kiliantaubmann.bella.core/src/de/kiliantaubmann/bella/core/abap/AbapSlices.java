package de.kiliantaubmann.bella.core.abap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Cuts the part of a source the model asked for, so a tool result carries one
 * method or the matching lines instead of a whole class.
 */
public final class AbapSlices {

	/** A piece of a source and the line it starts at (1-based). */
	public record Slice(String text, int firstLine) {
	}

	private AbapSlices() {
	}

	/**
	 * The {@code METHOD … ENDMETHOD.} block of a method. The name matches
	 * exactly ({@code zif_order~save}) or, if unique, by the part after
	 * {@code ~} ({@code save}).
	 */
	public static Optional<AbapStructureScanner.Block> methodBlock(String src, String methodName) {
		String wanted = methodName.trim().toUpperCase(Locale.ROOT);
		List<AbapStructureScanner.Block> methods = AbapStructureScanner.blocks(src).stream()
				.filter(b -> b.kind() == AbapStructureScanner.Kind.METHOD).toList();
		for (AbapStructureScanner.Block b : methods) {
			if (b.name().equals(wanted)) {
				return Optional.of(b);
			}
		}
		List<AbapStructureScanner.Block> bySuffix = methods.stream()
				.filter(b -> b.name().endsWith("~" + wanted)).toList();
		return bySuffix.size() == 1 ? Optional.of(bySuffix.get(0)) : Optional.empty();
	}

	/** Names of all implemented methods, in source order. */
	public static List<String> methodNames(String src) {
		return AbapStructureScanner.blocks(src).stream().filter(b -> b.kind() == AbapStructureScanner.Kind.METHOD)
				.map(AbapStructureScanner.Block::name).toList();
	}

	/** Declaration (if found in {@code className}'s definition) plus the implementation of a method. */
	public static Optional<Slice> method(String src, String className, String methodName) {
		Optional<AbapStructureScanner.Block> block = methodBlock(src, methodName);
		if (block.isEmpty()) {
			return Optional.empty();
		}
		AbapStructureScanner.Block b = block.get();
		int lineStart = AbapEdit.lineStart(src, b.start());
		String impl = src.substring(lineStart, b.end());
		String declaration = className == null ? null
				: AbapStructureScanner.methodDeclaration(src, className, b.name()).orElse(null);
		String text = declaration == null ? impl : "Declaration:\n" + declaration + "\n\nImplementation:\n" + impl;
		return Optional.of(new Slice(text, lineOf(src, lineStart)));
	}

	static int lineOf(String src, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < src.length(); i++) {
			if (src.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	/**
	 * Lines matching {@code regex} (case-insensitive; taken literally if it is
	 * no valid regex), each with {@code context} lines around it and its line
	 * number. Gaps are marked with {@code …}.
	 */
	public static String grep(String src, String regex, int context, int maxLines) {
		Pattern p;
		try {
			p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException e) {
			p = Pattern.compile(Pattern.quote(regex), Pattern.CASE_INSENSITIVE);
		}
		String[] lines = src.replace("\r\n", "\n").split("\n", -1);
		boolean[] keep = new boolean[lines.length];
		int matches = 0;
		for (int i = 0; i < lines.length; i++) {
			if (p.matcher(lines[i]).find()) {
				matches++;
				for (int j = Math.max(0, i - context); j <= Math.min(lines.length - 1, i + context); j++) {
					keep[j] = true;
				}
			}
		}
		if (matches == 0) {
			return "No line matches '" + regex + "'.";
		}
		List<String> out = new ArrayList<>();
		int last = -2;
		int written = 0;
		for (int i = 0; i < lines.length && written < maxLines; i++) {
			if (!keep[i]) {
				continue;
			}
			if (last >= 0 && i > last + 1) {
				out.add("…");
			}
			out.add((i + 1) + ": " + lines[i]);
			last = i;
			written++;
		}
		String result = matches + (matches == 1 ? " matching line" : " matching lines") + ":\n" + String.join("\n", out);
		return written >= maxLines ? result + "\n… (cut after " + maxLines + " lines, narrow the pattern)" : result;
	}
}

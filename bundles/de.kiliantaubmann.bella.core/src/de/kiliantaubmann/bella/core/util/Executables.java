package de.kiliantaubmann.bella.core.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Finding and starting command-line tools such as {@code claude} or {@code copilot}. */
public final class Executables {

	private Executables() {
	}

	public static boolean isWindows(String osName) {
		return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/**
	 * The configured path if set (only if it exists), otherwise the first of
	 * {@code names} found on {@code PATH}, otherwise the first existing
	 * {@code extraCandidates} (install locations that are often missing from
	 * {@code PATH} when Eclipse is started from the macOS Dock or a Windows
	 * shortcut).
	 */
	public static Optional<Path> find(String configured, Map<String, String> env, String osName,
			Predicate<Path> exists, List<String> names, List<Path> extraCandidates) {
		if (configured != null && !configured.isBlank()) {
			Path p = Path.of(configured.trim());
			return exists.test(p) ? Optional.of(p) : Optional.empty();
		}
		List<Path> candidates = new ArrayList<>();
		String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
		for (String dir : path.split(isWindows(osName) ? ";" : ":")) {
			if (dir.isBlank()) {
				continue;
			}
			for (String n : names) {
				candidates.add(Path.of(stripQuotes(dir.trim())).resolve(n));
			}
		}
		candidates.addAll(extraCandidates);
		return candidates.stream().filter(exists).findFirst();
	}

	private static String stripQuotes(String s) {
		return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

	/** Full command line for the executable plus {@code args}; {@code .cmd} shims run through {@code cmd.exe}. */
	public static List<String> command(Path executable, List<String> args) {
		List<String> cmd = new ArrayList<>();
		String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".cmd") || name.endsWith(".bat")) {
			cmd.add("cmd.exe");
			cmd.add("/c");
		}
		cmd.add(executable.toString());
		cmd.addAll(args);
		return cmd;
	}
}

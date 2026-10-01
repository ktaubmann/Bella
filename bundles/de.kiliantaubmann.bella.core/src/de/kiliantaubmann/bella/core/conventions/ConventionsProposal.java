package de.kiliantaubmann.bella.core.conventions;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Material for deriving a package's conventions (its objects and a few
 * sources) and the parsing of the model's answer.
 *
 * @param text   project information (Markdown)
 * @param naming naming rules in the text format of {@link NamingRules}
 */
public record ConventionsProposal(String text, String naming) {

	/** Objects of a package read at most. */
	public static final int MAX_OBJECTS = 200;

	/** Sources sent as samples, and characters per sample. */
	public static final int MAX_SAMPLES = 4;
	public static final int SAMPLE_CHARS = 4_000;

	private static final List<String> SAMPLE_TYPES = List.of("CLAS", "PROG", "INTF", "FUGR", "DDLS");
	private static final Pattern BLOCK = Pattern.compile("```(markdown|md|naming)\\s*\\n(.*?)```", Pattern.DOTALL);

	/** One line per object: {@code TYPE NAME – description}. */
	public static String objectList(List<AdtObjectRef> objects) {
		StringBuilder sb = new StringBuilder();
		for (AdtObjectRef o : objects) {
			sb.append(o.type()).append(' ').append(o.name());
			if (!o.description().isBlank()) {
				sb.append(" – ").append(o.description());
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	/** A few sources of different types, each headed by its name and cut to {@link #SAMPLE_CHARS}. */
	public static String samples(AdtClient client, List<AdtObjectRef> objects, CancelToken cancel) {
		StringBuilder sb = new StringBuilder();
		int taken = 0;
		for (String type : SAMPLE_TYPES) {
			for (AdtObjectRef o : objects) {
				if (taken >= MAX_SAMPLES) {
					return sb.toString();
				}
				String t = o.type().toUpperCase(Locale.ROOT);
				// of function groups only the modules have a source
				boolean fits = type.equals("FUGR") ? t.startsWith("FUGR/FF") : t.startsWith(type);
				if (!fits) {
					continue;
				}
				try {
					String src = client.readDefinition(o, cancel);
					sb.append("### ").append(o.type()).append(' ').append(o.name()).append("\n```abap\n")
							.append(src.length() > SAMPLE_CHARS ? src.substring(0, SAMPLE_CHARS) + "\n…" : src)
							.append("\n```\n");
					taken++;
					break; // one sample per type, then the next type
				} catch (IOException e) {
					// try the next object
				}
			}
		}
		return sb.toString();
	}

	/** Reads the ```markdown and ```naming blocks of the model's answer (missing ones stay empty). */
	public static ConventionsProposal parse(String answer) {
		String text = "";
		String naming = "";
		Matcher m = BLOCK.matcher(answer == null ? "" : answer);
		while (m.find()) {
			if (m.group(1).equals("naming")) {
				naming = m.group(2).strip();
			} else {
				text = m.group(2).strip();
			}
		}
		return new ConventionsProposal(text, naming);
	}
}

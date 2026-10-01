package de.kiliantaubmann.bella.core.conventions;

/**
 * What the model should know about a project: free text (package structure,
 * architecture, do's and don'ts) and naming rules that Bella also checks.
 */
public record ProjectConventions(String text, NamingRules naming) {

	public static final ProjectConventions NONE = new ProjectConventions("", NamingRules.NONE);

	/** Characters of the free text that go into a prompt at most. */
	public static final int MAX_PROMPT_CHARS = 8_000;

	public ProjectConventions {
		text = text == null ? "" : text.strip();
		naming = naming == null ? NamingRules.NONE : naming;
	}

	public static ProjectConventions of(String text, String naming) {
		return new ProjectConventions(text, NamingRules.parse(naming).rules());
	}

	public boolean isEmpty() {
		return text.isEmpty() && naming.isEmpty();
	}

	/** The general conventions with a system's own: texts are joined, the system's rules win per kind. */
	public static ProjectConventions merge(ProjectConventions general, ProjectConventions system) {
		String text = general.text().isEmpty() ? system.text()
				: system.text().isEmpty() ? general.text() : general.text() + "\n\n" + system.text();
		return new ProjectConventions(text, general.naming().merge(system.naming()));
	}

	/** Section for a system prompt; empty if there are no conventions. */
	public String promptSection() {
		if (isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("\nProject conventions (follow them for every name, package and structure; "
				+ "choose names of new objects accordingly before creating them):\n");
		if (!text.isEmpty()) {
			sb.append(text.length() > MAX_PROMPT_CHARS ? text.substring(0, MAX_PROMPT_CHARS) + "\n…" : text).append('\n');
		}
		if (!naming.isEmpty()) {
			sb.append("Naming rules (kind = allowed patterns, * any characters):\n").append(naming.describe());
		}
		return sb.toString();
	}
}

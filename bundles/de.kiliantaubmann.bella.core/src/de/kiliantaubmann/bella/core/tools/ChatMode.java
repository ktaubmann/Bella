package de.kiliantaubmann.bella.core.tools;

/**
 * How freely the chat may act, chosen per chat with the tick boxes below the
 * input field.
 */
public enum ChatMode {

	/** Read and analyse only, then propose a plan; every write is refused. */
	PLAN,

	/** Reading runs, writing asks first (the tool policy as configured). */
	NORMAL,

	/**
	 * Writes, creates and activates without asking, and runs the ABAP Unit
	 * tests after each activation. Refusals stay: releasing transports, DENY
	 * rules and the allowed packages.
	 */
	GOD;

	/**
	 * Instruction for the model that goes with every message in this mode, or
	 * an empty string. Kept out of the system prompt so switching the mode
	 * does not start the chat over.
	 */
	public String instruction() {
		return switch (this) {
		case PLAN -> """
				<chat_mode>Plan mode. Do not change anything: no writing, creating or activating, also not in the \
				editor (Bella refuses these tools in this mode). Read what you need with the read tools, then answer \
				with a concrete, numbered plan: which objects you would create or change, what changes in each \
				(short code sketches where useful), in which order, how you would activate and test it, and the \
				risks or open questions. End by saying that the developer can switch off plan mode or tick Godmode \
				to carry the plan out.</chat_mode>""";
		case GOD -> """
				<chat_mode>Godmode. The developer allowed writing, creating and activating without confirmation. \
				Carry the task out completely instead of only proposing code: read what you need, create or change \
				the objects (adt_create_object, adt_write_source), check syntax, activate (adt_activate), then run \
				the ABAP Unit tests (adt_activate runs them for you; otherwise adt_run_unit_tests) and fix errors and \
				failing tests until activation and tests pass, at most three attempts. Read table contents with \
				adt_table_contents when data helps. Open objects are still written into the editor only; tell the \
				developer to save and activate them. Never release transports. Finish with a short report: objects \
				created or changed, activation result, test result.</chat_mode>""";
		case NORMAL -> "";
		};
	}

	/** {@code prompt} with this mode's instruction appended. */
	public String apply(String prompt) {
		String note = instruction();
		return note.isEmpty() ? prompt : prompt + "\n\n" + note;
	}
}

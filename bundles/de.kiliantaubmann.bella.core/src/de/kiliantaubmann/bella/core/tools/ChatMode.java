package de.kiliantaubmann.bella.core.tools;

/**
 * How freely the chat may act, chosen per chat in the drop-down below the
 * input field. The modes are ordered from careful to free. Refusals always
 * stay: releasing transports, DENY rules and the allowed packages.
 */
public enum ChatMode {

	/** Read and analyse only, then propose a plan; every write is refused. */
	PLAN,

	/**
	 * Changes go only into the open editor as a proposal (diff preview); nothing
	 * is saved, created or activated in the SAP system.
	 */
	SUGGEST,

	/** Reading runs, writing and reading table contents ask first (the tool policy as configured). */
	NORMAL,

	/** Like {@link #NORMAL}, but table contents are read without asking. */
	READ_DATA,

	/** Writing, creating and activating run without asking; table contents still ask. */
	ACTIVATE,

	/**
	 * Everything runs without asking, and the ABAP Unit tests run after each
	 * activation. The model carries a task out completely.
	 */
	AUTO;

	/** Whether this mode writes to the SAP system without asking, so switching to it needs a confirmation. */
	public boolean writesWithoutAsking() {
		return this == ACTIVATE || this == AUTO;
	}

	/** Whether table contents are read without asking. */
	public boolean readsDataWithoutAsking() {
		return this == READ_DATA || this == AUTO;
	}

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
				risks or open questions. End by saying that the developer can choose another mode below the chat \
				to carry the plan out.</chat_mode>""";
		case SUGGEST -> """
				<chat_mode>Suggest mode. Nothing may be saved, created or activated in the SAP system (Bella refuses \
				it). Propose changes instead: for an object open in the editor use adt_write_source, which shows the \
				developer a diff preview and leaves the editor unsaved; otherwise give the code in ```abap blocks. \
				Read what you need first.</chat_mode>""";
		case NORMAL, READ_DATA -> "";
		case ACTIVATE -> """
				<chat_mode>The developer allowed writing, creating and activating without confirmation. Change the \
				objects (adt_create_object, adt_write_source), check syntax and activate them (adt_activate); fix \
				errors until activation passes. Open objects are still written into the editor only; tell the \
				developer to save and activate them. Never release transports.</chat_mode>""";
		case AUTO -> """
				<chat_mode>Automode. The developer allowed everything without confirmation. Carry the task out \
				completely instead of only proposing code: read what you need, create or change the objects \
				(adt_create_object, adt_write_source), check syntax, activate (adt_activate), then run the ABAP Unit \
				tests (adt_activate runs them for you; otherwise adt_run_unit_tests) and fix errors and failing \
				tests until activation and tests pass, at most three attempts. Read table contents with \
				adt_table_contents when data helps. Open objects are still written into the editor only; tell the \
				developer to save and activate them. Never release transports. Finish with a short report: objects \
				created or changed, activation result, test result.</chat_mode>""";
		};
	}

	/** {@code prompt} with this mode's instruction appended. */
	public String apply(String prompt) {
		String note = instruction();
		return note.isEmpty() ? prompt : prompt + "\n\n" + note;
	}
}

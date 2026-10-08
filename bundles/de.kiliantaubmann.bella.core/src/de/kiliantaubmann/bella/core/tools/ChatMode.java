package de.kiliantaubmann.bella.core.tools;

/**
 * How freely the chat may act, chosen per chat in the drop-down below the
 * input field. The modes are ordered from careful to free. Refusals always
 * stay: releasing transports, DENY rules and the allowed packages.
 */
public enum ChatMode {

	/**
	 * Read and analyse only, then propose a plan; every write is refused. Used
	 * for the turns of the Planning Mode button, not offered in the drop-down.
	 */
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
				risks or open questions. Do not ask how to continue: the developer accepts, changes or cancels the \
				plan with buttons below the chat.</chat_mode>""";
		case SUGGEST -> """
				<chat_mode>Suggest mode. Nothing may be saved, created or activated in the SAP system (Bella refuses \
				it). Propose changes instead: for an object open in the editor use adt_write_source, which shows the \
				developer a diff preview and leaves the editor unsaved; otherwise give the code in ```abap blocks. \
				Read what you need first.</chat_mode>""";
		case NORMAL, READ_DATA -> "";
		case ACTIVATE -> """
				<chat_mode>The developer allowed writing, creating and activating without confirmation. Change the \
				objects (adt_create_object, adt_write_source, adt_write_text_elements for text symbols) and fix \
				the style and syntax findings their results report. Activate (adt_activate) and fix errors \
				until activation passes. Once it passes, check the result with ATC once (the last \
				adt_activate with run_atc, or adt_atc_check); fix ATC priority 1 and 2 findings and activate \
				again, at most three rounds. Open objects are still written into the editor only; tell the \
				developer to save and activate them. Never release transports.</chat_mode>""";
		case AUTO -> """
				<chat_mode>Automode. The developer allowed everything without confirmation. Carry the task out \
				completely instead of only proposing code: read what you need, create or change the objects \
				(adt_create_object, adt_write_source, adt_write_text_elements for text symbols) and fix the style \
				and syntax findings their results report. Activate (adt_activate; it runs the ABAP Unit tests \
				for you, otherwise adt_run_unit_tests) and fix errors and failing tests. Once \
				activation and tests pass, check the result with ATC once (adt_activate with run_atc, or \
				adt_atc_check); fix ATC priority 1 and 2 findings and activate again, at most three rounds. Read \
				table contents with adt_table_contents when data helps. Open objects are still written into the \
				editor only; tell the developer to save and activate them. Never release transports. Finish with a short report: objects created or changed, activation result, ATC \
				result (with any finding left and why), test result.</chat_mode>""";
		};
	}

	/** {@code prompt} with this mode's instruction appended. */
	public String apply(String prompt) {
		String note = instruction();
		return note.isEmpty() ? prompt : prompt + "\n\n" + note;
	}
}

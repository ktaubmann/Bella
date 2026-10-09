package de.kiliantaubmann.bella.core.debug;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The debugger the developer runs in Eclipse; implemented in the UI bundle on
 * top of Eclipse's debug framework, which the ABAP debugger of ADT implements.
 */
public interface DebugBackend {

	/** How to go on from a stopped session. */
	enum Step {
		INTO, OVER, RETURN, RESUME
	}

	/** A line breakpoint in Eclipse. */
	record Breakpoint(String object, int line, boolean enabled) {
	}

	/** The selected stopped session, empty when none is stopped. */
	Optional<DebugSnapshot> snapshot() throws Exception;

	/**
	 * One variable of the current frame with its children loaded.
	 *
	 * @param path names as in the Variables view, separated by {@code /}, e.g. {@code LS_ORDER/ITEMS/[1]}
	 * @return empty when no session is stopped or the path names no variable
	 */
	Optional<DebugSnapshot.Variable> variable(String path) throws Exception;

	List<Breakpoint> breakpoints() throws Exception;

	/**
	 * Sets or removes a line breakpoint, as the developer would in the editor;
	 * an object that is not open is opened in an editor first.
	 *
	 * @param type object type (PROG, CLAS, FUGR …), may be empty
	 * @throws IllegalStateException with a message for the model when it cannot be done (e.g. the object
	 *                               cannot be opened)
	 */
	void setBreakpoint(String object, String type, int line, boolean on) throws Exception;

	/**
	 * Steps or resumes and waits until the session stops again.
	 *
	 * @return the new state, empty when the program still runs after {@code timeout} or has ended
	 */
	Optional<DebugSnapshot> step(Step step, Duration timeout) throws Exception;
}

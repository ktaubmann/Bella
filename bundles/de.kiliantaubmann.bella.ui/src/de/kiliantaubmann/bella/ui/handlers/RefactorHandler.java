package de.kiliantaubmann.bella.ui.handlers;

/** Suggests a refactoring of the selection in the chat. */
public class RefactorHandler extends ExplainHandler {
	@Override
	protected Kind kind() {
		return Kind.REFACTOR;
	}
}

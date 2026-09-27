package de.kiliantaubmann.bella.ui.handlers;

/** Proposes an ABAP Unit test class for the selection in the chat. */
public class UnitTestHandler extends ExplainHandler {
	@Override
	protected Kind kind() {
		return Kind.UNIT_TEST;
	}
}

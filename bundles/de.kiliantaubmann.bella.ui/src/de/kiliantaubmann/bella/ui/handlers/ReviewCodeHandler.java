package de.kiliantaubmann.bella.ui.handlers;

/** Reviews the selection (or the method at the cursor) in the chat. */
public class ReviewCodeHandler extends ExplainHandler {
	@Override
	protected Kind kind() {
		return Kind.REVIEW;
	}
}

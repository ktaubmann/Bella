package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;

/** An ADT request failed with an HTTP status. */
public class AdtException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int status;

	public AdtException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}

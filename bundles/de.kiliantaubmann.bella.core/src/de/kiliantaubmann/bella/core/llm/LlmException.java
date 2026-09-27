package de.kiliantaubmann.bella.core.llm;

/** A request failed; {@link #status()} is the HTTP status or 0 for I/O errors. */
public class LlmException extends Exception {

	private static final long serialVersionUID = 1L;

	private final int status;

	public LlmException(int status, String message) {
		super(message);
		this.status = status;
	}

	public LlmException(String message, Throwable cause) {
		super(message, cause);
		this.status = 0;
	}

	public int status() {
		return status;
	}

	public boolean retryable() {
		return status == 0 || status == 408 || status == 409 || status == 429 || status >= 500;
	}
}

package de.kiliantaubmann.bella.core.adt;

/** Response of an ADT REST request. Error statuses are returned, not thrown. */
public record AdtResponse(int status, String contentType, String body) {

	public boolean ok() {
		return status >= 200 && status < 300;
	}
}

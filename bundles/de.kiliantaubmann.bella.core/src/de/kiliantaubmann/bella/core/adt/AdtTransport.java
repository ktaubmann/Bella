package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;

import de.kiliantaubmann.bella.core.util.CancelToken;

/**
 * Sends ADT REST requests through the ADT communication layer of an ABAP
 * project, i.e. with the developer's logon. Implemented in the
 * {@code de.kiliantaubmann.bella.adt} bundle, which is the only part of Bella
 * that depends on SAP's ADT SDK.
 */
public interface AdtTransport {

	AdtResponse send(AdtRequest request, CancelToken cancel) throws IOException;

	/** Whether requests share one ABAP session (needed for lock, write, unlock). */
	default boolean isStateful() {
		return false;
	}

	/** A stateful session keeps the ABAP session (and its enqueue locks) across requests. */
	interface Session extends AdtTransport, AutoCloseable {
		@Override
		default boolean isStateful() {
			return true;
		}

		@Override
		void close();
	}
}

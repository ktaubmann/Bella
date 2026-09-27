package de.kiliantaubmann.bella.core.adt;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Access to ADT, registered as an OSGi service by the
 * {@code de.kiliantaubmann.bella.adt} bundle when ADT is installed.
 */
public interface AdtBackend {

	List<AdtSystem> systems();

	AdtTransport stateless(String destinationId);

	AdtTransport.Session stateful(String destinationId) throws IOException;

	/** The repository object behind an editor input, if it is an ADT editor. */
	Optional<AdtEditorObject> editorObject(Object editorInput);
}

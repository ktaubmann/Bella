package de.kiliantaubmann.bella.core.adt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import de.kiliantaubmann.bella.core.util.CancelToken;

/** Fake ADT backend: answers requests with a router function and records them. */
final class FakeAdt implements AdtBackend {

	final List<String> log = new ArrayList<>();
	final Map<String, Function<AdtRequest, AdtResponse>> routes = new LinkedHashMap<>();
	final List<AdtSystem> systems = new ArrayList<>();
	int openSessions;

	FakeAdt route(String pathPrefix, Function<AdtRequest, AdtResponse> handler) {
		routes.put(pathPrefix, handler);
		return this;
	}

	private AdtResponse handle(AdtRequest r, boolean stateful) {
		log.add((stateful ? "S " : "") + r.method() + " " + r.path());
		for (Map.Entry<String, Function<AdtRequest, AdtResponse>> e : routes.entrySet()) {
			if ((r.method() + " " + r.path()).startsWith(e.getKey())) {
				return e.getValue().apply(r);
			}
		}
		return new AdtResponse(404, "text/plain", "no route");
	}

	@Override
	public List<AdtSystem> systems() {
		return systems;
	}

	@Override
	public AdtTransport stateless(String destinationId) {
		return (r, c) -> handle(r, false);
	}

	@Override
	public AdtTransport.Session stateful(String destinationId) {
		openSessions++;
		return new AdtTransport.Session() {
			@Override
			public AdtResponse send(AdtRequest r, CancelToken c) {
				return handle(r, true);
			}

			@Override
			public void close() {
				openSessions--;
			}
		};
	}

	@Override
	public Optional<AdtEditorObject> editorObject(Object editorInput) {
		return Optional.empty();
	}

	static AdtResponse ok(String body) {
		return new AdtResponse(200, "application/xml", body);
	}
}

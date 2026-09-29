package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The overload shapes of an SDK resource, as they differ between releases. */
class ReflectionTest {

	interface Monitor {
	}

	interface Headers {
	}

	interface Body {
	}

	interface Param {
	}

	static final class Response {
	}

	/** post with four parameters, put only with trailing varargs, get without headers. */
	interface Resource {
		<T> T post(Monitor m, Headers h, Class<T> type, Object body);

		<T> T put(Monitor m, Headers h, Class<T> type, Object body, Param... params);

		<T> T put(Monitor m, Class<T> type, Object body);

		<T> T get(Monitor m, Class<T> type, Param... params);

		void delete(Monitor m, int flags);
	}

	private final Monitor monitor = new Monitor() {
	};
	private final Headers headers = new Headers() {
	};
	private final Body body = new Body() {
	};

	@Test
	void picksTheOverloadThatUsesMostValues() {
		Reflection.Call put = Reflection.bestMatch(Resource.class, "put", monitor, headers, Response.class, body)
				.orElseThrow();
		assertEquals(5, put.method().getParameterCount());
		assertSame(monitor, put.args()[0]);
		assertSame(headers, put.args()[1]);
		assertSame(Response.class, put.args()[2]);
		assertSame(body, put.args()[3]);
		assertArrayEquals(new Param[0], (Param[]) put.args()[4]);

		Reflection.Call post = Reflection.bestMatch(Resource.class, "post", monitor, headers, Response.class, body)
				.orElseThrow();
		assertEquals(4, post.method().getParameterCount());
	}

	@Test
	void nullFillsTheMissingBody() {
		Reflection.Call post = Reflection.bestMatch(Resource.class, "post", monitor, headers, Response.class, null)
				.orElseThrow();
		assertEquals(4, post.method().getParameterCount());
		assertNull(post.args()[3]);
	}

	@Test
	void unmatchedOverloadsAreReported() {
		Reflection.Call get = Reflection.bestMatch(Resource.class, "get", monitor, headers, Response.class)
				.orElseThrow();
		assertEquals(3, get.method().getParameterCount());
		assertEquals(Optional.empty(), Reflection.bestMatch(Resource.class, "delete", monitor, headers));
		assertEquals(Optional.empty(), Reflection.bestMatch(Resource.class, "patch", monitor));
		List<String> sigs = Reflection.signatures(Resource.class, "put");
		assertEquals(List.of("put(Monitor, Class, Object)", "put(Monitor, Headers, Class, Object, Param[])"), sigs);
		assertTrue(Reflection.signatures(Resource.class, "patch").isEmpty());
	}

	@Test
	void invokesTheChosenMethod() throws Exception {
		Resource r = new Resource() {
			@Override
			public <T> T post(Monitor m, Headers h, Class<T> type, Object b) {
				return null;
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> T put(Monitor m, Headers h, Class<T> type, Object b, Param... params) {
				return (T) ("put:" + params.length);
			}

			@Override
			public <T> T put(Monitor m, Class<T> type, Object b) {
				return null;
			}

			@Override
			public <T> T get(Monitor m, Class<T> type, Param... params) {
				return null;
			}

			@Override
			public void delete(Monitor m, int flags) {
			}
		};
		Object result = Reflection.bestMatch(Resource.class, "put", monitor, headers, Response.class, body)
				.orElseThrow().invoke(r);
		assertEquals("put:0", result);
	}

	/** The overloads of ADT 3.60's IRestResource (all with trailing query parameters). */
	interface Adt360Resource {
		<T> T get(Monitor m, Class<T> type, Param... p);

		<T> T get(Monitor m, Headers h, Class<T> type, Param... p);

		<T> T post(Monitor m, Class<T> type, Object body, Param... p);

		<T> T post(Monitor m, Headers h, Class<T> type, Object body, Param... p);

		<T> T post(Monitor m, Class<T> type, Param... p);

		<T> T post(Monitor m, Headers h, Class<T> type, Param... p);

		<T> T put(Monitor m, Class<T> type, Object body, Param... p);

		<T> T put(Monitor m, Headers h, Class<T> type, Object body, Param... p);
	}

	@Test
	void adt360OverloadsKeepHeadersAndBody() {
		for (String name : List.of("post", "put")) {
			Reflection.Call c = Reflection.bestMatch(Adt360Resource.class, name, monitor, headers, Response.class, body)
					.orElseThrow();
			assertEquals(5, c.method().getParameterCount(), name);
			assertSame(headers, c.args()[1], name);
			assertSame(body, c.args()[3], name);
			assertArrayEquals(new Param[0], (Param[]) c.args()[4], name);
		}
		// no body (e.g. LOCK): headers are kept, the body stays null
		Reflection.Call lock = Reflection.bestMatch(Adt360Resource.class, "post", monitor, headers, Response.class, null)
				.orElseThrow();
		assertSame(headers, lock.args()[1]);
		assertEquals(Response.class, lock.args()[2]);
		assertTrue(lock.args().length == 4 || lock.args()[3] == null);
	}
}

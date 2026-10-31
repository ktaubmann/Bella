package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.llm.ToolCall;
import de.kiliantaubmann.bella.core.util.CancelToken;

class ToolExecutorTest {

	/** Provider whose single write tool is refused when the input says so. */
	static final class Refusing implements ToolProvider {
		final List<String> calls = new ArrayList<>();

		@Override
		public String id() {
			return "adt";
		}

		@Override
		public String displayName() {
			return "fake";
		}

		@Override
		public List<ToolSpec> listTools() {
			return List.of(ToolSpec.of("adt_write_source", "", new JsonObject(), null, ToolSpec.Kind.WRITE));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			calls.add(remoteName);
			return ToolResult.ok("written");
		}

		@Override
		public Optional<String> refuse(String remoteName, JsonObject input, CancelToken cancel) {
			return input.has("foreign") ? Optional.of("not in an allowed package") : Optional.empty();
		}
	}

	@Test
	void providerRefusalComesBeforeConfirmation() {
		ToolRegistry registry = new ToolRegistry();
		Refusing provider = new Refusing();
		registry.addProvider(provider);
		registry.refresh(e -> {
		});
		List<String> confirmations = new ArrayList<>();
		ToolExecutor executor = new ToolExecutor(registry, () -> new ToolPolicy(List.of()), (tool, input) -> {
			confirmations.add(tool.name());
			return true;
		}, null);

		JsonObject foreign = new JsonObject();
		foreign.addProperty("foreign", true);
		ToolResult refused = executor.run(new ToolCall("1", "adt_write_source", foreign, "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE);
		assertTrue(refused.isError());
		assertEquals("not in an allowed package", refused.content());
		assertTrue(confirmations.isEmpty());
		assertTrue(provider.calls.isEmpty());

		ToolResult ok = executor.run(new ToolCall("2", "adt_write_source", new JsonObject(), "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE);
		assertEquals("written", ok.content());
		assertEquals(List.of("adt_write_source"), confirmations);
	}

	/** Records the input of every call. */
	static final class Recording implements ToolProvider {
		final List<JsonObject> inputs = new ArrayList<>();

		@Override
		public String id() {
			return ToolRegistry.ADT_PROVIDER_ID;
		}

		@Override
		public String displayName() {
			return "fake";
		}

		@Override
		public List<ToolSpec> listTools() {
			return List.of(ToolSpec.of("adt_activate", "", new JsonObject(), null, ToolSpec.Kind.WRITE),
					ToolSpec.of("adt_write_source", "", new JsonObject(), null, ToolSpec.Kind.WRITE));
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			inputs.add(input);
			return ToolResult.ok("done");
		}
	}

	private static ToolExecutor executor(Recording provider, ChatMode mode, List<String> confirmations) {
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(provider);
		registry.refresh(e -> {
		});
		return new ToolExecutor(registry, () -> ToolPolicy.defaults().withMode(mode), (tool, input) -> {
			confirmations.add(tool.name());
			return true;
		}, null);
	}

	@Test
	void autoModeActivatesWithoutAskingAndRunsTheTests() {
		Recording provider = new Recording();
		List<String> confirmations = new ArrayList<>();
		ToolResult r = executor(provider, ChatMode.AUTO, confirmations).run(
				new ToolCall("1", "adt_activate", new JsonObject(), "{}", null), ToolExecutor.Observer.NONE,
				CancelToken.NONE);
		assertEquals("done", r.content());
		assertTrue(confirmations.isEmpty());
		assertTrue(provider.inputs.get(0).get(ToolExecutor.AUTO_TEST).getAsBoolean());

		JsonObject optOut = new JsonObject();
		optOut.addProperty(ToolExecutor.AUTO_TEST, false);
		executor(provider, ChatMode.AUTO, confirmations).run(new ToolCall("2", "adt_activate", optOut, "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE);
		assertTrue(provider.inputs.get(1).get(ToolExecutor.AUTO_TEST).getAsBoolean(), "Godmode always tests");
	}

	@Test
	void normalModeAsksAndDoesNotAddTheTestRun() {
		Recording provider = new Recording();
		List<String> confirmations = new ArrayList<>();
		executor(provider, ChatMode.NORMAL, confirmations).run(
				new ToolCall("1", "adt_activate", new JsonObject(), "{}", null), ToolExecutor.Observer.NONE,
				CancelToken.NONE);
		assertEquals(List.of("adt_activate"), confirmations);
		assertFalse(provider.inputs.get(0).has(ToolExecutor.AUTO_TEST));
	}

	@Test
	void planModeRefusesWritesBeforeTheWriteGuard() {
		Recording provider = new Recording();
		List<String> confirmations = new ArrayList<>();
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(provider);
		registry.refresh(e -> {
		});
		List<String> guarded = new ArrayList<>();
		WriteGuard guard = (tool, input) -> {
			guarded.add(tool.name());
			return Optional.of(ToolResult.ok("into the editor"));
		};
		ToolResult r = new ToolExecutor(registry, () -> ToolPolicy.defaults().withMode(ChatMode.PLAN),
				(tool, input) -> confirmations.add(tool.name()), guard).run(
						new ToolCall("1", "adt_write_source", new JsonObject(), "{}", null),
						ToolExecutor.Observer.NONE, CancelToken.NONE);
		assertTrue(r.isError());
		assertTrue(r.content().startsWith("Refused in plan mode"), r.content());
		assertTrue(guarded.isEmpty());
		assertTrue(confirmations.isEmpty());
		assertTrue(provider.inputs.isEmpty());
	}

	@Test
	void suggestModeWritesOnlyIntoTheOpenEditor() {
		Recording provider = new Recording();
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(provider);
		registry.refresh(e -> {
		});
		List<String> confirmations = new ArrayList<>();
		WriteGuard editor = (tool, input) -> input.has("open")
				? Optional.of(ToolResult.ok("proposed in the editor"))
				: Optional.empty();
		ToolExecutor executor = new ToolExecutor(registry, () -> ToolPolicy.defaults().withMode(ChatMode.SUGGEST),
				(tool, input) -> confirmations.add(tool.name()), editor);
		JsonObject open = new JsonObject();
		open.addProperty("open", true);
		assertEquals("proposed in the editor", executor.run(new ToolCall("1", "adt_write_source", open, "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE).content());
		ToolResult saved = executor.run(new ToolCall("2", "adt_write_source", new JsonObject(), "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE);
		ToolResult activated = executor.run(new ToolCall("3", "adt_activate", new JsonObject(), "{}", null),
				ToolExecutor.Observer.NONE, CancelToken.NONE);
		assertTrue(saved.isError() && saved.content().startsWith("Refused in suggest mode"), saved.content());
		assertTrue(activated.isError(), activated.content());
		assertTrue(provider.inputs.isEmpty(), "nothing reached the SAP system");
		assertTrue(confirmations.isEmpty());
	}

	@Test
	void longResultsAreCut() {
		ToolResult small = ToolResult.ok("short");
		assertEquals(small, ToolExecutor.limit(small));
		String line = "x".repeat(99) + "\n";
		ToolResult big = ToolExecutor.limit(ToolResult.ok(line.repeat(ToolExecutor.MAX_RESULT_CHARS / 100 + 50)));
		assertTrue(big.content().length() < ToolExecutor.MAX_RESULT_CHARS + 400, "length " + big.content().length());
		assertTrue(big.content().contains("x\n\n[Bella cut this result after"), big.content().substring(
				ToolExecutor.MAX_RESULT_CHARS - 200));
		assertTrue(big.content().contains("never write it back as a complete source"));
		assertFalse(big.isError());
	}

	@Test
	void readOnlyCallsOfOneTurnRunSideBySideAndReportInOrder() {
		// both reads wait until the other one has started: they only finish when they run at the same time
		CountDownLatch bothStarted = new CountDownLatch(2);
		ToolProvider reads = new ToolProvider() {
			@Override
			public String id() {
				return "adt";
			}

			@Override
			public String displayName() {
				return "fake";
			}

			@Override
			public List<ToolSpec> listTools() {
				return List.of(ToolSpec.of("adt_read_source", "", new JsonObject(), null, ToolSpec.Kind.READ),
						ToolSpec.of("adt_write_source", "", new JsonObject(), null, ToolSpec.Kind.WRITE));
			}

			@Override
			public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
				if (remoteName.equals("adt_write_source")) {
					return ToolResult.ok("written");
				}
				bothStarted.countDown();
				try {
					return ToolResult.ok(bothStarted.await(5, TimeUnit.SECONDS) ? "read " + input.get("n") : "alone");
				} catch (InterruptedException e) {
					return ToolResult.error("interrupted");
				}
			}
		};
		ToolRegistry registry = new ToolRegistry();
		registry.addProvider(reads);
		registry.refresh(e -> {
		});
		ToolExecutor executor = new ToolExecutor(registry, () -> new ToolPolicy(List.of()), (tool, input) -> true, null);
		JsonObject first = new JsonObject();
		first.addProperty("n", 1);
		JsonObject second = new JsonObject();
		second.addProperty("n", 2);
		List<ToolCall> calls = List.of(new ToolCall("a", "adt_read_source", first, "{}", null),
				new ToolCall("w", "adt_write_source", new JsonObject(), "{}", null),
				new ToolCall("b", "adt_read_source", second, "{}", null));
		Map<String, Future<ToolResult>> started = executor.startUnattended(calls, CancelToken.NONE);
		assertEquals(List.of("a", "b"), List.copyOf(started.keySet()), "the write waits for its confirmation");
		List<String> events = new ArrayList<>();
		ToolExecutor.Observer observer = new ToolExecutor.Observer() {
			@Override
			public void onToolCall(ToolSpec tool, ToolCall call) {
				events.add("call " + call.id());
			}

			@Override
			public void onToolResult(ToolSpec tool, ToolCall call, ToolResult result) {
				events.add("result " + call.id() + " " + result.content());
			}
		};
		for (ToolCall call : calls) {
			if (started.containsKey(call.id())) {
				executor.deliver(call, started.get(call.id()), observer);
			} else {
				executor.run(call, observer, CancelToken.NONE);
			}
		}
		assertEquals(List.of("call a", "result a read 1", "call w", "result w written", "call b", "result b read 2"),
				events);
		// a single read runs as before
		assertTrue(executor.startUnattended(calls.subList(0, 2), CancelToken.NONE).isEmpty());
	}
}

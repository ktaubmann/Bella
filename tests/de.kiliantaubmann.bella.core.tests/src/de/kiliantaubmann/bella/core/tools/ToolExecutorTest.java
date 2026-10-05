package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
	void godModeActivatesWithoutAskingAndRunsTheTests() {
		Recording provider = new Recording();
		List<String> confirmations = new ArrayList<>();
		ToolResult r = executor(provider, ChatMode.GOD, confirmations).run(
				new ToolCall("1", "adt_activate", new JsonObject(), "{}", null), ToolExecutor.Observer.NONE,
				CancelToken.NONE);
		assertEquals("done", r.content());
		assertTrue(confirmations.isEmpty());
		assertTrue(provider.inputs.get(0).get(ToolExecutor.AUTO_TEST).getAsBoolean());
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
}

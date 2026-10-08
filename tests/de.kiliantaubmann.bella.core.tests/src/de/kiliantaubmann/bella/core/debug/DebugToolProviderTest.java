package de.kiliantaubmann.bella.core.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Variable;
import de.kiliantaubmann.bella.core.tools.ToolResult;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;

class DebugToolProviderTest {

	static final class FakeBackend implements DebugBackend {
		DebugSnapshot snapshot;
		DebugSnapshot afterStep;
		final List<Breakpoint> breakpoints = new ArrayList<>();
		final List<String> calls = new ArrayList<>();

		@Override
		public Optional<DebugSnapshot> snapshot() {
			return Optional.ofNullable(snapshot);
		}

		@Override
		public Optional<Variable> variable(String path) {
			calls.add("variable " + path);
			if (snapshot == null) {
				return Optional.empty();
			}
			return snapshot.variables().stream().filter(v -> v.name().equalsIgnoreCase(path)).findFirst();
		}

		@Override
		public List<Breakpoint> breakpoints() {
			return breakpoints;
		}

		@Override
		public void setBreakpoint(String object, String type, int line, boolean on) {
			calls.add((on ? "set " : "remove ") + object + " " + type + " " + line);
			if (on) {
				breakpoints.add(new Breakpoint(object, line, true));
			} else {
				breakpoints.removeIf(b -> b.object().equals(object) && b.line() == line);
			}
		}

		@Override
		public Optional<DebugSnapshot> step(Step step, Duration timeout) {
			calls.add("step " + step);
			return Optional.ofNullable(afterStep);
		}
	}

	private static DebugSnapshot stopped() {
		List<Variable> rows = new ArrayList<>();
		for (int i = 1; i <= 12; i++) {
			rows.add(Variable.of("[" + i + "]", "ZSD_ITEM", "item " + i));
		}
		return new DebugSnapshot("ZCL_ORDER", "CHECK_ITEMS", 42,
				List.of(new DebugSnapshot.Frame("CHECK_ITEMS", "ZCL_ORDER", 42),
						new DebugSnapshot.Frame("START-OF-SELECTION", "ZORDER_REPORT", 12)),
				List.of(Variable.of("LV_TOTAL", "P", "0"), new Variable("LT_ITEMS", "ZSD_ITEMS", "12 rows", rows, false),
						Variable.of("LV_TEXT", "STRING", "x".repeat(600))),
				new DebugSnapshot.Problem("CX_SY_ZERODIVIDE", "Division by zero"));
	}

	private static ToolResult call(DebugToolProvider p, String tool, String json) throws Exception {
		return p.call(tool, Json.parseObject(json), CancelToken.NONE);
	}

	@Test
	void contextShowsStateWithinLimits() throws Exception {
		FakeBackend b = new FakeBackend();
		b.snapshot = stopped();
		b.breakpoints.add(new DebugBackend.Breakpoint("ZCL_ORDER", 40, false));
		ToolResult r = call(new DebugToolProvider(b), "debug_context", "{}");
		assertFalse(r.isError(), r.content());
		String c = r.content();
		assertTrue(c.startsWith("Debugger stopped in ZCL_ORDER (CHECK_ITEMS) at line 42.\n"
				+ "Exception: CX_SY_ZERODIVIDE: Division by zero\n"), c);
		assertTrue(c.contains("- START-OF-SELECTION in ZORDER_REPORT, line 12"), c);
		assertTrue(c.contains("- LT_ITEMS (ZSD_ITEMS) = 12 rows\n  - [1] (ZSD_ITEM) = item 1"), c);
		assertTrue(c.contains("  - [10] (ZSD_ITEM) = item 10\n  - … 2 more"), c);
		assertFalse(c.contains("item 11"), c);
		assertTrue(c.contains("x".repeat(DebugSnapshot.MAX_VALUE) + " …"), c);
		assertFalse(c.contains("x".repeat(DebugSnapshot.MAX_VALUE + 1)), c);
		assertTrue(c.endsWith("Breakpoints:\n- ZCL_ORDER line 40 (disabled)\n"), c);
	}

	@Test
	void withoutSessionSaysSo() throws Exception {
		FakeBackend b = new FakeBackend();
		DebugToolProvider p = new DebugToolProvider(b);
		ToolResult r = call(p, "debug_context", "{}");
		assertFalse(r.isError(), r.content());
		assertTrue(r.content().startsWith(DebugToolProvider.NO_SESSION), r.content());
		assertTrue(r.content().endsWith("Breakpoints: none\n"), r.content());
		assertTrue(call(p, "debug_step", "{\"action\":\"over\"}").isError());
		assertTrue(call(p, "debug_context", "{\"variable\":\"LV_X\"}").content().startsWith(DebugToolProvider.NO_SESSION));
		assertFalse(b.calls.contains("step OVER"), b.calls.toString());
	}

	@Test
	void loadsOneVariable() throws Exception {
		FakeBackend b = new FakeBackend();
		b.snapshot = stopped();
		DebugToolProvider p = new DebugToolProvider(b);
		assertEquals("- LV_TOTAL (P) = 0\n", call(p, "debug_context", "{\"variable\":\" LV_TOTAL \"}").content());
		ToolResult missing = call(p, "debug_context", "{\"variable\":\"LV_NONE\"}");
		assertTrue(missing.isError() && missing.content().startsWith("No variable LV_NONE"), missing.content());
	}

	@Test
	void setsBreakpointsAndSteps() throws Exception {
		FakeBackend b = new FakeBackend();
		b.snapshot = stopped();
		DebugToolProvider p = new DebugToolProvider(b, Duration.ofSeconds(5));
		ToolResult set = call(p, "debug_breakpoint", "{\"action\":\"set\",\"name\":\"zcl_order\",\"type\":\"clas\",\"line\":30}");
		assertFalse(set.isError(), set.content());
		assertEquals(List.of("set ZCL_ORDER CLAS 30"), b.calls);
		assertTrue(set.content().startsWith("Set a breakpoint in ZCL_ORDER at line 30."), set.content());
		assertTrue(call(p, "debug_breakpoint", "{\"action\":\"set\",\"name\":\"ZCL_ORDER\"}").isError());
		assertTrue(call(p, "debug_breakpoint", "{\"action\":\"toggle\",\"name\":\"ZCL_ORDER\",\"line\":3}").isError());

		ToolResult running = call(p, "debug_step", "{\"action\":\"resume\"}");
		assertFalse(running.isError(), running.content());
		assertTrue(running.content().contains("has not stopped within 5 seconds"), running.content());
		b.afterStep = new DebugSnapshot("ZCL_ORDER", "", 43, List.of(), List.of(), null);
		assertTrue(call(p, "debug_step", "{\"action\":\"Over\"}").content()
				.startsWith("Debugger stopped in ZCL_ORDER at line 43."));
		assertTrue(call(p, "debug_step", "{\"action\":\"jump\"}").isError());
		assertEquals(List.of("set ZCL_ORDER CLAS 30", "step RESUME", "step OVER"), b.calls);
	}
}

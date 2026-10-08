package de.kiliantaubmann.bella.core.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Problem;
import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Variable;

class DebugExceptionsTest {

	@Test
	void exceptionEntryAmongTheVariables() {
		Variable exception = new Variable("{EXCEPTION}", "->CX_SY_ZERODIVIDE", "{O:12*\\CLASS=CX_SY_ZERODIVIDE}",
				List.of(Variable.of("TEXT", "STRING", "Division by zero")), false);
		assertEquals(Optional.of(new Problem("CX_SY_ZERODIVIDE", "Division by zero")),
				DebugExceptions.detect(List.of(Variable.of("LV_A", "I", "1"), exception), List.of()));
		assertEquals("ZCX_ORDER", DebugExceptions.detect(List.of(Variable.of("Exception", "", "ref to zcx_order")),
				List.of()).orElseThrow().type());
	}

	@Test
	void exceptionClassInTheThreadLabel() {
		assertEquals(Optional.of(new Problem("/ABC/CX_FAILED", "")), DebugExceptions.detect(List.of(),
				List.of("ABAP Thread (Suspended: exception /abc/cx_failed raised)", "ZCL_A->RUN")));
		// a class name alone is no exception stop
		assertTrue(DebugExceptions.detect(List.of(Variable.of("LO_CX", "ZCX_ORDER", "")),
				List.of("ABAP Thread (Suspended: breakpoint)", "ZCX_ORDER->CONSTRUCTOR")).isEmpty());
	}

	@Test
	void groupsAreNoLevel() {
		Variable deep = new Variable("LS_A", "ZS_A", "", List.of(new Variable("B", "ZS_B", "",
				List.of(new Variable("C", "ZS_C", "", List.of(Variable.of("D", "I", "4")), false)), false)), false);
		DebugSnapshot s = new DebugSnapshot("ZREP", "", 7, List.of(),
				List.of(new Variable("Locals", "", "", List.of(deep), false)), null);
		assertTrue(s.format().endsWith("Variables:\n- Locals\n  - LS_A (ZS_A)\n    - B (ZS_B)\n      - C (ZS_C)\n"
				+ "        - … (load with 'variable')\n"), s.format());
	}
}

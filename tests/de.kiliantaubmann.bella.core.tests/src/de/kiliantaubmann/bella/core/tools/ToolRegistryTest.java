package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.CancelToken;

class ToolRegistryTest {

	record Fake(String id, List<ToolSpec> specs) implements ToolProvider {
		@Override
		public String displayName() {
			return id;
		}

		@Override
		public List<ToolSpec> listTools() {
			return specs;
		}

		@Override
		public ToolResult call(String remoteName, JsonObject input, CancelToken cancel) {
			return ToolResult.ok(id + ":" + remoteName);
		}
	}

	private static ToolSpec spec(String name, String capability) {
		return ToolSpec.of(name, "", new JsonObject(), capability, ToolSpec.Kind.READ);
	}

	private static List<String> names(ToolRegistry r) {
		return r.tools().stream().map(ToolSpec::name).sorted().toList();
	}

	@Test
	void prefixesMcpToolsAndPrefersAdtForSharedCapabilities() {
		ToolRegistry r = new ToolRegistry();
		r.addProvider(new Fake("mcp:arc-1", List.of(spec("SAPRead", Capability.READ_SOURCE), spec("SAPLint", null))));
		r.addProvider(new Fake("adt", List.of(spec("adt_read_source", Capability.READ_SOURCE))));
		r.refresh(e -> {
		});
		assertEquals(List.of("adt_read_source", "mcp_arc-1_SAPLint"), names(r));
		assertEquals("SAPLint", r.find("mcp_arc-1_SAPLint").get().remoteName());
		assertEquals("mcp:arc-1", r.providerOf("mcp_arc-1_SAPLint").get().id());
	}

	@Test
	void mcpCanBePreferred() {
		ToolRegistry r = new ToolRegistry();
		r.addProvider(new Fake("adt", List.of(spec("adt_read_source", Capability.READ_SOURCE))));
		r.addProvider(new Fake("mcp:arc1", List.of(spec("SAPRead", Capability.READ_SOURCE))));
		r.setPreferredProvider("mcp");
		r.refresh(e -> {
		});
		assertEquals(List.of("mcp_arc1_SAPRead"), names(r));
	}

	@Test
	void failingProviderIsSkippedAndReported() {
		ToolRegistry r = new ToolRegistry();
		r.addProvider(new ToolProvider() {
			@Override
			public String id() {
				return "mcp:down";
			}

			@Override
			public String displayName() {
				return "down";
			}

			@Override
			public List<ToolSpec> listTools() throws Exception {
				throw new java.io.IOException("connection refused");
			}

			@Override
			public ToolResult call(String n, JsonObject i, CancelToken c) {
				return null;
			}
		});
		List<String> errors = new ArrayList<>();
		r.refresh(errors::add);
		assertTrue(errors.get(0).contains("connection refused"));
		assertTrue(r.tools().isEmpty());
	}

	@Test
	void namesAreSanitizedAndCut() {
		String n = ToolRegistry.exposedName(new Fake("mcp:my server", List.of()), "a.b".repeat(30));
		assertTrue(n.matches("[a-zA-Z0-9_-]{1,64}"), n);
		assertEquals("debug_step", ToolRegistry.exposedName(new Fake(ToolRegistry.DEBUG_PROVIDER_ID, List.of()),
				"debug_step"));
	}
}

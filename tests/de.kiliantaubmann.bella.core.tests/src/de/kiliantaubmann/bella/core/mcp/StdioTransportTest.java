package de.kiliantaubmann.bella.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StdioTransportTest {

	private static final Map<String, String> ENV = Map.of("PATH", "C:\\Program Files\\nodejs;C:\\tools");

	@Test
	void npxOnWindowsRunsThroughCmd() {
		Path npx = Path.of("C:\\Program Files\\nodejs", "npx.cmd");
		assertEquals(List.of("cmd.exe", "/c", npx.toString(), "-y", "arc-1@latest"),
				StdioTransport.resolve(List.of("npx", "-y", "arc-1@latest"), ENV, "Windows 11", npx::equals));
	}

	@Test
	void exeIsPreferredAndOtherSystemsAreUnchanged() {
		Path exe = Path.of("C:\\tools", "arc1.exe");
		assertEquals(List.of(exe.toString(), "--port", "0"),
				StdioTransport.resolve(List.of("arc1", "--port", "0"), ENV, "Windows 10", exe::equals));
		assertEquals(List.of("npx", "-y", "arc-1@latest"),
				StdioTransport.resolve(List.of("npx", "-y", "arc-1@latest"), ENV, "Linux", p -> true));
		assertEquals(List.of("unknown"), StdioTransport.resolve(List.of("unknown"), ENV, "Windows 11", p -> false));
	}

	@Test
	void shimArgumentsCmdWouldInterpretAreRefused() {
		Path npx = Path.of("C:\\Program Files\\nodejs", "npx.cmd");
		assertThrows(IllegalArgumentException.class, () -> StdioTransport.resolve(
				List.of("npx", "arc-1", "--url", "https://sap?a=1&b=2"), ENV, "Windows 11", npx::equals));
	}
}

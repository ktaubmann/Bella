package de.kiliantaubmann.bella.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SseParserTest {

	private static List<String> parse(String text) throws IOException {
		List<String> events = new ArrayList<>();
		SseParser.parse(new StringReader(text), (e, d) -> {
			events.add(e + "|" + d);
			return true;
		}, CancelToken.NONE);
		return events;
	}

	@Test
	void parsesEventsAndIgnoresComments() throws IOException {
		assertEquals(List.of("ping|{}", "message|a\nb"),
				parse(": keep-alive\nevent: ping\ndata: {}\n\ndata: a\ndata:b\n\n"));
	}

	@Test
	void dispatchesTrailingEventWithoutBlankLine() throws IOException {
		assertEquals(List.of("x|1"), parse("event: x\ndata: 1"));
	}

	@Test
	void handlerCanStop() throws IOException {
		List<String> seen = new ArrayList<>();
		SseParser.parse(new StringReader("data: 1\n\ndata: 2\n\n"), (e, d) -> {
			seen.add(d);
			return false;
		}, CancelToken.NONE);
		assertEquals(List.of("1"), seen);
	}
}

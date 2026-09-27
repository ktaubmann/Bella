package de.kiliantaubmann.bella.core.tools;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.util.Json;

class SchemaCheckTest {

	private static final JsonObject SCHEMA = Json.parseObject(
			"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"max\":{\"type\":\"integer\"},\"objects\":{\"type\":\"array\"}},\"required\":[\"name\"]}");

	@Test
	void acceptsValidInput() {
		assertNull(SchemaCheck.validate(SCHEMA, Json.parseObject("{\"name\":\"X\",\"max\":5,\"objects\":[]}")));
	}

	@Test
	void rejectsMissingRequiredAndWrongTypes() {
		assertNotNull(SchemaCheck.validate(SCHEMA, Json.parseObject("{}")));
		assertNotNull(SchemaCheck.validate(SCHEMA, Json.parseObject("{\"name\":1}")));
		assertNotNull(SchemaCheck.validate(SCHEMA, Json.parseObject("{\"name\":\"X\",\"max\":1.5}")));
		assertNotNull(SchemaCheck.validate(SCHEMA, Json.parseStrict("[]")));
	}
}

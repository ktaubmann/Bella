package de.kiliantaubmann.bella.core.prompt;

/** A system prompt and the first user message of a request. */
public record Prompt(String system, String user) {
}

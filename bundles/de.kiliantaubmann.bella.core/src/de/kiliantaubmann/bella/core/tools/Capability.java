package de.kiliantaubmann.bella.core.tools;

/**
 * Capability tags shared by Bella's own ADT tools and known MCP tools (ARC-1).
 * When two providers offer the same capability the registry keeps only the
 * tool of the preferred provider, so the model does not see duplicates.
 */
public final class Capability {

	public static final String SEARCH = "search";
	public static final String READ_SOURCE = "read_source";
	public static final String WHERE_USED = "where_used";
	public static final String SYNTAX_CHECK = "syntax_check";
	public static final String UNIT_TEST = "unit_test";
	public static final String ATC = "atc";
	public static final String WRITE_SOURCE = "write_source";
	public static final String CREATE_OBJECT = "create_object";
	public static final String ACTIVATE = "activate";
	public static final String TABLE_CONTENTS = "table_contents";

	private Capability() {
	}
}

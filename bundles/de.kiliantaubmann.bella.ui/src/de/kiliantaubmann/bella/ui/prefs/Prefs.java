package de.kiliantaubmann.bella.ui.prefs;

/** Preference keys (instance scope of the UI bundle). */
public final class Prefs {

	/** {@code anthropic}, {@code claude-code}, {@code copilot} or {@code openai}. */
	public static final String PROVIDER = "provider";
	public static final String ANTHROPIC_BASE_URL = "anthropic.baseUrl";
	public static final String CHAT_MODEL = "chat.model";
	public static final String COMPLETION_MODEL = "completion.model";
	/** Empty for the model default, otherwise low…max. */
	public static final String EFFORT = "chat.effort";
	public static final String MAX_TOKENS = "chat.maxTokens";
	public static final String REFUSAL_FALLBACK = "anthropic.refusalFallback";

	/** Path to the {@code claude} executable, empty for automatic detection. */
	public static final String CC_EXECUTABLE = "claudeCode.executable";
	public static final String CC_CHAT_MODEL = "claudeCode.chatModel";
	public static final String CC_COMPLETION_MODEL = "claudeCode.completionModel";

	/** Path to the {@code copilot} executable, empty for automatic detection. */
	public static final String CP_EXECUTABLE = "copilot.executable";
	/** Empty for the Copilot default model. */
	public static final String CP_CHAT_MODEL = "copilot.chatModel";
	public static final String CP_COMPLETION_MODEL = "copilot.completionModel";

	public static final String OPENAI_BASE_URL = "openai.baseUrl";
	public static final String OPENAI_CHAT_MODEL = "openai.chatModel";
	public static final String OPENAI_COMPLETION_MODEL = "openai.completionModel";

	/** Empty = Eclipse language, otherwise a locale tag (de, fr, ja, …). */
	public static final String UI_LANGUAGE = "ui.language";
	/** {@code ui}, {@code question} or a language tag. */
	public static final String ANSWER_LANGUAGE = "answer.language";
	/** Language tag for ABAP comments in generated code. */
	public static final String COMMENT_LANGUAGE = "comment.language";

	public static final String DIFF_PREVIEW = "editor.diffPreview";
	/** Editor actions load the definitions of the SAP objects the code uses. */
	public static final String EDITOR_SAP_CONTEXT = "editor.sapContext";
	public static final String AUTO_COMPLETION = "completion.auto";
	public static final String AUTO_COMPLETION_DELAY = "completion.delayMs";

	/** {@code adt} or {@code mcp}: whose tool wins when both offer the same capability. */
	public static final String PREFERRED_TOOLS = "tools.preferred";
	public static final String POLICY_RULES = "tools.policyRules";
	/** JSON array of MCP server definitions, see {@link McpServerConfig}. */
	public static final String MCP_SERVERS = "mcp.servers";

	private Prefs() {
	}
}

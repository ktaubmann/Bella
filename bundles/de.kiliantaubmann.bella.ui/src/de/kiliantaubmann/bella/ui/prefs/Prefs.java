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

	/** Packages last chosen in the chat window, most recent first, comma separated. */
	public static final String PACKAGE_HISTORY = "chat.packageHistory";

	public static final String DIFF_PREVIEW = "editor.diffPreview";
	/** Editor actions load the definitions of the SAP objects the code uses. */
	public static final String EDITOR_SAP_CONTEXT = "editor.sapContext";
	public static final String AUTO_COMPLETION = "completion.auto";
	public static final String AUTO_COMPLETION_DELAY = "completion.delayMs";

	/** Writes {@code bella.log} for troubleshooting; off by default. */
	public static final String LOG_ENABLED = "log.enabled";
	/** Also logs prompts, answers, source code and tool results. */
	public static final String LOG_DETAIL = "log.detail";

	/** {@code adt} or {@code mcp}: whose tool wins when both offer the same capability. */
	public static final String PREFERRED_TOOLS = "tools.preferred";
	public static final String POLICY_RULES = "tools.policyRules";
	public static final String WRITE_PACKAGES = "tools.writePackages";
	/** Project information per ABAP project; suffix is the destination id or {@link #CONVENTIONS_GLOBAL}. */
	public static final String CONVENTIONS_TEXT = "conventions.text.";
	/** Naming rules per ABAP project, same suffixes as {@link #CONVENTIONS_TEXT}. */
	public static final String CONVENTIONS_NAMING = "conventions.naming.";
	public static final String CONVENTIONS_GLOBAL = "global";
	/** ATC check variant, same suffixes as {@link #CONVENTIONS_TEXT}; empty for the system default. */
	public static final String ATC_VARIANT = "atc.variant.";
	/** Masks confidential data in Bella's log file; the model gets the real data. */
	public static final String MASK_ENABLED = "mask.enabled";
	/** Customer objects (Z*, Y*). */
	public static final String MASK_OBJECTS = "mask.objects";
	/** System id, ABAP project and logon user of the ABAP projects. */
	public static final String MASK_SYSTEM = "mask.system";
	/** E-mail addresses and IBANs. */
	public static final String MASK_PERSONAL = "mask.personal";
	/** Own terms, one per line (company, project, namespace). */
	public static final String MASK_TERMS = "mask.terms";
	/** JSON array of MCP server definitions, see {@link McpServerConfig}. */
	public static final String MCP_SERVERS = "mcp.servers";

	private Prefs() {
	}
}

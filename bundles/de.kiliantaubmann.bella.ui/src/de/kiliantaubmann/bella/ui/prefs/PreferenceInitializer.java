package de.kiliantaubmann.bella.ui.prefs;

import java.util.List;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.ui.BellaPlugin;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

	/** Personal data in common SAP tables: names, addresses, contact, bank, tax and HR data, user names. */
	public static final String DEFAULT_MASK_COLUMNS = "NAME1, NAME2, NAME3, NAME4, NAME_FIRST, NAME_LAST, NAME_CO, NAMEV, "
			+ "MC_NAME*, NAME_TEXT, STRAS, STREET, HOUSE_NUM*, ORT01, ORT02, CITY1, CITY2, PSTLZ, POST_CODE*, PFACH, "
			+ "PO_BOX, TELF*, TEL_NUMBER, MOB_NUMBER, TELFX, FAX_NUMBER, SMTP_ADDR, *EMAIL*, *E_MAIL*, IBAN, BANKN, "
			+ "BANKL, SWIFT, KOINH, STCD*, STCEG, *TAXNUM*, GBDAT, BIRTH*, GESCH, PERNR, ERNAM, AENAM, USNAM, UNAME, BNAME";

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore s = BellaPlugin.getDefault().getPreferenceStore();
		s.setDefault(Prefs.PROVIDER, AnthropicProvider.ID);
		s.setDefault(Prefs.ANTHROPIC_BASE_URL, AnthropicProvider.DEFAULT_BASE_URL);
		s.setDefault(Prefs.CHAT_MODEL, AnthropicProvider.DEFAULT_CHAT_MODEL);
		s.setDefault(Prefs.COMPLETION_MODEL, AnthropicProvider.DEFAULT_COMPLETION_MODEL);
		s.setDefault(Prefs.EFFORT, "");
		s.setDefault(Prefs.MAX_TOKENS, 32000);
		s.setDefault(Prefs.REFUSAL_FALLBACK, true);
		s.setDefault(Prefs.CC_EXECUTABLE, "");
		s.setDefault(Prefs.CC_CHAT_MODEL, "opus");
		s.setDefault(Prefs.CC_COMPLETION_MODEL, "haiku");
		s.setDefault(Prefs.CP_EXECUTABLE, "");
		s.setDefault(Prefs.CP_CHAT_MODEL, "");
		s.setDefault(Prefs.CP_COMPLETION_MODEL, "");
		s.setDefault(Prefs.OPENAI_BASE_URL, "http://localhost:11434/v1");
		s.setDefault(Prefs.OPENAI_CHAT_MODEL, "");
		s.setDefault(Prefs.OPENAI_COMPLETION_MODEL, "");
		s.setDefault(Prefs.UI_LANGUAGE, "");
		s.setDefault(Prefs.ANSWER_LANGUAGE, "ui");
		s.setDefault(Prefs.COMMENT_LANGUAGE, "en");
		s.setDefault(Prefs.DIFF_PREVIEW, true);
		s.setDefault(Prefs.EDITOR_SAP_CONTEXT, true);
		s.setDefault(Prefs.LOG_ENABLED, false);
		s.setDefault(Prefs.LOG_DETAIL, false);
		s.setDefault(Prefs.AUTO_COMPLETION, false);
		s.setDefault(Prefs.AUTO_COMPLETION_DELAY, 500);
		s.setDefault(Prefs.PREFERRED_TOOLS, "adt");
		s.setDefault(Prefs.POLICY_RULES, "");
		s.setDefault(Prefs.WRITE_PACKAGES, "$TMP, Z*, Y*");
		s.setDefault(Prefs.MASK_ENABLED, true);
		s.setDefault(Prefs.MASK_OBJECTS, true);
		s.setDefault(Prefs.MASK_SYSTEM, true);
		s.setDefault(Prefs.MASK_PERSONAL, true);
		s.setDefault(Prefs.MASK_TERMS, "");
		s.setDefault(Prefs.MASK_COLUMNS, DEFAULT_MASK_COLUMNS);
		s.setDefault(Prefs.MCP_SERVERS, McpServerConfig.toJson(List.of(
				new McpServerConfig("arc1", "ARC-1", true, "http://localhost:3000/mcp", "npx -y arc-1@latest", false))));
	}
}

package de.kiliantaubmann.bella.ui.prefs;

import java.util.List;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.ui.BellaPlugin;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

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
		s.setDefault(Prefs.AUTO_COMPLETION, false);
		s.setDefault(Prefs.AUTO_COMPLETION_DELAY, 500);
		s.setDefault(Prefs.PREFERRED_TOOLS, "adt");
		s.setDefault(Prefs.POLICY_RULES, "");
		s.setDefault(Prefs.MCP_SERVERS, McpServerConfig.toJson(List.of(
				new McpServerConfig("arc1", "ARC-1", true, "http://localhost:3000/mcp", "npx -y arc-1@latest", false))));
	}
}

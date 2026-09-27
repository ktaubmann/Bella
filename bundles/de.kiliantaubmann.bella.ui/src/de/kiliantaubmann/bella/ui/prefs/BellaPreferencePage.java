package de.kiliantaubmann.bella.ui.prefs;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.llm.OpenAiCompatibleProvider;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Languages;
import de.kiliantaubmann.bella.ui.Messages;

/** Main settings: model provider, API keys, models, languages, editor behaviour. */
public class BellaPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private Form form;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("prefs.description"));
		setImageDescriptor(BellaPlugin.descriptor(BellaPlugin.IMG_BELLA));
	}

	@Override
	protected Control createContents(Composite parent) {
		form = new Form(getPreferenceStore());
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group provider = Form.group(c, Messages.get("prefs.provider"));
		form.choice(provider, Messages.get("prefs.provider.label"), Prefs.PROVIDER,
				Form.options(AnthropicProvider.ID, Messages.get("prefs.provider.anthropic"), OpenAiCompatibleProvider.ID,
						Messages.get("prefs.provider.openai")));

		Group claude = Form.group(c, Messages.get("prefs.claude"));
		form.secret(claude, Messages.get("prefs.apiKey"), SecureStore.ANTHROPIC_KEY);
		form.text(claude, Messages.get("prefs.chatModel"), Prefs.CHAT_MODEL, Messages.get("prefs.chatModel.tip"));
		form.text(claude, Messages.get("prefs.completionModel"), Prefs.COMPLETION_MODEL,
				Messages.get("prefs.completionModel.tip"));
		form.choice(claude, Messages.get("prefs.effort"), Prefs.EFFORT, Form.options("",
				Messages.get("prefs.effort.default"), "low", "low", "medium", "medium", "high", "high", "xhigh",
				"xhigh", "max", "max"));
		form.check(claude, Messages.get("prefs.fallback"), Prefs.REFUSAL_FALLBACK);
		form.text(claude, Messages.get("prefs.baseUrl"), Prefs.ANTHROPIC_BASE_URL, null);
		Link console = new Link(claude, SWT.NONE);
		console.setText(Messages.get("prefs.claude.link"));
		console.addListener(SWT.Selection, e -> org.eclipse.swt.program.Program.launch(e.text));
		org.eclipse.jface.layout.GridDataFactory.fillDefaults().span(2, 1).applyTo(console);

		Group openai = Form.group(c, Messages.get("prefs.openai"));
		form.text(openai, Messages.get("prefs.baseUrl"), Prefs.OPENAI_BASE_URL, Messages.get("prefs.openai.baseUrl.tip"));
		form.secret(openai, Messages.get("prefs.apiKey"), SecureStore.OPENAI_KEY);
		form.text(openai, Messages.get("prefs.chatModel"), Prefs.OPENAI_CHAT_MODEL, null);
		form.text(openai, Messages.get("prefs.completionModel"), Prefs.OPENAI_COMPLETION_MODEL, null);

		Group general = Form.group(c, Messages.get("prefs.general"));
		form.number(general, Messages.get("prefs.maxTokens"), Prefs.MAX_TOKENS, 1024, 128000, 1024);
		Map<String, String> ui = new LinkedHashMap<>();
		ui.put("", Messages.get("prefs.lang.eclipse"));
		Map<String, String> answer = new LinkedHashMap<>();
		answer.put("ui", Messages.get("prefs.lang.likeUi"));
		answer.put("question", Messages.get("prefs.lang.likeQuestion"));
		Map<String, String> comments = new LinkedHashMap<>();
		for (String tag : Languages.SUPPORTED.keySet()) {
			ui.put(tag, Languages.nativeName(tag));
			answer.put(tag, Languages.nativeName(tag));
			comments.put(tag, Languages.nativeName(tag));
		}
		form.choice(general, Messages.get("prefs.uiLanguage"), Prefs.UI_LANGUAGE, ui);
		form.choice(general, Messages.get("prefs.answerLanguage"), Prefs.ANSWER_LANGUAGE, answer);
		form.choice(general, Messages.get("prefs.commentLanguage"), Prefs.COMMENT_LANGUAGE, comments);

		Group editor = Form.group(c, Messages.get("prefs.editor"));
		form.check(editor, Messages.get("prefs.diffPreview"), Prefs.DIFF_PREVIEW);
		form.check(editor, Messages.get("prefs.autoCompletion"), Prefs.AUTO_COMPLETION);
		form.number(editor, Messages.get("prefs.autoCompletionDelay"), Prefs.AUTO_COMPLETION_DELAY, 200, 5000, 100);

		form.load();
		return c;
	}

	@Override
	protected void performDefaults() {
		form.loadDefaults();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		form.store();
		return super.performOk();
	}
}

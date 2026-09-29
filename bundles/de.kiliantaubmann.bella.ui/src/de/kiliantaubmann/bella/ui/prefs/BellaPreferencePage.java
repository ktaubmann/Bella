package de.kiliantaubmann.bella.ui.prefs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.core.claudecode.ClaudeCli;
import de.kiliantaubmann.bella.core.claudecode.ClaudeCodeProvider;
import de.kiliantaubmann.bella.core.copilot.CopilotCli;
import de.kiliantaubmann.bella.core.copilot.CopilotProvider;
import de.kiliantaubmann.bella.core.llm.AnthropicProvider;
import de.kiliantaubmann.bella.core.llm.OpenAiCompatibleProvider;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Languages;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.Shortcuts;

/**
 * Main settings: model provider (Claude API key, Claude subscription, GitHub
 * Copilot or OpenAI-compatible), models, languages, editor behaviour.
 */
public class BellaPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private static final List<String> PROVIDERS = List.of(AnthropicProvider.ID, ClaudeCodeProvider.ID,
			CopilotProvider.ID, OpenAiCompatibleProvider.ID);

	/** Settings of a provider that runs through a local CLI. */
	private record CliGroup(Group group, Text executable, Text token, Label status, Button check) {
	}

	private Form form;
	private Composite content;
	private Combo provider;
	private Label providerHint;
	private Label editorHint;
	private Group claude;
	private CliGroup claudeCode;
	private CliGroup copilot;
	private Group openai;
	private Button autoCompletion;

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
		content = c;
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group providerGroup = Form.group(c, Messages.get("prefs.provider"));
		provider = form.choice(providerGroup, Messages.get("prefs.provider.label"), Prefs.PROVIDER,
				Form.options(AnthropicProvider.ID, Messages.get("prefs.provider.anthropic"), ClaudeCodeProvider.ID,
						Messages.get("prefs.provider.claudeCode"), CopilotProvider.ID,
						Messages.get("prefs.provider.copilot"), OpenAiCompatibleProvider.ID,
						Messages.get("prefs.provider.openai")));
		providerHint = Form.hint(providerGroup);

		claude = Form.group(c, Messages.get("prefs.claude"));
		form.secret(claude, Messages.get("prefs.apiKey"), SecureStore.ANTHROPIC_KEY);
		form.text(claude, Messages.get("prefs.chatModel"), Prefs.CHAT_MODEL, Messages.get("prefs.chatModel.tip"));
		form.text(claude, Messages.get("prefs.completionModel"), Prefs.COMPLETION_MODEL,
				Messages.get("prefs.completionModel.tip"));
		form.check(claude, Messages.get("prefs.fallback"), Prefs.REFUSAL_FALLBACK);
		form.text(claude, Messages.get("prefs.baseUrl"), Prefs.ANTHROPIC_BASE_URL, null);
		link(claude, Messages.get("prefs.claude.link"));

		claudeCode = cliGroup(c, "prefs.cc", "prefs.cc.intro", "prefs.cc.executable", Prefs.CC_EXECUTABLE,
				"prefs.cc.token", SecureStore.CLAUDE_CODE_TOKEN, "prefs.cc.token.tip", Prefs.CC_CHAT_MODEL,
				Prefs.CC_COMPLETION_MODEL, "prefs.cc.chatModel.tip");
		claudeCode.check().addListener(SWT.Selection, e -> checkClaudeCode());
		copilot = cliGroup(c, "prefs.cp", "prefs.cp.intro", "prefs.cp.executable", Prefs.CP_EXECUTABLE,
				"prefs.cp.token", SecureStore.COPILOT_TOKEN, "prefs.cp.token.tip", Prefs.CP_CHAT_MODEL,
				Prefs.CP_COMPLETION_MODEL, "prefs.cp.model.tip");
		copilot.check().addListener(SWT.Selection, e -> checkCopilot());

		openai = Form.group(c, Messages.get("prefs.openai"));
		form.text(openai, Messages.get("prefs.baseUrl"), Prefs.OPENAI_BASE_URL, Messages.get("prefs.openai.baseUrl.tip"));
		form.secret(openai, Messages.get("prefs.apiKey"), SecureStore.OPENAI_KEY);
		form.text(openai, Messages.get("prefs.chatModel"), Prefs.OPENAI_CHAT_MODEL, null);
		form.text(openai, Messages.get("prefs.completionModel"), Prefs.OPENAI_COMPLETION_MODEL, null);

		Group general = Form.group(c, Messages.get("prefs.general"));
		form.choice(general, Messages.get("prefs.effort"), Prefs.EFFORT, Form.options("",
				Messages.get("prefs.effort.default"), "low", "low", "medium", "medium", "high", "high", "xhigh",
				"xhigh", "max", "max"));
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
		autoCompletion = form.check(editor, Messages.get("prefs.autoCompletion"), Prefs.AUTO_COMPLETION);
		form.number(editor, Messages.get("prefs.autoCompletionDelay"), Prefs.AUTO_COMPLETION_DELAY, 200, 5000, 100);
		editorHint = Form.hint(editor);

		form.load();
		provider.addListener(SWT.Selection, e -> updateProvider());
		updateProvider();
		return c;
	}

	private CliGroup cliGroup(Composite parent, String titleKey, String introKey, String exeLabelKey, String exePref,
			String tokenLabelKey, String tokenSecureKey, String tokenTipKey, String chatPref, String completionPref,
			String modelTipKey) {
		Group g = Form.group(parent, Messages.get(titleKey));
		link(g, Messages.get(introKey));
		Text exe = form.file(g, Messages.get(exeLabelKey), exePref, Messages.get("prefs.cc.executable.tip"),
				Messages.get("prefs.cc.browse"));
		new Label(g, SWT.NONE).setText(Messages.get("prefs.cc.status"));
		Composite statusRow = new Composite(g, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(statusRow);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(statusRow);
		Label status = new Label(statusRow, SWT.WRAP);
		status.setText(Messages.get("prefs.cc.status.unknown"));
		GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).hint(260, SWT.DEFAULT)
				.applyTo(status);
		Button check = new Button(statusRow, SWT.PUSH);
		check.setText(Messages.get("prefs.cc.check"));
		Text token = form.secret(g, Messages.get(tokenLabelKey), tokenSecureKey);
		token.setToolTipText(Messages.get(tokenTipKey));
		form.text(g, Messages.get("prefs.chatModel"), chatPref, Messages.get(modelTipKey));
		form.text(g, Messages.get("prefs.completionModel"), completionPref, Messages.get(modelTipKey));
		return new CliGroup(g, exe, token, status, check);
	}

	private static void link(Composite parent, String text) {
		Link link = new Link(parent, SWT.WRAP);
		link.setText(text);
		link.addListener(SWT.Selection, e -> org.eclipse.swt.program.Program.launch(e.text));
		GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(380, SWT.DEFAULT).applyTo(link);
	}

	/** Selected provider id (not yet stored). */
	String selectedProvider() {
		int i = provider.getSelectionIndex();
		return i < 0 ? AnthropicProvider.ID : PROVIDERS.get(i);
	}

	/** Shows only the settings of the selected provider and the matching completion hint. */
	private void updateProvider() {
		String id = selectedProvider();
		boolean cc = ClaudeCodeProvider.ID.equals(id);
		boolean cp = CopilotProvider.ID.equals(id);
		show(claude, AnthropicProvider.ID.equals(id));
		show(claudeCode.group(), cc);
		show(copilot.group(), cp);
		show(openai, OpenAiCompatibleProvider.ID.equals(id));
		String hintKey = cc ? "prefs.hint.completion.cc" : cp ? "prefs.hint.completion.cp" : "prefs.hint.completion";
		String hint = Messages.fmt(hintKey, Shortcuts.completion());
		providerHint.setText(hint);
		editorHint.setText(hint);
		autoCompletion.setEnabled(!cc && !cp);
		autoCompletion.setToolTipText(cc ? Messages.get("prefs.autoCompletion.ccTip")
				: cp ? Messages.get("prefs.autoCompletion.cpTip") : null);
		content.layout(true, true);
		if (content.getParent() != null) {
			content.getParent().layout(true, true);
		}
	}

	private static void show(Group g, boolean visible) {
		g.setVisible(visible);
		((GridData) g.getLayoutData()).exclude = !visible;
	}

	/** Runs {@code claude auth status} in the background with the values currently in the form. */
	private void checkClaudeCode() {
		String exe = claudeCode.executable().getText().trim();
		String token = claudeCode.token().getText().trim();
		runCheck(claudeCode, () -> {
			ClaudeCli.Status st = BellaPlugin.getDefault().claudeCli(exe, token).status();
			return switch (st.state()) {
			case LOGGED_IN -> Messages.fmt("prefs.cc.status.ok",
					st.subscription() != null ? st.subscription() : String.valueOf(st.authMethod()),
					st.version() == null ? "" : st.version());
			case NOT_LOGGED_IN -> Messages.get("prefs.cc.status.notLoggedIn");
			case API_KEY -> Messages.get("prefs.cc.status.apiKey");
			case NOT_FOUND -> Messages.get("prefs.cc.status.notFound");
			case ERROR -> Messages.fmt("prefs.cc.status.error", String.valueOf(st.detail()));
			};
		});
	}

	/** Starts {@code copilot --acp} once to see whether it is logged in and which models it offers. */
	private void checkCopilot() {
		String exe = copilot.executable().getText().trim();
		String token = copilot.token().getText().trim();
		runCheck(copilot, () -> {
			CopilotCli.Status st = BellaPlugin.getDefault().copilotCli(exe, token).status();
			return switch (st.state()) {
			case LOGGED_IN -> {
				String ok = Messages.fmt("prefs.cp.status.ok", st.version() == null ? "" : st.version());
				yield st.models().isEmpty() ? ok
						: ok + "\n" + Messages.fmt("prefs.cp.status.models", String.join(", ", st.models()));
			}
			case NOT_LOGGED_IN -> Messages.get("prefs.cp.status.notLoggedIn");
			case NOT_FOUND -> Messages.get("prefs.cp.status.notFound");
			case ERROR -> Messages.fmt("prefs.cc.status.error", String.valueOf(st.detail()));
			};
		});
	}

	private void runCheck(CliGroup g, java.util.function.Supplier<String> check) {
		g.check().setEnabled(false);
		g.status().setText(Messages.get("prefs.cc.status.checking"));
		Thread t = new Thread(() -> {
			String text;
			try {
				text = check.get();
			} catch (RuntimeException e) {
				text = Messages.fmt("prefs.cc.status.error", String.valueOf(e.getMessage()));
			}
			String result = text;
			Display.getDefault().asyncExec(() -> {
				if (!g.status().isDisposed()) {
					g.status().setText(result);
					g.check().setEnabled(true);
					content.layout(true, true);
				}
			});
		}, "bella-cli-status");
		t.setDaemon(true);
		t.start();
	}

	@Override
	protected void performDefaults() {
		form.loadDefaults();
		updateProvider();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		form.store();
		return super.performOk();
	}
}

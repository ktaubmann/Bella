package de.kiliantaubmann.bella.ui.prefs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.mcp.McpSetupCommands;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.internal.ExternalMcp;

/**
 * Bella's MCP server for other programs on this machine: switch it on, port,
 * mode, and the console commands (with the token) that register it with
 * Claude Code and the GitHub Copilot CLI.
 */
public class ExternalMcpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	/** Shown in place of the token; the copied commands contain the real one. */
	private static final String HIDDEN = "••••••••";

	private Form form;
	private Spinner port;
	private Label status;
	private Combo shell;
	private Text claude;
	private Text copilot;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("mcpx.description"));
	}

	private static ExternalMcp mcp() {
		return BellaPlugin.getDefault().externalMcp();
	}

	@Override
	protected Control createContents(Composite parent) {
		form = new Form(getPreferenceStore());
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group server = Form.group(c, Messages.get("mcpx.server"));
		Button enabled = form.check(server, Messages.get("mcpx.enabled"), Prefs.MCP_EXTERNAL_ENABLED);
		GridDataFactory.fillDefaults().span(2, 1).applyTo(enabled);
		port = form.number(server, Messages.get("mcpx.port"), Prefs.MCP_EXTERNAL_PORT, 1024, 65535, 1);
		Map<String, String> modes = new LinkedHashMap<>();
		modes.put("PLAN", Messages.get("mcpx.mode.plan"));
		modes.put("SUGGEST", Messages.get("chat.mode.suggest"));
		modes.put("NORMAL", Messages.get("chat.mode.normal"));
		modes.put("READ_DATA", Messages.get("chat.mode.read_data"));
		form.choice(server, Messages.get("mcpx.mode"), Prefs.MCP_EXTERNAL_MODE, modes)
				.setToolTipText(Messages.get("mcpx.mode.tip"));
		new Label(server, SWT.NONE).setText(Messages.get("mcpx.status"));
		status = new Label(server, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).hint(380, SWT.DEFAULT).applyTo(status);
		Form.hint(server).setText(Messages.get("mcpx.hint"));

		Group setup = Form.group(c, Messages.get("mcpx.setup"));
		new Label(setup, SWT.NONE).setText(Messages.get("mcpx.shell"));
		shell = new Combo(setup, SWT.READ_ONLY | SWT.DROP_DOWN);
		shell.setItems("bash / zsh", "PowerShell", "cmd");
		shell.select(McpSetupCommands.Shell.ofSystem().ordinal());
		shell.addListener(SWT.Selection, e -> showCommands());

		new Label(setup, SWT.NONE).setText("Claude Code");
		claude = command(setup);
		new Label(setup, SWT.NONE);
		buttons(setup, button(Messages.get("mcpx.copy"), this::copyClaude));

		new Label(setup, SWT.NONE).setText("GitHub Copilot");
		copilot = command(setup);
		new Label(setup, SWT.NONE);
		buttons(setup, button(Messages.get("mcpx.copy"), this::copyCopilot),
				button(Messages.get("mcpx.copilotConfig"), this::addToCopilot));

		new Label(setup, SWT.NONE).setText(Messages.get("mcpx.others"));
		buttons(setup, button(Messages.get("mcpx.copyJson"), this::copyJson),
				button(Messages.get("mcpx.newToken"), this::newToken));
		Form.hint(setup).setText(Messages.get("mcpx.tokenHint"));

		form.load();
		port.addListener(SWT.Modify, e -> showCommands());
		showStatus();
		showCommands();
		return c;
	}

	private static Text command(Composite parent) {
		Text t = new Text(parent, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
		t.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
		GridDataFactory.fillDefaults().grab(true, false).hint(380, SWT.DEFAULT).applyTo(t);
		return t;
	}

	private record Action(String label, Runnable run) {
	}

	private static Action button(String label, Runnable run) {
		return new Action(label, run);
	}

	/** A row of buttons in the second column. */
	private static void buttons(Composite parent, Action... actions) {
		Composite row = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(actions.length).applyTo(row);
		for (Action a : actions) {
			Button b = new Button(row, SWT.PUSH);
			b.setText(a.label());
			b.addListener(SWT.Selection, e -> a.run().run());
		}
	}

	private McpSetupCommands.Shell selectedShell() {
		return McpSetupCommands.Shell.values()[Math.max(0, shell.getSelectionIndex())];
	}

	private String url() {
		return ExternalMcp.url(port.getSelection());
	}

	private Path copilotFile() {
		return BellaPlugin.getDefault().getStateLocation().toFile().toPath().resolve("mcp")
				.resolve(McpSetupCommands.SERVER_NAME + "-copilot.json");
	}

	/** The commands with the token hidden; copying puts in the real one. */
	private void showCommands() {
		claude.setText(McpSetupCommands.claudeCode(url(), HIDDEN, selectedShell()));
		copilot.setText(McpSetupCommands.copilot(copilotFile(), selectedShell()));
		claude.getParent().layout(true, true);
	}

	private void showStatus() {
		ExternalMcp m = mcp();
		String text = m.running() ? Messages.fmt("mcpx.status.running", ExternalMcp.url(m.port()))
				: m.problem() != null ? m.problem() : Messages.get("mcpx.status.off");
		if (m.running() && m.tokenNotSaved()) {
			text += "\n" + Messages.get("mcpx.status.tokenNotSaved");
		}
		status.setText(text);
		status.getParent().layout(true, true);
	}

	private void copy(String text) {
		Clipboard cb = new Clipboard(getShell().getDisplay());
		try {
			cb.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			cb.dispose();
		}
	}

	private void copyClaude() {
		copy(McpSetupCommands.claudeCode(url(), mcp().token(), selectedShell()));
		setMessage(Messages.get("mcpx.copied"), INFORMATION);
	}

	private void copyCopilot() {
		try {
			Path file = mcp().writeCopilotFile(port.getSelection());
			copy(McpSetupCommands.copilot(file, selectedShell()));
			setMessage(Messages.get("mcpx.copied"), INFORMATION);
		} catch (IOException e) {
			MessageDialog.openError(getShell(), Messages.get("app.name"), e.getMessage());
		}
	}

	private void addToCopilot() {
		try {
			Path file = mcp().addToCopilotConfig(port.getSelection());
			MessageDialog.openInformation(getShell(), Messages.get("app.name"),
					Messages.fmt("mcpx.copilotConfig.done", McpSetupCommands.SERVER_NAME, file));
		} catch (IOException e) {
			MessageDialog.openError(getShell(), Messages.get("app.name"), e.getMessage());
		}
	}

	private void copyJson() {
		JsonObject entry = McpSetupCommands.claudeCodeEntry(url(), mcp().token());
		copy(Json.PRETTY.toJson(McpSetupCommands.servers(McpSetupCommands.SERVER_NAME, entry)));
		setMessage(Messages.get("mcpx.copied"), INFORMATION);
	}

	private void newToken() {
		if (MessageDialog.openConfirm(getShell(), Messages.get("app.name"), Messages.get("mcpx.newToken.confirm"))) {
			mcp().newToken();
			setMessage(Messages.get("mcpx.newToken.done"), INFORMATION);
			showStatus();
		}
	}

	@Override
	protected void performDefaults() {
		form.loadDefaults();
		showCommands();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		form.store();
		mcp().apply();
		if (!getControl().isDisposed()) {
			showStatus();
		}
		return true;
	}
}

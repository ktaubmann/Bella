package de.kiliantaubmann.bella.ui.prefs;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/** Edits one MCP server (e.g. ARC-1). */
final class McpServerDialog extends TitleAreaDialog {

	private McpServerConfig config;
	private String token;
	private Text id;
	private Text name;
	private Button http;
	private Button stdio;
	private Text url;
	private Text command;
	private Text tokenText;
	private Button enabled;

	McpServerDialog(Shell shell, McpServerConfig config, String token) {
		super(shell);
		this.config = config;
		this.token = token;
	}

	McpServerConfig config() {
		return config;
	}

	String token() {
		return token;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("mcp.dialog.shell"));
		shell.setImage(BellaPlugin.image("tool"));
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(Messages.get("mcp.dialog.title"));
		setMessage(Messages.get("mcp.dialog.message"));
		Composite c = new Composite(area, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(c);
		GridLayoutFactory.swtDefaults().numColumns(2).applyTo(c);
		id = field(c, Messages.get("mcp.id"), config.id());
		name = field(c, Messages.get("mcp.name"), config.name());
		new Label(c, SWT.NONE).setText(Messages.get("mcp.transport"));
		Composite radios = new Composite(c, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(radios);
		http = new Button(radios, SWT.RADIO);
		http.setText(Messages.get("mcp.http"));
		stdio = new Button(radios, SWT.RADIO);
		stdio.setText(Messages.get("mcp.stdio"));
		http.setSelection(config.http());
		stdio.setSelection(!config.http());
		url = field(c, Messages.get("mcp.url"), config.url());
		command = field(c, Messages.get("mcp.command"), config.command());
		new Label(c, SWT.NONE).setText(Messages.get("mcp.token"));
		tokenText = new Text(c, SWT.BORDER | SWT.PASSWORD);
		tokenText.setText(token == null ? "" : token);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(tokenText);
		enabled = new Button(c, SWT.CHECK);
		enabled.setText(Messages.get("mcp.enabled"));
		enabled.setSelection(config.enabled());
		GridDataFactory.fillDefaults().span(2, 1).applyTo(enabled);
		Runnable sync = () -> {
			url.setEnabled(http.getSelection());
			tokenText.setEnabled(http.getSelection());
			command.setEnabled(stdio.getSelection());
		};
		http.addListener(SWT.Selection, e -> sync.run());
		stdio.addListener(SWT.Selection, e -> sync.run());
		sync.run();
		return area;
	}

	private static Text field(Composite c, String label, String value) {
		new Label(c, SWT.NONE).setText(label);
		Text t = new Text(c, SWT.BORDER);
		t.setText(value == null ? "" : value);
		GridDataFactory.fillDefaults().grab(true, false).hint(380, SWT.DEFAULT).applyTo(t);
		return t;
	}

	@Override
	protected void okPressed() {
		String idValue = id.getText().trim();
		if (!idValue.matches("[A-Za-z0-9_-]{1,20}")) {
			setErrorMessage(Messages.get("mcp.id.invalid"));
			return;
		}
		config = new McpServerConfig(idValue, name.getText().trim().isEmpty() ? idValue : name.getText().trim(),
				http.getSelection(), url.getText().trim(), command.getText().trim(), enabled.getSelection());
		token = tokenText.getText().trim();
		super.okPressed();
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
	}
}

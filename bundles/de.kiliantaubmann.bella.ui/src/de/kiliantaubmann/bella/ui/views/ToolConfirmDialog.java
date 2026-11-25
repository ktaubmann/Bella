package de.kiliantaubmann.bella.ui.views;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/** Asks the developer whether Bella may run a tool that changes something. */
public final class ToolConfirmDialog extends TitleAreaDialog {

	private final ToolSpec tool;
	private final JsonObject input;
	/** The program that called the tool through MCP, {@code null} for Bella's chat. */
	private final String caller;

	private ToolConfirmDialog(Shell shell, ToolSpec tool, JsonObject input, String caller) {
		super(shell);
		this.tool = tool;
		this.input = input;
		this.caller = caller;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/**
	 * Opens the dialog and waits for the answer. UI thread. Once {@code cancel}
	 * is cancelled (the caller stopped waiting), the dialog closes as declined.
	 */
	static boolean ask(Shell shell, ToolSpec tool, JsonObject input, CancelToken cancel) {
		return ask(shell, tool, input, cancel, null);
	}

	/**
	 * As {@link #ask(Shell, ToolSpec, JsonObject, CancelToken)}, for a call of
	 * another program through Bella's MCP server.
	 *
	 * @param caller the program's name, {@code null} for Bella's chat
	 */
	public static boolean ask(Shell shell, ToolSpec tool, JsonObject input, CancelToken cancel, String caller) {
		ToolConfirmDialog dialog = new ToolConfirmDialog(shell, tool, input, caller);
		Display display = shell.getDisplay();
		cancel.onCancel(() -> {
			if (!display.isDisposed()) {
				display.asyncExec(dialog::abandon);
			}
		});
		return dialog.open() == OK && !cancel.isCancelled();
	}

	/** Closes a dialog still open as declined. */
	private void abandon() {
		Shell s = getShell();
		if (s != null && !s.isDisposed()) {
			setReturnCode(CANCEL);
			close();
		}
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("confirm.shellTitle"));
		shell.setImage(BellaPlugin.image(BellaPlugin.IMG_BELLA));
	}

	@Override
	protected Point getInitialSize() {
		return new Point(760, 560);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(Messages.fmt("confirm.title", tool.name()));
		boolean read = tool.kind() == ToolSpec.Kind.READ;
		setMessage(caller == null ? Messages.get(read ? "confirm.messageRead" : "confirm.messageWrite")
				: Messages.fmt(read ? "confirm.messageExternalRead" : "confirm.messageExternalWrite", caller));
		setTitleImage(BellaPlugin.image("tool"));
		Composite c = new Composite(area, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(c);
		org.eclipse.jface.layout.GridLayoutFactory.swtDefaults().applyTo(c);
		Label desc = new Label(c, SWT.WRAP);
		desc.setText(tool.description());
		GridDataFactory.fillDefaults().grab(true, false).hint(700, SWT.DEFAULT).applyTo(desc);
		Text details = new Text(c, SWT.MULTI | SWT.BORDER | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
		details.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
		details.setText(describe(input));
		GridDataFactory.fillDefaults().grab(true, true).applyTo(details);
		return area;
	}

	/** Arguments with long source code shown as a block instead of an escaped JSON string. */
	static String describe(JsonObject input) {
		StringBuilder sb = new StringBuilder();
		String code = null;
		for (var e : input.entrySet()) {
			JsonElement v = e.getValue();
			if (v.isJsonPrimitive() && v.getAsString().contains("\n")) {
				code = e.getKey() + ":\n" + v.getAsString();
			} else {
				sb.append(e.getKey()).append(": ").append(v.isJsonPrimitive() ? v.getAsString() : Json.GSON.toJson(v))
						.append('\n');
			}
		}
		if (code != null) {
			sb.append('\n').append(code);
		}
		return sb.toString();
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, Messages.get("confirm.allow"), false);
		createButton(parent, IDialogConstants.CANCEL_ID, Messages.get("confirm.deny"), true);
	}
}

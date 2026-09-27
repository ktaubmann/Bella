package de.kiliantaubmann.bella.ui.handlers;

import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/** Asks what Bella should write, with one-click presets. */
final class InstructionDialog extends TitleAreaDialog {

	private final String title;
	private final String message;
	private final Image image;
	private final List<String> presets;
	private final boolean optional;
	private Text text;
	private String result;

	private InstructionDialog(Shell shell, String title, String message, Image image, List<String> presets,
			boolean optional) {
		super(shell);
		this.title = title;
		this.message = message;
		this.image = image;
		this.presets = presets;
		this.optional = optional;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** @return the instruction, "" if left empty while optional, or {@code null} if cancelled */
	static String ask(Shell shell, String title, String message, String imageKey, List<String> presets,
			boolean optional) {
		InstructionDialog d = new InstructionDialog(shell, title, message, BellaPlugin.image(imageKey), presets,
				optional);
		return d.open() == OK ? d.result : null;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("app.name"));
		shell.setImage(BellaPlugin.image(BellaPlugin.IMG_BELLA));
	}

	@Override
	protected Point getInitialSize() {
		return new Point(620, 380);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(title);
		setMessage(message);
		if (image != null) {
			setTitleImage(image);
		}
		Composite c = new Composite(area, SWT.NONE);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(c);
		GridLayoutFactory.swtDefaults().applyTo(c);
		text = new Text(c, SWT.MULTI | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		text.setMessage(Messages.get("instruction.hint"));
		GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 90).applyTo(text);
		text.addListener(SWT.Modify, e -> updateOk());
		text.addListener(SWT.Traverse, e -> {
			if (e.detail == SWT.TRAVERSE_RETURN && (e.stateMask & SWT.MOD1) != 0) {
				okPressed();
			}
		});
		if (!presets.isEmpty()) {
			Composite chips = new Composite(c, SWT.NONE);
			GridDataFactory.fillDefaults().grab(true, false).applyTo(chips);
			org.eclipse.swt.layout.RowLayout row = new org.eclipse.swt.layout.RowLayout();
			row.wrap = true;
			row.spacing = 4;
			chips.setLayout(row);
			for (String p : presets) {
				Button b = new Button(chips, SWT.PUSH);
				b.setText(p);
				b.addListener(SWT.Selection, e -> {
					text.setText(p);
					text.setFocus();
					text.setSelection(p.length());
				});
			}
		}
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, Messages.get("instruction.go"), true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		updateOk();
	}

	private void updateOk() {
		Button ok = getButton(IDialogConstants.OK_ID);
		if (ok != null && text != null) {
			ok.setEnabled(optional || !text.getText().isBlank());
		}
	}

	@Override
	protected void okPressed() {
		result = text.getText().trim();
		super.okPressed();
	}
}

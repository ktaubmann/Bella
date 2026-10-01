package de.kiliantaubmann.bella.ui.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;

/**
 * Lists ATC findings with a tick box each. Errors and warnings are ticked,
 * infos are not; a double-click shows the line in the editor.
 */
final class AtcFindingsDialog extends Dialog {

	private final ITextEditor editor;
	private final String objectName;
	private final List<AdtClient.Message> findings;
	private final List<AdtClient.Message> chosen = new ArrayList<>();
	private Table table;

	private AtcFindingsDialog(Shell shell, ITextEditor editor, String objectName, List<AdtClient.Message> findings) {
		super(shell);
		this.editor = editor;
		this.objectName = objectName;
		this.findings = findings;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** The ticked findings, or empty if the developer closed the dialog. */
	static List<AdtClient.Message> choose(Shell shell, ITextEditor editor, String objectName,
			List<AdtClient.Message> findings) {
		AtcFindingsDialog d = new AtcFindingsDialog(shell, editor, objectName, findings);
		return d.open() == OK ? d.chosen : List.of();
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("atc.title"));
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Label intro = new Label(area, SWT.WRAP);
		intro.setText(Messages.fmt("atc.message", objectName));
		GridDataFactory.fillDefaults().grab(true, false).hint(640, SWT.DEFAULT).applyTo(intro);
		table = new Table(area, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION);
		table.setHeaderVisible(true);
		String[] headers = { Messages.get("atc.col.priority"), Messages.get("atc.col.line"),
				Messages.get("atc.col.finding") };
		int[] widths = { 90, 60, 480 };
		for (int i = 0; i < headers.length; i++) {
			TableColumn c = new TableColumn(table, SWT.NONE);
			c.setText(headers[i]);
			c.setWidth(widths[i]);
		}
		for (AdtClient.Message m : findings) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(new String[] { priority(m.severity()), m.line() > 0 ? String.valueOf(m.line()) : "",
					m.text() });
			item.setChecked(!m.severity().equals("Info"));
			item.setData(m);
		}
		table.addListener(SWT.DefaultSelection, e -> reveal((AdtClient.Message) e.item.getData()));
		table.addListener(SWT.Selection, e -> updateButton());
		GridDataFactory.fillDefaults().grab(true, true).hint(640, 260).applyTo(table);
		return area;
	}

	static String priority(String severity) {
		return switch (severity) {
		case "Error" -> Messages.get("atc.prio.error");
		case "Warning" -> Messages.get("atc.prio.warning");
		default -> Messages.get("atc.prio.info");
		};
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, Messages.get("atc.fix"), true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CLOSE_LABEL, false);
		updateButton();
	}

	private void updateButton() {
		if (getButton(IDialogConstants.OK_ID) == null) {
			return;
		}
		boolean any = false;
		for (TableItem item : table.getItems()) {
			any |= item.getChecked();
		}
		getButton(IDialogConstants.OK_ID).setEnabled(any);
	}

	private void reveal(AdtClient.Message m) {
		IDocument doc = EditorBridge.document(editor);
		if (doc == null || m.line() <= 0 || m.line() > doc.getNumberOfLines()) {
			return;
		}
		try {
			editor.selectAndReveal(doc.getLineOffset(m.line() - 1), doc.getLineLength(m.line() - 1));
		} catch (BadLocationException e) {
			// line no longer exists
		}
	}

	@Override
	protected void okPressed() {
		chosen.clear();
		for (TableItem item : table.getItems()) {
			if (item.getChecked()) {
				chosen.add((AdtClient.Message) item.getData());
			}
		}
		super.okPressed();
	}
}

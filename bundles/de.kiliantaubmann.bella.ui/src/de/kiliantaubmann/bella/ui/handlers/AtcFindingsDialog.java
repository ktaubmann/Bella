package de.kiliantaubmann.bella.ui.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AtcSelection;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;

/**
 * Lists ATC findings with a tick box each. Errors and warnings of the
 * editor's source are ticked, up to {@link AtcSelection#MAX_PER_RUN}; infos
 * are not. Findings of another include cannot be ticked. A double-click shows
 * the line in the editor.
 */
final class AtcFindingsDialog extends Dialog {

	private final ITextEditor editor;
	private final String editorUri;
	private final String objectName;
	private final List<AdtClient.Message> findings;
	private final List<AdtClient.Message> chosen = new ArrayList<>();
	private Table table;
	private Label count;

	private AtcFindingsDialog(Shell shell, ITextEditor editor, String editorUri, String objectName,
			List<AdtClient.Message> findings) {
		super(shell);
		this.editor = editor;
		this.editorUri = editorUri;
		this.objectName = objectName;
		this.findings = findings;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** The ticked findings, or empty if the developer closed the dialog. */
	static List<AdtClient.Message> choose(Shell shell, ITextEditor editor, String editorUri, String objectName,
			List<AdtClient.Message> findings) {
		AtcFindingsDialog d = new AtcFindingsDialog(shell, editor, editorUri, objectName, findings);
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
		List<Integer> ticked = AtcSelection.defaultTicks(findings, editorUri);
		Color other = table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
		table.setRedraw(false);
		for (int i = 0; i < findings.size(); i++) {
			AdtClient.Message m = findings.get(i);
			TableItem item = new TableItem(table, SWT.NONE);
			String line = m.line() > 0 ? String.valueOf(m.line()) : "";
			// SAP's texts may span lines, which a table row cannot show
			item.setText(new String[] { priority(m.severity()),
					m.include().isEmpty() ? line : line + " (" + m.include() + ")",
					m.text().replaceAll("\\s+", " ").strip() });
			item.setChecked(ticked.contains(i));
			if (!AtcSelection.fixable(m, editorUri)) {
				item.setForeground(other);
			}
			item.setData(m);
		}
		table.setRedraw(true);
		table.addListener(SWT.DefaultSelection, e -> reveal((AdtClient.Message) e.item.getData()));
		table.addListener(SWT.Selection, e -> {
			if (e.detail == SWT.CHECK && e.item instanceof TableItem item && item.getChecked()
					&& !AtcSelection.fixable((AdtClient.Message) item.getData(), editorUri)) {
				item.setChecked(false);
			}
			updateButton();
		});
		GridDataFactory.fillDefaults().grab(true, true).hint(640, 260).applyTo(table);
		count = new Label(area, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).hint(640, SWT.DEFAULT).applyTo(count);
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
		int ticked = 0;
		for (TableItem item : table.getItems()) {
			ticked += item.getChecked() ? 1 : 0;
		}
		getButton(IDialogConstants.OK_ID).setEnabled(ticked > 0 && ticked <= AtcSelection.MAX_PER_RUN);
		count.setText(Messages.fmt(ticked > AtcSelection.MAX_PER_RUN ? "atc.count.tooMany" : "atc.count",
				String.valueOf(ticked), String.valueOf(AtcSelection.MAX_PER_RUN)));
		count.getParent().layout(true);
	}

	private void reveal(AdtClient.Message m) {
		IDocument doc = EditorBridge.document(editor);
		// a line of another include (e.g. the test classes) is not in this editor
		if (doc == null || !AtcSelection.fixable(m, editorUri) || m.line() <= 0 || m.line() > doc.getNumberOfLines()) {
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

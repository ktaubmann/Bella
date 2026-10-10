package de.kiliantaubmann.bella.ui.views;

import java.io.IOException;
import java.util.List;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.adt.AdtToolProvider;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.ui.Messages;

/** Searches the SAP system for a development package by the start of its name. */
final class PackageDialog extends Dialog {

	/** Hits shown at most; more say that the list is cut. */
	static final int MAX = 200;

	private final AdtToolProvider tools;
	private final String initial;
	private final CancelToken cancel = new CancelToken();
	private Text pattern;
	private Label info;
	private Table table;
	private String result;
	/** Counts the searches; only the newest one fills the table. */
	private int searches;

	PackageDialog(Shell parent, AdtToolProvider tools, String initial) {
		super(parent);
		this.tools = tools;
		this.initial = initial == null ? "" : initial.trim();
	}

	/** The chosen package, {@code null} if none. */
	String result() {
		return result;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("chat.scope.searchTitle"));
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		GridLayoutFactory.swtDefaults().numColumns(2).applyTo(area);
		pattern = new Text(area, SWT.SEARCH | SWT.BORDER);
		pattern.setMessage(Messages.get("chat.scope.searchHint"));
		pattern.setText(initial);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(pattern);
		// Enter searches and must not also press OK with a row selected from the previous search
		pattern.addListener(SWT.Traverse, e -> {
			if (e.detail == SWT.TRAVERSE_RETURN) {
				e.doit = false;
				e.detail = SWT.TRAVERSE_NONE;
				search();
			}
		});
		Button find = new Button(area, SWT.PUSH);
		find.setText(Messages.get("chat.scope.searchButton"));
		find.addListener(SWT.Selection, e -> search());
		info = new Label(area, SWT.WRAP);
		GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(info);
		table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
		table.setHeaderVisible(true);
		GridDataFactory.fillDefaults().span(2, 1).grab(true, true).hint(460, 280).applyTo(table);
		TableColumn name = new TableColumn(table, SWT.NONE);
		name.setText(Messages.get("chat.scope.column.name"));
		name.setWidth(180);
		TableColumn description = new TableColumn(table, SWT.NONE);
		description.setText(Messages.get("chat.scope.column.description"));
		description.setWidth(260);
		table.addListener(SWT.DefaultSelection, e -> okPressed());
		table.addListener(SWT.Selection, e -> getButton(IDialogConstants.OK_ID).setEnabled(true));
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		super.createButtonsForButtonBar(parent);
		getButton(IDialogConstants.OK_ID).setEnabled(false);
		if (!initial.isEmpty()) {
			search();
		}
	}

	private void search() {
		String q = pattern.getText().trim();
		if (q.isEmpty()) {
			info.setText(Messages.get("chat.scope.searchHint"));
			return;
		}
		info.setText(Messages.get("chat.scope.searching"));
		table.removeAll();
		getButton(IDialogConstants.OK_ID).setEnabled(false);
		int search = ++searches;
		Job.create(Messages.get("chat.scope.searchTitle"), monitor -> {
			List<AdtObjectRef> hits;
			String problem = null;
			try {
				hits = tools.packages(q, MAX + 1, cancel);
			} catch (IOException | RuntimeException e) {
				hits = List.of();
				problem = e.getMessage() == null ? e.toString() : e.getMessage();
			}
			List<AdtObjectRef> found = hits;
			String error = problem;
			Display.getDefault().asyncExec(() -> {
				if (search == searches) {
					show(found, error);
				}
			});
			return Status.OK_STATUS;
		}).schedule();
	}

	private void show(List<AdtObjectRef> hits, String problem) {
		if (table.isDisposed()) {
			return;
		}
		table.removeAll();
		for (AdtObjectRef r : hits.subList(0, Math.min(MAX, hits.size()))) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(new String[] { r.name(), r.description() });
		}
		info.setText(problem != null ? problem
				: hits.isEmpty() ? Messages.get("chat.scope.searchNone")
						: hits.size() > MAX ? Messages.fmt("chat.scope.searchCut", MAX) : "");
		info.getParent().layout();
	}

	@Override
	protected void okPressed() {
		int index = table.getSelectionIndex();
		if (index < 0) {
			return;
		}
		result = table.getItem(index).getText(0);
		super.okPressed();
	}

	@Override
	public boolean close() {
		cancel.cancel();
		return super.close();
	}
}

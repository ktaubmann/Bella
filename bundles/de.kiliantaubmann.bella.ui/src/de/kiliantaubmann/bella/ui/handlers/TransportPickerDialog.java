package de.kiliantaubmann.bella.ui.handlers;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.core.adt.AdtTransportRequest;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * Picks the transport request to review: the system, then one of the
 * developer's modifiable requests (loaded in the background) or any number.
 */
final class TransportPickerDialog extends Dialog {

	/** The request to review and its system. */
	record Choice(AdtSystem system, String request) {
	}

	private final AdtBackend adt;
	private final List<AdtSystem> systems;
	private final String preferredDestination;
	private Combo system;
	private Table table;
	private Text number;
	private Label status;
	private Choice choice;
	private CancelToken loading;

	private TransportPickerDialog(Shell shell, AdtBackend adt, List<AdtSystem> systems, String preferredDestination) {
		super(shell);
		this.adt = adt;
		this.systems = systems;
		this.preferredDestination = preferredDestination;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	static Optional<Choice> choose(Shell shell, AdtBackend adt, List<AdtSystem> systems, String preferredDestination) {
		TransportPickerDialog d = new TransportPickerDialog(shell, adt, systems, preferredDestination);
		return d.open() == OK ? Optional.ofNullable(d.choice) : Optional.empty();
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("tr.title"));
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Label intro = new Label(area, SWT.WRAP);
		intro.setText(Messages.get("tr.message"));
		GridDataFactory.fillDefaults().grab(true, false).hint(620, SWT.DEFAULT).applyTo(intro);

		Composite row = new Composite(area, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(row);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(row);
		new Label(row, SWT.NONE).setText(Messages.get("tr.system"));
		system = new Combo(row, SWT.READ_ONLY);
		int selected = 0;
		for (int i = 0; i < systems.size(); i++) {
			system.add(systems.get(i).label());
			if (systems.get(i).destinationId().equals(preferredDestination)) {
				selected = i;
			}
		}
		system.select(selected);
		system.addListener(SWT.Selection, e -> load());

		table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
		table.setHeaderVisible(true);
		String[] headers = { Messages.get("tr.col.request"), Messages.get("tr.col.description"),
				Messages.get("tr.col.target"), Messages.get("tr.col.objects") };
		int[] widths = { 110, 330, 80, 70 };
		for (int i = 0; i < headers.length; i++) {
			TableColumn c = new TableColumn(table, SWT.NONE);
			c.setText(headers[i]);
			c.setWidth(widths[i]);
		}
		GridDataFactory.fillDefaults().grab(true, true).hint(620, 220).applyTo(table);
		table.addListener(SWT.Selection, e -> {
			if (e.item != null) {
				number.setText(((TableItem) e.item).getText(0));
			}
		});
		table.addListener(SWT.DefaultSelection, e -> okPressed());

		status = new Label(area, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(status);

		Composite numberRow = new Composite(area, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(numberRow);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(numberRow);
		new Label(numberRow, SWT.NONE).setText(Messages.get("tr.number"));
		number = new Text(numberRow, SWT.BORDER);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(number);
		number.addListener(SWT.Modify, e -> updateButton());
		load();
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, Messages.get("tr.start"), true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		updateButton();
	}

	private void updateButton() {
		if (getButton(IDialogConstants.OK_ID) != null) {
			getButton(IDialogConstants.OK_ID).setEnabled(!number.getText().isBlank());
		}
	}

	private AdtSystem selectedSystem() {
		return systems.get(Math.max(0, system.getSelectionIndex()));
	}

	/** Loads the developer's modifiable requests of the selected system in the background. */
	private void load() {
		if (loading != null) {
			loading.cancel();
		}
		CancelToken cancel = new CancelToken();
		loading = cancel;
		AdtSystem s = selectedSystem();
		table.removeAll();
		status.setText(Messages.get("tr.loading"));
		Job job = new Job(Messages.get("tr.loading")) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				List<AdtTransportRequest> list;
				String error = null;
				try {
					list = new AdtClient(adt.stateless(s.destinationId())).transports(s.user(), "D", cancel);
				} catch (Exception e) {
					list = List.of();
					error = String.valueOf(e.getMessage());
				}
				List<AdtTransportRequest> result = list;
				String failure = error;
				Display.getDefault().asyncExec(() -> show(cancel, s, result, failure));
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	private void show(CancelToken token, AdtSystem s, List<AdtTransportRequest> list, String error) {
		if (token.isCancelled() || table == null || table.isDisposed()) {
			return;
		}
		for (AdtTransportRequest t : list) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(new String[] { t.id(), t.description(), t.target(), String.valueOf(t.entries().size()) });
		}
		String user = s.user() == null ? "" : s.user().toUpperCase(Locale.ROOT);
		status.setText(error != null ? Messages.fmt("tr.failed", error)
				: list.isEmpty() ? Messages.fmt("tr.none", user) : "");
		status.getParent().layout(true, true);
	}

	@Override
	protected void okPressed() {
		String id = number.getText().trim().toUpperCase(Locale.ROOT);
		if (id.isEmpty()) {
			return;
		}
		choice = new Choice(selectedSystem(), id);
		super.okPressed();
	}

	@Override
	public boolean close() {
		if (loading != null) {
			loading.cancel();
		}
		return super.close();
	}
}

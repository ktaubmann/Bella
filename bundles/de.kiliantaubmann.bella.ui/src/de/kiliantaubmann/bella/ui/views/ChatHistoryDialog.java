package de.kiliantaubmann.bella.ui.views;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import de.kiliantaubmann.bella.core.agent.ChatStore;
import de.kiliantaubmann.bella.ui.Messages;

/** The saved chats: filter, open, delete. */
final class ChatHistoryDialog extends Dialog {

	private final ChatStore store;
	private final String current;
	private Text filter;
	private Table table;
	private Button delete;
	private List<ChatStore.Summary> chats = List.of();
	/** The chats in the table's order. */
	private List<ChatStore.Summary> shown = List.of();
	private String result;

	/** @param current id of the chat shown now, which cannot be deleted here */
	ChatHistoryDialog(Shell parent, ChatStore store, String current) {
		super(parent);
		this.store = store;
		this.current = current;
	}

	/** Id of the chat to open, {@code null} if none. */
	String result() {
		return result;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("chat.history.title"));
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		GridLayoutFactory.swtDefaults().numColumns(1).applyTo(area);
		filter = new Text(area, SWT.SEARCH | SWT.BORDER | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
		filter.setMessage(Messages.get("chat.history.filter"));
		GridDataFactory.fillDefaults().grab(true, false).applyTo(filter);
		filter.addListener(SWT.Modify, e -> fill());
		table = new Table(area, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION | SWT.V_SCROLL);
		table.setHeaderVisible(true);
		GridDataFactory.fillDefaults().grab(true, true).hint(560, 320).applyTo(table);
		TableColumn title = new TableColumn(table, SWT.NONE);
		title.setText(Messages.get("chat.history.column.title"));
		title.setWidth(380);
		TableColumn changed = new TableColumn(table, SWT.NONE);
		changed.setText(Messages.get("chat.history.column.changed"));
		changed.setWidth(160);
		table.addListener(SWT.DefaultSelection, e -> okPressed());
		table.addListener(SWT.Selection, e -> enableButtons());
		table.addListener(SWT.KeyDown, e -> {
			if (e.keyCode == SWT.DEL) {
				deleteSelected();
			}
		});
		chats = store.list();
		fill();
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		delete = createButton(parent, IDialogConstants.CLIENT_ID + 1, Messages.get("chat.history.delete"), false);
		delete.addListener(SWT.Selection, e -> deleteSelected());
		Button deleteAll = createButton(parent, IDialogConstants.CLIENT_ID + 2, Messages.get("chat.history.deleteAll"),
				false);
		deleteAll.addListener(SWT.Selection, e -> deleteAll());
		createButton(parent, IDialogConstants.OK_ID, Messages.get("chat.history.open"), true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		enableButtons();
	}

	@Override
	protected void buttonPressed(int id) {
		if (id == IDialogConstants.CLIENT_ID + 1 || id == IDialogConstants.CLIENT_ID + 2) {
			return; // handled by their listeners; the dialog stays open
		}
		super.buttonPressed(id);
	}

	@Override
	protected void okPressed() {
		int i = table.getSelectionIndex();
		if (i < 0 || i >= shown.size()) {
			return;
		}
		result = shown.get(i).id();
		super.okPressed();
	}

	private void fill() {
		String q = filter.getText().trim().toLowerCase(Locale.ROOT);
		List<ChatStore.Summary> matching = new ArrayList<>();
		for (ChatStore.Summary c : chats) {
			if (q.isEmpty() || c.title().toLowerCase(Locale.ROOT).contains(q)) {
				matching.add(c);
			}
		}
		shown = matching;
		table.removeAll();
		DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
		for (ChatStore.Summary c : matching) {
			TableItem item = new TableItem(table, SWT.NONE);
			String title = c.title().isBlank() ? Messages.get("chat.history.untitled") : c.title();
			item.setText(0, c.id().equals(current) ? title + "  " + Messages.get("chat.history.current") : title);
			item.setText(1, format.format(new Date(c.updated())));
		}
		if (!matching.isEmpty()) {
			table.select(0);
		}
		enableButtons();
	}

	private void enableButtons() {
		Button ok = getButton(IDialogConstants.OK_ID);
		boolean selected = table != null && table.getSelectionCount() > 0;
		if (ok != null) {
			ok.setEnabled(selected);
		}
		if (delete != null) {
			delete.setEnabled(selected);
		}
	}

	private void deleteSelected() {
		int[] rows = table.getSelectionIndices();
		if (rows.length == 0) {
			return;
		}
		for (int i : rows) {
			if (i < shown.size()) {
				store.delete(shown.get(i).id());
			}
		}
		chats = store.list();
		fill();
	}

	private void deleteAll() {
		if (chats.isEmpty() || !MessageDialog.openConfirm(getShell(), Messages.get("chat.history.title"),
				Messages.get("chat.history.deleteAllConfirm"))) {
			return;
		}
		store.deleteAll();
		chats = store.list();
		fill();
	}

	/** How long ago, e.g. "5 min" or "3 d"; the menu's second column. */
	static String age(long time, long now) {
		long minutes = Math.max(0, (now - time) / 60_000);
		if (minutes < 1) {
			return Messages.get("chat.history.now");
		}
		if (minutes < 60) {
			return Messages.fmt("chat.history.minutes", minutes);
		}
		if (minutes < 24 * 60) {
			return Messages.fmt("chat.history.hours", minutes / 60);
		}
		return Messages.fmt("chat.history.days", minutes / (24 * 60));
	}

	static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1).strip() + "…";
	}
}

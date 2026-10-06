package de.kiliantaubmann.bella.ui.prefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.internal.LogFile;

/** Troubleshooting: switch Bella's log file on or off, choose the detail level, open or clear it. */
public class LogPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private Form form;
	private Button enabled;
	private Button detail;
	private Button mask;
	private List<Control> maskOptions;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("log.description"));
	}

	@Override
	protected Control createContents(Composite parent) {
		form = new Form(getPreferenceStore());
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group g = Form.group(c, Messages.get("log.group"));
		enabled = form.check(g, Messages.get("log.enabled"), Prefs.LOG_ENABLED);
		detail = form.check(g, Messages.get("log.detail"), Prefs.LOG_DETAIL);
		GridDataFactory.fillDefaults().span(2, 1).indent(16, 0).applyTo(detail);
		Form.hint(g).setText(Messages.get("log.detail.hint"));

		Label fileLabel = new Label(g, SWT.NONE);
		fileLabel.setText(Messages.get("log.file"));
		Text path = new Text(g, SWT.READ_ONLY | SWT.BORDER);
		path.setText(logFile().path().toString());
		GridDataFactory.fillDefaults().grab(true, false).hint(320, SWT.DEFAULT).applyTo(path);

		Composite buttons = new Composite(g, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(3).applyTo(buttons);
		GridDataFactory.fillDefaults().span(2, 1).applyTo(buttons);
		button(buttons, Messages.get("log.open"), this::openLog);
		button(buttons, Messages.get("log.folder"), () -> Program.launch(logFile().path().getParent().toString()));
		button(buttons, Messages.get("log.clear"), this::clearLog);
		Form.hint(g).setText(Messages.get("log.privacy"));

		Group m = Form.group(c, Messages.get("mask.group"));
		mask = form.check(m, Messages.get("mask.enabled"), Prefs.MASK_ENABLED);
		Button objects = indented(form.check(m, Messages.get("mask.objects"), Prefs.MASK_OBJECTS));
		Button system = indented(form.check(m, Messages.get("mask.system"), Prefs.MASK_SYSTEM));
		Button personal = indented(form.check(m, Messages.get("mask.personal"), Prefs.MASK_PERSONAL));
		Text terms = form.area(m, Messages.get("mask.terms"), Prefs.MASK_TERMS, 4);
		terms.setToolTipText(Messages.get("mask.termsTip"));
		Form.hint(m).setText(Messages.get("mask.hint"));
		maskOptions = List.of(objects, system, personal, terms);

		form.load();
		enabled.addListener(SWT.Selection, e -> updateEnablement());
		mask.addListener(SWT.Selection, e -> updateEnablement());
		updateEnablement();
		return c;
	}

	private static LogFile logFile() {
		return BellaPlugin.getDefault().logFile();
	}

	private static Button indented(Button b) {
		GridDataFactory.fillDefaults().span(2, 1).indent(16, 0).applyTo(b);
		return b;
	}

	private void updateEnablement() {
		detail.setEnabled(enabled.getSelection());
		maskOptions.forEach(w -> w.setEnabled(mask.getSelection()));
	}

	private static void button(Composite parent, String label, Runnable action) {
		Button b = new Button(parent, SWT.PUSH);
		b.setText(label);
		b.addListener(SWT.Selection, e -> action.run());
	}

	private void openLog() {
		Path file = logFile().path();
		if (!Files.exists(file)) {
			MessageDialog.openInformation(getShell(), Messages.get("app.name"), Messages.get("log.empty"));
			return;
		}
		// The preference dialog is modal, so the system viewer is the quicker way to read it now.
		if (!Program.launch(file.toString())) {
			LogViewer.openInEditor(file);
		}
	}

	private void clearLog() {
		BellaPlugin.getDefault().clearLog();
		MessageDialog.openInformation(getShell(), Messages.get("app.name"), Messages.get("log.cleared"));
	}

	@Override
	protected void performDefaults() {
		form.loadDefaults();
		updateEnablement();
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		form.store();
		return true;
	}
}

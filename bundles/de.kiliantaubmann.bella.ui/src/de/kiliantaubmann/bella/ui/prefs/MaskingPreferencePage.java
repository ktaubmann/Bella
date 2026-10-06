package de.kiliantaubmann.bella.ui.prefs;

import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/** What Bella replaces with placeholders before anything goes to the model. */
public class MaskingPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private Form form;
	private Button enabled;
	private List<Control> dependent;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("mask.description"));
	}

	@Override
	protected Control createContents(Composite parent) {
		form = new Form(getPreferenceStore());
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group g = Form.group(c, Messages.get("mask.group"));
		enabled = form.check(g, Messages.get("mask.enabled"), Prefs.MASK_ENABLED);
		Button objects = indented(form.check(g, Messages.get("mask.objects"), Prefs.MASK_OBJECTS));
		Button system = indented(form.check(g, Messages.get("mask.system"), Prefs.MASK_SYSTEM));
		Button personal = indented(form.check(g, Messages.get("mask.personal"), Prefs.MASK_PERSONAL));
		Text terms = form.area(g, Messages.get("mask.terms"), Prefs.MASK_TERMS, 4);
		terms.setToolTipText(Messages.get("mask.termsTip"));
		Text columns = form.area(g, Messages.get("mask.columns"), Prefs.MASK_COLUMNS, 4);
		columns.setToolTipText(Messages.get("mask.columnsTip"));
		Form.hint(g).setText(Messages.get("mask.hint"));
		dependent = List.of(objects, system, personal, terms, columns);

		form.load();
		enabled.addListener(SWT.Selection, e -> updateEnablement());
		updateEnablement();
		return c;
	}

	private static Button indented(Button b) {
		GridDataFactory.fillDefaults().span(2, 1).indent(16, 0).applyTo(b);
		return b;
	}

	private void updateEnablement() {
		dependent.forEach(w -> w.setEnabled(enabled.getSelection()));
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

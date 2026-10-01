package de.kiliantaubmann.bella.ui.prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.core.conventions.ConventionsProposal;
import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.prompt.Prompt;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * Project information and naming rules, for all systems and per ABAP project.
 * Bella gives them to the model and checks the naming rules in its style check.
 */
public class ConventionsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	/** Scope key: {@link Prefs#CONVENTIONS_GLOBAL} or a destination id. */
	private final List<String> scopes = new ArrayList<>();
	private final List<AdtSystem> systems = new ArrayList<>();
	/** Edited texts per scope: [project information, naming rules]. */
	private final Map<String, String[]> edits = new LinkedHashMap<>();
	private Combo scope;
	private String current;
	private Text text;
	private Text naming;
	private Label status;
	private Button derive;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("conv.description"));
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Composite row = new Composite(c, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(row);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(row);
		new Label(row, SWT.NONE).setText(Messages.get("conv.scope"));
		scope = new Combo(row, SWT.READ_ONLY);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(scope);
		scopes.add(Prefs.CONVENTIONS_GLOBAL);
		scope.add(Messages.get("conv.scope.all"));
		AdtBackend adt = BellaPlugin.getDefault().adt();
		try {
			if (adt != null) {
				systems.addAll(adt.systems());
			}
		} catch (RuntimeException | LinkageError e) {
			// no ADT: only the settings for all systems
		}
		for (AdtSystem s : systems) {
			scopes.add(s.destinationId());
			scope.add(s.loggedOn() ? s.label() : Messages.fmt("conv.notLoggedOn", s.label()));
		}
		scope.select(0);
		current = Prefs.CONVENTIONS_GLOBAL;
		scope.addListener(SWT.Selection, e -> switchScope());

		Group info = Form.group(c, Messages.get("conv.text"));
		GridDataFactory.fillDefaults().grab(true, true).applyTo(info);
		text = area(info, 140);

		Group rules = Form.group(c, Messages.get("conv.naming"));
		GridDataFactory.fillDefaults().grab(true, true).applyTo(rules);
		naming = area(rules, 140);
		naming.setFont(JFaceResources.getTextFont());
		naming.addListener(SWT.Modify, e -> validate());
		Label kinds = new Label(rules, SWT.WRAP);
		kinds.setText(Messages.fmt("conv.kinds", NamingRules.kinds()));
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(kinds);
		status = new Label(rules, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(status);

		Composite buttons = new Composite(c, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(buttons);
		Button template = new Button(buttons, SWT.PUSH);
		template.setText(Messages.get("conv.template"));
		template.addListener(SWT.Selection, e -> naming.setText(NamingRules.TEMPLATE));
		derive = new Button(buttons, SWT.PUSH);
		derive.setText(Messages.get("conv.derive"));
		derive.addListener(SWT.Selection, e -> derive());

		load(current);
		return c;
	}

	private static Text area(Composite parent, int height) {
		Text t = new Text(parent, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, true).span(2, 1).hint(500, height).applyTo(t);
		return t;
	}

	private void switchScope() {
		if (!validate()) {
			scope.select(scopes.indexOf(current)); // fix the rules of this scope first
			return;
		}
		edits.put(current, new String[] { text.getText(), naming.getText() });
		current = scopes.get(Math.max(0, scope.getSelectionIndex()));
		load(current);
	}

	private void load(String key) {
		String[] e = edits.get(key);
		text.setText(e != null ? e[0] : getPreferenceStore().getString(Prefs.CONVENTIONS_TEXT + key));
		naming.setText(e != null ? e[1] : getPreferenceStore().getString(Prefs.CONVENTIONS_NAMING + key));
		validate();
	}

	private boolean validate() {
		List<String> errors = NamingRules.parse(naming.getText()).errors();
		status.setText(errors.isEmpty() ? "" : Messages.fmt("conv.errors", String.join("; ", errors)));
		status.getParent().layout(true, true);
		setValid(errors.isEmpty());
		return errors.isEmpty();
	}

	/** The system to read a package from: the selected one, else the first logged-on one. */
	private AdtSystem deriveSystem() {
		int i = scope.getSelectionIndex();
		if (i > 0 && systems.get(i - 1).loggedOn()) {
			return systems.get(i - 1);
		}
		return systems.stream().filter(AdtSystem::loggedOn).findFirst().orElse(null);
	}

	private void derive() {
		AdtSystem system = deriveSystem();
		AdtBackend adt = BellaPlugin.getDefault().adt();
		if (system == null || adt == null) {
			status.setText(Messages.get("conv.derive.noSystem"));
			status.getParent().layout(true, true);
			return;
		}
		InputDialog ask = new InputDialog(getShell(), Messages.get("conv.derive.title"),
				Messages.get("conv.derive.message"), "", v -> v == null || v.isBlank() ? "" : null);
		if (ask.open() != Window.OK) {
			return;
		}
		String pkg = ask.getValue().trim().toUpperCase(Locale.ROOT);
		BellaPlugin plugin = BellaPlugin.getDefault();
		String model = plugin.chatModel();
		var settings = plugin.chatSettings();
		var prompts = plugin.prompts();
		CancelToken cancel = new CancelToken();
		derive.setEnabled(false);
		status.setText(Messages.fmt("conv.derive.running", pkg));
		status.getParent().layout(true, true);
		Job job = new Job(Messages.fmt("conv.derive.running", pkg)) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				String message;
				ConventionsProposal proposal = null;
				try {
					AdtClient client = new AdtClient(adt.stateless(system.destinationId()));
					List<AdtObjectRef> objects = client.packageContents(pkg, ConventionsProposal.MAX_OBJECTS, cancel);
					if (objects.isEmpty()) {
						message = Messages.fmt("conv.derive.empty", pkg);
					} else {
						Prompt p = prompts.deriveConventions(pkg, ConventionsProposal.objectList(objects),
								ConventionsProposal.samples(client, objects, cancel));
						ChatRequest request = new ChatRequest(model, p.system(), List.of(Json.userText(p.user())),
								List.of(), settings.maxTokens(), ChatRequest.Purpose.CHAT, settings.effort());
						ChatResult r = plugin.provider().chat(request, StreamListener.NONE, cancel);
						proposal = ConventionsProposal.parse(r.text());
						message = Messages.fmt("conv.derive.done", pkg);
					}
				} catch (Exception e) {
					Log.warn("prefs", "deriving conventions from " + pkg + " failed: " + e);
					message = Messages.fmt("conv.derive.failed", String.valueOf(e.getMessage()));
				}
				String m = message;
				ConventionsProposal result = proposal;
				Display.getDefault().asyncExec(() -> {
					if (text.isDisposed()) {
						return;
					}
					if (result != null) {
						if (!result.text().isBlank()) {
							text.setText(result.text());
						}
						if (!result.naming().isBlank()) {
							naming.setText(result.naming());
						}
					}
					derive.setEnabled(true);
					validate();
					status.setText(status.getText().isBlank() ? m : status.getText() + "\n" + m);
					status.getParent().layout(true, true);
				});
				return Status.OK_STATUS;
			}

			@Override
			protected void canceling() {
				cancel.cancel();
			}
		};
		job.setUser(true);
		job.schedule();
	}

	@Override
	protected void performDefaults() {
		text.setText("");
		naming.setText("");
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		if (!validate()) {
			return false;
		}
		edits.put(current, new String[] { text.getText(), naming.getText() });
		for (Map.Entry<String, String[]> e : edits.entrySet()) {
			getPreferenceStore().setValue(Prefs.CONVENTIONS_TEXT + e.getKey(), e.getValue()[0]);
			getPreferenceStore().setValue(Prefs.CONVENTIONS_NAMING + e.getKey(), e.getValue()[1]);
		}
		return super.performOk();
	}
}

package de.kiliantaubmann.bella.ui.handlers;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;

/**
 * "Check with ATC and fix…": runs the ATC check on the object of the editor,
 * lists the findings and lets Bella fix the ticked ones in the editor buffer
 * (after the diff preview; nothing is saved or activated).
 */
public class AtcFixHandler extends EditorHandler {

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		BellaPlugin plugin = BellaPlugin.getDefault();
		AdtBackend adt = plugin.adt();
		Optional<AdtEditorObject> obj = adt == null ? Optional.empty() : EditorBridge.adtObject(part);
		if (obj.isEmpty() || !loggedOn(adt, obj.get().destinationId())) {
			MessageDialog.openInformation(part.getSite().getShell(), Messages.get("app.name"),
					Messages.get("atc.noAdt"));
			return;
		}
		if (editor.isDirty()) {
			if (!MessageDialog.openQuestion(part.getSite().getShell(), Messages.get("atc.title"),
					Messages.fmt("atc.saveFirst", obj.get().name()))) {
				return;
			}
			editor.doSave(new NullProgressMonitor());
			if (editor.isDirty()) {
				return;
			}
		}
		check(part, editor, adt, obj.get());
	}

	private static boolean loggedOn(AdtBackend adt, String destinationId) {
		try {
			return adt.systems().stream().anyMatch(s -> s.destinationId().equals(destinationId) && s.loggedOn());
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static void check(IEditorPart part, ITextEditor editor, AdtBackend adt, AdtEditorObject obj) {
		CancelToken cancel = new CancelToken();
		Job job = new Job(Messages.fmt("atc.running", obj.name())) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					List<AdtClient.Message> findings = new AdtClient(adt.stateless(obj.destinationId()))
							.atcVariant(BellaPlugin.getDefault().atcVariant(obj.destinationId()))
							.atcCheck(AdtObjectRef.objectUri(obj.uri()), null, cancel);
					Log.info("editor", "ATC on " + obj.name() + ": " + findings.size() + " findings");
					Display.getDefault().asyncExec(() -> show(part, editor, obj, findings));
				} catch (Exception e) {
					Log.warn("editor", "ATC on " + obj.name() + " failed: " + e);
					Display.getDefault().asyncExec(() -> MessageDialog.openError(part.getSite().getShell(),
							Messages.get("atc.title"), Messages.fmt("atc.failed", String.valueOf(e.getMessage())) + "\n\n"
									+ Messages.get("atc.failed.hint")));
				}
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

	private static void show(IEditorPart part, ITextEditor editor, AdtEditorObject obj, List<AdtClient.Message> findings) {
		if (findings.isEmpty()) {
			MessageDialog.openInformation(part.getSite().getShell(), Messages.get("atc.title"),
					Messages.fmt("atc.none", obj.name()));
			return;
		}
		List<AdtClient.Message> chosen = AtcFindingsDialog.choose(part.getSite().getShell(), editor, obj.name(),
				findings);
		if (chosen.isEmpty()) {
			return;
		}
		EditorContext ctx = EditorBridge.context(part, editor);
		List<String> lines = chosen.stream().map(AdtClient.Message::format).toList();
		generateInto(part, editor, BellaPlugin.getDefault().prompts().fixAtcFindings(ctx, lines),
				CodeActions.Target.DOCUMENT, ctx.source(), null);
	}
}

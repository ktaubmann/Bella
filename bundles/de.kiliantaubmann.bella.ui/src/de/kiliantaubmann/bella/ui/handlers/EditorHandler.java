package de.kiliantaubmann.bella.ui.handlers;

import java.util.List;
import java.util.Optional;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.prompt.Prompt;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Markdown;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;

/** Base for commands that work on the active text editor. */
abstract class EditorHandler extends AbstractHandler {

	@Override
	public final Object execute(ExecutionEvent event) {
		IEditorPart part = HandlerUtil.getActiveEditor(event);
		Optional<ITextEditor> editor = EditorBridge.textEditor(part);
		if (editor.isEmpty()) {
			MessageDialog.openInformation(HandlerUtil.getActiveShell(event), Messages.get("app.name"),
					Messages.get("editor.none"));
			return null;
		}
		execute(part, editor.get(), EditorBridge.context(part, editor.get()));
		return null;
	}

	protected abstract void execute(IEditorPart part, ITextEditor editor, EditorContext ctx);

	/**
	 * Runs a single request without tools in the background and puts the code
	 * of the answer into the editor (after the diff preview).
	 */
	protected static void generateInto(IEditorPart part, ITextEditor editor, Prompt prompt,
			CodeActions.Target target) {
		BellaPlugin plugin = BellaPlugin.getDefault();
		ChatRequest request = new ChatRequest(plugin.chatModel(), prompt.system(), List.of(Json.userText(prompt.user())),
				List.of(), plugin.chatSettings().maxTokens(), ChatRequest.Purpose.CHAT, plugin.chatSettings().effort());
		CancelToken cancel = new CancelToken();
		Job job = new Job(Messages.get("generate.jobName")) {
			@Override
			protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor) {
				Thread watcher = new Thread(() -> {
					while (!cancel.isCancelled() && getState() == RUNNING) {
						if (monitor.isCanceled()) {
							cancel.cancel();
						}
						try {
							Thread.sleep(200);
						} catch (InterruptedException e) {
							return;
						}
					}
				}, "bella-cancel-watch");
				watcher.setDaemon(true);
				watcher.start();
				try {
					ChatResult r = plugin.provider().chat(request, StreamListener.NONE, cancel);
					if (r.stopReason() == StopReason.REFUSAL) {
						error(part, Messages.fmt("chat.notice.refusal", r.stopDetail() == null ? "" : r.stopDetail()));
						return Status.OK_STATUS;
					}
					String code = Markdown.firstCodeBlock(r.text());
					if (code.isBlank()) {
						error(part, Messages.get("generate.empty"));
						return Status.OK_STATUS;
					}
					Display.getDefault().asyncExec(() -> CodeActions.apply(part, editor, target, code));
				} catch (CancelToken.CancelledException e) {
					return Status.CANCEL_STATUS;
				} catch (Exception e) {
					error(part, e.getMessage());
				} finally {
					cancel.cancel();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private static void error(IEditorPart part, String message) {
		Display.getDefault().asyncExec(
				() -> MessageDialog.openError(part.getSite().getShell(), Messages.get("app.name"), message));
	}

	protected static List<String> presets(String prefix, int count) {
		java.util.ArrayList<String> list = new java.util.ArrayList<>();
		for (int i = 1; i <= count; i++) {
			list.add(Messages.get(prefix + i));
		}
		return list;
	}
}

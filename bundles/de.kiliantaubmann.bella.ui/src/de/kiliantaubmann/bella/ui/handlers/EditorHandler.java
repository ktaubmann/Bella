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

import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtContext;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtSystemInfo;
import de.kiliantaubmann.bella.core.conventions.NamingRules;
import de.kiliantaubmann.bella.core.lint.AbapLint;
import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StopReason;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.prompt.Prompt;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.core.util.Markdown;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.editor.CodeActions;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.prefs.Prefs;

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
	 * of the answer into the editor (after the diff preview). Before the
	 * request, the definitions of the SAP objects that {@code contextCode} and
	 * the instruction mention are loaded through ADT and added to the prompt;
	 * the generated code goes through Bella's style check.
	 *
	 * @param contextCode code whose referenced objects matter, may be empty
	 * @param instruction the developer's instruction, may be {@code null}
	 */
	protected static void generateInto(IEditorPart part, ITextEditor editor, Prompt prompt,
			CodeActions.Target target, String contextCode, String instruction) {
		BellaPlugin plugin = BellaPlugin.getDefault();
		SapContext sap = SapContext.of(part, contextCode, instruction);
		String objectName = EditorBridge.objectName(part);
		Log.info("editor", target + " on " + objectName + ", SAP definitions "
				+ (sap == null ? "off (no ADT object, not logged on or switched off)" : "on"));
		Log.debug("editor", () -> "instruction: " + instruction);
		String model = plugin.chatModel();
		var settings = plugin.chatSettings();
		NamingRules naming = plugin.conventions(EditorBridge.adtObject(part).map(AdtEditorObject::destinationId)
				.orElse(null)).naming();
		CancelToken cancel = new CancelToken();
		CodeActions.Anchor anchor = CodeActions.Anchor.of(editor);
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
					Prompt p = prompt;
					List<String> used = List.of();
					if (sap != null) {
						monitor.subTask(Messages.get("generate.loadingDefinitions"));
						AdtContext.Result defs = sap.load(cancel);
						if (defs != null && !defs.isEmpty()) {
							p = AbapPrompts.withDefinitions(p, defs.text());
							used = defs.used();
						}
						p = AbapPrompts.withSystem(p, sap.system(cancel));
						monitor.subTask(Messages.get("generate.jobName"));
					}
					ChatRequest request = new ChatRequest(model, p.system(), List.of(Json.userText(p.user())),
							List.of(), settings.maxTokens(), ChatRequest.Purpose.CHAT, settings.effort());
					ChatResult r = plugin.provider().chat(request, StreamListener.NONE, cancel);
					if (r.stopReason() == StopReason.REFUSAL) {
						error(part, Messages.fmt("chat.notice.refusal", r.stopDetail() == null ? "" : r.stopDetail()));
						return Status.OK_STATUS;
					}
					String code = Markdown.firstCodeBlock(r.text());
					if (code.isBlank()) {
						Log.warn("editor", "answer without a code block: " + Log.clip(r.text(), 500));
						error(part, Messages.get("generate.empty"));
						return Status.OK_STATUS;
					}
					List<AbapLint.Finding> findings = AbapLint.check(code, naming);
					Log.info("editor", "proposal for " + objectName + ": " + code.length() + " chars, "
							+ findings.size() + " style findings, definitions " + used);
					String notes = CodeActions.previewNotes(used, findings);
					Display.getDefault().asyncExec(() -> CodeActions.apply(part, editor, target, code, notes, anchor));
				} catch (CancelToken.CancelledException e) {
					return Status.CANCEL_STATUS;
				} catch (Exception e) {
					Log.warn("editor", target + " on " + objectName + " failed: " + e);
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

	/** Where to load SAP definitions from; captured on the UI thread, used in the job. */
	record SapContext(AdtBackend adt, String destinationId, String objectName, String code, String instruction) {

		/** {@code null} when switched off, ADT is missing, or the editor holds no ADT object of a logged-on system. */
		static SapContext of(IEditorPart part, String code, String instruction) {
			BellaPlugin plugin = BellaPlugin.getDefault();
			AdtBackend adt = plugin.adt();
			if (adt == null || !plugin.prefs().getBoolean(Prefs.EDITOR_SAP_CONTEXT)) {
				return null;
			}
			Optional<AdtEditorObject> obj = EditorBridge.adtObject(part);
			if (obj.isEmpty()) {
				return null;
			}
			String dest = obj.get().destinationId();
			try {
				boolean loggedOn = adt.systems().stream()
						.anyMatch(s -> s.destinationId().equals(dest) && s.loggedOn());
				return loggedOn ? new SapContext(adt, dest, obj.get().name(), code == null ? "" : code, instruction)
						: null;
			} catch (RuntimeException | LinkageError e) {
				return null;
			}
		}

		/** Release of the editor's system, e.g. "SAP_BASIS 758, on-premise"; {@code null} if unknown. */
		String system(CancelToken cancel) {
			try {
				return AdtSystemInfo.of(destinationId, new AdtClient(adt.stateless(destinationId)), cancel)
						.map(AdtClient.SystemInfo::describe).orElse(null);
			} catch (RuntimeException | LinkageError e) {
				return null;
			}
		}

		/** Loads the definitions; {@code null} when that fails, the action then runs without them. */
		AdtContext.Result load(CancelToken cancel) throws CancelToken.CancelledException {
			try {
				// the object being edited is in the editor already
				List<AbapReferences.Reference> candidates = AdtContext.candidates(code, instruction).stream()
						.filter(r -> !r.name().equalsIgnoreCase(objectName)).toList();
				if (candidates.isEmpty()) {
					return null;
				}
				return AdtContext.build(new AdtClient(adt.stateless(destinationId)), candidates,
						AdtContext.Limits.DEFAULT, cancel);
			} catch (RuntimeException | LinkageError e) {
				BellaPlugin.log("Cannot load SAP definitions", e);
				return null;
			}
		}
	}

	private static void error(IEditorPart part, String message) {
		Display.getDefault().asyncExec(
				() -> MessageDialog.openError(part.getSite().getShell(), Messages.get("app.name"), message));
	}

	/** Characters around the cursor whose referenced objects count when no routine surrounds it. */
	private static final int AROUND_CURSOR = 2_000;

	/** The routine around the cursor, or the code near it. */
	protected static String codeAround(EditorContext ctx) {
		String src = ctx.source();
		int offset = ctx.selectionOffset();
		return AbapStructureScanner.routineAt(src, offset).map(b -> src.substring(b.start(), b.end()))
				.orElseGet(() -> src.substring(Math.max(0, offset - AROUND_CURSOR),
						Math.min(src.length(), offset + AROUND_CURSOR)));
	}

	protected static List<String> presets(String prefix, int count) {
		java.util.ArrayList<String> list = new java.util.ArrayList<>();
		for (int i = 1; i <= count; i++) {
			list.add(Messages.get(prefix + i));
		}
		return list;
	}
}

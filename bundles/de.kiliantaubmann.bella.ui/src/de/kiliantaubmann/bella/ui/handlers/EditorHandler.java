package de.kiliantaubmann.bella.ui.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.kiliantaubmann.bella.core.abap.AbapReferences;
import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtClient;
import de.kiliantaubmann.bella.core.adt.AdtContext;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtObjectRef;
import de.kiliantaubmann.bella.core.adt.AdtSystemInfo;
import de.kiliantaubmann.bella.core.adt.AdtTextSymbols;
import de.kiliantaubmann.bella.core.adt.AdtTransport;
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
	 * the generated code goes through Bella's style check. Text symbols the
	 * code names as {@code 'Text'(001)} and the text pool lacks are added to
	 * it once the developer applies and saves the code.
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
				+ (sap == null ? "off (no ADT object or not logged on)"
						: sap.definitions() ? "on" : "off (switched off)"));
		if (instruction != null && !instruction.isBlank()) {
			Log.debug("editor", () -> "instruction: " + instruction);
		}
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
					String definitionsError = null;
					String pool = sap == null ? null : sap.textSymbols(cancel);
					if (pool != null) {
						p = AbapPrompts.withTextSymbols(p, sap.objectName(), pool);
					}
					if (sap != null && sap.definitions()) {
						monitor.subTask(Messages.get("generate.loadingDefinitions"));
						AdtContext.Result defs = sap.load(cancel);
						if (defs != null && !defs.isEmpty()) {
							p = AbapPrompts.withDefinitions(p, defs.text());
							used = defs.used();
						}
						if (defs != null && defs.error() != null) {
							definitionsError = defs.error();
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
					if (r.stopReason() == StopReason.MAX_TOKENS) {
						// the code is cut off; putting it in would delete the rest of the source
						Log.warn("editor", target + " on " + objectName + ": answer cut off at " + settings.maxTokens()
								+ " output tokens");
						error(part, Messages.fmt("generate.truncated", String.valueOf(settings.maxTokens())));
						return Status.OK_STATUS;
					}
					String code = Markdown.firstCodeBlock(r.text());
					if (code.isBlank()) {
						Log.warn("editor", "answer without a code block: " + Log.clip(r.text(), 500));
						error(part, Messages.get("generate.empty"));
						return Status.OK_STATUS;
					}
					AbapLint.Target lintTarget = sap == null || !sap.definitions() ? AbapLint.Target.UNKNOWN
							: sap.lintTarget(cancel);
					List<AbapLint.Finding> findings = AbapLint.check(code, naming, lintTarget);
					List<String> syntax = sap == null ? List.of() : sap.newSyntaxErrors(anchor, target, code, cancel);
					Log.info("editor", "proposal for " + objectName + ": " + code.length() + " chars, "
							+ findings.size() + " style findings, " + syntax.size() + " new syntax errors, definitions "
							+ used);
					List<String> problems = problems(findings, syntax);
					if (!problems.isEmpty()) {
						// one correction round, as the chat does after a write: the model sees what the checks found
						monitor.subTask(Messages.get("generate.correcting"));
						JsonObject answer = r.assistantMessage() != null ? r.assistantMessage()
								: Json.message("assistant", textContent(r.text()));
						ChatRequest again = new ChatRequest(model, p.system(), List.of(Json.userText(p.user()), answer,
								Json.userText(plugin.prompts().correctCode(problems))), List.of(), settings.maxTokens(),
								ChatRequest.Purpose.CHAT, settings.effort());
						ChatResult r2 = plugin.provider().chat(again, StreamListener.NONE, cancel);
						String corrected = r2.stopReason() == StopReason.REFUSAL || r2.stopReason() == StopReason.MAX_TOKENS
								? ""
								: Markdown.firstCodeBlock(r2.text());
						if (corrected.isBlank()) {
							Log.warn("editor", "correction without a code block, keeping the first proposal: "
									+ Log.clip(r2.text(), 300));
						} else {
							code = corrected;
							findings = AbapLint.check(code, naming, lintTarget);
							syntax = sap == null ? List.of() : sap.newSyntaxErrors(anchor, target, code, cancel);
							Log.info("editor", "corrected proposal for " + objectName + ": " + code.length()
									+ " chars, " + findings.size() + " style findings, " + syntax.size()
									+ " new syntax errors");
						}
					}
					String finalCode = code;
					AdtTextSymbols.Plan symbols = pool == null ? null : AdtTextSymbols.plan(finalCode, pool);
					String notes = CodeActions.previewNotes(used, definitionsError, findings, syntax)
							+ symbolNotes(symbols);
					Display.getDefault().asyncExec(() -> {
						if (CodeActions.apply(part, editor, target, finalCode, notes, anchor) && sap != null) {
							sap.afterSave(part, editor, symbols == null ? Map.of() : symbols.missing());
						}
					});
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

	/**
	 * What the correction round sends back: style findings that are errors or
	 * warnings, and the syntax errors the code adds. Information-level style
	 * hints only go into the diff preview.
	 */
	static List<String> problems(List<AbapLint.Finding> findings, List<String> syntaxErrors) {
		List<String> out = new ArrayList<>();
		syntaxErrors.forEach(s -> out.add("Syntax check: " + s));
		findings.stream().filter(f -> f.severity() != AbapLint.Severity.INFO)
				.forEach(f -> out.add("Style check: " + f.format()));
		return out;
	}

	private static JsonArray textContent(String text) {
		JsonArray content = new JsonArray();
		content.add(Json.textBlock(text == null ? "" : text));
		return content;
	}

	/** Text for the diff preview: which text symbols are added to the pool, and which differ from it. */
	static String symbolNotes(AdtTextSymbols.Plan symbols) {
		if (symbols == null || symbols.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		if (!symbols.missing().isEmpty()) {
			sb.append(Messages.get("diff.textSymbols.add")).append('\n');
			symbols.missing().forEach((id, text) -> sb.append("  ").append(id).append(" = ").append(text).append('\n'));
		}
		if (!symbols.differing().isEmpty()) {
			sb.append(Messages.get("diff.textSymbols.differ")).append('\n');
			symbols.differing().forEach((id, text) -> sb.append("  ").append(id).append(" = ").append(text).append('\n'));
		}
		return sb.toString();
	}

	/**
	 * The editor's ADT object; captured on the UI thread, used in the job.
	 *
	 * @param definitions whether to load SAP definitions (preference)
	 */
	record SapContext(AdtBackend adt, String destinationId, String objectName, String objectUri, String objectType,
			String code, String instruction, boolean definitions) {

		/** {@code null} when ADT is missing or the editor holds no ADT object of a logged-on system. */
		static SapContext of(IEditorPart part, String code, String instruction) {
			BellaPlugin plugin = BellaPlugin.getDefault();
			AdtBackend adt = plugin.adt();
			if (adt == null) {
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
				return loggedOn ? new SapContext(adt, dest, obj.get().name(), obj.get().uri(), obj.get().type(),
						code == null ? "" : code, instruction, plugin.prefs().getBoolean(Prefs.EDITOR_SAP_CONTEXT))
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

		/** Release and cloud flag of the editor's system for the style check; unknown if the system does not tell. */
		AbapLint.Target lintTarget(CancelToken cancel) {
			try {
				return AdtSystemInfo.of(destinationId, new AdtClient(adt.stateless(destinationId)), cancel)
						.map(AdtClient.SystemInfo::lintTarget).orElse(AbapLint.Target.UNKNOWN);
			} catch (RuntimeException | LinkageError e) {
				return AbapLint.Target.UNKNOWN;
			}
		}

		/** The text pool the edited object uses; empty when it has none Bella can tell. */
		private Optional<AdtTextSymbols.Pool> pool() {
			return AdtTextSymbols.poolOf(new AdtEditorObject(destinationId, objectUri, objectName, objectType));
		}

		/**
		 * The text symbols of the pool the object uses; {@code null} when it
		 * has none Bella can tell or reading it fails, text symbols are then
		 * left alone.
		 */
		String textSymbols(CancelToken cancel) {
			Optional<AdtTextSymbols.Pool> pool = pool();
			if (pool.isEmpty()) {
				return null;
			}
			try {
				return AdtTextSymbols.read(new AdtClient(adt.stateless(destinationId)), pool.get(), cancel);
			} catch (java.io.IOException | RuntimeException | LinkageError e) {
				Log.info("editor", "Cannot read the text symbols of " + pool.get().name() + ": " + e.getMessage());
				return null;
			}
		}

		/**
		 * Once the developer saved the editor with the applied code: adds the
		 * missing text symbols to the pool, then runs ATC on the object and
		 * offers to fix priority 1 and 2 findings. Text symbols written while
		 * the editor holds unsaved changes count as a change of the program in
		 * the backend, and ADT then reports a version conflict on the save; ATC
		 * checks the saved version only. UI thread.
		 */
		void afterSave(IEditorPart part, ITextEditor editor, Map<String, String> missing) {
			if (!part.isDirty()) {
				runAfterSave(part, editor, missing);
				return;
			}
			Log.info("editor", (missing.isEmpty() ? "" : "text symbols " + missing.keySet() + " and ")
					+ "ATC of " + objectName + " wait for the save");
			part.addPropertyListener(new org.eclipse.ui.IPropertyListener() {
				@Override
				public void propertyChanged(Object source, int property) {
					if (property == IEditorPart.PROP_DIRTY && !part.isDirty()) {
						part.removePropertyListener(this);
						runAfterSave(part, editor, missing);
					}
				}
			});
		}

		/** Text symbols first, so ATC does not report them as missing; failures are shown to the developer. */
		private void runAfterSave(IEditorPart part, ITextEditor editor, Map<String, String> missing) {
			CancelToken cancel = new CancelToken();
			Job job = new Job(Messages.fmt("atc.running", objectName)) {
				@Override
				protected org.eclipse.core.runtime.IStatus run(IProgressMonitor monitor) {
					if (!missing.isEmpty()) {
						writeTextSymbols(part, missing);
					}
					AdtEditorObject obj = new AdtEditorObject(destinationId, objectUri, objectName, objectType);
					try {
						AdtClient client = new AdtClient(adt.stateless(destinationId), null, destinationId)
								.atcVariant(BellaPlugin.getDefault().atcVariant(destinationId));
						List<AdtClient.Message> findings = client.atcCheck(AdtObjectRef.objectUri(objectUri), null,
								cancel).stream().filter(m -> m.severity().equals("Error") || m.severity().equals("Warning"))
								.toList();
						Log.info("editor", "ATC after save of " + objectName + ": " + findings.size()
								+ " priority 1/2 findings");
						if (!findings.isEmpty()) {
							Display.getDefault().asyncExec(() -> AtcFixHandler.show(part, editor, obj, findings));
						}
					} catch (java.io.IOException | RuntimeException | LinkageError e) {
						// the developer did not ask for this run; a failure only goes to the log
						Log.warn("editor", "ATC after save of " + objectName + " failed: " + e);
					}
					return Status.OK_STATUS;
				}

				@Override
				protected void canceling() {
					cancel.cancel();
				}
			};
			job.schedule();
		}

		/** Adds text symbols to the pool; a failure is shown to the developer. Background thread. */
		private void writeTextSymbols(IEditorPart part, Map<String, String> missing) {
			try (AdtTransport.Session session = adt.stateful(destinationId)) {
				String tr = AdtTextSymbols.add(session, new AdtClient(adt.stateless(destinationId)),
						pool().orElseThrow(), missing, CancelToken.NONE);
				Log.info("editor", "text symbols " + missing.keySet() + " added to " + objectName
						+ (tr.isEmpty() ? "" : " (transport " + tr + ")"));
			} catch (java.io.IOException | RuntimeException | LinkageError e) {
				Log.warn("editor", "text symbols " + missing.keySet() + " of " + objectName + " not added: " + e);
				StringBuilder texts = new StringBuilder();
				missing.forEach((id, text) -> texts.append('\n').append(id).append(" = ").append(text));
				error(part, Messages.fmt("textSymbols.failed", objectName, e.getMessage(), texts.toString()));
			}
		}

		/** Whether the editor shows the object's main source, which the syntax check takes as unsaved code. */
		boolean mainSource() {
			if (objectUri == null || objectUri.isBlank()) {
				return false;
			}
			int hash = objectUri.indexOf('#');
			String u = hash < 0 ? objectUri : objectUri.substring(0, hash);
			String obj = AdtObjectRef.objectUri(u);
			return u.equals(obj) || u.equals(obj + "/source/main");
		}

		/**
		 * Syntax errors the code adds to the object once it is put in, each with
		 * the line of code; empty when the code adds none, when the editor shows
		 * an include or when the check fails.
		 */
		List<String> newSyntaxErrors(CodeActions.Anchor anchor, CodeActions.Target target, String code,
				CancelToken cancel) {
			if (anchor == null || !mainSource()) {
				return List.of();
			}
			Optional<String> after = CodeActions.after(anchor, target, code);
			if (after.isEmpty()) {
				return List.of();
			}
			try {
				AdtClient c = new AdtClient(adt.stateless(destinationId));
				String obj = AdtObjectRef.objectUri(objectUri);
				List<AdtClient.Message> now = errors(c.syntaxCheck(obj, after.get(), cancel));
				if (now.isEmpty()) {
					return List.of();
				}
				// errors the object already had are not the code's
				Map<String, Integer> old = new HashMap<>();
				errors(c.syntaxCheck(obj, anchor.source(), cancel)).forEach(m -> old.merge(m.text(), 1, Integer::sum));
				String[] lines = after.get().replace("\r\n", "\n").split("\n", -1);
				return now.stream().filter(m -> old.merge(m.text(), -1, Integer::sum) < 0)
						.map(m -> m.format() + (m.include().isEmpty() && m.line() > 0 && m.line() <= lines.length
								? " (code: " + lines[m.line() - 1].strip() + ")"
								: ""))
						.toList();
			} catch (java.io.IOException | RuntimeException | LinkageError e) {
				Log.info("editor", "Syntax check of the proposal for " + objectName + " failed: " + e.getMessage());
				return List.of();
			}
		}

		private static List<AdtClient.Message> errors(List<AdtClient.Message> msgs) {
			return msgs.stream().filter(m -> m.severity().equals("Error")).toList();
		}

		/**
		 * Loads the definitions; {@code null} when that fails, the action then
		 * runs without them. Customer objects of other packages than the edited
		 * object's are left out; when that package cannot be read, none are.
		 */
		AdtContext.Result load(CancelToken cancel) throws CancelToken.CancelledException {
			try {
				// the object being edited is in the editor already
				List<AbapReferences.Reference> candidates = AdtContext.candidates(code, instruction).stream()
						.filter(r -> !r.name().equalsIgnoreCase(objectName)).toList();
				if (candidates.isEmpty()) {
					return null;
				}
				AdtClient client = new AdtClient(adt.stateless(destinationId));
				return AdtContext.build(client, candidates, AdtContext.Limits.DEFAULT, inOwnPackage(client, cancel),
						cancel);
			} catch (RuntimeException | LinkageError e) {
				BellaPlugin.log("Cannot load SAP definitions", e);
				return null;
			}
		}

		/** Customer objects of the edited object's package; all objects when its package is unknown. */
		private java.util.function.Predicate<AdtObjectRef> inOwnPackage(AdtClient client, CancelToken cancel) {
			try {
				// an include's URI has no package of its own; its object has
				String pkg = client.packageOf(AdtObjectRef.objectUri(objectUri), cancel);
				if (!pkg.isEmpty()) {
					return AdtContext.inPackage(client, pkg, cancel);
				}
				Log.info("editor", "No package for " + objectUri + "; SAP definitions are not limited to it");
			} catch (java.io.IOException | RuntimeException e) {
				Log.info("editor", "Cannot read the package of " + objectUri + " (" + e.getMessage()
						+ "); SAP definitions are not limited to it");
			}
			return r -> true;
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

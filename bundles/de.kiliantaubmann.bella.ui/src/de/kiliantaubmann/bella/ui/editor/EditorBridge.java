package de.kiliantaubmann.bella.ui.editor;

import java.util.Locale;
import java.util.Optional;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.adt.AdtBackend;
import de.kiliantaubmann.bella.core.adt.AdtEditorObject;
import de.kiliantaubmann.bella.core.adt.AdtSystem;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.ui.BellaPlugin;

/**
 * Reads from and writes into text editors, including ADT's ABAP editors.
 * Writes only change the editor buffer: nothing is saved or activated, and
 * each change is a single undoable step.
 */
public final class EditorBridge {

	private EditorBridge() {
	}

	/** The active text editor, looking into multi-page editors (ADT class editor tabs). */
	public static Optional<ITextEditor> activeTextEditor() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null) {
			return Optional.empty();
		}
		IWorkbenchPage page = window.getActivePage();
		return page == null ? Optional.empty() : textEditor(page.getActiveEditor());
	}

	public static Optional<ITextEditor> textEditor(IEditorPart part) {
		if (part == null) {
			return Optional.empty();
		}
		if (part instanceof MultiPageEditorPart multi && multi.getSelectedPage() instanceof ITextEditor te) {
			return Optional.of(te);
		}
		if (part instanceof ITextEditor te) {
			return Optional.of(te);
		}
		return Optional.ofNullable(Adapters.adapt(part, ITextEditor.class));
	}

	public static IDocument document(ITextEditor editor) {
		IDocumentProvider provider = editor.getDocumentProvider();
		return provider == null ? null : provider.getDocument(editor.getEditorInput());
	}

	public static ITextSelection selection(ITextEditor editor) {
		ISelection sel = editor.getSelectionProvider().getSelection();
		return sel instanceof ITextSelection ts ? ts : null;
	}

	/**
	 * Replaces a range as one undoable change and selects the new text so the
	 * developer sees what Bella wrote. Does not save.
	 */
	public static void replace(ITextEditor editor, int offset, int length, String text) throws BadLocationException {
		if (!editor.isEditable()) {
			throw new BadLocationException(de.kiliantaubmann.bella.ui.Messages.get("editor.readOnly"));
		}
		IDocument doc = document(editor);
		IRewriteTarget target = editor.getAdapter(IRewriteTarget.class);
		if (target != null) {
			target.beginCompoundChange();
		}
		try {
			doc.replace(offset, length, text);
		} finally {
			if (target != null) {
				target.endCompoundChange();
			}
		}
		editor.selectAndReveal(offset, text.length());
		editor.setFocus();
	}

	public static void insert(ITextEditor editor, int offset, String text) throws BadLocationException {
		replace(editor, offset, 0, text);
	}

	/** The ADT object behind an editor, when ADT is installed. */
	public static Optional<AdtEditorObject> adtObject(IEditorPart part) {
		AdtBackend adt = BellaPlugin.getDefault().adt();
		if (adt == null || part == null) {
			return Optional.empty();
		}
		try {
			return adt.editorObject(part.getEditorInput());
		} catch (RuntimeException | LinkageError e) {
			return Optional.empty();
		}
	}

	/**
	 * Object name for an editor: from ADT when available, otherwise from the
	 * title, which ADT shows as {@code [SID] NAME}.
	 */
	public static String objectName(IEditorPart part) {
		Optional<AdtEditorObject> o = adtObject(part);
		if (o.isPresent()) {
			return o.get().name();
		}
		String title = part.getTitle() == null ? "" : part.getTitle().trim();
		if (title.startsWith("[") && title.indexOf(']') > 0) {
			title = title.substring(title.indexOf(']') + 1).trim();
		}
		int dot = title.lastIndexOf('.');
		if (dot > 0 && title.length() - dot <= 6) {
			title = title.substring(0, dot);
		}
		return title.toUpperCase(Locale.ROOT);
	}

	public static String systemLabel(IEditorPart part) {
		Optional<AdtEditorObject> o = adtObject(part);
		AdtBackend adt = BellaPlugin.getDefault().adt();
		if (o.isEmpty() || adt == null) {
			return null;
		}
		for (AdtSystem s : adt.systems()) {
			if (s.destinationId().equals(o.get().destinationId())) {
				return s.label();
			}
		}
		return null;
	}

	/** Snapshot of editor, object and selection for a prompt. Call on the UI thread. */
	public static EditorContext context(IEditorPart part, ITextEditor editor) {
		IDocument doc = document(editor);
		ITextSelection sel = selection(editor);
		String source = doc == null ? "" : doc.get();
		int offset = sel == null ? 0 : Math.max(0, Math.min(source.length(), sel.getOffset()));
		String selected = sel == null || sel.getText() == null ? "" : sel.getText();
		Optional<AdtEditorObject> o = adtObject(part);
		return new EditorContext(objectName(part), o.map(AdtEditorObject::type).orElse(null), systemLabel(part), source,
				selected, offset);
	}
}

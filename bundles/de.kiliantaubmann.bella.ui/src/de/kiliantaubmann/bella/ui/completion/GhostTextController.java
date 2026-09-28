package de.kiliantaubmann.bella.ui.completion;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.llm.ChatRequest;
import de.kiliantaubmann.bella.core.llm.ChatResult;
import de.kiliantaubmann.bella.core.llm.StreamListener;
import de.kiliantaubmann.bella.core.prompt.AbapPrompts;
import de.kiliantaubmann.bella.core.prompt.CompletionCleaner;
import de.kiliantaubmann.bella.core.prompt.Prompt;
import de.kiliantaubmann.bella.core.util.CancelToken;
import de.kiliantaubmann.bella.core.util.Json;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.prefs.Prefs;

/**
 * Inline AI completion shown as grey "ghost text" at the cursor. Tab accepts,
 * Esc or any other key dismisses. Accepting only inserts into the editor
 * buffer; nothing is saved.
 */
public final class GhostTextController implements PaintListener, VerifyKeyListener, CaretListener, IDocumentListener {

	private static final Map<ITextEditor, GhostTextController> CONTROLLERS = new WeakHashMap<>();
	private static final int MAX_PREFIX = 6000;
	private static final int MAX_SUFFIX = 2000;

	private final ITextEditor editor;
	private final ITextViewer viewer;
	private final StyledText text;
	private final IDocument document;
	private final String objectName;
	private final Color ghostColor;

	private String suggestion;
	private int modelOffset = -1;
	private int indentedLine = -1;
	private int previousIndent;
	private boolean applying;
	private long sequence;
	private long documentVersion;
	private CancelToken running = new CancelToken();

	private GhostTextController(ITextEditor editor, ITextViewer viewer, IEditorPart part) {
		this.editor = editor;
		this.viewer = viewer;
		this.text = viewer.getTextWidget();
		this.document = viewer.getDocument();
		this.objectName = EditorBridge.objectName(part);
		RGB fg = text.getForeground().getRGB();
		RGB bg = text.getBackground().getRGB();
		this.ghostColor = new Color((fg.red + bg.red * 2) / 3, (fg.green + bg.green * 2) / 3, (fg.blue + bg.blue * 2) / 3);
		text.addPaintListener(this);
		text.addCaretListener(this);
		if (viewer instanceof ITextViewerExtension ext) {
			ext.prependVerifyKeyListener(this);
		} else {
			text.addVerifyKeyListener(this);
		}
		document.addDocumentListener(this);
		text.addDisposeListener(e -> {
			document.removeDocumentListener(this);
			running.cancel();
			synchronized (CONTROLLERS) {
				CONTROLLERS.remove(editor);
			}
		});
	}

	/** Controller for an editor, installed on first use; {@code null} if the editor has no text viewer. */
	public static GhostTextController of(IEditorPart part, ITextEditor editor) {
		synchronized (CONTROLLERS) {
			GhostTextController c = CONTROLLERS.get(editor);
			if (c == null) {
				ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
				if (!(target instanceof ITextViewer v) || v.getTextWidget() == null || v.getDocument() == null) {
					return null;
				}
				c = new GhostTextController(editor, v, part);
				CONTROLLERS.put(editor, c);
			}
			return c;
		}
	}

	/** Installs automatically into ABAP editors so typing pauses can trigger completion. */
	public static void installIfAbap(IEditorPart part, ITextEditor editor) {
		String id = part.getSite() == null ? "" : String.valueOf(part.getSite().getId()).toLowerCase(Locale.ROOT);
		String name = part.getEditorInput() == null ? "" : part.getEditorInput().getName().toLowerCase(Locale.ROOT);
		if (id.contains("abap") || id.startsWith("com.sap.adt") || name.endsWith(".abap")) {
			of(part, editor);
		}
	}

	// ---- request --------------------------------------------------------------

	/** Asks the model for a completion at the cursor. UI thread. */
	public void trigger() {
		dismiss();
		if (!editor.isEditable()) {
			return;
		}
		int offset = modelOffset(text.getCaretOffset());
		if (offset < 0) {
			return;
		}
		String all = document.get();
		String prefix = all.substring(Math.max(0, offset - MAX_PREFIX), offset);
		String suffix = all.substring(offset, Math.min(all.length(), offset + MAX_SUFFIX));
		long seq = ++sequence;
		long version = documentVersion;
		running.cancel();
		CancelToken cancel = new CancelToken();
		running = cancel;
		BellaPlugin plugin = BellaPlugin.getDefault();
		Prompt prompt = AbapPrompts.completion(prefix, suffix, objectName);
		ChatRequest request = new ChatRequest(plugin.completionModel(), prompt.system(),
				List.of(Json.userText(prompt.user())), List.of(), 400, ChatRequest.Purpose.COMPLETION, null);
		Job job = Job.create("Bella completion", (IProgressMonitor monitor) -> {
			try {
				ChatResult r = plugin.provider().chat(request, StreamListener.NONE, cancel);
				String cleaned = CompletionCleaner.clean(r.text(), prefix, suffix);
				Display.getDefault().asyncExec(() -> {
					if (!text.isDisposed() && seq == sequence && version == documentVersion
							&& modelOffset(text.getCaretOffset()) == offset && !cleaned.isEmpty()) {
						show(cleaned, offset);
					}
				});
			} catch (CancelToken.CancelledException e) {
				return Status.CANCEL_STATUS;
			} catch (Exception e) {
				return new Status(IStatus.WARNING, BellaPlugin.ID, "Completion failed: " + e.getMessage());
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	// ---- display ----------------------------------------------------------------

	private void show(String s, int offset) {
		suggestion = s;
		modelOffset = offset;
		int lines = s.split("\n", -1).length;
		int widgetOffset = widgetOffset(offset);
		int line = text.getLineAtOffset(widgetOffset);
		if (lines > 1 && line + 1 < text.getLineCount()) {
			indentedLine = line + 1;
			previousIndent = text.getLineVerticalIndent(indentedLine);
			text.setLineVerticalIndent(indentedLine, previousIndent + (lines - 1) * text.getLineHeight());
		}
		text.redraw();
	}

	public boolean isShowing() {
		return suggestion != null;
	}

	public void dismiss() {
		running.cancel();
		if (suggestion == null) {
			return;
		}
		suggestion = null;
		if (indentedLine >= 0 && indentedLine < text.getLineCount()) {
			text.setLineVerticalIndent(indentedLine, previousIndent);
		}
		indentedLine = -1;
		if (!text.isDisposed()) {
			text.redraw();
		}
	}

	private void accept() {
		String s = suggestion;
		int offset = modelOffset;
		dismiss();
		IRewriteTarget target = editor.getAdapter(IRewriteTarget.class);
		applying = true;
		try {
			if (target != null) {
				target.beginCompoundChange();
			}
			document.replace(offset, 0, s);
			viewer.setSelectedRange(offset + s.length(), 0);
		} catch (BadLocationException e) {
			BellaPlugin.log("Cannot insert completion", e);
		} finally {
			if (target != null) {
				target.endCompoundChange();
			}
			applying = false;
		}
	}

	@Override
	public void paintControl(PaintEvent e) {
		if (suggestion == null) {
			return;
		}
		int widgetOffset = widgetOffset(modelOffset);
		if (widgetOffset < 0) {
			return;
		}
		Point p = text.getLocationAtOffset(widgetOffset);
		String[] lines = suggestion.split("\n", -1);
		e.gc.setForeground(ghostColor);
		e.gc.setFont(text.getFont());
		int lineHeight = text.getLineHeight();
		// The first line continues the current line; paint over what follows the cursor.
		Point first = e.gc.textExtent(lines[0], SWT.DRAW_TAB | SWT.DRAW_TRANSPARENT);
		e.gc.setBackground(text.getBackground());
		e.gc.fillRectangle(p.x, p.y, first.x, lineHeight);
		e.gc.drawText(lines[0], p.x, p.y, SWT.DRAW_TAB | SWT.DRAW_TRANSPARENT);
		int x = text.getLeftMargin() - text.getHorizontalPixel();
		for (int i = 1; i < lines.length; i++) {
			e.gc.drawText(lines[i], x, p.y + i * lineHeight, SWT.DRAW_TAB | SWT.DRAW_TRANSPARENT);
		}
	}

	// ---- input ------------------------------------------------------------------------

	@Override
	public void verifyKey(VerifyEvent e) {
		if (suggestion == null) {
			return;
		}
		if (e.keyCode == SWT.TAB && (e.stateMask & SWT.MODIFIER_MASK) == 0) {
			e.doit = false;
			accept();
		} else if (e.keyCode == SWT.ESC) {
			e.doit = false;
			dismiss();
		} else if (e.keyCode != SWT.SHIFT && e.keyCode != SWT.CTRL && e.keyCode != SWT.ALT) {
			dismiss();
		}
	}

	@Override
	public void caretMoved(CaretEvent event) {
		if (suggestion != null && modelOffset(event.caretOffset) != modelOffset) {
			dismiss();
		}
	}

	@Override
	public void documentAboutToBeChanged(DocumentEvent event) {
	}

	@Override
	public void documentChanged(DocumentEvent event) {
		documentVersion++;
		if (applying) {
			return;
		}
		dismiss();
		if (!BellaPlugin.getDefault().autoCompletion() || event.getText() == null
				|| event.getText().isEmpty() || event.getText().contains("\n")) {
			return;
		}
		long version = documentVersion;
		int delay = Math.max(200, BellaPlugin.getDefault().prefs().getInt(Prefs.AUTO_COMPLETION_DELAY));
		Display.getDefault().timerExec(delay, () -> {
			if (!text.isDisposed() && version == documentVersion && text.isFocusControl() && atEndOfLine()) {
				trigger();
			}
		});
	}

	private boolean atEndOfLine() {
		int offset = modelOffset(text.getCaretOffset());
		try {
			int line = document.getLineOfOffset(offset);
			int end = document.getLineOffset(line) + document.getLineLength(line);
			String rest = document.get(offset, end - offset);
			String before = document.get(document.getLineOffset(line), offset - document.getLineOffset(line));
			return rest.isBlank() && !before.isBlank();
		} catch (BadLocationException e) {
			return false;
		}
	}

	private int modelOffset(int widgetOffset) {
		return viewer instanceof ITextViewerExtension5 ext ? ext.widgetOffset2ModelOffset(widgetOffset) : widgetOffset;
	}

	private int widgetOffset(int modelOffset) {
		return viewer instanceof ITextViewerExtension5 ext ? ext.modelOffset2WidgetOffset(modelOffset) : modelOffset;
	}
}

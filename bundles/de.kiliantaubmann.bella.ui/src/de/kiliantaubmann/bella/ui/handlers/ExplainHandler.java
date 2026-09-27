package de.kiliantaubmann.bella.ui.handlers;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.abap.AbapStructureScanner;
import de.kiliantaubmann.bella.core.prompt.EditorContext;
import de.kiliantaubmann.bella.core.prompt.Prompt;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.views.ChatView;

/** "What happens here?": explains the selection (or the method at the cursor) in the chat. */
public class ExplainHandler extends EditorHandler {

	/** Chat action a subclass performs on the code. */
	protected enum Kind {
		EXPLAIN, REFACTOR, UNIT_TEST
	}

	protected Kind kind() {
		return Kind.EXPLAIN;
	}

	@Override
	protected void execute(IEditorPart part, ITextEditor editor, EditorContext ctx) {
		EditorContext c = withSelectionOrRoutine(ctx);
		var prompts = BellaPlugin.getDefault().prompts();
		Prompt prompt = switch (kind()) {
		case EXPLAIN -> prompts.explain(c);
		case REFACTOR -> prompts.suggestRefactoring(c);
		case UNIT_TEST -> prompts.suggestUnitTest(c);
		};
		String what = firstLine(c.selection());
		String display = Messages.fmt("chat.display." + kind().name().toLowerCase(), c.objectName(), what);
		ChatView.open().ifPresent(v -> v.ask(display, prompt.user()));
	}

	/** Without a selection, use the method around the cursor, or the whole source. */
	static EditorContext withSelectionOrRoutine(EditorContext ctx) {
		if (!ctx.selection().isBlank()) {
			return ctx;
		}
		String src = ctx.source();
		String selection = AbapStructureScanner.routineAt(src, ctx.selectionOffset())
				.map(b -> src.substring(b.start(), b.end())).orElse(src);
		return new EditorContext(ctx.objectName(), ctx.objectType(), ctx.system(), src, selection,
				ctx.selectionOffset());
	}

	private static String firstLine(String s) {
		String t = s.strip();
		int nl = t.indexOf('\n');
		String line = nl < 0 ? t : t.substring(0, nl) + " …";
		return line.length() > 80 ? line.substring(0, 80) + " …" : line;
	}
}

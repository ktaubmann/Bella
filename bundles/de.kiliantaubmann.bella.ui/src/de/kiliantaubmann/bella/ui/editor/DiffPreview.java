package de.kiliantaubmann.bella.ui.editor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.prefs.Prefs;

/** Side-by-side comparison of the current and the proposed code, with apply / discard. */
public final class DiffPreview extends TitleAreaDialog {

	private final String title;
	private final String before;
	private final String after;
	private final String notes;

	private DiffPreview(Shell shell, String title, String before, String after, String notes) {
		super(shell);
		this.title = title;
		this.before = before;
		this.after = after;
		this.notes = notes;
		setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
	}

	/**
	 * Shows the preview unless it is switched off. Must run on the UI thread.
	 *
	 * @return {@code true} if the change should be applied
	 */
	public static boolean confirm(Shell shell, String title, String before, String after) {
		return confirm(shell, title, before, after, null);
	}

	/** @param notes extra information above the comparison, e.g. definitions used and style findings */
	public static boolean confirm(Shell shell, String title, String before, String after, String notes) {
		if (!BellaPlugin.getDefault().prefs().getBoolean(Prefs.DIFF_PREVIEW)) {
			return true;
		}
		return new DiffPreview(shell, title, before, after, notes).open() == OK;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(Messages.get("diff.shellTitle"));
		shell.setImage(BellaPlugin.image(BellaPlugin.IMG_BELLA));
	}

	@Override
	protected Point getInitialSize() {
		return new Point(1100, 700);
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		setTitle(title);
		setMessage(Messages.get("diff.message"));
		Image logo = BellaPlugin.image(BellaPlugin.IMG_BELLA);
		if (logo != null) {
			setTitleImage(logo);
		}
		if (notes != null && !notes.isBlank()) {
			org.eclipse.swt.widgets.Text info = new org.eclipse.swt.widgets.Text(area,
					SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
			info.setText(notes.strip());
			GridData gd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
			gd.horizontalIndent = 5;
			gd.heightHint = Math.min(8, (int) notes.strip().lines().count() + 1) * info.getLineHeight();
			info.setLayoutData(gd);
		}
		CompareConfiguration cc = new CompareConfiguration();
		cc.setLeftLabel(Messages.get("diff.proposed"));
		cc.setRightLabel(Messages.get("diff.current"));
		cc.setLeftEditable(false);
		cc.setRightEditable(false);
		TextMergeViewer viewer = new TextMergeViewer(area, SWT.BORDER, cc);
		viewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		viewer.setInput(new DiffNode(new Text("proposed", after), new Text("current", before)));
		return area;
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, Messages.get("diff.apply"), true);
		createButton(parent, IDialogConstants.CANCEL_ID, Messages.get("diff.discard"), false);
	}

	private record Text(String name, String content) implements ITypedElement, IEncodedStreamContentAccessor {

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return ITypedElement.TEXT_TYPE;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public String getCharset() {
			return StandardCharsets.UTF_8.name();
		}
	}
}

package de.kiliantaubmann.bella.ui.views;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.ToolTip;

import de.kiliantaubmann.bella.core.agent.TurnUsage;
import de.kiliantaubmann.bella.core.llm.Usage;
import de.kiliantaubmann.bella.ui.Messages;

/**
 * How full the context window of the chat is, as a circle that fills
 * clockwise. The tooltip and a click show the tokens of the chat.
 */
public final class ContextGauge {

	/** Diameter in points; SWT scales it on HiDPI screens. */
	static final int SIZE = 14;
	/** From this share on the circle warns, from {@link #FULL} it is red. */
	static final int WARN = 80;
	static final int FULL = 95;

	/** Colors like the chat page: light and dark theme. */
	private static final RGB ACCENT = new RGB(0xe9, 0x1e, 0x63), ACCENT_DARK = new RGB(0xf0, 0x62, 0x92);
	private static final RGB WARN_RGB = new RGB(0x9a, 0x67, 0x00), WARN_DARK = new RGB(0xc6, 0x90, 0x26);
	private static final RGB FULL_RGB = new RGB(0xcf, 0x22, 0x2e), FULL_DARK = new RGB(0xf4, 0x70, 0x67);

	private final Canvas canvas;
	private Usage chat = Usage.NONE;
	private TurnUsage last;
	private ToolTip tip;

	ContextGauge(Composite parent) {
		canvas = new Canvas(parent, SWT.DOUBLE_BUFFERED);
		GridDataFactory.swtDefaults().align(SWT.CENTER, SWT.CENTER).hint(SIZE + 4, SIZE + 4).applyTo(canvas);
		canvas.addListener(SWT.Paint, this::paint);
		canvas.addListener(SWT.MouseUp, e -> showDetails());
		canvas.addListener(SWT.Dispose, e -> {
			if (tip != null) {
				tip.dispose();
			}
		});
		canvas.setCursor(canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
		update();
	}

	/** The tokens of the chat so far and of its last answer ({@code null} before the first). */
	public void set(Usage chatTotal, TurnUsage lastAnswer) {
		chat = chatTotal == null ? Usage.NONE : chatTotal;
		last = lastAnswer;
		update();
	}

	void setVisible(boolean visible) {
		canvas.setVisible(visible);
		Object data = canvas.getLayoutData();
		if (data instanceof org.eclipse.swt.layout.GridData g) {
			g.exclude = !visible;
		}
	}

	/** Share of the context window in percent, {@code -1} if unknown. */
	public int percent() {
		return last == null ? -1 : last.contextPercent();
	}

	/** Text of tooltip and popup. */
	public String details() {
		StringBuilder sb = new StringBuilder();
		if (last != null && last.contextTokens() > 0) {
			sb.append(last.contextPercent() >= 0
					? Messages.fmt("chat.usage.context", last.contextTokens(), last.contextWindow(),
							last.contextPercent())
					: Messages.fmt("chat.usage.contextNoWindow", last.contextTokens()));
		}
		if (!chat.isEmpty()) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(Messages.fmt("chat.usage.chat", chat.promptTokens(), chat.cacheReadTokens(),
					chat.outputTokens()));
		}
		return sb.length() == 0 ? Messages.get("chat.usage.none") : sb.toString();
	}

	/** Degrees of the filled part, clockwise from the top. */
	public static int sweep(int percent) {
		return percent <= 0 ? 0 : (int) Math.round(360.0 * Math.min(100, percent) / 100);
	}

	/** The fill color for a share: accent, warning or full. */
	public static RGB color(int percent, boolean dark) {
		if (percent >= FULL) {
			return dark ? FULL_DARK : FULL_RGB;
		}
		if (percent >= WARN) {
			return dark ? WARN_DARK : WARN_RGB;
		}
		return dark ? ACCENT_DARK : ACCENT;
	}

	private void update() {
		if (canvas.isDisposed()) {
			return;
		}
		canvas.setToolTipText(Messages.get("chat.usage.title") + "\n" + details());
		canvas.redraw();
	}

	private void paint(Event e) {
		GC gc = e.gc;
		gc.setAntialias(SWT.ON);
		Point size = canvas.getSize();
		int d = Math.min(SIZE, Math.min(size.x, size.y) - 2);
		int x = (size.x - d) / 2, y = (size.y - d) / 2;
		RGB bg = canvas.getBackground().getRGB();
		boolean dark = (bg.red * 299 + bg.green * 587 + bg.blue * 114) / 1000 < 128;
		Color ring = canvas.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);
		int p = percent();
		int angle = sweep(p);
		if (angle > 0) {
			gc.setBackground(new Color(color(p, dark)));
			gc.fillArc(x, y, d, d, 90, -angle);
		}
		gc.setForeground(ring);
		gc.setLineWidth(1);
		gc.drawOval(x, y, d, d);
	}

	/** A balloon with the numbers, for when the tooltip is too fleeting. */
	private void showDetails() {
		if (tip == null || tip.isDisposed()) {
			tip = new ToolTip(canvas.getShell(), SWT.NONE);
		}
		tip.setText(Messages.get("chat.usage.title"));
		tip.setMessage(details());
		tip.setAutoHide(true);
		Point at = canvas.toDisplay(0, 0);
		tip.setLocation(at.x, at.y);
		tip.setVisible(true);
	}
}

package de.kiliantaubmann.bella.ui.debug;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import de.kiliantaubmann.bella.core.debug.DebugSnapshot;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;
import de.kiliantaubmann.bella.ui.handlers.ExplainDebugStateHandler;
import de.kiliantaubmann.bella.ui.prefs.Prefs;

/**
 * Offers an analysis when the ABAP debugger stops at an exception: a small
 * notification with a link to the chat, at most once per exception, thread
 * and line. Nothing is sent before the developer clicks.
 */
public final class DebugExceptionWatcher implements IDebugEventSetListener {

	/** How long the notification stays. */
	private static final long SHOW_MILLIS = 15_000;

	/** Most offers remembered. */
	private static final int REMEMBERED = 200;

	/** Exceptions already offered; jobs of several threads add to it. */
	private final Set<String> offered = Collections.synchronizedSet(Collections.newSetFromMap(
			new LinkedHashMap<String, Boolean>() {
				private static final long serialVersionUID = 1L;

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > REMEMBERED;
				}
			}));

	public static void install() {
		DebugPlugin.getDefault().addDebugEventListener(new DebugExceptionWatcher());
	}

	@Override
	public void handleDebugEvents(DebugEvent[] events) {
		for (DebugEvent e : events) {
			// steps and expression evaluations stop too; reading all variables after each would be slow
			boolean stop = e.getKind() == DebugEvent.SUSPEND && e.getDetail() != DebugEvent.STEP_END
					&& e.getDetail() != DebugEvent.EVALUATION && e.getDetail() != DebugEvent.EVALUATION_IMPLICIT;
			if (stop && e.getSource() instanceof IThread thread && enabled()) {
				check(thread);
			}
		}
	}

	private static boolean enabled() {
		BellaPlugin plugin = BellaPlugin.getDefault();
		return plugin != null && plugin.prefs().getBoolean(Prefs.DEBUG_OFFER_ANALYSIS);
	}

	private void check(IThread thread) {
		Job job = Job.create("Bella", monitor -> {
			try {
				if (!thread.isSuspended() || !thread.hasStackFrames()
						|| !EclipseDebugBackend.mayBeException(thread.getTopStackFrame())) {
					return Status.OK_STATUS;
				}
				DebugSnapshot s = EclipseDebugBackend.snapshot(thread.getTopStackFrame());
				if (s.exception() == null) {
					return Status.OK_STATUS;
				}
				String key = System.identityHashCode(thread) + "|" + s.exception().type() + "|" + s.object() + "|"
						+ s.line();
				if (offered.add(key)) {
					Display.getDefault().asyncExec(() -> offer(s));
				}
			} catch (Exception ex) {
				Log.warn("debug", "checking the stopped thread failed: " + ex.getMessage());
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.schedule();
	}

	private static void offer(DebugSnapshot s) {
		Optional.ofNullable(Display.getDefault()).ifPresent(display -> NotificationPopup.forDisplay(display)
				.title(Messages.get("debug.title"), true)
				.content(parent -> content(parent, s))
				.delay(SHOW_MILLIS)
				.open());
	}

	private static Composite content(Composite parent, DebugSnapshot s) {
		Composite c = new Composite(parent, SWT.NONE);
		c.setLayout(new GridLayout(1, false));
		Label text = new Label(c, SWT.WRAP);
		text.setText(Messages.fmt("debug.exception.text", s.exception().type(), s.object()));
		text.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		Link link = new Link(c, SWT.NONE);
		link.setText("<a>" + Messages.get("debug.exception.link") + "</a>");
		link.addListener(SWT.Selection, e -> {
			c.getShell().close();
			ExplainDebugStateHandler.ask(s);
		});
		return c;
	}
}

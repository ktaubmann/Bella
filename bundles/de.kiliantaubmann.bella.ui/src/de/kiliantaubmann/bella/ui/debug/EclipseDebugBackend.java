package de.kiliantaubmann.bella.ui.debug;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;

import de.kiliantaubmann.bella.core.abap.ObjectTarget;
import de.kiliantaubmann.bella.core.debug.DebugBackend;
import de.kiliantaubmann.bella.core.debug.DebugExceptions;
import de.kiliantaubmann.bella.core.debug.DebugSnapshot;
import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Frame;
import de.kiliantaubmann.bella.core.debug.DebugSnapshot.Variable;
import de.kiliantaubmann.bella.core.util.Log;
import de.kiliantaubmann.bella.ui.editor.EditorBridge;
import de.kiliantaubmann.bella.ui.editor.OpenEditorRouter;

/**
 * The ABAP debugger of ADT seen through Eclipse's debug framework: the
 * selected (or first) stopped thread, its frames and variables, the
 * breakpoint manager, and the editor's Toggle Breakpoint command, so ADT
 * itself creates the breakpoints. No internal SAP API is used.
 */
public final class EclipseDebugBackend implements DebugBackend {

	private static final String TOGGLE_BREAKPOINT = "org.eclipse.debug.ui.commands.ToggleBreakpoint";
	/** Most frames of the call stack listed. */
	private static final int MAX_FRAMES = 20;
	/** Time to load variable children; each child is a round trip to the SAP system. */
	private static final long LOAD_MILLIS = 8_000;
	/** Most values loaded for one snapshot. */
	private static final int MAX_LOADED = 400;

	@Override
	public Optional<DebugSnapshot> snapshot() throws DebugException {
		Optional<IStackFrame> frame = currentFrame();
		return frame.isEmpty() ? Optional.empty() : Optional.of(snapshot(frame.get()));
	}

	/** The state at the top frame of a stopped thread; also used for the exception notice. */
	static DebugSnapshot snapshot(IStackFrame frame) throws DebugException {
		IThread thread = frame.getThread();
		List<Frame> stack = new ArrayList<>();
		for (IStackFrame f : thread.getStackFrames()) {
			if (stack.size() == MAX_FRAMES) {
				break;
			}
			stack.add(new Frame(f.getName(), "", f.getLineNumber()));
		}
		Loader loader = new Loader();
		List<Variable> variables = loader.load(frame.getVariables(), 0, DebugSnapshot.MAX_VARIABLES);
		DebugSnapshot.Problem problem = DebugExceptions.detect(variables, List.of(thread.getName(), frame.getName()))
				.orElse(null);
		return new DebugSnapshot(frame.getName(), "", frame.getLineNumber(), stack, variables, problem);
	}

	@Override
	public Optional<Variable> variable(String path) throws DebugException {
		Optional<IStackFrame> frame = currentFrame();
		if (frame.isEmpty()) {
			return Optional.empty();
		}
		IVariable[] level = frame.get().getVariables();
		IVariable found = null;
		for (String part : path.split("/")) {
			String name = part.strip();
			if (name.isEmpty()) {
				continue;
			}
			found = find(level, name);
			if (found == null) {
				return Optional.empty();
			}
			IValue value = found.getValue();
			level = value != null && value.hasVariables() ? value.getVariables() : new IVariable[0];
		}
		if (found == null) {
			return Optional.empty();
		}
		return Optional.of(new Loader().load(new IVariable[] { found }, 0, 1).get(0));
	}

	/** A variable of this level by name, also inside groups such as "Locals" that ADT may show. */
	private static IVariable find(IVariable[] level, String name) throws DebugException {
		for (IVariable v : level) {
			if (v.getName().strip().equalsIgnoreCase(name)) {
				return v;
			}
		}
		for (IVariable v : level) {
			if (Loader.isGroup(v)) {
				for (IVariable c : v.getValue().getVariables()) {
					if (c.getName().strip().equalsIgnoreCase(name)) {
						return c;
					}
				}
			}
		}
		return null;
	}

	@Override
	public List<Breakpoint> breakpoints() {
		return ui(() -> {
			List<Breakpoint> out = new ArrayList<>();
			IDebugModelPresentation labels = DebugUITools.newDebugModelPresentation();
			try {
				for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
					if (!(bp instanceof ILineBreakpoint line) || isJava(bp)) {
						continue;
					}
					String label = labels.getText(bp);
					if (label == null || label.isBlank()) {
						IResource r = bp.getMarker() == null ? null : bp.getMarker().getResource();
						label = r == null ? "" : r.getName();
					}
					out.add(new Breakpoint(label, line.getLineNumber(), bp.isEnabled()));
				}
			} catch (Exception e) {
				Log.warn("debug", "reading breakpoints failed: " + e.getMessage());
			} finally {
				labels.dispose();
			}
			return out;
		});
	}

	private static boolean isJava(IBreakpoint bp) {
		String model = bp.getModelIdentifier();
		return model != null && model.toLowerCase(Locale.ROOT).contains("jdt");
	}

	@Override
	public void setBreakpoint(String object, String type, int line, boolean on) {
		String problem = ui(() -> toggleInEditor(object, type, line, on));
		if (problem != null) {
			throw new IllegalStateException(problem);
		}
	}

	/** @return {@code null} when done, else what went wrong */
	private static String toggleInEditor(String object, String type, int line, boolean on) {
		IEditorPart part = OpenEditorRouter.findOpenEditor(new ObjectTarget(object, type.isEmpty() ? null : type));
		if (part == null) {
			return object + " is not open in an editor. Ask the developer to open it; then set the breakpoint again.";
		}
		Optional<ITextEditor> editor = EditorBridge.textEditor(part);
		if (editor.isEmpty()) {
			return "The editor of " + object + " is no text editor.";
		}
		IResource resource = Adapters.adapt(part.getEditorInput(), IResource.class);
		try {
			if (hasBreakpoint(resource, line) == on) {
				return null;
			}
			IDocument doc = EditorBridge.document(editor.get());
			if (line > doc.getNumberOfLines()) {
				return object + " has only " + doc.getNumberOfLines() + " lines.";
			}
			int before = lineBreakpointCount();
			part.getSite().getPage().activate(part);
			editor.get().selectAndReveal(doc.getLineOffset(line - 1), 0);
			IHandlerService handlers = part.getSite().getService(IHandlerService.class);
			handlers.executeCommand(TOGGLE_BREAKPOINT, null);
			boolean done = resource != null ? hasBreakpoint(resource, line) == on
					: lineBreakpointCount() == before + (on ? 1 : -1);
			return done ? null
					: "Eclipse did not " + (on ? "set" : "remove") + " the breakpoint at line " + line + " of " + object
							+ " (it may not be an executable statement). Choose another line.";
		} catch (BadLocationException e) {
			return "Line " + line + " does not exist in " + object + ".";
		} catch (Exception e) {
			Log.warn("debug", "toggling a breakpoint failed: " + e);
			return "Could not toggle the breakpoint: " + e.getMessage();
		}
	}

	private static boolean hasBreakpoint(IResource resource, int line) throws Exception {
		if (resource == null) {
			return false;
		}
		for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
			if (bp instanceof ILineBreakpoint lb && bp.getMarker() != null
					&& resource.equals(bp.getMarker().getResource()) && lb.getLineNumber() == line) {
				return true;
			}
		}
		return false;
	}

	private static int lineBreakpointCount() {
		int n = 0;
		for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints()) {
			if (bp instanceof ILineBreakpoint) {
				n++;
			}
		}
		return n;
	}

	@Override
	public Optional<DebugSnapshot> step(Step step, Duration timeout) throws Exception {
		Optional<IStackFrame> frame = currentFrame();
		if (frame.isEmpty()) {
			return Optional.empty();
		}
		IThread thread = frame.get().getThread();
		IDebugTarget target = thread.getDebugTarget();
		CountDownLatch stopped = new CountDownLatch(1);
		IDebugEventSetListener listener = events -> {
			for (DebugEvent e : events) {
				boolean ours = e.getSource() == thread || e.getSource() == target;
				if (ours && (e.getKind() == DebugEvent.SUSPEND || e.getKind() == DebugEvent.TERMINATE)) {
					stopped.countDown();
				}
			}
		};
		DebugPlugin.getDefault().addDebugEventListener(listener);
		try {
			switch (step) {
			case INTO -> {
				check(thread.canStepInto(), "step into");
				thread.stepInto();
			}
			case OVER -> {
				check(thread.canStepOver(), "step over");
				thread.stepOver();
			}
			case RETURN -> {
				check(thread.canStepReturn(), "step return");
				thread.stepReturn();
			}
			case RESUME -> {
				check(thread.canResume(), "resume");
				thread.resume();
			}
			}
			if (!stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				return Optional.empty();
			}
		} finally {
			DebugPlugin.getDefault().removeDebugEventListener(listener);
		}
		if (thread.isTerminated() || !thread.isSuspended() || !thread.hasStackFrames()) {
			return currentFrame().map(f -> {
				try {
					return snapshot(f);
				} catch (DebugException e) {
					return null;
				}
			});
		}
		return Optional.of(snapshot(thread.getTopStackFrame()));
	}

	private static void check(boolean possible, String what) {
		if (!possible) {
			throw new IllegalStateException("The debugger cannot " + what + " here.");
		}
	}

	/**
	 * The frame selected in the Debug view, else the top frame of the first
	 * stopped thread (ABAP threads first).
	 */
	static Optional<IStackFrame> currentFrame() throws DebugException {
		IAdaptable context = ui(DebugUITools::getDebugContext);
		IStackFrame selected = frameOf(context);
		if (selected != null && selected.isSuspended()) {
			return Optional.of(selected);
		}
		IStackFrame other = null;
		for (IDebugTarget target : DebugPlugin.getDefault().getLaunchManager().getDebugTargets()) {
			if (target.isTerminated() || isJava(target)) {
				continue;
			}
			for (IThread thread : target.getThreads()) {
				if (thread.isSuspended() && thread.hasStackFrames()) {
					IStackFrame top = thread.getTopStackFrame();
					if (isAbap(target)) {
						return Optional.of(top);
					}
					if (other == null) {
						other = top;
					}
				}
			}
		}
		return Optional.ofNullable(other);
	}

	private static IStackFrame frameOf(IAdaptable context) throws DebugException {
		if (context == null) {
			return null;
		}
		IStackFrame frame = Adapters.adapt(context, IStackFrame.class);
		if (frame != null) {
			return frame;
		}
		IThread thread = Adapters.adapt(context, IThread.class);
		if (thread != null && thread.isSuspended()) {
			return thread.getTopStackFrame();
		}
		return null;
	}

	private static boolean isJava(IDebugTarget target) {
		String model = target.getModelIdentifier();
		return model != null && model.toLowerCase(Locale.ROOT).contains("jdt");
	}

	private static boolean isAbap(IDebugTarget target) {
		String model = target.getModelIdentifier();
		String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
		return m.contains("abap") || m.contains("sap.adt");
	}

	private static <T> T ui(Supplier<T> work) {
		Display display = Display.getDefault();
		if (Display.getCurrent() != null) {
			return work.get();
		}
		AtomicReference<T> result = new AtomicReference<>();
		display.syncExec(() -> result.set(work.get()));
		return result.get();
	}

	/**
	 * Loads variables with their children within a time and count budget:
	 * every child the ADT debugger shows is fetched from the SAP system.
	 */
	static final class Loader {

		private final long deadline = System.currentTimeMillis() + LOAD_MILLIS;
		private int loaded;

		List<Variable> load(IVariable[] vars, int depth, int max) throws DebugException {
			List<Variable> out = new ArrayList<>();
			for (int i = 0; i < vars.length && i < max; i++) {
				out.add(load(vars[i], depth));
			}
			// the rest by name only, so DebugSnapshot#format can say how many more there are
			for (int i = max; i < vars.length; i++) {
				out.add(Variable.of(vars[i].getName(), "", ""));
			}
			return out;
		}

		private Variable load(IVariable v, int depth) throws DebugException {
			loaded++;
			IValue value = v.getValue();
			String type = safe(() -> v.getReferenceTypeName());
			String text = value == null ? "" : safe(value::getValueString);
			boolean hasChildren = value != null && value.hasVariables();
			if (!hasChildren) {
				return Variable.of(v.getName(), type, text);
			}
			boolean group = depth == 0 && isGroup(v);
			if (depth >= DebugSnapshot.DEPTH && !group || exhausted()) {
				return new Variable(v.getName(), type, text, List.of(), true);
			}
			IVariable[] children = value.getVariables();
			int max = group ? DebugSnapshot.MAX_VARIABLES : DebugSnapshot.MAX_CHILDREN;
			// a group such as "Locals" counts as no level of its own
			List<Variable> loadedChildren = load(children, group ? depth : depth + 1, max);
			return new Variable(v.getName(), type, text, loadedChildren, false);
		}

		private boolean exhausted() {
			return loaded >= MAX_LOADED || System.currentTimeMillis() > deadline;
		}

		/** An entry without type and value that only holds variables, e.g. "Locals". */
		static boolean isGroup(IVariable v) throws DebugException {
			IValue value = v.getValue();
			return value != null && value.hasVariables() && safe(v::getReferenceTypeName).isBlank()
					&& safe(value::getValueString).isBlank();
		}

		private interface DebugSupplier {
			String get() throws DebugException;
		}

		private static String safe(DebugSupplier s) {
			try {
				String r = s.get();
				return r == null ? "" : r;
			} catch (DebugException e) {
				return "";
			}
		}
	}
}

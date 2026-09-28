package de.kiliantaubmann.bella.ui;

import java.util.Objects;

import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.util.Util;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.keys.IBindingService;

/** Bella's key bindings as the developer sees them. */
public final class Shortcuts {

	public static final String COMPLETE_COMMAND = "de.kiliantaubmann.bella.ui.complete";
	/**
	 * Context active in text editors Bella is attached to. Its parent is the
	 * text editor scope, so Bella's Ctrl+Up wins over "Scroll Line Up" there
	 * instead of causing a key conflict.
	 */
	public static final String EDITOR_CONTEXT = "de.kiliantaubmann.bella.editorScope";

	/** Default completion shortcut: Ctrl+Up, on macOS Cmd+Option+Enter (Ctrl+Up is Mission Control). */
	public static String defaultCompletion() {
		return Util.isMac() ? "M1+M3+ENTER" : "M1+ARROW_UP";
	}

	private Shortcuts() {
	}

	/**
	 * The completion shortcut formatted for this platform, following a binding
	 * the developer changed under Preferences → General → Keys.
	 */
	public static String completion() {
		TriggerSequence found = null;
		try {
			IBindingService bs = PlatformUI.getWorkbench().getService(IBindingService.class);
			String scheme = bs.getActiveScheme() == null ? null : bs.getActiveScheme().getId();
			Binding[] all = bs.getBindings();
			for (Binding b : all) {
				ParameterizedCommand cmd = b.getParameterizedCommand();
				if (cmd == null || !COMPLETE_COMMAND.equals(cmd.getId()) || !Objects.equals(scheme, b.getSchemeId())
						|| (b.getPlatform() != null && !b.getPlatform().equals(Util.getWS())) || deleted(b, all)) {
					continue;
				}
				if (found == null || b.getType() == Binding.USER) {
					found = b.getTriggerSequence();
				}
			}
		} catch (RuntimeException e) {
			// no workbench (tests) or bindings not ready: fall back to the default
		}
		if (found != null) {
			return found.format();
		}
		try {
			return KeySequence.getInstance(defaultCompletion()).format();
		} catch (ParseException e) {
			return defaultCompletion();
		}
	}

	/** A deletion marker (binding without command) that removes {@code b}. */
	private static boolean deleted(Binding b, Binding[] all) {
		for (Binding d : all) {
			if (d.getParameterizedCommand() == null && d.getTriggerSequence().equals(b.getTriggerSequence())
					&& Objects.equals(d.getContextId(), b.getContextId())
					&& Objects.equals(d.getSchemeId(), b.getSchemeId())
					&& (d.getPlatform() == null || d.getPlatform().equals(Util.getWS()))
					&& (d.getType() == Binding.USER || b.getType() == Binding.SYSTEM)) {
				return true;
			}
		}
		return false;
	}
}

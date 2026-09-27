package de.kiliantaubmann.bella.ui.prefs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/** Tiny helper that binds widgets to preferences, so pages stay declarative. */
final class Form {

	private interface Binding {
		void load(boolean defaults);

		void store();
	}

	private final IPreferenceStore store;
	private final List<Binding> bindings = new ArrayList<>();

	Form(IPreferenceStore store) {
		this.store = store;
	}

	static Group group(Composite parent, String title) {
		Group g = new Group(parent, SWT.NONE);
		g.setText(title);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(g);
		GridLayoutFactory.swtDefaults().numColumns(2).applyTo(g);
		return g;
	}

	private static void label(Composite parent, String text) {
		Label l = new Label(parent, SWT.NONE);
		l.setText(text);
	}

	Text text(Composite parent, String label, String key, String tooltip) {
		label(parent, label);
		Text t = new Text(parent, SWT.BORDER);
		t.setToolTipText(tooltip);
		GridDataFactory.fillDefaults().grab(true, false).hint(260, SWT.DEFAULT).applyTo(t);
		bindings.add(new Binding() {
			@Override
			public void load(boolean d) {
				t.setText(d ? store.getDefaultString(key) : store.getString(key));
			}

			@Override
			public void store() {
				store.setValue(key, t.getText().trim());
			}
		});
		return t;
	}

	/** Password field kept in secure storage instead of the preference store. */
	Text secret(Composite parent, String label, String secureKey) {
		label(parent, label);
		Text t = new Text(parent, SWT.BORDER | SWT.PASSWORD);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(t);
		bindings.add(new Binding() {
			@Override
			public void load(boolean d) {
				if (!d) {
					t.setText(SecureStore.get(secureKey));
				}
			}

			@Override
			public void store() {
				SecureStore.put(secureKey, t.getText().trim());
			}
		});
		return t;
	}

	Button check(Composite parent, String label, String key) {
		Button b = new Button(parent, SWT.CHECK);
		b.setText(label);
		GridDataFactory.fillDefaults().span(2, 1).applyTo(b);
		bindings.add(new Binding() {
			@Override
			public void load(boolean d) {
				b.setSelection(d ? store.getDefaultBoolean(key) : store.getBoolean(key));
			}

			@Override
			public void store() {
				store.setValue(key, b.getSelection());
			}
		});
		return b;
	}

	Spinner number(Composite parent, String label, String key, int min, int max, int step) {
		label(parent, label);
		Spinner s = new Spinner(parent, SWT.BORDER);
		s.setValues(0, min, max, 0, step, step * 10);
		bindings.add(new Binding() {
			@Override
			public void load(boolean d) {
				s.setSelection(d ? store.getDefaultInt(key) : store.getInt(key));
			}

			@Override
			public void store() {
				store.setValue(key, s.getSelection());
			}
		});
		return s;
	}

	/** Read-only combo; {@code options} maps stored value to label. */
	Combo choice(Composite parent, String label, String key, Map<String, String> options) {
		label(parent, label);
		Combo c = new Combo(parent, SWT.READ_ONLY);
		List<String> values = new ArrayList<>(options.keySet());
		options.values().forEach(c::add);
		bindings.add(new Binding() {
			@Override
			public void load(boolean d) {
				int i = values.indexOf(d ? store.getDefaultString(key) : store.getString(key));
				c.select(Math.max(0, i));
			}

			@Override
			public void store() {
				int i = c.getSelectionIndex();
				store.setValue(key, i < 0 ? "" : values.get(i));
			}
		});
		return c;
	}

	static Map<String, String> options(String... valueLabel) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < valueLabel.length; i += 2) {
			m.put(valueLabel[i], valueLabel[i + 1]);
		}
		return m;
	}

	void load() {
		bindings.forEach(b -> b.load(false));
	}

	void loadDefaults() {
		bindings.forEach(b -> b.load(true));
	}

	void store() {
		bindings.forEach(Binding::store);
	}
}

package de.kiliantaubmann.bella.ui.prefs;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import de.kiliantaubmann.bella.core.mcp.McpToolProvider;
import de.kiliantaubmann.bella.core.tools.ToolPolicy;
import de.kiliantaubmann.bella.core.tools.ToolSpec;
import de.kiliantaubmann.bella.ui.BellaPlugin;
import de.kiliantaubmann.bella.ui.Messages;

/** SAP tools: MCP servers such as ARC-1, preferred tool source and the tool policy. */
public class ToolsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

	private Form form;
	private Table table;
	private final List<McpServerConfig> servers = new ArrayList<>();
	private final Map<String, String> tokens = new HashMap<>();
	private Text rules;

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BellaPlugin.getDefault().getPreferenceStore());
		setDescription(Messages.get("tools.description"));
	}

	@Override
	protected Control createContents(Composite parent) {
		form = new Form(getPreferenceStore());
		Composite c = new Composite(parent, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(c);

		Group adt = Form.group(c, Messages.get("tools.adt"));
		Label adtInfo = new Label(adt, SWT.WRAP);
		adtInfo.setText(BellaPlugin.getDefault().adt() == null ? Messages.get("tools.adt.missing")
				: Messages.get("tools.adt.present"));
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(adtInfo);
		form.choice(adt, Messages.get("tools.preferred"), Prefs.PREFERRED_TOOLS,
				Form.options("adt", Messages.get("tools.preferred.adt"), "mcp", Messages.get("tools.preferred.mcp")));

		Group mcp = Form.group(c, Messages.get("tools.mcp"));
		table = new Table(mcp, SWT.BORDER | SWT.FULL_SELECTION | SWT.CHECK);
		table.setHeaderVisible(true);
		for (String h : new String[] { Messages.get("mcp.name"), Messages.get("mcp.transport"), Messages.get("mcp.target") }) {
			TableColumn col = new TableColumn(table, SWT.NONE);
			col.setText(h);
			col.setWidth(h.equals(Messages.get("mcp.target")) ? 280 : 110);
		}
		GridDataFactory.fillDefaults().grab(true, true).hint(420, 110).applyTo(table);
		Composite buttons = new Composite(mcp, SWT.NONE);
		GridLayoutFactory.fillDefaults().applyTo(buttons);
		GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(buttons);
		button(buttons, Messages.get("tools.add"), this::add);
		button(buttons, Messages.get("tools.edit"), this::edit);
		button(buttons, Messages.get("tools.remove"), this::remove);
		button(buttons, Messages.get("tools.test"), this::test);
		table.addListener(SWT.DefaultSelection, e -> edit());
		table.addListener(SWT.Selection, e -> {
			if (e.detail == SWT.CHECK) {
				int i = table.indexOf((TableItem) e.item);
				McpServerConfig s = servers.get(i);
				servers.set(i, new McpServerConfig(s.id(), s.name(), s.http(), s.url(), s.command(),
						((TableItem) e.item).getChecked()));
			}
		});
		Label arc1 = new Label(mcp, SWT.WRAP);
		arc1.setText(Messages.get("tools.mcp.hint"));
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(arc1);

		Group policy = Form.group(c, Messages.get("tools.policy"));
		form.text(policy, Messages.get("tools.packages"), Prefs.WRITE_PACKAGES, Messages.get("tools.packages.hint"));
		Label pkgInfo = new Label(policy, SWT.WRAP);
		pkgInfo.setText(Messages.get("tools.packages.hint"));
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(pkgInfo);
		Label pinfo = new Label(policy, SWT.WRAP);
		pinfo.setText(Messages.get("tools.policy.hint"));
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(pinfo);
		rules = new Text(policy, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
		rules.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());
		GridDataFactory.fillDefaults().grab(true, true).span(2, 1).hint(SWT.DEFAULT, 90).applyTo(rules);
		Label defaults = new Label(policy, SWT.WRAP);
		StringBuilder d = new StringBuilder(Messages.get("tools.policy.defaults")).append(' ');
		for (ToolPolicy.Rule r : ToolPolicy.DEFAULT_RULES) {
			d.append(r.glob()).append('=').append(r.decision()).append("  ");
		}
		defaults.setText(d.toString());
		GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(500, SWT.DEFAULT).applyTo(defaults);

		load(false);
		return c;
	}

	private static void button(Composite parent, String text, Runnable action) {
		Button b = new Button(parent, SWT.PUSH);
		b.setText(text);
		b.addListener(SWT.Selection, e -> action.run());
		GridDataFactory.fillDefaults().grab(true, false).applyTo(b);
	}

	private void load(boolean defaults) {
		if (defaults) {
			form.loadDefaults();
		} else {
			form.load();
		}
		servers.clear();
		servers.addAll(McpServerConfig.parse(defaults ? getPreferenceStore().getDefaultString(Prefs.MCP_SERVERS)
				: getPreferenceStore().getString(Prefs.MCP_SERVERS)));
		tokens.clear();
		for (McpServerConfig s : servers) {
			tokens.put(s.id(), SecureStore.get(SecureStore.mcpTokenKey(s.id())));
		}
		rules.setText(defaults ? getPreferenceStore().getDefaultString(Prefs.POLICY_RULES)
				: getPreferenceStore().getString(Prefs.POLICY_RULES));
		refreshTable();
	}

	private void refreshTable() {
		table.removeAll();
		for (McpServerConfig s : servers) {
			TableItem item = new TableItem(table, SWT.NONE);
			item.setText(new String[] { s.name(), s.http() ? "HTTP" : "stdio", s.http() ? s.url() : s.command() });
			item.setChecked(s.enabled());
			item.setImage(BellaPlugin.image("tool"));
		}
	}

	private void add() {
		McpServerDialog d = new McpServerDialog(getShell(),
				new McpServerConfig("arc1", "ARC-1", true, "http://localhost:3000/mcp", "npx -y arc-1@latest", true), "");
		if (d.open() == org.eclipse.jface.window.Window.OK) {
			servers.add(d.config());
			tokens.put(d.config().id(), d.token());
			refreshTable();
		}
	}

	private void edit() {
		int i = table.getSelectionIndex();
		if (i < 0) {
			return;
		}
		McpServerDialog d = new McpServerDialog(getShell(), servers.get(i), tokens.get(servers.get(i).id()));
		if (d.open() == org.eclipse.jface.window.Window.OK) {
			servers.set(i, d.config());
			tokens.put(d.config().id(), d.token());
			refreshTable();
		}
	}

	private void remove() {
		int i = table.getSelectionIndex();
		if (i >= 0) {
			servers.remove(i);
			refreshTable();
		}
	}

	private void test() {
		int i = table.getSelectionIndex();
		if (i < 0) {
			return;
		}
		McpServerConfig cfg = servers.get(i);
		SecureStore.put(SecureStore.mcpTokenKey(cfg.id()), tokens.get(cfg.id()));
		List<ToolSpec> found = new ArrayList<>();
		try {
			new ProgressMonitorDialog(getShell()).run(true, false, monitor -> {
				monitor.beginTask(Messages.fmt("tools.testing", cfg.name()), -1);
				McpToolProvider p = null;
				try {
					p = BellaPlugin.getDefault().createMcpProvider(cfg);
					found.addAll(p.listTools());
				} catch (Exception e) {
					throw new InvocationTargetException(e);
				} finally {
					if (p != null) {
						p.close();
					}
				}
			});
			StringBuilder names = new StringBuilder();
			found.forEach(t -> names.append(t.name()).append("  "));
			MessageDialog.openInformation(getShell(), Messages.get("app.name"),
					Messages.fmt("tools.test.ok", cfg.name(), found.size(), names.toString()));
		} catch (InvocationTargetException e) {
			MessageDialog.openError(getShell(), Messages.get("app.name"),
					Messages.fmt("tools.test.failed", cfg.name(), String.valueOf(e.getCause().getMessage())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	protected void performDefaults() {
		load(true);
		super.performDefaults();
	}

	@Override
	public boolean performOk() {
		form.store();
		for (McpServerConfig s : servers) {
			SecureStore.put(SecureStore.mcpTokenKey(s.id()), tokens.get(s.id()));
		}
		getPreferenceStore().setValue(Prefs.POLICY_RULES, rules.getText());
		getPreferenceStore().setValue(Prefs.MCP_SERVERS, McpServerConfig.toJson(servers));
		BellaPlugin.getDefault().reconnectMcpServers();
		return super.performOk();
	}
}

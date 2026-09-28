package de.kiliantaubmann.bella.ui.internal;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.Separator;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;

import de.kiliantaubmann.bella.ui.Messages;

/**
 * Entries of Bella's editor context menu and main menu. They are built on
 * every opening so their texts follow Bella's UI language (Eclipse would
 * resolve labels in plugin.xml with the operating system language only).
 * Icons and key bindings come from the commands as usual.
 */
public class BellaMenuItems extends CompoundContributionItem implements IWorkbenchContribution {

	public static final String POPUP_ID = "de.kiliantaubmann.bella.ui.editorMenu.items";
	public static final String MAIN_ID = "de.kiliantaubmann.bella.ui.mainMenu.items";

	private static final String CMD = "de.kiliantaubmann.bella.ui.";
	/** Command names; {@code null} is a separator. */
	private static final String[] POPUP = { "explain", null, "generate", "rewrite", "implementMethod", "complete",
			null, "refactor", "unitTest", "openChat" };
	private static final String[] MAIN = { "openChat", null, "explain", "generate", "rewrite", "implementMethod",
			"complete", "refactor", "unitTest" };

	private IServiceLocator services;

	public BellaMenuItems() {
	}

	public BellaMenuItems(String id) {
		super(id);
	}

	@Override
	public void initialize(IServiceLocator serviceLocator) {
		this.services = serviceLocator;
	}

	@Override
	protected IContributionItem[] getContributionItems() {
		List<IContributionItem> items = new ArrayList<>();
		for (String name : MAIN_ID.equals(getId()) ? MAIN : POPUP) {
			if (name == null) {
				items.add(new Separator());
				continue;
			}
			CommandContributionItemParameter p = new CommandContributionItemParameter(services, getId() + "." + name,
					CMD + name, CommandContributionItem.STYLE_PUSH);
			p.label = Messages.plugin("menu." + name);
			p.tooltip = Messages.plugin("cmd." + name + ".desc");
			items.add(new CommandContributionItem(p));
		}
		return items.toArray(IContributionItem[]::new);
	}
}

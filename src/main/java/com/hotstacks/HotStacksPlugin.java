package com.hotstacks;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Hot Stacks",
	description = "Shows each bank stack's value on it as a heat map; colours rank against the whole bank, or the open tab",
	tags = {"bank", "value", "wealth", "heatmap", "heat", "density map", "density", "embers", "price", "gp", "stacks", "worth", "withdraws"}
)
public class HotStacksPlugin extends Plugin
{
	private static final String WITHDRAW_OPTION_PREFIX = "Withdraw";

	@Inject
	private OverlayManager overlayManager;
	@Inject
	private HotStacksOverlay overlay;
	@Inject
	private HeatFieldOverlay heatFieldOverlay;
	@Inject
	private BankValueModel model;
	@Inject
	private WithdrawalTracker withdrawalTracker;

	@Provides
	HotStacksConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HotStacksConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(heatFieldOverlay);
		overlayManager.add(overlay);
		withdrawalTracker.load();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(heatFieldOverlay);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		model.syncToView();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Prices drift even while the shown items stay the same.
		model.invalidate();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (HotStacksConfig.GROUP.equals(event.getGroup()))
		{
			model.invalidate();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (isWithdrawOption(event) && clickedInBank(event))
		{
			withdrawalTracker.record(event.getItemId());
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		withdrawalTracker.load();
	}

	/** Matched by text because the withdraw variants (1/5/10/X/All) share no single MenuAction. */
	private static boolean isWithdrawOption(MenuOptionClicked event)
	{
		return event.getMenuOption().startsWith(WITHDRAW_OPTION_PREFIX);
	}

	private static boolean clickedInBank(MenuOptionClicked event)
	{
		int group = WidgetUtil.componentToInterface(event.getWidgetId());
		return group == InterfaceID.BANKMAIN || group == InterfaceID.SHARED_BANK;
	}
}

package com.hotstacks;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Hot Stacks",
	description = "Shows each bank stack's value on it as a heat map; colours rank against the whole bank, or the open tab",
	tags = {"bank", "value", "wealth", "heatmap", "heat", "price", "gp", "stacks", "worth"}
)
public class HotStacksPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private HotStacksOverlay overlay;
	@Inject
	private BankValueModel model;

	@Provides
	HotStacksConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HotStacksConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Keep the colour scale in step with bank contents (a no-op when the bank is closed).
		model.recompute();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Switching tab or toggling search changes which slots are shown; re-rank immediately
		// rather than waiting for the next tick.
		model.recompute();
	}
}

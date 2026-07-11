package com.hotstacks;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Hot Stacks",
	description = "Shows each bank stack's value on it as a heat map; colours rank against the whole bank, or the open tab",
	tags = {"bank", "value", "wealth", "heatmap", "heat", "density map", "density", "embers", "price", "gp", "stacks", "worth"}
)
public class HotStacksPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private HotStacksOverlay overlay;
	@Inject
	private HeatFieldOverlay heatFieldOverlay;
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
		overlayManager.add(heatFieldOverlay);
		overlayManager.add(overlay);
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
		// Re-rank against the slots shown this frame, right before the overlay draws. Keeping this
		// in lockstep with rendering is what avoids the one-frame flash when the shown set changes.
		model.syncToView();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Force a rebuild each tick so price changes are picked up even when the shown items don't
		// change; syncToView() does the actual work on the next frame.
		model.invalidate();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Changing the value basis (or any setting) alters the values without changing the shown
		// items, so force a rebuild rather than waiting for the signature to change.
		if (HotStacksConfig.GROUP.equals(event.getGroup()))
		{
			model.invalidate();
		}
	}
}

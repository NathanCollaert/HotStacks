package com.hotstacks;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(HotStacksConfig.GROUP)
public interface HotStacksConfig extends Config
{
	String GROUP = "hotstacks";

	@ConfigSection(
		name = "UI",
		description = "What is drawn on each bank slot.",
		position = 0
	)
	String SECTION_UI = "ui";

	@ConfigSection(
		name = "Heat map",
		description = "How each stack's value is coloured.",
		position = 1
	)
	String SECTION_HEAT = "heat";

	// ---- UI ------------------------------------------------------------------

	@ConfigItem(
		keyName = "enabled",
		name = "Show stack values",
		description = "Draw each item's stack value on it in the bank.",
		section = SECTION_UI,
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "coverText",
		name = "Text background",
		description = "Paint a background strip behind the value at the bottom of the slot, hiding any other text drawn there (e.g. item charges).",
		section = SECTION_UI,
		position = 2
	)
	default boolean coverText()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "textBackgroundColor",
		name = "Background colour",
		description = "Colour (and opacity) of the strip drawn behind the value when 'Cover other text' is on.",
		section = SECTION_UI,
		position = 3
	)
	default Color textBackgroundColor()
	{
		// ARGB 0xC8000000 -> black at ~78% opacity.
		return new Color(0x00, 0x00, 0x00, 0xC8);
	}

	@ConfigItem(
		keyName = "scaleFontByValue",
		name = "Scale text by value",
		description = "Draw dearer stacks in a larger/bolder font so they stand out even when colours are close.",
		section = SECTION_UI,
		position = 4
	)
	default boolean scaleFontByValue()
	{
		return false;
	}

	@ConfigItem(
		keyName = "sparkleTopItems",
		name = "Sparkle on top items",
		description = "Give the most valuable stacks in view a twinkling sparkle animation.",
		section = SECTION_UI,
		position = 5
	)
	default boolean sparkleTopItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "sparkleTopPercent",
		name = "Sparkle top (%)",
		description = "Stacks in this dearest percentage of the current view get the sparkle. E.g. 2 = top 2%.",
		section = SECTION_UI,
		position = 6
	)
	default int sparkleTopPercent()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "sparkleScale",
		name = "Sparkle size",
		description = "Scale factor for the sparkle animation. 1.0 is the default size; increase for larger sparkles, decrease for smaller.",
		section = SECTION_UI,
		position = 7
	)
	default double sparkleScale()
	{
		return 1.0;
	}

	// ---- Heat map ------------------------------------------------------------

	@ConfigItem(
		keyName = "colourMode",
		name = "Colour mode",
		description = "How each stack's rank (cheapest to dearest in view) is coloured.",
		section = SECTION_HEAT,
		position = 1
	)
	default ColourMode colourMode()
	{
		return ColourMode.HEAT_RAMP;
	}

	@Alpha
	@ConfigItem(
		keyName = "lowColor",
		name = "Lowest colour",
		description = "Two-colour gradient mode only: colour for the cheapest stack in view.",
		section = SECTION_HEAT,
		position = 2
	)
	default Color lowColor()
	{
		// Bright cyan: readable on the dark bank, reads as the "cold" end.
		return new Color(0x58, 0xC7, 0xF3, 0xFF);
	}

	@Alpha
	@ConfigItem(
		keyName = "highColor",
		name = "Highest colour",
		description = "Two-colour gradient mode only: colour for the dearest stack in view.",
		section = SECTION_HEAT,
		position = 3
	)
	default Color highColor()
	{
		// Warm gold: strong hue contrast against the cyan low end, reads as "hot"/valuable.
		return new Color(0xFF, 0xC5, 0x3D, 0xFF);
	}

	@ConfigItem(
		keyName = "minValue",
		name = "Hide below (gp)",
		description = "Don't draw a value on stacks worth less than this, to cut clutter. 0 shows everything.",
		section = SECTION_HEAT,
		position = 4
	)
	default int minValue()
	{
		return 2000;
	}
}

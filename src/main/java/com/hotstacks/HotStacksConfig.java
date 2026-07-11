package com.hotstacks;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(HotStacksConfig.GROUP)
public interface HotStacksConfig extends Config
{
	String GROUP = "hotstacks";

	@ConfigSection(
		name = "Stack value",
		description = "What each stack is valued at, and how the value label is shown.",
		position = 0
	)
	String SECTION_STACK_VALUE = "stackValue";

	@ConfigSection(
		name = "Heat map",
		description = "How each stack's value is coloured.",
		position = 1
	)
	String SECTION_HEATMAP = "heatmap";

	@ConfigSection(
		name = "Effect",
		description = "Animated effect on the most valuable stacks.",
		position = 2
	)
	String SECTION_EFFECT = "effect";

	// ---- Stack value ---------------------------------------------------------

	@ConfigItem(
		keyName = "valueBasis",
		name = "Stack value",
		description = "Which value drives the label, heat map and effects. 'None' hides the label but keeps the heat map and effects running (on GE value).",
		section = SECTION_STACK_VALUE,
		position = 1
	)
	default ValueBasis valueBasis()
	{
		return ValueBasis.GE;
	}

	@ConfigItem(
		keyName = "minValue",
		name = "Hide below",
		description = "Don't draw a value label on stacks worth less than this (in the chosen value basis). 0 shows everything.",
		section = SECTION_STACK_VALUE,
		position = 2
	)
	default int minValue()
	{
		return 2000;
	}

	@ConfigItem(
		keyName = "coverText",
		name = "Text background",
		description = "Paint a background strip behind the value, hiding any other text drawn at the bottom of the slot (e.g. item charges).",
		section = SECTION_STACK_VALUE,
		position = 3
	)
	default boolean coverText()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "textBackgroundColor",
		name = "Background colour",
		description = "Colour (and opacity) of the strip drawn behind the value when 'Text background' is on.",
		section = SECTION_STACK_VALUE,
		position = 4
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
		section = SECTION_STACK_VALUE,
		position = 5
	)
	default boolean scaleFontByValue()
	{
		return false;
	}

	// ---- Heat map ------------------------------------------------------------

	@ConfigItem(
		keyName = "thermalTint",
		name = "Heat map",
		description = "Wash each slot in its heat colour, so the bank reads like a thermal map at a glance.",
		section = SECTION_HEATMAP,
		position = 1
	)
	default boolean thermalTint()
	{
		return false;
	}

	@ConfigItem(
		keyName = "scaleHeatmapByValue",
		name = "Scale heatmap by value",
		description = "Make each slot's tint stronger and larger the bigger its share of your total bank wealth.",
		section = SECTION_HEATMAP,
		position = 2
	)
	default boolean scaleHeatmapByValue()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colourMode",
		name = "Colour mode",
		description = "How each stack's rank (cheapest to dearest in view) is coloured. Shared by the value label and the heat map tint.",
		section = SECTION_HEATMAP,
		position = 3
	)
	default ColourMode colourMode()
	{
		return ColourMode.DENSITY_FIELD;
	}

	@Alpha
	@ConfigItem(
		keyName = "lowColor",
		name = "Lowest colour",
		description = "Gradient and Density field modes: colour for the cheapest / coolest end.",
		section = SECTION_HEATMAP,
		position = 4
	)
	default Color lowColor()
	{
		return new Color(0x58, 0xC7, 0xF3, 0xFF);
	}

	@Alpha
	@ConfigItem(
		keyName = "highColor",
		name = "Highest colour",
		description = "Gradient and Density field modes: colour for the dearest / hottest end.",
		section = SECTION_HEATMAP,
		position = 5
	)
	default Color highColor()
	{
		return new Color(0xFF, 0xC5, 0x3D, 0xFF);
	}

	// ---- Effect --------------------------------------------------------------

	@ConfigItem(
		keyName = "topEffect",
		name = "Effect",
		description = "Animated effect drawn on the most valuable stacks in view.",
		section = SECTION_EFFECT,
		position = 1
	)
	default TopEffect topEffect()
	{
		return TopEffect.EMBERS;
	}

	@ConfigItem(
		keyName = "sparkleTopPercent",
		name = "Effect top (%)",
		description = "Stacks in this dearest percentage of the current view get the effect. E.g. 2 = top 2%.",
		section = SECTION_EFFECT,
		position = 2
	)
	default int sparkleTopPercent()
	{
		return 2;
	}

	@Range(min = 0, max = 3)
	@ConfigItem(
		keyName = "sparkleScale",
		name = "Effect size",
		description = "Scale factor for the top-item effect. 1.0 is the base size; increase for larger, decrease for smaller.",
		section = SECTION_EFFECT,
		position = 3
	)
	default double sparkleScale()
	{
		return 0.6;
	}

	@ConfigItem(
		keyName = "scaleEffectByValue",
		name = "Scale effect by value",
		description = "Make the effect brighter and faster on stacks that are a bigger share of your total bank wealth.",
		section = SECTION_EFFECT,
		position = 4
	)
	default boolean scaleEffectByValue()
	{
		return true;
	}
}

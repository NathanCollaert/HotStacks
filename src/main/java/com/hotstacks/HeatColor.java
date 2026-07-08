package com.hotstacks;

import java.awt.Color;

/**
 * Turns a rank fraction (0 = cheapest stack in view, 1 = dearest) into a colour.
 *
 * <p>Because items are positioned by rank rather than raw value, the palette is always spread
 * evenly across whatever is in view. Three palettes are offered (see {@link ColourMode}): a
 * continuous multi-stop spectrum, a small number of solid tiers cut from that spectrum, and a
 * plain two-colour blend.</p>
 */
final class HeatColor
{
	/** Cold → hot spectrum: blue, green, yellow, orange, red (evenly spaced). */
	private static final Color[] RAMP = {
		new Color(0x2E, 0x6F, 0xF2),
		new Color(0x2E, 0xCC, 0x40),
		new Color(0xFF, 0xDC, 0x00),
		new Color(0xFF, 0x85, 0x1B),
		new Color(0xFF, 0x41, 0x36),
	};

	private HeatColor()
	{
	}

	/** Continuous spectrum colour for a rank fraction in {@code [0, 1]}. */
	static Color ramp(double f)
	{
		f = clamp01(f);
		double scaled = f * (RAMP.length - 1);
		int i = (int) Math.floor(scaled);
		if (i >= RAMP.length - 1)
		{
			return RAMP[RAMP.length - 1];
		}
		return lerp(RAMP[i], RAMP[i + 1], scaled - i);
	}

	/** Blend between two configured colours (incl. alpha) for a rank fraction in {@code [0, 1]}. */
	static Color blend(double f, Color low, Color high)
	{
		return lerp(low, high, clamp01(f));
	}

	private static Color lerp(Color a, Color b, double f)
	{
		return new Color(
			lerp(a.getRed(), b.getRed(), f),
			lerp(a.getGreen(), b.getGreen(), f),
			lerp(a.getBlue(), b.getBlue(), f),
			lerp(a.getAlpha(), b.getAlpha(), f));
	}

	private static int lerp(int a, int b, double f)
	{
		return (int) Math.round(a + (b - a) * f);
	}

	private static double clamp01(double f)
	{
		if (f < 0)
		{
			return 0;
		}
		return f > 1 ? 1 : f;
	}
}

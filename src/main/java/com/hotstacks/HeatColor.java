package com.hotstacks;

import java.awt.Color;

final class HeatColor
{
	private static final Color[] COLD_TO_HOT = {
		new Color(0x2E, 0x6F, 0xF2),
		new Color(0x2E, 0xCC, 0x40),
		new Color(0xFF, 0xDC, 0x00),
		new Color(0xFF, 0x85, 0x1B),
		new Color(0xFF, 0x41, 0x36),
	};

	private HeatColor()
	{
	}

	static Color ramp(double rankFraction)
	{
		double scaled = clamp01(rankFraction) * (COLD_TO_HOT.length - 1);
		int i = (int) Math.floor(scaled);
		if (i >= COLD_TO_HOT.length - 1)
		{
			return COLD_TO_HOT[COLD_TO_HOT.length - 1];
		}
		return lerp(COLD_TO_HOT[i], COLD_TO_HOT[i + 1], scaled - i);
	}

	static Color blend(double rankFraction, Color low, Color high)
	{
		return lerp(low, high, clamp01(rankFraction));
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

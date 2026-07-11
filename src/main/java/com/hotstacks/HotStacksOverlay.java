package com.hotstacks;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Point;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.QuantityFormatter;

/**
 * Draws each bank stack's value on its slot, coloured by the stack's rank among everything in
 * view (cheapest → dearest), an optional thermal tint of the whole slot, and an animated effect on
 * the most valuable stacks. {@code renderItemOverlay} is invoked once per visible bank item each
 * frame.
 */
@Singleton
class HotStacksOverlay extends WidgetItemOverlay
{
	/** Share of total bank wealth at which value-scaling reaches full intensity. */
	private static final double WEALTH_REF = 0.06;
	/** Intensity multiplier range for value-scaling (effect and heat map tint). */
	private static final double INTENSITY_FLOOR = 0.3;
	private static final double INTENSITY_CEIL = 1.35;
	/** Base alpha of the heat map tint at its fixed 40% strength. */
	private static final int TINT_BASE_ALPHA = 84;

	private final BankValueModel model;
	private final HotStacksConfig config;

	@Inject
	HotStacksOverlay(BankValueModel model, HotStacksConfig config)
	{
		this.model = model;
		this.config = config;
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		long value = model.valueOf(itemId, widgetItem.getQuantity());
		if (value <= 0)
		{
			// No value in the current basis (untradeable in GE mode, un-alchable, ≤0 profit, …).
			return;
		}

		// Everything keys off the one stack value. The label is shown unless the basis is None (and
		// it clears the clutter threshold); the tint and effect run regardless of the label.
		boolean showValue = config.valueBasis() != ValueBasis.NONE && value >= config.minValue();
		// The per-slot tint handles every colour mode except Density field, which is drawn as one
		// whole-bank pass by HeatFieldOverlay instead.
		boolean tint = config.thermalTint() && config.colourMode() != ColourMode.DENSITY_FIELD;
		TopEffect effect = model.isTop(value, config.sparkleTopPercent()) ? config.topEffect() : TopEffect.NONE;
		if (!showValue && !tint && effect == TopEffect.NONE)
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		double rank = model.rankFraction(value);
		Color color = colourFor(rank);

		// 1. Heat map tint sits under everything. Scaled by rank so the whole view gets a gradient
		//    of blooms (wealth-fraction would collapse all but the top few to nothing here).
		if (tint)
		{
			double tintIntensity = config.scaleHeatmapByValue() ? intensityFor(rank) : 1.0;
			drawTint(graphics, bounds, color, tintIntensity);
		}

		// 2. Value label.
		if (showValue)
		{
			String text = QuantityFormatter.quantityToStackSize(value);
			graphics.setFont(fontFor(rank));
			FontMetrics metrics = graphics.getFontMetrics();
			int x = bounds.x + (bounds.width - metrics.stringWidth(text)) / 2;
			int y = bounds.y + bounds.height - metrics.getDescent();

			if (config.coverText())
			{
				// Overwrite anything else drawn at the bottom of the slot (item charges, etc.).
				int top = y - metrics.getAscent();
				graphics.setColor(config.textBackgroundColor());
				graphics.fillRect(bounds.x, top, bounds.width, metrics.getAscent() + metrics.getDescent());
			}

			OverlayUtil.renderTextLocation(graphics, new Point(x, y), text, color);
		}

		// 3. Effect sits on top so it drifts around/in front of the item. Scaled by the stack's
		//    share of whole-bank wealth (it only marks the top few, where fractions vary enough).
		double intensity = config.scaleEffectByValue() ? intensityFor(wealthNorm(value)) : 1.0;
		if (effect == TopEffect.EMBERS)
		{
			drawEmbers(graphics, bounds, false, intensity);
		}
		else if (effect == TopEffect.RADIAL_EMBERS)
		{
			drawEmbers(graphics, bounds, true, intensity);
		}
		else if (effect == TopEffect.SPARKLE)
		{
			drawSparkles(graphics, bounds, intensity);
		}
	}

	/** Maps a normalised position [0, 1] to an intensity multiplier [floor, ceil]. */
	private double intensityFor(double norm)
	{
		double n = norm < 0 ? 0 : Math.min(1.0, norm);
		return INTENSITY_FLOOR + (INTENSITY_CEIL - INTENSITY_FLOOR) * n;
	}

	/** A stack's share of the current view's total value, normalised so {@link #WEALTH_REF} maps to full. */
	private double wealthNorm(long value)
	{
		long total = model.viewTotal();
		double frac = total > 0 ? (double) value / total : 0;
		return Math.min(1.0, frac / WEALTH_REF);
	}

	/**
	 * Soft radial wash of the slot in its heat colour. {@code intensity} scales both its alpha and
	 * its radius, so a value-scaled heat map shows bigger, stronger blooms on your dearest stacks.
	 */
	private void drawTint(Graphics2D graphics, Rectangle bounds, Color color, double intensity)
	{
		int alpha = clampAlpha((int) (TINT_BASE_ALPHA * intensity));
		double radius = Math.max(bounds.width, bounds.height) * 0.62 * intensity;
		if (alpha <= 0 || radius < 1)
		{
			return;
		}
		Color hot = new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
		Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
		double cx = bounds.x + bounds.width / 2.0;
		double cy = bounds.y + bounds.height / 2.0;
		graphics.setPaint(new RadialGradientPaint(
			new java.awt.geom.Point2D.Double(cx, cy),
			(float) radius,
			new float[]{0f, 1f},
			new Color[]{hot, edge}));
		graphics.fill(new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
	}

	private Color colourFor(double rank)
	{
		switch (config.colourMode())
		{
			case SINGLE:
				return Color.WHITE;
			case GRADIENT:
			case DENSITY_FIELD:   // label follows the configured ramp; the field itself is drawn separately
				return HeatColor.blend(rank, config.lowColor(), config.highColor());
			case HEAT_RAMP:
			default:
				return HeatColor.ramp(rank);
		}
	}

	private Font fontFor(double rank)
	{
		if (!config.scaleFontByValue())
		{
			return FontManager.getRunescapeSmallFont();
		}
		if (rank >= 0.9)
		{
			return FontManager.getRunescapeBoldFont();
		}
		if (rank >= 0.6)
		{
			return FontManager.getRunescapeFont();
		}
		return FontManager.getRunescapeSmallFont();
	}

	/** A sparkle: relative position within the slot (0..1), animation phase, and max radius. */
	private static final double[][] SPARKLES = {
		// {fx, fy, phase, maxRadius}
		{0.84, 0.15, 0.0, 7.5},
		{0.16, 0.24, 2.1, 6.0},
		{0.68, 0.78, 4.2, 5.0},
		{0.34, 0.62, 5.5, 4.5},
	};

	/**
	 * A few four-point stars around the most valuable stacks, each twinkling on and off out of
	 * phase with the others. Each star is a bright core, a soft body, and long thin cross-flares
	 * for the classic sparkle glint. Animated off wall-clock time so it plays every frame.
	 */
	private void drawSparkles(Graphics2D graphics, Rectangle bounds, double intensity)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
		// Time is NOT scaled by intensity so a changing intensity never jumps the animation.
		double time = System.currentTimeMillis() / 240.0;
		for (double[] s : SPARKLES)
		{
			// max(0, sin) so each star fully vanishes for part of its cycle — a twinkle, not a pulse.
			double twinkle = Math.sin(time + s[2]);
			if (twinkle <= 0.03)
			{
				continue;
			}
			double cx = bounds.x + s[0] * bounds.width;
			double cy = bounds.y + s[1] * bounds.height;
			drawStar(graphics, cx, cy, s[3] * scale * intensity * (0.45 + 0.55 * twinkle), twinkle, intensity);
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	private void drawStar(Graphics2D graphics, double cx, double cy, double outer, double twinkle, double intensity)
	{
		int alpha = clampAlpha((int) (255 * twinkle * intensity));   // dearer stacks glint brighter
		// Long thin cross-flares (the glint), then the fuller star body, then a bright core.
		fillStar(graphics, cx, cy, outer * 2.0, outer * 0.08, new Color(255, 255, 255, clampAlpha((int) (200 * twinkle * intensity))));
		fillStar(graphics, cx, cy, outer, outer * 0.36, new Color(255, 250, 205, alpha));
		double core = Math.max(1.0, outer * 0.28);
		graphics.setColor(new Color(255, 255, 255, alpha));
		graphics.fill(new java.awt.geom.Ellipse2D.Double(cx - core, cy - core, core * 2, core * 2));
	}

	private void fillStar(Graphics2D graphics, double cx, double cy, double outer, double inner, Color color)
	{
		Path2D star = new Path2D.Double();
		for (int i = 0; i < 8; i++)
		{
			double angle = Math.PI / 2 + i * Math.PI / 4;
			double radius = (i % 2 == 0) ? outer : inner;
			double px = cx + Math.cos(angle) * radius;
			double py = cy - Math.sin(angle) * radius;
			if (i == 0)
			{
				star.moveTo(px, py);
			}
			else
			{
				star.lineTo(px, py);
			}
		}
		star.closePath();
		graphics.setColor(color);
		graphics.fill(star);
	}

	private static int clampAlpha(int a)
	{
		return a < 0 ? 0 : Math.min(255, a);
	}

	private static final int EMBER_COUNT = 6;
	/** Golden-ratio step spreads each ember's cycle offset evenly without any clumping. */
	private static final double GOLDEN = 0.6180339887;

	/**
	 * Glowing embers marking the most valuable stacks — a nod to the "Hot Stacks" name. Each ember
	 * runs a continuous, looping life cycle, cools from yellow to red, and fades as it travels.
	 * When {@code radial} they spit outward from the centre (as if the item were a hot coal);
	 * otherwise they rise from the bottom (as if from a fire beneath it).
	 *
	 * <p>Positions are a pure function of time and a per-slot seed (no stored state), so it animates
	 * smoothly and each item differs. Every cycle is a fresh trajectory — angle/distance/drift (or
	 * spawn/sway/height) are hashed from the ember's current cycle number, so an ember never
	 * retraces the same path twice while staying deterministic and stateless.</p>
	 */
	private void drawEmbers(Graphics2D graphics, Rectangle bounds, boolean radial, double intensity)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
		// Time is NOT scaled by intensity: a changing intensity (on a recompute) must not jump the
		// phase and restart the animation. Intensity shows through brightness and size instead.
		double t = System.currentTimeMillis() / 1000.0;
		double sizeMul = (0.7 + scale) * intensity;
		double seed = bounds.x * 0.137 + bounds.y * 0.071;
		double maxDist = Math.max(bounds.width, bounds.height) * 0.72;
		double baseY = bounds.y + bounds.height;
		double rise = bounds.height * 1.25;

		// A faint glow in the middle of the slot backs the embers.
		double gcx = bounds.x + bounds.width / 2.0;
		double gcy = bounds.y + bounds.height / 2.0;
		double glowRadius = bounds.width * 0.32;
		int glowAlpha = clampAlpha((int) (38 * intensity));
		graphics.setPaint(new RadialGradientPaint(
			new java.awt.geom.Point2D.Double(gcx, gcy),
			(float) glowRadius,
			new float[]{0f, 1f},
			new Color[]{new Color(255, 120, 30, glowAlpha), new Color(255, 120, 30, 0)}));
		graphics.fill(new java.awt.geom.Ellipse2D.Double(gcx - glowRadius, gcy - glowRadius, glowRadius * 2, glowRadius * 2));

		for (int i = 0; i < EMBER_COUNT; i++)
		{
			double offset = frac(seed + i * GOLDEN);
			double life = 1.5 + (i % 4) * 0.35;                 // seconds per cycle, varied per ember
			double cyclePos = t / life + offset;
			double cycle = Math.floor(cyclePos);                // which cycle this is — reseeds each loop
			double p = cyclePos - cycle;                        // 0 at the start, 1 at the end of travel

			// Fresh pseudo-random traits per cycle, so no cycle repeats the previous one.
			double h1 = hash(seed + i * 12.9 + cycle * 78.2);
			double h2 = hash(seed + i * 39.3 + cycle * 11.1);
			double h3 = hash(seed + i * 63.7 + cycle * 27.6);

			double x;
			double y;
			if (radial)
			{
				double angle = h1 * Math.PI * 2.0;              // a fresh outward direction each cycle
				double dist = p * maxDist * (0.7 + 0.5 * h2);   // vary how far each spark flies
				double curve = Math.sin(p * Math.PI * (1.0 + h3)) * bounds.width * 0.06;
				x = gcx + Math.cos(angle) * dist - Math.sin(angle) * curve;
				y = gcy + Math.sin(angle) * dist + Math.cos(angle) * curve;
			}
			else
			{
				double spawnX = bounds.x + bounds.width * (0.12 + 0.76 * h1);
				double sway = Math.sin(p * (1.5 + 2.5 * h3) * Math.PI + h1 * Math.PI * 2) * bounds.width * (0.07 + 0.13 * h2);
				x = spawnX + sway;
				y = baseY - p * rise * (0.8 + 0.4 * h2);        // vary how high each rise reaches
			}

			// Quick fade-in at the start, fade out as it travels; dampened, and brighter for dearer stacks.
			double alpha = Math.min(1.0, p * 6.0) * (1.0 - p) * 0.55 * intensity;
			if (alpha <= 0.02)
			{
				continue;
			}
			double r = (0.7 + 0.9 * (1.0 - p)) * sizeMul;       // shrink slightly as it travels

			int g = clampChannel((int) (215 - 175 * p));        // yellow (hot) → red (cool)
			int b = clampChannel((int) (95 - 85 * p));
			int a = clampAlpha((int) (255 * alpha));
			fillDot(graphics, x, y, r * 1.7, new Color(255, g, b, clampAlpha((int) (a * 0.22))));
			fillDot(graphics, x, y, r, new Color(255, g, b, a));
			fillDot(graphics, x, y, r * 0.45, new Color(255, 245, 205, a));
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	/** Deterministic pseudo-random value in [0, 1) from any input — the classic sine hash. */
	private static double hash(double n)
	{
		return frac(Math.sin(n) * 43758.5453123);
	}

	private void fillDot(Graphics2D graphics, double cx, double cy, double radius, Color color)
	{
		graphics.setColor(color);
		graphics.fill(new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
	}

	/** The configured effect size, clamped to [0, 3] in case an out-of-range value was stored. */
	private double effectScale()
	{
		double s = config.sparkleScale();
		return s < 0 ? 0 : Math.min(3.0, s);
	}

	private static double frac(double v)
	{
		return v - Math.floor(v);
	}

	private static int clampChannel(int c)
	{
		return c < 0 ? 0 : Math.min(255, c);
	}
}

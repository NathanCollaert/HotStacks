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
 * view (cheapest → dearest). Dearer stacks are optionally drawn in a heavier font, and the very
 * top stacks get an animated glow and sparkle.
 *
 * <p>{@code renderItemOverlay} is invoked once per visible bank item each frame, so it doubles as
 * the pass that feeds {@link BankValueModel#record(long)} the values used to rank the colours.</p>
 */
@Singleton
class HotStacksOverlay extends WidgetItemOverlay
{
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
			// Untradeable (and not a charged tradeable), or worth nothing: nothing to show.
			return;
		}

		// The value label and the top-item effect are independent: "Show stack values" only governs
		// the label, so the effect can still mark top items when the label is turned off.
		boolean showValue = config.enabled() && value >= config.minValue();
		TopEffect effect = model.isTop(value, config.sparkleTopPercent()) ? config.topEffect() : TopEffect.NONE;
		if (!showValue && effect == TopEffect.NONE)
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		if (showValue)
		{
			double rank = model.rankFraction(value);
			Color color = colourFor(rank);
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

		// Effects sit on top so they drift around/in front of the item.
		if (effect == TopEffect.EMBERS)
		{
			drawEmbers(graphics, bounds);
		}
		else if (effect == TopEffect.SPARKLE)
		{
			drawSparkles(graphics, bounds);
		}
	}

	private Color colourFor(double rank)
	{
		switch (config.colourMode())
		{
			case SINGLE:
				return Color.WHITE;
			case GRADIENT:
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
	private void drawSparkles(Graphics2D graphics, Rectangle bounds)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
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
			drawStar(graphics, cx, cy, s[3] * scale * (0.45 + 0.55 * twinkle), twinkle);
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	private void drawStar(Graphics2D graphics, double cx, double cy, double outer, double twinkle)
	{
		int alpha = clampAlpha((int) (255 * twinkle));
		// Long thin cross-flares (the glint), then the fuller star body, then a bright core.
		fillStar(graphics, cx, cy, outer * 2.0, outer * 0.08, new Color(255, 255, 255, clampAlpha((int) (200 * twinkle))));
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
	 * Glowing embers drifting up and around the slot, as if from a fire beneath it — a nod to the
	 * "Hot Stacks" name. Each ember rises on a continuous, looping life cycle, sways sideways,
	 * cools from yellow to red, and fades as it climbs. Positions are a pure function of time and a
	 * per-slot seed (no stored state), so it animates smoothly and each item's embers differ.
	 */
	private void drawEmbers(Graphics2D graphics, Rectangle bounds)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
		double t = System.currentTimeMillis() / 1000.0;
		double baseY = bounds.y + bounds.height;
		double rise = bounds.height * 1.25;
		double sizeMul = 0.7 + scale;
		double seed = bounds.x * 0.137 + bounds.y * 0.071;

		// A faint glow in the middle of the slot backs the embers.
		double gcx = bounds.x + bounds.width / 2.0;
		double gcy = bounds.y + bounds.height / 2.0;
		double glowRadius = bounds.width * 0.32;
		graphics.setPaint(new RadialGradientPaint(
			new java.awt.geom.Point2D.Double(gcx, gcy),
			(float) glowRadius,
			new float[]{0f, 1f},
			new Color[]{new Color(255, 120, 30, 38), new Color(255, 120, 30, 0)}));
		graphics.fill(new java.awt.geom.Ellipse2D.Double(gcx - glowRadius, gcy - glowRadius, glowRadius * 2, glowRadius * 2));

		for (int i = 0; i < EMBER_COUNT; i++)
		{
			double offset = frac(seed + i * GOLDEN);
			double life = 1.5 + (i % 4) * 0.35;                 // seconds per rise, varied per ember
			double p = frac(t / life + offset);                 // 0 at the base, 1 fully risen
			double spawnX = bounds.x + bounds.width * frac(seed * 3.1 + i * 0.41);
			double sway = Math.sin(p * (2.0 + (i % 3)) * Math.PI + i) * bounds.width * 0.14;
			double x = spawnX + sway;
			double y = baseY - p * rise;

			// Quick fade-in at the base, fade out as it climbs; dampened for a subtle look.
			double alpha = Math.min(1.0, p * 6.0) * (1.0 - p) * 0.55;
			if (alpha <= 0.02)
			{
				continue;
			}
			double r = (0.7 + 0.9 * (1.0 - p)) * sizeMul;       // shrink slightly as it rises

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

package com.hotstacks;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Point;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.QuantityFormatter;

@Singleton
class HotStacksOverlay extends WidgetItemOverlay
{
	private static final double FULL_INTENSITY_WEALTH_SHARE = 0.06;
	private static final double INTENSITY_FLOOR = 0.3;
	private static final double INTENSITY_CEIL = 1.35;
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
		if (model.layoutActive())
		{
			return;
		}

		long value = model.weightOf(itemId, widgetItem.getQuantity());
		if (value <= 0)
		{
			return;
		}

		boolean valueIsCount = config.valueSource() == ValueSource.WITHDRAWS;
		boolean showValue = config.showStackValue() && (valueIsCount || value >= config.minValue());
		boolean tint = slotTintEnabled();
		TopEffect effect = model.isTop(value, itemId) ? config.topEffect() : TopEffect.NONE;
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
		if (tint)
		{
			drawTint(graphics, bounds, color, tintIntensity(rank));
		}
		if (showValue)
		{
			drawValueLabel(graphics, bounds, formatValue(value, valueIsCount), rank, color);
		}
		drawEffect(graphics, bounds, effect, effectIntensity(value));
	}

	/** Excludes Density field, which HeatFieldOverlay draws as one whole-bank pass instead. */
	private boolean slotTintEnabled()
	{
		return config.thermalTint() && config.colourMode() != ColourMode.DENSITY_FIELD;
	}

	/** Scaled by rank, not wealth share: wealth share would fade all but the top few stacks to nothing. */
	private double tintIntensity(double rank)
	{
		return config.scaleHeatmapByValue() ? intensityFor(rank) : 1.0;
	}

	/** Scaled by wealth share: the effect only marks the top few stacks, where shares differ enough to show. */
	private double effectIntensity(long value)
	{
		return config.scaleEffectByValue() ? intensityFor(normalisedWealthShare(value)) : 1.0;
	}

	private static String formatValue(long value, boolean valueIsCount)
	{
		return valueIsCount ? Long.toString(value) : QuantityFormatter.quantityToStackSize(value);
	}

	private void drawValueLabel(Graphics2D graphics, Rectangle bounds, String text, double rank, Color color)
	{
		graphics.setFont(fontFor(rank));
		FontMetrics metrics = graphics.getFontMetrics();
		int x = bounds.x + (bounds.width - metrics.stringWidth(text)) / 2;
		int y = bounds.y + bounds.height - metrics.getDescent();

		if (config.coverText())
		{
			drawTextBackground(graphics, bounds, metrics, y);
		}

		OverlayUtil.renderTextLocation(graphics, new Point(x, y), text, color);
	}

	private void drawTextBackground(Graphics2D graphics, Rectangle bounds, FontMetrics metrics, int baseline)
	{
		int top = baseline - metrics.getAscent();
		graphics.setColor(config.textBackgroundColor());
		graphics.fillRect(bounds.x, top, bounds.width, metrics.getAscent() + metrics.getDescent());
	}

	private void drawEffect(Graphics2D graphics, Rectangle bounds, TopEffect effect, double intensity)
	{
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

	private double intensityFor(double norm)
	{
		double n = norm < 0 ? 0 : Math.min(1.0, norm);
		return INTENSITY_FLOOR + (INTENSITY_CEIL - INTENSITY_FLOOR) * n;
	}

	private double normalisedWealthShare(long value)
	{
		long total = model.viewTotal();
		double share = total > 0 ? (double) value / total : 0;
		return Math.min(1.0, share / FULL_INTENSITY_WEALTH_SHARE);
	}

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
			case DENSITY_FIELD:
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

	private static final int SPARKLE_X_FRACTION = 0;
	private static final int SPARKLE_Y_FRACTION = 1;
	private static final int SPARKLE_PHASE = 2;
	private static final int SPARKLE_MAX_RADIUS = 3;
	private static final double[][] SPARKLES = {
		{0.84, 0.15, 0.0, 7.5},
		{0.16, 0.24, 2.1, 6.0},
		{0.68, 0.78, 4.2, 5.0},
		{0.34, 0.62, 5.5, 4.5},
	};

	private void drawSparkles(Graphics2D graphics, Rectangle bounds, double intensity)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
		double time = animationTime(240.0);
		for (double[] s : SPARKLES)
		{
			double twinkle = Math.sin(time + s[SPARKLE_PHASE]);
			if (twinkle <= 0.03)
			{
				continue;
			}
			double cx = bounds.x + s[SPARKLE_X_FRACTION] * bounds.width;
			double cy = bounds.y + s[SPARKLE_Y_FRACTION] * bounds.height;
			drawStar(graphics, cx, cy, s[SPARKLE_MAX_RADIUS] * scale * intensity * (0.45 + 0.55 * twinkle), twinkle, intensity);
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	private void drawStar(Graphics2D graphics, double cx, double cy, double outer, double twinkle, double intensity)
	{
		int alpha = clampAlpha((int) (255 * twinkle * intensity));
		int flareAlpha = clampAlpha((int) (200 * twinkle * intensity));
		drawGlintFlares(graphics, cx, cy, outer, flareAlpha);
		drawStarBody(graphics, cx, cy, outer, alpha);
		drawStarCore(graphics, cx, cy, outer, alpha);
	}

	private void drawGlintFlares(Graphics2D graphics, double cx, double cy, double outer, int alpha)
	{
		fillStar(graphics, cx, cy, outer * 2.0, outer * 0.08, new Color(255, 255, 255, alpha));
	}

	private void drawStarBody(Graphics2D graphics, double cx, double cy, double outer, int alpha)
	{
		fillStar(graphics, cx, cy, outer, outer * 0.36, new Color(255, 250, 205, alpha));
	}

	private void drawStarCore(Graphics2D graphics, double cx, double cy, double outer, int alpha)
	{
		fillDot(graphics, cx, cy, Math.max(1.0, outer * 0.28), new Color(255, 255, 255, alpha));
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
	/** Stepping each ember's cycle offset by this spreads them evenly, with no clumping. */
	private static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887;

	private void drawEmbers(Graphics2D graphics, Rectangle bounds, boolean radial, double intensity)
	{
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		double scale = effectScale();
		double seconds = animationTime(1000.0);
		double sizeMul = (0.7 + scale) * intensity;
		double slotSeed = bounds.x * 0.137 + bounds.y * 0.071;

		drawEmberGlow(graphics, bounds, intensity);

		for (int i = 0; i < EMBER_COUNT; i++)
		{
			double offset = frac(slotSeed + i * GOLDEN_RATIO_CONJUGATE);
			double cycleSeconds = 1.5 + (i % 4) * 0.35;
			double cyclePosition = seconds / cycleSeconds + offset;
			double cycleNumber = Math.floor(cyclePosition);
			double progress = cyclePosition - cycleNumber;

			double h1 = pseudoRandomFraction(slotSeed + i * 12.9 + cycleNumber * 78.2);
			double h2 = pseudoRandomFraction(slotSeed + i * 39.3 + cycleNumber * 11.1);
			double h3 = pseudoRandomFraction(slotSeed + i * 63.7 + cycleNumber * 27.6);
			Point2D.Double position = radial
				? radialEmberPosition(bounds, progress, h1, h2, h3)
				: risingEmberPosition(bounds, progress, h1, h2, h3);

			double alpha = emberAlpha(progress, intensity);
			if (alpha <= 0.02)
			{
				continue;
			}
			drawEmber(graphics, position, progress, alpha, sizeMul);
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	private void drawEmberGlow(Graphics2D graphics, Rectangle bounds, double intensity)
	{
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
	}

	private static Point2D.Double radialEmberPosition(Rectangle bounds, double progress, double h1, double h2, double h3)
	{
		double centreX = bounds.x + bounds.width / 2.0;
		double centreY = bounds.y + bounds.height / 2.0;
		double maxDistance = Math.max(bounds.width, bounds.height) * 0.72;
		double outwardAngle = h1 * Math.PI * 2.0;
		double distance = progress * maxDistance * (0.7 + 0.5 * h2);
		double curve = Math.sin(progress * Math.PI * (1.0 + h3)) * bounds.width * 0.06;
		return new Point2D.Double(
			centreX + Math.cos(outwardAngle) * distance - Math.sin(outwardAngle) * curve,
			centreY + Math.sin(outwardAngle) * distance + Math.cos(outwardAngle) * curve);
	}

	private static Point2D.Double risingEmberPosition(Rectangle bounds, double progress, double h1, double h2, double h3)
	{
		double spawnX = bounds.x + bounds.width * (0.12 + 0.76 * h1);
		double sway = Math.sin(progress * (1.5 + 2.5 * h3) * Math.PI + h1 * Math.PI * 2) * bounds.width * (0.07 + 0.13 * h2);
		double bottom = bounds.y + bounds.height;
		double maxRise = bounds.height * 1.25;
		return new Point2D.Double(spawnX + sway, bottom - progress * maxRise * (0.8 + 0.4 * h2));
	}

	private static double emberAlpha(double progress, double intensity)
	{
		double fadeIn = Math.min(1.0, progress * 6.0);
		double fadeOut = 1.0 - progress;
		return fadeIn * fadeOut * 0.55 * intensity;
	}

	private void drawEmber(Graphics2D graphics, Point2D.Double position, double progress, double alpha, double sizeMul)
	{
		double shrinkingRadius = (0.7 + 0.9 * (1.0 - progress)) * sizeMul;
		int coolingGreen = clampChannel((int) (215 - 175 * progress));
		int coolingBlue = clampChannel((int) (95 - 85 * progress));
		int a = clampAlpha((int) (255 * alpha));
		fillDot(graphics, position.x, position.y, shrinkingRadius * 1.7, new Color(255, coolingGreen, coolingBlue, clampAlpha((int) (a * 0.22))));
		fillDot(graphics, position.x, position.y, shrinkingRadius, new Color(255, coolingGreen, coolingBlue, a));
		fillDot(graphics, position.x, position.y, shrinkingRadius * 0.45, new Color(255, 245, 205, a));
	}

	/** Not scaled by intensity: an intensity change would then jump the animation's phase. */
	private static double animationTime(double millisPerUnit)
	{
		return System.currentTimeMillis() / millisPerUnit;
	}

	private static double pseudoRandomFraction(double seed)
	{
		return frac(Math.sin(seed) * 43758.5453123);
	}

	private void fillDot(Graphics2D graphics, double cx, double cy, double radius, Color color)
	{
		graphics.setColor(color);
		graphics.fill(new java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
	}

	/** {@code @Range} only limits the config UI, so a stored value can still be out of range. */
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

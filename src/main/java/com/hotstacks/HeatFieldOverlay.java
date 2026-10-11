package com.hotstacks;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The field is built in content space: positioned relative to the top-left blob, covering every slot
 * in the tab including those scrolled out of view, and normalised over all of them. Scrolling then
 * only pans the cached image, with no rebuild and no re-normalisation flicker.
 *
 * <p>The left/right edge fade is baked into the cache because the bank never scrolls horizontally.
 * The top/bottom fade sits at viewport edges the content slides past, so it is masked in each frame.</p>
 */
@Singleton
class HeatFieldOverlay extends Overlay
{
	private static final int DOWNSCALE = 3;
	private static final int MAX_ALPHA = 205;
	private static final int BLOB_REACH = 60;
	private static final int IMAGE_PADDING = 72;      // must exceed BLOB_REACH, or blobs are cut off at the image edge
	private static final int EDGE_FEATHER_X = 20;
	private static final int EDGE_FEATHER_TOP = 22;
	private static final int EDGE_FEATHER_BOTTOM = 8;
	private static final int TOP_BLEED = 12;          // the top fade spills into the gap below the bank tabs
	private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;

	private final Client client;
	private final BankValueModel model;
	private final HotStacksConfig config;

	private BufferedImage cachedField;
	private long cacheKey;
	private int cachedFieldCanvasWidth;
	private int cachedFieldCanvasHeight;
	private BufferedImage maskBuffer;

	@Inject
	HeatFieldOverlay(Client client, BankValueModel model, HotStacksConfig config)
	{
		this.client = client;
		this.model = model;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!densityFieldEnabled() || model.layoutActive())
		{
			cachedField = null;
			return null;
		}

		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (container == null || container.isHidden())
		{
			cachedField = null;
			return null;
		}
		Widget[] children = container.getChildren();
		Point viewLocation = container.getCanvasLocation();
		if (children == null || viewLocation == null)
		{
			return null;
		}
		int viewX = viewLocation.getX();
		int viewY = viewLocation.getY();
		int viewW = container.getWidth();
		int viewH = container.getHeight();

		int[] xs = new int[children.length];
		int[] ys = new int[children.length];
		double[] peaks = new double[children.length];
		int n = collectBlobs(children, xs, ys, peaks);
		if (n == 0)
		{
			cachedField = null;
			return null;
		}

		int minX = minOf(xs, n);
		int minY = minOf(ys, n);
		int imageX = minX - IMAGE_PADDING;
		int imageY = minY - IMAGE_PADDING;
		int imageW = maxOf(xs, n) + IMAGE_PADDING - imageX;
		int imageH = maxOf(ys, n) + IMAGE_PADDING - imageY;

		long key = scrollInvariantKey(xs, ys, peaks, n, minX, minY, imageX - viewX, viewW, imageW, imageH);
		if (key != cacheKey || cachedField == null)
		{
			rebuild(xs, ys, peaks, n, imageX, imageY, imageW, imageH, viewX, viewW);
			cacheKey = key;
		}

		drawWithVerticalFeather(graphics, imageX, imageY, viewX, viewY, viewW, viewH);
		return null;
	}

	private boolean densityFieldEnabled()
	{
		return config.thermalTint() && config.colourMode() == ColourMode.DENSITY_FIELD;
	}

	/** Scrolled-off slots are clipped, not hidden, so they count too. */
	private static boolean isShownItem(Widget slot)
	{
		return slot != null && !slot.isSelfHidden() && slot.getItemId() > 0;
	}

	/** Fills {@code xs}, {@code ys} and {@code peaks} from index 0 and returns how many blobs it wrote. */
	private int collectBlobs(Widget[] children, int[] xs, int[] ys, double[] peaks)
	{
		int n = 0;
		for (Widget child : children)
		{
			if (!isShownItem(child))
			{
				continue;
			}
			long value = model.weightOf(child.getItemId(), child.getItemQuantity());
			if (value <= 0)
			{
				continue;
			}
			Point loc = child.getCanvasLocation();
			if (loc == null)
			{
				continue;
			}
			xs[n] = loc.getX() + child.getWidth() / 2;
			ys[n] = loc.getY() + child.getHeight() / 2;
			peaks[n] = model.rankFraction(value);
			n++;
		}
		return n;
	}

	private static int minOf(int[] values, int count)
	{
		int min = Integer.MAX_VALUE;
		for (int i = 0; i < count; i++)
		{
			min = Math.min(min, values[i]);
		}
		return min;
	}

	private static int maxOf(int[] values, int count)
	{
		int max = Integer.MIN_VALUE;
		for (int i = 0; i < count; i++)
		{
			max = Math.max(max, values[i]);
		}
		return max;
	}

	/**
	 * Uses positions relative to the top-left blob, so scrolling doesn't change it, plus the
	 * horizontal geometry the baked left/right fade depends on.
	 */
	private long scrollInvariantKey(int[] xs, int[] ys, double[] peaks, int n, int minX, int minY,
		int imageLeftInView, int viewW, int imageW, int imageH)
	{
		long key = FNV_OFFSET_BASIS;
		for (int i = 0; i < n; i++)
		{
			key = fnvMix(key, xs[i] - minX);
			key = fnvMix(key, ys[i] - minY);
			key = fnvMix(key, Double.doubleToLongBits(peaks[i]));
		}
		key = fnvMix(key, imageLeftInView);
		key = fnvMix(key, viewW);
		key = fnvMix(key, imageW);
		key = fnvMix(key, imageH);
		key = fnvMix(key, config.lowColor().getRGB());
		key = fnvMix(key, config.highColor().getRGB());
		return key;
	}

	private static long fnvMix(long hash, long value)
	{
		return (hash ^ value) * FNV_PRIME;
	}

	private void drawWithVerticalFeather(Graphics2D graphics, int imageX, int imageY, int viewX, int viewY, int viewW, int viewH)
	{
		int bufferW = viewW;
		int bufferH = viewH + TOP_BLEED;
		if (bufferW <= 0 || bufferH <= 0)
		{
			return;
		}
		if (maskBuffer == null || maskBuffer.getWidth() != bufferW || maskBuffer.getHeight() != bufferH)
		{
			maskBuffer = new BufferedImage(bufferW, bufferH, BufferedImage.TYPE_INT_ARGB);
		}

		int bufferTop = viewY - TOP_BLEED;
		Graphics2D bg = maskBuffer.createGraphics();
		clearBuffer(bg, bufferW, bufferH);
		bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		bg.drawImage(cachedField, imageX - viewX, imageY - bufferTop, cachedFieldCanvasWidth, cachedFieldCanvasHeight, null);
		applyVerticalFeather(bg, bufferW, bufferH);
		bg.dispose();

		graphics.drawImage(maskBuffer, viewX, bufferTop, null);
	}

	private static void clearBuffer(Graphics2D bg, int width, int height)
	{
		bg.setComposite(AlphaComposite.Clear);
		bg.fillRect(0, 0, width, height);
		bg.setComposite(AlphaComposite.SrcOver);
	}

	private static void applyVerticalFeather(Graphics2D bg, int width, int height)
	{
		float topStop = Math.min(0.49f, (float) EDGE_FEATHER_TOP / height);
		float bottomStop = Math.max(topStop + 0.01f, 1f - (float) EDGE_FEATHER_BOTTOM / height);
		Color clear = new Color(255, 255, 255, 0);
		Color solid = new Color(255, 255, 255, 255);
		bg.setComposite(AlphaComposite.DstIn);
		bg.setPaint(new LinearGradientPaint(
			new Point2D.Float(0, 0), new Point2D.Float(0, height),
			new float[]{0f, topStop, bottomStop, 1f},
			new Color[]{clear, solid, solid, clear}));
		bg.fillRect(0, 0, width, height);
	}

	private void rebuild(int[] xs, int[] ys, double[] peaks, int n, int imageX, int imageY, int imageW, int imageH, int viewX, int viewW)
	{
		int gw = Math.max(1, imageW / DOWNSCALE);
		int gh = Math.max(1, imageH / DOWNSCALE);
		float[] field = stampBlobs(xs, ys, peaks, n, imageX, imageY, gw, gh);
		boxBlur(field, gw, gh, 2, 2);
		cachedField = colourField(field, gw, gh, imageX, viewX, viewW);
		cachedFieldCanvasWidth = imageW;
		cachedFieldCanvasHeight = imageH;
	}

	private static float[] stampBlobs(int[] xs, int[] ys, double[] peaks, int n, int imageX, int imageY, int gw, int gh)
	{
		float[] field = new float[gw * gh];
		double blobR = (double) BLOB_REACH / DOWNSCALE;
		double sigma = blobR / 3.0;
		double twoSigmaSq = 2.0 * sigma * sigma;
		for (int i = 0; i < n; i++)
		{
			double gx = (double) (xs[i] - imageX) / DOWNSCALE;
			double gy = (double) (ys[i] - imageY) / DOWNSCALE;
			int x0 = Math.max(0, (int) (gx - blobR));
			int x1 = Math.min(gw - 1, (int) (gx + blobR));
			int y0 = Math.max(0, (int) (gy - blobR));
			int y1 = Math.min(gh - 1, (int) (gy + blobR));
			for (int py = y0; py <= y1; py++)
			{
				for (int px = x0; px <= x1; px++)
				{
					double dx = px - gx;
					double dy = py - gy;
					field[py * gw + px] += peaks[i] * Math.exp(-(dx * dx + dy * dy) / twoSigmaSq);
				}
			}
		}
		return field;
	}

	private BufferedImage colourField(float[] field, int gw, int gh, int imageX, int viewX, int viewW)
	{
		double max = highestDensity(field);
		Color low = config.lowColor();
		Color high = config.highColor();
		BufferedImage img = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB);
		double inv = max > 0 ? 1.0 / max : 0;
		for (int i = 0; i < field.length; i++)
		{
			double t = field[i] * inv;
			if (t <= 0.02)
			{
				continue;
			}
			int px = i % gw;
			int py = i / gw;
			double fade = horizontalEdgeFade(imageX + px * DOWNSCALE, viewX, viewW);
			if (fade <= 0)
			{
				continue;
			}

			Color c = HeatColor.blend(t, low, high);
			int a = (int) (Math.min(1.0, t * 1.25) * MAX_ALPHA * fade);
			if (a <= 0)
			{
				continue;
			}
			img.setRGB(px, py, (a << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue());
		}
		return img;
	}

	private static double highestDensity(float[] field)
	{
		double max = 0;
		for (float v : field)
		{
			if (v > max)
			{
				max = v;
			}
		}
		return max;
	}

	private static double horizontalEdgeFade(double canvasX, int viewX, int viewW)
	{
		double distX = Math.min(canvasX - viewX, viewX + viewW - canvasX);
		if (distX <= 0)
		{
			return 0;
		}
		return smoothstep(Math.min(1.0, distX / EDGE_FEATHER_X));
	}

	private static double smoothstep(double x)
	{
		return x * x * (3.0 - 2.0 * x);
	}

	private static void boxBlur(float[] data, int w, int h, int radius, int passes)
	{
		float[] tmp = new float[data.length];
		for (int pass = 0; pass < passes; pass++)
		{
			for (int y = 0; y < h; y++)
			{
				int row = y * w;
				for (int x = 0; x < w; x++)
				{
					float sum = 0;
					int count = 0;
					for (int k = -radius; k <= radius; k++)
					{
						int xx = x + k;
						if (xx >= 0 && xx < w)
						{
							sum += data[row + xx];
							count++;
						}
					}
					tmp[row + x] = sum / count;
				}
			}
			for (int x = 0; x < w; x++)
			{
				for (int y = 0; y < h; y++)
				{
					float sum = 0;
					int count = 0;
					for (int k = -radius; k <= radius; k++)
					{
						int yy = y + k;
						if (yy >= 0 && yy < h)
						{
							sum += tmp[yy * w + x];
							count++;
						}
					}
					data[y * w + x] = sum / count;
				}
			}
		}
	}
}

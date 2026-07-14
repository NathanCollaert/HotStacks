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
 * Renders the bank as a continuous density heat field (the {@link ColourMode#DENSITY_FIELD} mode):
 * every shown stack stamps a soft blob whose height is its rank, the blobs sum into one surface,
 * and that surface is coloured along the configured Lowest→Highest ramp. Where valuable stacks sit
 * (or cluster) the field runs hot, so you can see at a glance where your wealth lives.
 *
 * <p>The field is built in <b>content space</b>: it covers the whole open tab (all non-hidden
 * slots, including any scrolled out of view) using positions relative to the top-left slot, and is
 * normalised once over the whole tab. Scrolling therefore leaves the cached image unchanged — the
 * field just pans with the items, so there's no per-frame rebuild or re-normalisation flicker. The
 * cache only rebuilds when the tab's items, their values or the ramp colours change.</p>
 *
 * <p>The left/right edge fade is baked into the image (the bank doesn't scroll horizontally). The
 * top/bottom fade is at the viewport edges, which the content slides past, so it's applied each
 * frame with an offscreen buffer and a {@code DstIn} gradient mask rather than baked in.</p>
 */
@Singleton
class HeatFieldOverlay extends Overlay
{
	private static final int DOWNSCALE = 3;
	private static final int MAX_ALPHA = 205;
	private static final int PAD = 72;                 // image padding; must exceed a blob's reach
	private static final int EDGE_FEATHER_X = 20;
	private static final int EDGE_FEATHER_TOP = 22;
	private static final int EDGE_FEATHER_BOTTOM = 8;
	private static final int TOP_BLEED = 12;           // top fade spills into the gap below the tabs
	private static final long FNV = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;

	private final Client client;
	private final BankValueModel model;
	private final HotStacksConfig config;

	private BufferedImage cache;   // content-space field, left/right fade baked in
	private long cacheKey;
	private int cacheW;
	private int cacheH;
	private BufferedImage frame;   // reused viewport-sized scratch buffer for the top/bottom mask

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
		if (!config.thermalTint() || config.colourMode() != ColourMode.DENSITY_FIELD)
		{
			cache = null;
			return null;
		}

		// Stand down over a loadout (Bank Tag Layout): its curated, fake-quantity items would skew
		// the field.
		if (model.layoutActive())
		{
			cache = null;
			return null;
		}

		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (container == null || container.isHidden())
		{
			cache = null;
			return null;
		}
		Widget[] children = container.getChildren();
		Point cloc = container.getCanvasLocation();
		if (children == null || cloc == null)
		{
			return null;
		}
		int vx = cloc.getX();
		int vy = cloc.getY();
		int vw = container.getWidth();
		int vh = container.getHeight();

		// Gather EVERY non-hidden slot in the tab (scrolled-off ones too — they're clipped, not
		// hidden). Positions are canvas coords this frame; the cache keys off positions relative to
		// the top-left slot, which are scroll-invariant.
		int[] xs = new int[children.length];
		int[] ys = new int[children.length];
		double[] peaks = new double[children.length];
		int n = 0;
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden() || child.getItemId() <= 0)
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
			int cx = loc.getX() + child.getWidth() / 2;
			int cy = loc.getY() + child.getHeight() / 2;
			xs[n] = cx;
			ys[n] = cy;
			peaks[n] = model.rankFraction(value);
			n++;
			minX = Math.min(minX, cx);
			minY = Math.min(minY, cy);
			maxX = Math.max(maxX, cx);
			maxY = Math.max(maxY, cy);
		}

		if (n == 0)
		{
			cache = null;
			return null;
		}

		int ox = minX - PAD;   // canvas position of the image's top-left (current frame)
		int oy = minY - PAD;
		int ow = maxX + PAD - ox;
		int oh = maxY + PAD - oy;

		// Scroll-invariant key: relative positions + the horizontal geometry the baked L/R fade uses.
		long key = FNV;
		for (int i = 0; i < n; i++)
		{
			key = (key ^ (xs[i] - minX)) * FNV_PRIME;
			key = (key ^ (ys[i] - minY)) * FNV_PRIME;
			key = (key ^ Double.doubleToLongBits(peaks[i])) * FNV_PRIME;
		}
		key = (key ^ (ox - vx)) * FNV_PRIME;   // image-left relative to viewport-left (horizontal only)
		key = (key ^ vw) * FNV_PRIME;
		key = (key ^ ow) * FNV_PRIME;
		key = (key ^ oh) * FNV_PRIME;
		key = (key ^ config.lowColor().getRGB()) * FNV_PRIME;
		key = (key ^ config.highColor().getRGB()) * FNV_PRIME;

		if (key != cacheKey || cache == null)
		{
			rebuild(xs, ys, peaks, n, ox, oy, ow, oh, vx, vw);
			cacheKey = key;
		}

		drawMasked(graphics, ox, oy, vx, vy, vw, vh);
		return null;
	}

	/**
	 * Blits the cached field to the viewport through an offscreen buffer, then multiplies its alpha
	 * by a vertical gradient (via {@code DstIn}) so the top and bottom fade out at the viewport edges.
	 */
	private void drawMasked(Graphics2D graphics, int ox, int oy, int vx, int vy, int vw, int vh)
	{
		int bufW = vw;
		int bufH = vh + TOP_BLEED;
		if (bufW <= 0 || bufH <= 0)
		{
			return;
		}
		if (frame == null || frame.getWidth() != bufW || frame.getHeight() != bufH)
		{
			frame = new BufferedImage(bufW, bufH, BufferedImage.TYPE_INT_ARGB);
		}

		Graphics2D bg = frame.createGraphics();
		bg.setComposite(AlphaComposite.Clear);
		bg.fillRect(0, 0, bufW, bufH);
		bg.setComposite(AlphaComposite.SrcOver);
		bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

		// Buffer's top-left is canvas (vx, vy - TOP_BLEED); place the cached field within it.
		bg.drawImage(cache, ox - vx, oy - (vy - TOP_BLEED), cacheW, cacheH, null);

		// Top/bottom feather mask: alpha 0 at the buffer edges, 1 across the middle.
		float topStop = Math.min(0.49f, (float) EDGE_FEATHER_TOP / bufH);
		float bottomStop = Math.max(topStop + 0.01f, 1f - (float) EDGE_FEATHER_BOTTOM / bufH);
		Color clear = new Color(255, 255, 255, 0);
		Color solid = new Color(255, 255, 255, 255);
		bg.setComposite(AlphaComposite.DstIn);
		bg.setPaint(new LinearGradientPaint(
			new Point2D.Float(0, 0), new Point2D.Float(0, bufH),
			new float[]{0f, topStop, bottomStop, 1f},
			new Color[]{clear, solid, solid, clear}));
		bg.fillRect(0, 0, bufW, bufH);
		bg.dispose();

		graphics.drawImage(frame, vx, vy - TOP_BLEED, null);
	}

	/** Builds the cached content-space field (left/right fade baked; top/bottom done at draw time). */
	private void rebuild(int[] xs, int[] ys, double[] peaks, int n, int ox, int oy, int ow, int oh, int vx, int vw)
	{
		int gw = Math.max(1, ow / DOWNSCALE);
		int gh = Math.max(1, oh / DOWNSCALE);
		float[] field = new float[gw * gh];

		double blobR = 60.0 / DOWNSCALE;   // stamp reach (grid px); PAD must exceed blobR*DOWNSCALE
		double sigma = blobR / 3.0;
		double twoSigmaSq = 2.0 * sigma * sigma;
		for (int i = 0; i < n; i++)
		{
			double gx = (double) (xs[i] - ox) / DOWNSCALE;
			double gy = (double) (ys[i] - oy) / DOWNSCALE;
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

		boxBlur(field, gw, gh, 2, 2);

		double max = 0;
		for (float v : field)
		{
			if (v > max)
			{
				max = v;
			}
		}

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
			// Left/right fade only (horizontal is scroll-fixed); top/bottom is masked at draw time.
			double canvasX = ox + px * DOWNSCALE;
			double distX = Math.min(canvasX - vx, vx + vw - canvasX);
			if (distX <= 0)
			{
				continue;
			}
			double feather = Math.min(1.0, distX / EDGE_FEATHER_X);
			feather = feather * feather * (3.0 - 2.0 * feather);   // smoothstep

			Color c = HeatColor.blend(t, low, high);
			int a = (int) (Math.min(1.0, t * 1.25) * MAX_ALPHA * feather);
			if (a <= 0)
			{
				continue;
			}
			img.setRGB(px, py, (a << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue());
		}
		cache = img;
		cacheW = ow;
		cacheH = oh;
	}

	/** Separable box blur, {@code passes} times, to smooth the summed density field. */
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

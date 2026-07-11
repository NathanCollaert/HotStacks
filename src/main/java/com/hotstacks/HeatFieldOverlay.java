package com.hotstacks;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
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
 * <p>Unlike the per-slot tint this is one whole-bank pass, so it lives in its own {@link Overlay}
 * drawn over the bank interface. The field is rendered once to a small (downscaled) image and
 * cached; it only rebuilds when the shown slots, their values, scroll position or the ramp colours
 * change, and is drawn each frame as a single bilinear-scaled blit.</p>
 */
@Singleton
class HeatFieldOverlay extends Overlay
{
	private static final int DOWNSCALE = 3;   // field built at 1/3 res, scaled up for a soft look
	private static final int MAX_ALPHA = 205;
	private static final int EDGE_FEATHER_X = 20;        // px fade at the left/right bank edges
	private static final int EDGE_FEATHER_TOP = 22;      // px fade at the top edge
	private static final int EDGE_FEATHER_BOTTOM = 8;    // narrower: fades into open space below
	// Let the top fade start above the container (in the gap below the tabs) so the fade doesn't
	// dim the top item row — it completes over the gap instead of eating into the items.
	private static final int TOP_BLEED = 12;
	private static final long FNV = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;
	// Image padding around the slots: must exceed a blob's reach so edge blobs fully fade inside
	// the image (no hard cut at the image boundary, which the container-edge feather doesn't reach).
	private static final int PAD = 72;

	private final Client client;
	private final BankValueModel model;
	private final HotStacksConfig config;

	private BufferedImage cache;
	private long cacheKey;

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

		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		if (container == null || container.isHidden())
		{
			cache = null;
			return null;
		}
		Widget[] children = container.getChildren();
		if (children == null)
		{
			return null;
		}

		// Derive the container rectangle from its canvas location (same space as the slots'
		// getCanvasLocation), so the clip and edge feather line up exactly with the item area.
		Point cloc = container.getCanvasLocation();
		if (cloc == null)
		{
			cache = null;
			return null;
		}
		Rectangle view = new Rectangle(cloc.getX(), cloc.getY(), container.getWidth(), container.getHeight());

		// Only slots on (or just off) screen contribute — keeps the field viewport-sized and cheap.
		int margin = 40;
		int loX = view.x - margin;
		int loY = view.y - margin;
		int hiX = view.x + view.width + margin;
		int hiY = view.y + view.height + margin;

		int[] xs = new int[children.length];
		int[] ys = new int[children.length];
		double[] peaks = new double[children.length];
		int n = 0;
		int slotMinX = Integer.MAX_VALUE;
		int slotMinY = Integer.MAX_VALUE;
		int slotMaxX = Integer.MIN_VALUE;
		int slotMaxY = Integer.MIN_VALUE;
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden() || child.getItemId() <= 0)
			{
				continue;
			}
			long value = model.valueOf(child.getItemId(), child.getItemQuantity());
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
			if (cx < loX || cx > hiX || cy < loY || cy > hiY)
			{
				continue;
			}
			xs[n] = cx;
			ys[n] = cy;
			peaks[n] = model.rankFraction(value);
			n++;
			slotMinX = Math.min(slotMinX, cx);
			slotMinY = Math.min(slotMinY, cy);
			slotMaxX = Math.max(slotMaxX, cx);
			slotMaxY = Math.max(slotMaxY, cy);
		}

		if (n == 0)
		{
			cache = null;
			return null;
		}

		// Image origin/size are recomputed from the current slot positions every frame, so the
		// field always draws over the bank wherever it is (e.g. after the side panel shifts it).
		int ox = slotMinX - PAD;
		int oy = slotMinY - PAD;
		int ow = slotMaxX + PAD - ox;
		int oh = slotMaxY + PAD - oy;

		// The cache key uses positions RELATIVE to the origin (and the view relative to it), so a
		// pure shift leaves the key unchanged — we reuse the image and just draw it at the new
		// origin, instead of rebuilding.
		long key = FNV;
		for (int i = 0; i < n; i++)
		{
			key = (key ^ (xs[i] - ox)) * FNV_PRIME;
			key = (key ^ (ys[i] - oy)) * FNV_PRIME;
			key = (key ^ Double.doubleToLongBits(peaks[i])) * FNV_PRIME;
		}
		key = (key ^ (view.x - ox)) * FNV_PRIME;
		key = (key ^ (view.y - oy)) * FNV_PRIME;
		key = (key ^ view.width) * FNV_PRIME;
		key = (key ^ view.height) * FNV_PRIME;
		key = (key ^ ow) * FNV_PRIME;
		key = (key ^ oh) * FNV_PRIME;
		key = (key ^ config.lowColor().getRGB()) * FNV_PRIME;
		key = (key ^ config.highColor().getRGB()) * FNV_PRIME;

		if (key != cacheKey || cache == null)
		{
			rebuild(xs, ys, peaks, n, ox, oy, ow, oh, view);
			cacheKey = key;
		}

		Shape oldClip = graphics.getClip();
		// Expand the clip upward by the bleed so the top fade can spill into the gap below the tabs.
		graphics.setClip(view.x, view.y - TOP_BLEED, view.width, view.height + TOP_BLEED);
		Object oldInterp = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.drawImage(cache, ox, oy, ow, oh, null);
		if (oldInterp != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
		}
		graphics.setClip(oldClip);
		return null;
	}

	/** Builds the cached density image for the gathered slots, relative to origin ({@code ox},{@code oy}). */
	private void rebuild(int[] xs, int[] ys, double[] peaks, int n, int ox, int oy, int ow, int oh, Rectangle view)
	{
		int gw = Math.max(1, ow / DOWNSCALE);
		int gh = Math.max(1, oh / DOWNSCALE);
		float[] field = new float[gw * gh];

		double blobR = 60.0 / DOWNSCALE;   // stamp reach (grid px); PAD must exceed blobR*DOWNSCALE
		double sigma = blobR / 3.0;        // tight enough that the blob is ~1% by its stamp edge
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

		// Blur the summed field to erase the ridges where individual blobs meet.
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
			// Fade the field to nothing as it nears the bank container edge, so the hard clip
			// boundary is invisible instead of slicing bright blobs into a straight line.
			double canvasX = ox + px * DOWNSCALE;
			double canvasY = oy + py * DOWNSCALE;
			double distX = Math.min(canvasX - view.x, view.x + view.width - canvasX);
			double distTop = canvasY - (view.y - TOP_BLEED);   // top fade starts above the container
			double distBottom = view.y + view.height - canvasY;
			if (distX <= 0 || distTop <= 0 || distBottom <= 0)
			{
				continue;
			}
			double feather = Math.min(1.0, distX / EDGE_FEATHER_X);
			feather = Math.min(feather, Math.min(1.0, distTop / EDGE_FEATHER_TOP));
			feather = Math.min(feather, Math.min(1.0, distBottom / EDGE_FEATHER_BOTTOM));
			feather = feather * feather * (3.0 - 2.0 * feather);   // smoothstep — no sharp knee

			Color c = HeatColor.blend(t, low, high);
			int a = (int) (Math.min(1.0, t * 1.25) * MAX_ALPHA * feather);
			if (a <= 0)
			{
				continue;
			}
			img.setRGB(px, py, (a << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue());
		}
		cache = img;
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

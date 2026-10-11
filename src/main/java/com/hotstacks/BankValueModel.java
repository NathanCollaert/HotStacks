package com.hotstacks;

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsService;

@Singleton
class BankValueModel
{
	private static final long[] EMPTY = new long[0];
	private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;
	/** The quantity Bank Tag Layouts give a laid-out item you don't own (see LayoutManager). */
	private static final int LAYOUT_PLACEHOLDER_QUANTITY = Integer.MAX_VALUE;

	private final Client client;
	private final ItemManager itemManager;
	private final HotStacksConfig config;
	private final PluginManager pluginManager;
	private final WithdrawalTracker withdrawals;

	// Published by syncToView() and read by the overlays while they render.
	private volatile long[] sortedShownValues = EMPTY;
	private volatile long viewTotal;
	private volatile long topThresholdValue = Long.MAX_VALUE;
	private volatile int topThresholdItemId = Integer.MAX_VALUE;
	private volatile boolean layoutActive;

	private volatile boolean forceRebuild = true;
	private long contentSignature;
	private BankTagsService bankTags;

	@Inject
	BankValueModel(Client client, ItemManager itemManager, HotStacksConfig config, PluginManager pluginManager,
		WithdrawalTracker withdrawals)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		this.pluginManager = pluginManager;
		this.withdrawals = withdrawals;
	}

	/**
	 * Whether a Bank Tag Layout is shown, as of the last {@link #syncToView()}. Layouts hold duplicates
	 * and ghost placeholders, so their values don't reflect real holdings and nothing is drawn over them.
	 */
	boolean layoutActive()
	{
		return layoutActive;
	}

	/** Null without the banktags plugin, which lives in its own injector and so can't be {@code @Inject}ed. */
	private BankTagsService bankTags()
	{
		if (bankTags == null)
		{
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p instanceof BankTagsService)
				{
					bankTags = (BankTagsService) p;
					break;
				}
			}
		}
		return bankTags;
	}

	/** The stack's weight in the configured {@link ValueSource}, or 0 when it has none. */
	long weightOf(int itemId, int quantity)
	{
		if (isEmptyOrLayoutPlaceholder(quantity))
		{
			return 0;
		}
		if (config.valueSource() == ValueSource.WITHDRAWS)
		{
			return withdrawals.count(itemId);
		}
		long unit = unitValue(itemId);
		return unit <= 0 ? 0 : unit * quantity;
	}

	private static boolean isEmptyOrLayoutPlaceholder(int quantity)
	{
		return quantity <= 0 || quantity == LAYOUT_PLACEHOLDER_QUANTITY;
	}

	private long unitValue(int itemId)
	{
		switch (config.valueSource())
		{
			case HIGH_ALCH:
				return itemManager.getItemComposition(itemId).getHaPrice();
			case HIGH_ALCH_PROFIT:
				return (long) itemManager.getItemComposition(itemId).getHaPrice() - itemManager.getItemPrice(ItemID.NATURERUNE);
			case GE:
			default:
				return itemManager.getItemPrice(itemId);
		}
	}

	long viewTotal()
	{
		return viewTotal;
	}

	/**
	 * Forces the next {@link #syncToView()} to rebuild. This doesn't count as a content change, so
	 * pending withdrawals stay hidden.
	 */
	void invalidate()
	{
		forceRebuild = true;
	}

	/**
	 * Must run every frame just before the overlays draw (on {@code BeforeRender}), so ranks are
	 * measured against the same slots the render sees. Otherwise a change in the shown set (e.g.
	 * opening the whole bank from a tab) flashes one frame ranked against the previous set.
	 */
	void syncToView()
	{
		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		Widget[] children = container == null ? null : container.getChildren();
		if (children == null || bank == null)
		{
			clearForClosedBank();
			return;
		}
		if (isBankTagLayoutShown())
		{
			clearForBankTagLayout();
			return;
		}
		layoutActive = false;

		int itemWidgetCount = Math.min(bank.size(), children.length);
		long signature = shownContentSignature(children, itemWidgetCount);
		boolean shownSlotsChanged = signature != contentSignature;
		if (!shownSlotsChanged && !forceRebuild)
		{
			return;
		}
		contentSignature = signature;
		forceRebuild = false;

		if (shownSlotsChanged)
		{
			withdrawals.commitPending();
		}
		rebuildRanking(children, itemWidgetCount);
	}

	private boolean isBankTagLayoutShown()
	{
		BankTagsService tags = bankTags();
		return tags != null && tags.getActiveLayout() != null;
	}

	private void clearForClosedBank()
	{
		sortedShownValues = EMPTY;
		viewTotal = 0;
		contentSignature = 0;
		layoutActive = false;
	}

	private void clearForBankTagLayout()
	{
		sortedShownValues = EMPTY;
		viewTotal = 0;
		// Matches no real view, so the real bank re-ranks as soon as the layout closes.
		contentSignature = -1;
		layoutActive = true;
	}

	/** The open tab or search matches, including slots scrolled out of view: those are clipped, not hidden. */
	private static boolean isShownItem(Widget slot)
	{
		return slot != null && !slot.isSelfHidden() && slot.getItemId() > 0;
	}

	private static long shownContentSignature(Widget[] children, int itemWidgetCount)
	{
		long signature = FNV_OFFSET_BASIS;
		for (int i = 0; i < itemWidgetCount; i++)
		{
			Widget child = children[i];
			if (!isShownItem(child))
			{
				continue;
			}
			signature = (signature ^ child.getItemId()) * FNV_PRIME;
			signature = (signature ^ child.getItemQuantity()) * FNV_PRIME;
		}
		return signature;
	}

	private void rebuildRanking(Widget[] children, int itemWidgetCount)
	{
		long[] values = new long[itemWidgetCount];
		int[] itemIds = new int[itemWidgetCount];
		int weightedCount = 0;
		for (int i = 0; i < itemWidgetCount; i++)
		{
			Widget child = children[i];
			if (!isShownItem(child))
			{
				continue;
			}
			long value = weightOf(child.getItemId(), child.getItemQuantity());
			if (value > 0)
			{
				values[weightedCount] = value;
				itemIds[weightedCount] = child.getItemId();
				weightedCount++;
			}
		}

		viewTotal = sumOf(values, weightedCount);
		sortedShownValues = sortedCopy(values, weightedCount);
		computeTopThreshold(values, itemIds, weightedCount);
	}

	private static long sumOf(long[] values, int count)
	{
		long total = 0;
		for (int i = 0; i < count; i++)
		{
			total += values[i];
		}
		return total;
	}

	private static long[] sortedCopy(long[] values, int count)
	{
		long[] sorted = Arrays.copyOf(values, count);
		Arrays.sort(sorted);
		return sorted;
	}

	/**
	 * Ties at the cutoff value are broken by item id, so however many items share the richest value,
	 * at most the top percent of them are marked, and the same ones every frame.
	 */
	private void computeTopThreshold(long[] values, int[] itemIds, int count)
	{
		double topPercent = config.sparkleTopPercent();
		if (count <= 1 || topPercent <= 0)
		{
			markNothingAsTop();
			return;
		}

		Integer[] order = indicesByValueThenItemId(values, itemIds, count);
		int cutoff = order[count - topCount(count, topPercent)];
		topThresholdValue = values[cutoff];
		topThresholdItemId = itemIds[cutoff];
	}

	private void markNothingAsTop()
	{
		topThresholdValue = Long.MAX_VALUE;
		topThresholdItemId = Integer.MAX_VALUE;
	}

	private static Integer[] indicesByValueThenItemId(long[] values, int[] itemIds, int count)
	{
		Integer[] order = new Integer[count];
		for (int i = 0; i < count; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) ->
		{
			int c = Long.compare(values[a], values[b]);
			return c != 0 ? c : Integer.compare(itemIds[a], itemIds[b]);
		});
		return order;
	}

	private static int topCount(int count, double topPercent)
	{
		return Math.min(count, Math.max(1, (int) Math.round(count * topPercent / 100.0)));
	}

	/**
	 * Where {@code value} ranks in the current view, from 0 (cheapest) to 1 (dearest); 1 when there is
	 * nothing to rank against. Rank rather than magnitude keeps the palette evenly spread however the
	 * values cluster.
	 */
	double rankFraction(long value)
	{
		long[] sorted = sortedShownValues;
		int count = sorted.length;
		if (count <= 1)
		{
			return 1.0;
		}
		int index = Math.min(topOfTieIndex(sorted, value), count - 1);
		return (double) index / (count - 1);
	}

	/** Ties take the top of their run, since binarySearch may land anywhere in a run of equal values. */
	private static int topOfTieIndex(long[] sorted, long value)
	{
		int index = Arrays.binarySearch(sorted, value);
		if (index < 0)
		{
			int insertionPoint = -index - 1;
			return insertionPoint;
		}
		while (index + 1 < sorted.length && sorted[index + 1] == value)
		{
			index++;
		}
		return index;
	}

	boolean isTop(long value, int itemId)
	{
		if (sortedShownValues.length <= 1)
		{
			return false;
		}
		if (value != topThresholdValue)
		{
			return value > topThresholdValue;
		}
		return itemId >= topThresholdItemId;
	}
}

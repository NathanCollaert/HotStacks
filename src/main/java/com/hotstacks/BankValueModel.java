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

/**
 * Works out what value (if any) a bank slot should display, and holds the sorted set of values in
 * the current view so the overlay can colour each item by its rank (cheapest → dearest).
 *
 * <p>The value set is taken from the bank item container itself, counting only the slots the game
 * hasn't hidden ({@code !isSelfHidden()}). That gives the whole bank when all tabs are shown, just
 * the open tab when one is selected, and the matches while a bank search is active — and it is
 * unaffected by scrolling, since scrolled-off slots are clipped, not hidden. This mirrors how
 * RuneLite's own bank plugin totals the currently-shown bank value.</p>
 *
 * <p>{@link #recompute()} runs on the client thread (game tick / varbit change); the overlay reads
 * the published {@code sorted} array while rendering. That array is {@code volatile} so the render
 * pass always sees a complete snapshot.</p>
 */
@Singleton
class BankValueModel
{
	private static final long[] EMPTY = new long[0];

	private final Client client;
	private final ItemManager itemManager;
	private final HotStacksConfig config;
	private final PluginManager pluginManager;
	private final WithdrawalTracker withdrawals;

	/** Sorted (ascending) shown values in the current view; what each item's rank is measured against. */
	private volatile long[] sorted = EMPTY;
	/** Total value of the current view (open tab / search / whole bank) — what effects scale against. */
	private volatile long viewTotal;
	/** The (value, itemId) pair an item must meet or beat to count as "top" this frame — see {@link
	 *  #isTop}. Computed once per rebuild from the configured top percent, rather than per item, so a
	 *  large tie at the richest value doesn't all clear the bar together. */
	private volatile long topThresholdValue = Long.MAX_VALUE;
	private volatile int topThresholdId = Integer.MAX_VALUE;
	/** Signature of the shown (itemId, quantity) slots the current {@link #sorted} was built from —
	 *  purely a reflection of the bank's own displayed contents, never nudged by {@link #invalidate()}.
	 *  This is what gates {@link WithdrawalTracker#commitPending()}, so a fresh withdrawal count only
	 *  becomes visible in lockstep with the bank slot actually losing the item. */
	private long contentSignature;
	/** Set by {@link #invalidate()}; forces the next {@link #syncToView()} to rebuild regardless of
	 *  whether the content signature changed (e.g. once a tick, to pick up price drift). */
	private volatile boolean forceRebuild = true;
	/** Whether a Bank Tag Layout is active this frame; recomputed in {@link #syncToView()}. */
	private volatile boolean layoutActive;
	/** The banktags plugin, resolved lazily as a {@link BankTagsService}; null if that plugin is absent. */
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
	 * Whether a Bank Tag Layout (a "loadout") is currently applied to the bank. Such views are a
	 * hand-arranged subset — they can contain duplicated items and dimmed "ghost" placeholders for
	 * things you don't own (see {@code LayoutManager}), so their values don't reflect real holdings.
	 * The whole overlay stands down while one is active. Read from the flag cached each frame by
	 * {@link #syncToView()}, so per-item render calls don't each re-query.
	 */
	boolean layoutActive()
	{
		return layoutActive;
	}

	/**
	 * The banktags plugin exposed as a {@link BankTagsService}. It lives in its own plugin injector,
	 * so it can't be {@code @Inject}ed here; instead it's found by identity through the
	 * {@link PluginManager} (banktags implements the service) and cached once resolved.
	 */
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

	/**
	 * The weight to show/rank a bank slot by, or {@code 0} for none, in the current
	 * {@link ValueSource}:
	 * <ul>
	 *   <li><b>GE</b> — {@link ItemManager#getItemPrice(int)}, the same source as RuneLite's
	 *   built-in Item Prices, which resolves combination items (charged tridents, avernic defender,
	 *   echo boots, …) to their tradeable parts.</li>
	 *   <li><b>High alch</b> — the item's high-alchemy value.</li>
	 *   <li><b>High alch profit</b> — high-alch value minus the current nature-rune price; a
	 *   non-positive result yields 0 (no value).</li>
	 *   <li><b>Withdraws</b> — how many times you've withdrawn the item, ever, on this account.
	 *   Quantity-independent: withdrawing 28 of an item counts the same as withdrawing 1.</li>
	 * </ul>
	 */
	long weightOf(int itemId, int quantity)
	{
		// Bank Tag Layouts marks an item placed in a layout but not actually owned (a "layout
		// placeholder", dimmed and non-interactable) with a sentinel quantity of Integer.MAX_VALUE
		// (see LayoutManager). Those ghosts carry no weight, so exclude them like an empty slot —
		// otherwise unit × MAX_VALUE ranks them as the richest stack in the bank.
		if (quantity <= 0 || quantity == Integer.MAX_VALUE)
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

	/** Total value of the current view (tab / search / whole bank); what {@code Scale effect by value} uses. */
	long viewTotal()
	{
		return viewTotal;
	}

	/** Forces {@link #syncToView()} to rebuild on its next call (e.g. once a tick, to pick up price
	 *  changes even when the shown items are unchanged). Does not by itself reveal a newly recorded
	 *  withdrawal — see {@link WithdrawalTracker}. */
	void invalidate()
	{
		forceRebuild = true;
	}

	/**
	 * Brings the ranking set in step with the bank slots currently shown. Meant to run every frame
	 * on {@code BeforeRender}, just before the overlay draws, so the ranking reflects exactly the
	 * same widget state the render sees — that is what prevents the one-frame flash when the shown
	 * set changes (e.g. opening the whole bank from a tab), where the overlay would otherwise rank
	 * every item against the previous, smaller set.
	 *
	 * <p>A cheap signature over the shown slots is computed first; the expensive pricing/sort pass
	 * only runs when that signature changes (or after {@link #invalidate()}), so the common
	 * unchanged frame is just one light scan.</p>
	 */
	void syncToView()
	{
		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		Widget[] children = container == null ? null : container.getChildren();
		if (children == null || bank == null)
		{
			sorted = EMPTY;
			viewTotal = 0;
			contentSignature = 0;
			layoutActive = false;
			return;
		}

		// A loadout (Bank Tag Layout) is a curated arrangement with fake quantities and possible
		// duplicates, so its values would be misleading — stand the whole overlay down while one is
		// shown. Reset the content signature so the real bank is re-ranked once it closes.
		BankTagsService tags = bankTags();
		if (tags != null && tags.getActiveLayout() != null)
		{
			sorted = EMPTY;
			viewTotal = 0;
			contentSignature = -1;
			layoutActive = true;
			return;
		}
		layoutActive = false;

		// The bank item widgets are the first bank.size() children; later children are tabs etc.
		int slots = Math.min(bank.size(), children.length);

		// Cheap pass: signature of the shown (id, quantity) slots — no pricing. This reflects only
		// the bank's own displayed contents, so it's what gates committing a pending withdrawal
		// count into visibility (see WithdrawalTracker) — a forced rebuild alone must not do that,
		// or a slot would rank by its just-clicked count before the game has actually removed the item.
		long sig = 1469598103934665603L;
		for (int i = 0; i < slots; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden() || child.getItemId() <= 0)
			{
				continue;
			}
			sig = (sig ^ child.getItemId()) * 1099511628211L;
			sig = (sig ^ child.getItemQuantity()) * 1099511628211L;
		}
		boolean contentChanged = sig != contentSignature;
		if (!contentChanged && !forceRebuild)
		{
			return;
		}
		contentSignature = sig;
		forceRebuild = false;

		if (contentChanged)
		{
			withdrawals.commitPending();
		}

		// The view changed: price the shown slots and rebuild the ranking set.
		long[] values = new long[slots];
		int[] ids = new int[slots];
		int n = 0;
		for (int i = 0; i < slots; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden() || child.getItemId() <= 0)
			{
				continue;
			}
			long value = weightOf(child.getItemId(), child.getItemQuantity());
			if (value > 0)
			{
				values[n] = value;
				ids[n] = child.getItemId();
				n++;
			}
		}

		long total = 0;
		for (int i = 0; i < n; i++)
		{
			total += values[i];
		}
		viewTotal = total;

		long[] s = Arrays.copyOf(values, n);
		Arrays.sort(s);
		sorted = s;

		computeTopThreshold(values, ids, n);
	}

	/**
	 * Picks the (value, itemId) cutoff an item must meet or beat to count as {@link #isTop}. Ties at
	 * the cutoff value are broken by item id — a stable, arbitrary but deterministic order — so that
	 * however many items share the richest value, at most the configured top percent of them (never
	 * all of them) end up marked, and the same subset is picked every frame rather than flickering.
	 */
	private void computeTopThreshold(long[] values, int[] ids, int n)
	{
		double topPercent = config.sparkleTopPercent();
		if (n <= 1 || topPercent <= 0)
		{
			topThresholdValue = Long.MAX_VALUE;
			topThresholdId = Integer.MAX_VALUE;
			return;
		}

		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) ->
		{
			int c = Long.compare(values[a], values[b]);
			return c != 0 ? c : Integer.compare(ids[a], ids[b]);
		});

		int topCount = Math.min(n, Math.max(1, (int) Math.round(n * topPercent / 100.0)));
		int cutoff = order[n - topCount];
		topThresholdValue = values[cutoff];
		topThresholdId = ids[cutoff];
	}

	/**
	 * Where {@code value} sits in the current view from 0 (cheapest shown stack) to 1 (dearest).
	 * Ranking, rather than raw magnitude, guarantees the palette is spread evenly across the items
	 * however their values cluster. Returns 1 when there is nothing to rank against.
	 *
	 * <p>Ties resolve to the <em>top</em> of their band: {@link Arrays#binarySearch} may land on any
	 * matching index, so a run of equal values is walked forward to its last occurrence. Without
	 * this, a view where every shown value is identical (a plausible whole-bank case for {@link
	 * ValueSource#WITHDRAWS}, where a small integer count ties far more often than a gp value would)
	 * could have every item hash to rank 0 instead of the 1 they all equally deserve — silently
	 * flattening every blob in the density field to zero height.</p>
	 */
	double rankFraction(long value)
	{
		long[] s = sorted;
		int n = s.length;
		if (n <= 1)
		{
			return 1.0;
		}
		int idx = Arrays.binarySearch(s, value);
		if (idx < 0)
		{
			idx = -idx - 1;
		}
		else
		{
			while (idx + 1 < n && s[idx + 1] == value)
			{
				idx++;
			}
		}
		if (idx >= n)
		{
			idx = n - 1;
		}
		return (double) idx / (n - 1);
	}

	/**
	 * Whether ({@code value}, {@code itemId}) meets or beats the current top-percent cutoff computed
	 * in {@link #computeTopThreshold}. Comparing the pair (not just the value) is what keeps a large
	 * tie at the richest value from all qualifying together — see that method's javadoc.
	 */
	boolean isTop(long value, int itemId)
	{
		if (sorted.length <= 1)
		{
			return false;
		}
		if (value != topThresholdValue)
		{
			return value > topThresholdValue;
		}
		return itemId >= topThresholdId;
	}
}

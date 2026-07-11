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

	/** Sorted (ascending) shown values in the current view; what each item's rank is measured against. */
	private volatile long[] sorted = EMPTY;
	/** Total value of the current view (open tab / search / whole bank) — what effects scale against. */
	private volatile long viewTotal;
	/** Signature of the shown slots the current {@link #sorted} was built from; -1 forces a rebuild. */
	private long signature = -1;

	@Inject
	BankValueModel(Client client, ItemManager itemManager, HotStacksConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
	}

	/**
	 * The stack value to show for a bank slot, or {@code 0} for no value, in the current
	 * {@link ValueBasis}:
	 * <ul>
	 *   <li><b>GE</b> (and the {@code NONE} label-off case) — {@link ItemManager#getItemPrice(int)},
	 *   the same source as RuneLite's built-in Item Prices, which resolves combination items
	 *   (charged tridents, avernic defender, echo boots, …) to their tradeable parts.</li>
	 *   <li><b>High alch</b> — the item's high-alchemy value.</li>
	 *   <li><b>High alch profit</b> — high-alch value minus the current nature-rune price; a
	 *   non-positive result yields 0 (no value).</li>
	 * </ul>
	 */
	long valueOf(int itemId, int quantity)
	{
		if (quantity <= 0)
		{
			return 0;
		}
		long unit = unitValue(itemId);
		return unit <= 0 ? 0 : unit * quantity;
	}

	private long unitValue(int itemId)
	{
		ValueBasis basis = config.valueBasis();
		switch (basis)
		{
			case HIGH_ALCH:
				return itemManager.getItemComposition(itemId).getHaPrice();
			case HIGH_ALCH_PROFIT:
				return (long) itemManager.getItemComposition(itemId).getHaPrice() - itemManager.getItemPrice(ItemID.NATURERUNE);
			case NONE:      // label hidden, but heat map / effects still run on the GE value
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
	 *  changes even when the shown items are unchanged). */
	void invalidate()
	{
		signature = -1;
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
			signature = 0;
			return;
		}

		// The bank item widgets are the first bank.size() children; later children are tabs etc.
		int slots = Math.min(bank.size(), children.length);

		// Cheap pass: signature of the shown (id, quantity) slots — no pricing.
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
		if (sig == signature)
		{
			return;
		}
		signature = sig;

		// The view changed: price the shown slots and rebuild the ranking set.
		long[] values = new long[slots];
		int n = 0;
		for (int i = 0; i < slots; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden() || child.getItemId() <= 0)
			{
				continue;
			}
			long value = valueOf(child.getItemId(), child.getItemQuantity());
			if (value > 0)
			{
				values[n++] = value;
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
	}

	/**
	 * Where {@code value} sits in the current view from 0 (cheapest shown stack) to 1 (dearest).
	 * Ranking, rather than raw magnitude, guarantees the palette is spread evenly across the items
	 * however their values cluster. Returns 1 when there is nothing to rank against.
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
		if (idx >= n)
		{
			idx = n - 1;
		}
		return (double) idx / (n - 1);
	}

	/** Whether {@code value} is within the dearest {@code topPercent}% of shown stacks in view. */
	boolean isTop(long value, double topPercent)
	{
		return sorted.length > 1 && rankFraction(value) >= 1.0 - topPercent / 100.0;
	}
}

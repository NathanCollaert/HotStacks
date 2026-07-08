package com.hotstacks;

import java.util.Arrays;
import java.util.Collection;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;

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

	/** Sorted (ascending) shown values in the current view; what each item's rank is measured against. */
	private volatile long[] sorted = EMPTY;

	@Inject
	BankValueModel(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/**
	 * The stack value to show under a bank slot, or {@code 0} for no label.
	 *
	 * <p>Tradeable items are valued at the raw wiki price. Untradeable items show nothing —
	 * <em>except</em> the charged variant of a tradeable item (e.g. a charged trident or blowpipe),
	 * which is valued at its tradeable base's price, via {@link ItemVariationMapping}.</p>
	 */
	long valueOf(int itemId, int quantity)
	{
		if (quantity <= 0)
		{
			return 0;
		}

		ItemComposition comp = itemManager.getItemComposition(itemId);
		int priceId = comp.isTradeable() ? itemId : tradeableVariant(itemId);
		if (priceId < 0)
		{
			return 0;
		}

		long unit = itemManager.getItemPrice(priceId);
		return unit * quantity;
	}

	/**
	 * For an untradeable item, the id of a tradeable item in the same variation group whose price
	 * stands in for its worth — e.g. a charged trident (untradeable) valued at the uncharged,
	 * sellable trident. Returns {@code -1} when the item has no tradeable variant (a genuinely
	 * untradeable item such as a quest item), so it gets no label.
	 *
	 * <p>We can't just take the group's base id: for some groups (the trident) the base is itself
	 * untradeable and the sellable form sits later in the list, so we scan the whole group. The
	 * lowest-id tradeable member is preferred as it's the plain uncharged form.</p>
	 */
	private int tradeableVariant(int itemId)
	{
		Collection<Integer> group = ItemVariationMapping.getVariations(ItemVariationMapping.map(itemId));
		if (group == null)
		{
			return -1;
		}
		int best = -1;
		for (int id : group)
		{
			if (id == itemId)
			{
				continue;
			}
			if (itemManager.getItemComposition(id).isTradeable() && itemManager.getItemPrice(id) > 0
				&& (best < 0 || id < best))
			{
				best = id;
			}
		}
		return best;
	}

	/**
	 * Rebuilds the value set from the currently-shown bank slots. Cheap enough to run every tick;
	 * a no-op when the bank isn't open.
	 */
	void recompute()
	{
		Widget container = client.getWidget(ComponentID.BANK_ITEM_CONTAINER);
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		Widget[] children = container == null ? null : container.getChildren();
		if (children == null || bank == null)
		{
			sorted = EMPTY;
			return;
		}

		// The bank item widgets are the first bank.size() children; later children are tabs etc.
		int slots = Math.min(bank.size(), children.length);
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

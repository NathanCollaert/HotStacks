package com.hotstacks;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Counts how many times each item has been withdrawn from the bank, for the {@link
 * ValueSource#WITHDRAWS} value source. A withdrawal is a single Withdraw-N/X/All click, counted
 * once regardless of the quantity it moved — this tracks how often you reach for an item, not how
 * much of it passes through.
 *
 * <p>Counts are lifetime and kept per game account (RuneLite's per-RSProfile config storage), so an
 * alt's habits don't bleed into another's. There is no reset; the map only grows.</p>
 *
 * <p>A click and the bank actually losing the item are two different moments — the server takes a
 * tick or two to process the withdrawal, but the click fires immediately. If {@link #count} picked
 * up a fresh increment straight away, the bank's forced per-tick re-rank (see {@code
 * BankValueModel#invalidate()}) would briefly rank the slot by its new count while the slot still
 * shows its pre-withdrawal quantity — a one-tick flash. So a new count is held in {@link #pending}
 * (and persisted right away, so a click is never lost) and only becomes visible to {@link #count}
 * once {@link #commitPending()} is called — which {@code BankValueModel} does exactly when the
 * bank's own displayed contents have actually changed, keeping the two in lockstep.</p>
 *
 * <p>The map is stored as a JSON string, (de)serialized here: ConfigManager's typed get/set only
 * route {@code Set}s through Gson, so a {@code Map} would be written with {@code toString()} and read
 * back as a raw String.</p>
 */
@Slf4j
@Singleton
class WithdrawalTracker
{
	private static final String KEY = "withdrawals";
	private static final Type MAP_TYPE = new TypeToken<Map<Integer, Integer>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final Gson gson;

	/** The persisted, up-to-date total — updated (and saved) the instant a withdrawal is recorded. */
	private Map<Integer, Integer> counts = new HashMap<>();
	/** What {@link #count} reads; only caught up to {@link #counts} by {@link #commitPending()}. */
	private Map<Integer, Integer> visible = new HashMap<>();
	private boolean dirty;

	@Inject
	WithdrawalTracker(ConfigManager configManager, ItemManager itemManager, Gson gson)
	{
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	/** Loads this account's counts, replacing whatever was held for the previous one. */
	void load()
	{
		counts = decode(gson, configManager.getRSProfileConfiguration(HotStacksConfig.GROUP, KEY));
		visible = new HashMap<>(counts);
		dirty = false;
	}

	/** Records one withdrawal of {@code itemId} and persists immediately; not yet visible to {@link #count}. */
	void record(int itemId)
	{
		int id = itemManager.canonicalize(itemId);
		counts.merge(id, 1, Integer::sum);
		dirty = true;
		configManager.setRSProfileConfiguration(HotStacksConfig.GROUP, KEY, encode(gson, counts));
	}

	/** Makes any counts recorded since the last commit visible to {@link #count}. */
	void commitPending()
	{
		if (dirty)
		{
			visible = new HashMap<>(counts);
			dirty = false;
		}
	}

	/** How many times {@code itemId} (or its noted/placeholder variant) has been withdrawn. */
	int count(int itemId)
	{
		Integer c = visible.get(itemManager.canonicalize(itemId));
		return c == null ? 0 : c;
	}

	static String encode(Gson gson, Map<Integer, Integer> counts)
	{
		return gson.toJson(counts, MAP_TYPE);
	}

	/**
	 * Parses stored counts into a fresh mutable map. Earlier builds stored the map's {@code
	 * toString()} ({@code {1234=5, 995=2}}), which Gson's lenient parsing also reads, so those counts
	 * carry over. Anything unreadable starts the account fresh rather than failing the load.
	 */
	static Map<Integer, Integer> decode(Gson gson, String stored)
	{
		if (stored == null || stored.isEmpty())
		{
			return new HashMap<>();
		}
		try
		{
			Map<Integer, Integer> parsed = gson.fromJson(stored, MAP_TYPE);
			return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
		}
		catch (JsonParseException e)
		{
			log.warn("Discarding unreadable withdrawal counts: {}", stored, e);
			return new HashMap<>();
		}
	}
}

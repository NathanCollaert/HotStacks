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

/** Counts withdraw clicks, not quantities moved: it measures how often you reach for an item. */
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

	private Map<Integer, Integer> recordedCounts = new HashMap<>();
	private Map<Integer, Integer> visibleCounts = new HashMap<>();
	private boolean hasUncommittedRecords;

	@Inject
	WithdrawalTracker(ConfigManager configManager, ItemManager itemManager, Gson gson)
	{
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	void load()
	{
		recordedCounts = decode(gson, configManager.getRSProfileConfiguration(HotStacksConfig.GROUP, KEY));
		visibleCounts = new HashMap<>(recordedCounts);
		hasUncommittedRecords = false;
	}

	/** Persists immediately, but stays invisible to {@link #count} until {@link #commitPending()}. */
	void record(int itemId)
	{
		int id = itemManager.canonicalize(itemId);
		recordedCounts.merge(id, 1, Integer::sum);
		hasUncommittedRecords = true;
		configManager.setRSProfileConfiguration(HotStacksConfig.GROUP, KEY, encode(gson, recordedCounts));
	}

	/**
	 * Call only once the bank's shown contents have changed. A withdraw click lands a tick or two
	 * before the bank loses the item, and revealing its count sooner would rank the slot by the new
	 * count while it still shows the old quantity.
	 */
	void commitPending()
	{
		if (hasUncommittedRecords)
		{
			visibleCounts = new HashMap<>(recordedCounts);
			hasUncommittedRecords = false;
		}
	}

	int count(int itemId)
	{
		Integer c = visibleCounts.get(itemManager.canonicalize(itemId));
		return c == null ? 0 : c;
	}

	/**
	 * ConfigManager only routes {@code Set}s through Gson: a {@code Map} would be stored via
	 * {@code toString()} and read back as a raw String, so the map is encoded here.
	 */
	static String encode(Gson gson, Map<Integer, Integer> counts)
	{
		return gson.toJson(counts, MAP_TYPE);
	}

	/**
	 * Returns a fresh mutable map, empty when nothing readable is stored. Gson's lenient parsing also
	 * reads the legacy {@code Map.toString()} format ({@code {1234=5, 995=2}}).
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

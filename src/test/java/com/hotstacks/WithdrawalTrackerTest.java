package com.hotstacks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

public class WithdrawalTrackerTest
{
	// The instance RuneLite injects as Gson.
	private final Gson gson = RuneLiteAPI.GSON;

	private static Map<Integer, Integer> sample()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		counts.put(1234, 5);
		counts.put(995, 2);
		counts.put(4151, 1);
		return counts;
	}

	@Test
	public void roundTrips()
	{
		Map<Integer, Integer> counts = sample();
		assertEquals(counts, WithdrawalTracker.decode(gson, WithdrawalTracker.encode(gson, counts)));
	}

	@Test
	public void encodesAsJson()
	{
		Map<Integer, Integer> counts = new HashMap<>();
		counts.put(995, 2);
		assertEquals("{\"995\":2}", WithdrawalTracker.encode(gson, counts));
	}

	@Test
	public void readsLegacyToStringFormat()
	{
		// What earlier builds actually stored: ConfigManager fell back to Map.toString().
		Map<Integer, Integer> counts = sample();
		assertEquals(counts, WithdrawalTracker.decode(gson, counts.toString()));
		assertEquals(new HashMap<>(), WithdrawalTracker.decode(gson, new HashMap<>().toString()));
	}

	@Test
	public void missingStartsEmpty()
	{
		assertTrue(WithdrawalTracker.decode(gson, null).isEmpty());
		assertTrue(WithdrawalTracker.decode(gson, "").isEmpty());
	}

	@Test
	public void unreadableStartsFresh()
	{
		for (String junk : new String[]{"not a map", "{1234=5, 99", "[1, 2]", "{abc=1}", "{1234=lots}", "42"})
		{
			assertTrue(junk, WithdrawalTracker.decode(gson, junk).isEmpty());
		}
	}

	@Test
	public void decodedMapIsMutable()
	{
		Map<Integer, Integer> counts = WithdrawalTracker.decode(gson, "{\"995\":2}");
		counts.merge(995, 1, Integer::sum);
		counts.merge(4151, 1, Integer::sum);
		assertEquals(Integer.valueOf(3), counts.get(995));
		assertEquals(Integer.valueOf(1), counts.get(4151));
	}
}

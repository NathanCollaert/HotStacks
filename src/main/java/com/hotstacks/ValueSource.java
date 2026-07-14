package com.hotstacks;

/**
 * Which number drives the whole plugin — the value label, the heat-map ranking, the thermal tint
 * and the effects alike. Must be {@code public}: config methods return this via a JDK proxy in
 * another module, and a package-private enum there throws {@code IllegalAccessError} at render.
 */
public enum ValueSource
{
	/** Grand Exchange / wiki price (via RuneLite's item price mapping). */
	GE("GE value"),
	/** High alchemy value. */
	HIGH_ALCH("High alch value"),
	/** High alchemy value minus the current nature-rune price. */
	HIGH_ALCH_PROFIT("High alch profit"),
	/** How many times you've withdrawn the item from the bank, this account, ever. */
	WITHDRAWS("Withdraws");

	private final String label;

	ValueSource(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

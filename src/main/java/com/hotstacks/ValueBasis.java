package com.hotstacks;

/**
 * Which value each stack is measured by. Drives the label, the heat-map ranking, the thermal tint
 * and the effects alike — the whole plugin keys off this one number per item.
 */
public enum ValueBasis
{
	/** No value label drawn. The heat map and effects still run, using the GE value. */
	NONE("None"),
	/** Grand Exchange / wiki price (via RuneLite's item price mapping). */
	GE("GE value"),
	/** High alchemy value. */
	HIGH_ALCH("High alch value"),
	/** High alchemy value minus the current nature-rune price. */
	HIGH_ALCH_PROFIT("High alch profit");

	private final String label;

	ValueBasis(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

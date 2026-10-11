package com.hotstacks;

/** Must stay {@code public}, like {@link ValueSource}. */
public enum ColourMode
{
	HEAT_RAMP("Heat ramp (spectrum)"),
	GRADIENT("Two-colour gradient"),
	SINGLE("Single colour (white)"),
	DENSITY_FIELD("Density field");

	private final String label;

	ColourMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

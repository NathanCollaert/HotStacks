package com.hotstacks;

/**
 * How a stack's rank (cheapest → dearest in the current view) is turned into a colour.
 * All modes position items by rank, so the palette is always fully used no matter how the
 * raw values cluster; they differ only in the palette itself.
 */
public enum ColourMode
{
	/** Continuous multi-stop spectrum (blue → green → yellow → orange → red). */
	HEAT_RAMP("Heat ramp (spectrum)"),
	/** Blend between the two configured colours. */
	GRADIENT("Two-colour gradient"),
	/** Every value drawn plain white, regardless of worth. */
	SINGLE("Single colour (white)"),
	/** Continuous density field over the whole bank, ramped between the two configured colours. */
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

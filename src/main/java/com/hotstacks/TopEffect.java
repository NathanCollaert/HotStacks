package com.hotstacks;

/** The animated effect drawn on the most valuable stacks in view. */
public enum TopEffect
{
	/** No effect. */
	NONE("None"),
	/** Glowing embers rising from the bottom of the slot, as if from a fire beneath it. */
	EMBERS("Embers"),
	/** Glowing embers spitting outward from the centre, as if the item were a hot coal. */
	RADIAL_EMBERS("Radial embers"),
	/** Twinkling four-point stars around the slot. */
	SPARKLE("Sparkle");

	private final String label;

	TopEffect(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}

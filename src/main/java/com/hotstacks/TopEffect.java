package com.hotstacks;

/** The animated effect drawn on the most valuable stacks in view. */
public enum TopEffect
{
	/** No effect. */
	NONE("None"),
	/** Glowing embers drifting up and around the slot, as if from a fire beneath it. */
	EMBERS("Embers"),
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

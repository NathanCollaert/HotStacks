package com.hotstacks;

/** Must stay {@code public}, like {@link ValueSource}. */
public enum TopEffect
{
	NONE("None"),
	EMBERS("Embers"),
	RADIAL_EMBERS("Radial embers"),
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

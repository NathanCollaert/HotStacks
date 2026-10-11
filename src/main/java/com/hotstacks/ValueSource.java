package com.hotstacks;

/**
 * Must stay {@code public}: config methods return it through a JDK proxy in another module, where a
 * package-private enum throws {@code IllegalAccessError} at render.
 */
public enum ValueSource
{
	GE("GE value"),
	HIGH_ALCH("High alch value"),
	HIGH_ALCH_PROFIT("High alch profit"),
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

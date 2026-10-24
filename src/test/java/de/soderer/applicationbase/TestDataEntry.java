package de.soderer.applicationbase;

import java.util.Objects;

/**
 * Simple data entry for store tests.
 */
public class TestDataEntry {
	public String name;
	public int value;

	public TestDataEntry() {
		// Default constructor for deserialization
	}

	public TestDataEntry(final String name, final int value) {
		this.name = name;
		this.value = value;
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		} else if (!(other instanceof TestDataEntry)) {
			return false;
		} else {
			return Objects.equals(name, ((TestDataEntry) other).name) && value == ((TestDataEntry) other).value;
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, Integer.valueOf(value));
	}

	@Override
	public String toString() {
		return name + "=" + value;
	}

	/**
	 * Entry which cannot be serialized, because it references itself.
	 */
	public static class CyclicEntry {
		public CyclicEntry self;
	}
}

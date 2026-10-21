package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import de.soderer.utilities.Version;

@SuppressWarnings("static-method")
public class ManuallyApplicationUpdateTest {
	@Test
	public void testSodererDe() {
		try {
			final boolean result = ApplicationUpdateUtilities.executeUpdate(
					null,
					"https://soderer.de/index.php?download=Versions.json",
					null,
					"LanguagePropertiesManager",
					new Version("1.0.0"),
					null,
					null,
					null,
					null,
					false,
					false);
			assertEquals(true, result);
		} catch (final Exception e) {
			fail(e.getMessage());
		}
	}
}

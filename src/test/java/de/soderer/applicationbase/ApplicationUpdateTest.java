package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import de.soderer.utilities.Version;

@SuppressWarnings("static-method")
public class ApplicationUpdateTest {
	@Test
	public void testGitHubtestSodererDe() {
		try {
			final boolean result = ApplicationUpdateUtilities.executeUpdate(
					null,
					"https://api.github.com/repos/hudeany/LanguagePropertiesManager/releases/latest",
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

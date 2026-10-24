package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.utilities.ConfigurationProperties;

/**
 * Tests of {@link ApplicationConfigurationProperties}. The user home directory is redirected to a temporary directory,
 * so no real configuration file is touched.
 */
@SuppressWarnings("static-method")
public class ApplicationConfigurationPropertiesTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final String APPLICATION_NAME = "ApplicationConfigurationPropertiesTestApp";

	private String originalUserHome;
	private File tempHomeDirectory;

	@BeforeEach
	public void setUp() throws Exception {
		originalUserHome = System.getProperty("user.home");
		tempHomeDirectory = TestHelper.createTempDirectory("ApplicationConfigurationPropertiesTest");
		System.setProperty("user.home", tempHomeDirectory.getAbsolutePath());
	}

	@AfterEach
	public void tearDown() {
		System.setProperty("user.home", originalUserHome);
		TestHelper.deleteRecursively(tempHomeDirectory);
	}

	private File getConfigFile() {
		return new File(tempHomeDirectory, "." + APPLICATION_NAME + ".config");
	}

	@Test
	public void testSetupDefaultConfig() throws Exception {
		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		// A preset proxy type avoids the WPAD lookup in the network
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.None.name());
		configuration.setupDefaultConfig();

		assertEquals("None,System,Proxy-URL,WPAD,PAC-URL", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE + ConfigurationProperties.ENUM_EXTENSION));
		assertEquals("None", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE));
		assertEquals("", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL));
		assertFalse(configuration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK));
		assertEquals("", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK));
		assertTrue(configuration.containsKey(ConfigurationProperties.CONFIG_KEY_LANGUAGE));
	}

	@Test
	public void testSetupDefaultConfigDeterminesProxyType() throws Exception {
		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.setupDefaultConfig();

		// Depending on the network a WPAD proxy configuration may be found
		final String proxyType = configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE);
		assertTrue(ProxyConfigurationType.None.name().equals(proxyType) || ProxyConfigurationType.WPAD.name().equals(proxyType), proxyType);
		// The new proxy type is saved immediately
		assertTrue(getConfigFile().exists());

		final ApplicationConfigurationProperties reloadedConfiguration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		assertEquals(proxyType, reloadedConfiguration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE));
	}

	@Test
	public void testSetupDefaultConfigKeepsExistingValues() throws Exception {
		TestHelper.writeTextFile(getConfigFile(),
				ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE + "=ProxyURL\n"
						+ ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL + "=http\\://proxy\\:8080\n"
						+ ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK + "=true\n"
						+ ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK + "=2030-01-01\n");

		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.setupDefaultConfig();

		assertEquals("ProxyURL", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE));
		assertEquals("http://proxy:8080", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL));
		assertTrue(configuration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK));
		assertEquals("2030-01-01", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK));
	}

	@Test
	public void testSetupDefaultConfigMigratesLegacyKeys() throws Exception {
		TestHelper.writeTextFile(getConfigFile(),
				ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE + "=None\n"
						+ "DailyUpdateCheck=true\n"
						+ "NextDailyUpdateCheck=2031-02-03\n");

		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.setupDefaultConfig();

		assertTrue(configuration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK));
		assertEquals("2031-02-03", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK));
		assertFalse(configuration.containsKey("DailyUpdateCheck"));
		assertFalse(configuration.containsKey("NextDailyUpdateCheck"));
	}

	@Test
	public void testSetupDefaultConfigRemovesLegacyKeysIfNewKeysExist() throws Exception {
		TestHelper.writeTextFile(getConfigFile(),
				ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE + "=None\n"
						+ "DailyUpdateCheck=true\n"
						+ ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK + "=false\n"
						+ "NextDailyUpdateCheck=2031-02-03\n"
						+ ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK + "=2032-04-05\n");

		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.setupDefaultConfig();

		assertFalse(configuration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK));
		assertEquals("2032-04-05", configuration.get(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK));
		assertFalse(configuration.containsKey("DailyUpdateCheck"));
		assertFalse(configuration.containsKey("NextDailyUpdateCheck"));
	}

	@Test
	public void testSubDirectory() throws Exception {
		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME, true);
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, "http://proxy:3128");
		configuration.save();

		final File configFile = new File(new File(tempHomeDirectory, "." + APPLICATION_NAME), "." + APPLICATION_NAME + ".config");
		assertTrue(configFile.exists());

		final ApplicationConfigurationProperties reloadedConfiguration = new ApplicationConfigurationProperties(APPLICATION_NAME, true);
		assertEquals("http://proxy:3128", reloadedConfiguration.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL));
	}

	@Test
	public void testProxyConfigurationIsCached() throws Exception {
		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.ProxyURL.name());
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, "http://proxy1:8080");

		final ProxyConfiguration proxyConfiguration = configuration.getProxyConfiguration();
		assertEquals(ProxyConfigurationType.ProxyURL, proxyConfiguration.getProxyConfigurationType());
		assertEquals("http://proxy1:8080", proxyConfiguration.getProxyOrPacUrl());
		assertSame(proxyConfiguration, configuration.getProxyConfiguration());

		// Other keys do not reset the cache
		configuration.set("Other.Key", "value");
		configuration.removeKey("Other.Key");
		assertSame(proxyConfiguration, configuration.getProxyConfiguration());
	}

	@Test
	public void testProxyConfigurationIsResetOnChange() throws Exception {
		final ApplicationConfigurationProperties configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.ProxyURL.name());
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, "http://proxy1:8080");
		final ProxyConfiguration firstProxyConfiguration = configuration.getProxyConfiguration();

		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, "http://proxy2:8080");
		final ProxyConfiguration secondProxyConfiguration = configuration.getProxyConfiguration();
		assertNotSame(firstProxyConfiguration, secondProxyConfiguration);
		assertEquals("http://proxy2:8080", secondProxyConfiguration.getProxyOrPacUrl());

		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.None.name());
		final ProxyConfiguration thirdProxyConfiguration = configuration.getProxyConfiguration();
		assertNotSame(secondProxyConfiguration, thirdProxyConfiguration);
		assertEquals(ProxyConfigurationType.None, thirdProxyConfiguration.getProxyConfigurationType());

		// Typed setters delegate to set(String, String) and reset the cache too
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, 1);
		final ProxyConfiguration fourthProxyConfiguration = configuration.getProxyConfiguration();
		assertNotSame(thirdProxyConfiguration, fourthProxyConfiguration);

		configuration.removeKey(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL);
		assertNotSame(fourthProxyConfiguration, configuration.getProxyConfiguration());
	}
}

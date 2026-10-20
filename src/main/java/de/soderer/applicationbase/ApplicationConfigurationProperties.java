package de.soderer.applicationbase;

import de.soderer.pac.PacScriptParser;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.utilities.ConfigurationProperties;

/**
 * Configuration of updateable applications. Adds the proxy configuration and the daily update check to the plain
 * {@link ConfigurationProperties}. The keys and the file format are unchanged, so existing configuration files stay
 * valid.
 */
public class ApplicationConfigurationProperties extends ConfigurationProperties {
	/** Key of the proxy configuration type (name of a {@link ProxyConfigurationType}) */
	public static final String CONFIG_KEY_PROXY_CONFIGURATION_TYPE = "Application.ProxyConfigurationType";
	/** Key of the proxy URL (or PAC file URL) */
	public static final String CONFIG_KEY_PROXY_URL = "Application.ProxyUrl";
	/** Key of the flag for a daily update check */
	public static final String CONFIG_KEY_DAILY_UPDATE_CHECK = "Application.DailyUpdateCheck";
	/** Key of the date of the next daily update check */
	public static final String CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK = "Application.NextDailyUpdateCheck";

	private ProxyConfiguration proxyConfiguration = null;

	/**
	 * Loads the configuration of an application from "~/.&lt;applicationName&gt;.config", if it exists.
	 *
	 * @param applicationName name of the application
	 * @throws Exception if the existing configuration file cannot be read
	 */
	public ApplicationConfigurationProperties(final String applicationName) throws Exception {
		super(applicationName);
	}

	/**
	 * Loads the configuration of an application, if it exists.
	 *
	 * @param applicationName name of the application
	 * @param useSubDirectory true to store the file in the sub directory "~/.&lt;applicationName&gt;"
	 * @throws Exception if the existing configuration file cannot be read
	 */
	public ApplicationConfigurationProperties(final String applicationName, final boolean useSubDirectory) throws Exception {
		super(applicationName, useSubDirectory);
	}

	/**
	 * Sets a string value. All other typed setters of {@link ConfigurationProperties} delegate to this method, so the
	 * cached proxy configuration is always reset when one of its keys changes.
	 *
	 * @param name configuration key
	 * @param value value, null is stored as empty string
	 */
	@Override
	public synchronized void set(final String name, final String value) {
		super.set(name, value);
		resetProxyConfigurationIfAffected(name);
	}

	/**
	 * Removes a key.
	 *
	 * @param name configuration key
	 */
	@Override
	public synchronized void removeKey(final String name) {
		super.removeKey(name);
		resetProxyConfigurationIfAffected(name);
	}

	private void resetProxyConfigurationIfAffected(final String name) {
		if (CONFIG_KEY_PROXY_CONFIGURATION_TYPE.equals(name) || CONFIG_KEY_PROXY_URL.equals(name)) {
			proxyConfiguration = null;
		}
	}

	/**
	 * Sets up the default keys of {@link ConfigurationProperties} plus the proxy configuration and the daily update
	 * check, and migrates or removes obsolete keys. May save the configuration.
	 */
	@Override
	public synchronized void setupDefaultConfig() {
		super.setupDefaultConfig();

		set(CONFIG_KEY_PROXY_CONFIGURATION_TYPE + ENUM_EXTENSION, "None,System,Proxy-URL,WPAD,PAC-URL");
		if (!containsKey(CONFIG_KEY_PROXY_CONFIGURATION_TYPE)) {
			if (PacScriptParser.findPacFileUrlByWpad() != null) {
				set(CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.WPAD.name());
			} else {
				set(CONFIG_KEY_PROXY_CONFIGURATION_TYPE, ProxyConfigurationType.None.name());
			}
			save();
		}
		if (!containsKey(CONFIG_KEY_PROXY_URL)) {
			set(CONFIG_KEY_PROXY_URL, "");
		}

		if (containsKey("DailyUpdateCheck")) {
			if (!containsKey(CONFIG_KEY_DAILY_UPDATE_CHECK)) {
				set(CONFIG_KEY_DAILY_UPDATE_CHECK, getBoolean("DailyUpdateCheck"));
			}
			removeKey("DailyUpdateCheck");
		}
		if (!containsKey(CONFIG_KEY_DAILY_UPDATE_CHECK)) {
			set(CONFIG_KEY_DAILY_UPDATE_CHECK, false);
		}

		if (containsKey("NextDailyUpdateCheck")) {
			if (!containsKey(CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK)) {
				set(CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK, get("NextDailyUpdateCheck"));
			}
			removeKey("NextDailyUpdateCheck");
		}
		if (!containsKey(CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK)) {
			set(CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK, "");
		}
	}

	/**
	 * Proxy configuration of the configured type and URL, cached until one of these keys changes.
	 * Synchronized like the setters, so a reset of the cache by another thread is always seen.
	 *
	 * @return proxy configuration
	 */
	public synchronized ProxyConfiguration getProxyConfiguration() {
		if (proxyConfiguration == null) {
			final ProxyConfigurationType proxyConfigurationType = ProxyConfigurationType.getFromString(get(CONFIG_KEY_PROXY_CONFIGURATION_TYPE));
			final String proxyUrl = get(CONFIG_KEY_PROXY_URL);
			proxyConfiguration = new ProxyConfiguration(proxyConfigurationType, proxyUrl);
		}
		return proxyConfiguration;
	}
}

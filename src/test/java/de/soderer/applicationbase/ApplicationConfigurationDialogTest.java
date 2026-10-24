package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.swing.DropDown;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.ShowDataDialog;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.SystemUtilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.YesNoAsk;

/**
 * GUI tests of {@link ApplicationConfigurationDialog}. The dialogs are operated by a {@link DialogRobot}. Skipped in
 * headless environments. The user home directory is redirected to a temporary directory.
 */
public class ApplicationConfigurationDialogTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final String APPLICATION_NAME = "ConfigDialogTestApp";

	private String originalUserHome;
	private String originalRunningJarProperty;
	private File tempDirectory;
	private UpdateableGuiApplication application;
	private ApplicationConfigurationProperties configuration;
	private ApplicationConfigurationDialog dialog;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(DialogRobot.isGuiAvailable(), DialogRobot.GUI_TESTS_DISABLED_MESSAGE);
		tempDirectory = TestHelper.createTempDirectory("ApplicationConfigurationDialogTest").getCanonicalFile();
		originalUserHome = System.getProperty("user.home");
		System.setProperty("user.home", tempDirectory.getAbsolutePath());
		originalRunningJarProperty = System.getProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);
		System.clearProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);

		application = new UpdateableGuiApplication(APPLICATION_NAME, new Version(1, 2, 3), null);

		configuration = new ApplicationConfigurationProperties(APPLICATION_NAME);
		configuration.set(ConfigurationProperties.CONFIG_KEY_LANGUAGE, "en");
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, "Proxy-URL");
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, "http://proxy:8080");
		configuration.set("Test.Flag", true);
		configuration.set("Test.Text", "some text");
		configuration.set("Test.Recent", "recent value");
		configuration.set("Test.Recent" + ConfigurationProperties.MAXSIZE_EXTENSION, 5);
		configuration.set("Test.Choice", "b");
		configuration.set("Test.Choice" + ConfigurationProperties.ENUM_EXTENSION, "a,b,c");
		configuration.set("Test.UnknownChoice", "x");
		configuration.set("Test.UnknownChoice" + ConfigurationProperties.ENUM_EXTENSION, "a,b");
		configuration.set("Test.EmptyChoice", "");
		configuration.set("Test.EmptyChoice" + ConfigurationProperties.ENUM_EXTENSION, "first,second");
		configuration.set("Test.NoneChoice", "None");
		configuration.set("Test.NoneChoice" + ConfigurationProperties.ENUM_EXTENSION, "None,Other");
		configuration.ensureYesNoAsk("Test.Ask", YesNoAsk.ASK);
	}

	@AfterEach
	public void tearDown() {
		if (dialog != null) {
			dialog.dispose();
		}
		if (application != null) {
			application.dispose();
		}
		if (originalUserHome != null) {
			System.setProperty("user.home", originalUserHome);
		}
		if (originalRunningJarProperty == null) {
			System.clearProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);
		} else {
			System.setProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR, originalRunningJarProperty);
		}
		TestHelper.deleteRecursively(tempDirectory);
	}

	private static BufferedImage createIcon() {
		final BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		icon.setRGB(1, 1, 0xFFFF0000);
		return icon;
	}

	private ApplicationConfigurationDialog createDialog(final String versionIndexLocation) {
		dialog = new ApplicationConfigurationDialog(application, APPLICATION_NAME, "test-wm-class", new Version(1, 2, 3), LocalDateTime.now(), configuration,
				new byte[] { 0, 0, 1, 0 }, createIcon(), versionIndexLocation, null, Arrays.asList("arg"));
		return dialog;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, JComponent> getValueComponents(final Object configurationDialog) throws Exception {
		return (Map<String, JComponent>) TestHelper.getField(configurationDialog, "valueComponents");
	}

	private static Consumer<Window> modifyValues() {
		return window -> {
			try {
				final Map<String, JComponent> valueComponents = getValueComponents(window);
				((JTextField) valueComponents.get("Test.Text")).setText("changed text");
				((JCheckBox) valueComponents.get("Test.Flag")).setSelected(false);
				DialogRobot.findComponent(window, JSpinner.class).setValue(Integer.valueOf(7));
			} catch (final Exception e) {
				throw new RuntimeException(e);
			}
		};
	}

	private String writeVersionIndex(final String version) throws Exception {
		final File indexFile = TestHelper.writeTextFile(new File(tempDirectory, "versions.json"), "{ \"" + APPLICATION_NAME + "\": { \"version\": \"" + version + "\", \"downloadUrl\": \"https://example.com/App.jar\" } }");
		return "file://" + indexFile.toURI().getRawPath();
	}

	@Test
	public void testComponents() throws Exception {
		createDialog(null);
		final Map<String, JComponent> valueComponents = getValueComponents(dialog);

		assertTrue(valueComponents.get("Test.Flag") instanceof JCheckBox);
		assertTrue(((JCheckBox) valueComponents.get("Test.Flag")).isSelected());
		assertEquals("some text", ((JTextField) valueComponents.get("Test.Text")).getText());
		assertEquals("recent value", ((JTextField) valueComponents.get("Test.Recent")).getText());
		assertTrue(valueComponents.get("Test.Choice") instanceof DropDown);
		assertTrue(valueComponents.get("Test.Ask") instanceof DropDown);
		assertTrue(valueComponents.get(ConfigurationProperties.CONFIG_KEY_LANGUAGE) instanceof DropDown);
		assertTrue(valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE) instanceof DropDown);
		// Companion keys get no own row
		assertFalse(valueComponents.containsKey("Test.Choice" + ConfigurationProperties.ENUM_EXTENSION));
		assertFalse(valueComponents.containsKey("Test.Recent" + ConfigurationProperties.MAXSIZE_EXTENSION));

		// A configured value which is no option is kept, an empty value selects the first option
		assertEquals("x", ((DropDown) valueComponents.get("Test.UnknownChoice")).getText());
		assertEquals("first", ((DropDown) valueComponents.get("Test.EmptyChoice")).getText());
		assertEquals(LangResources.get("None"), ((DropDown) valueComponents.get("Test.NoneChoice")).getText());

		// Without update URL there is no update button
		assertNull(DialogRobot.findButton(dialog.getContentPane() instanceof JPanel ? (JPanel) dialog.getContentPane() : null, LangResources.get("checkUpdate")));
		assertNotNull(DialogRobot.findButton(dialog.getContentPane(), LangResources.get("save")));
	}

	@Test
	public void testDialogWithoutOptionalValues() throws Exception {
		dialog = new ApplicationConfigurationDialog(application, APPLICATION_NAME, null, new Version(1, 2, 3), null, configuration, null, null, "https://example.com/versions.json", null, null);
		assertNotNull(DialogRobot.findButton(dialog.getContentPane(), LangResources.get("checkUpdate")));
	}

	@Test
	public void testSave() throws Exception {
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps().on(ApplicationConfigurationDialog.class, DialogRobot.all(modifyValues(), DialogRobot.clickButton(LangResources.get("save")))).start()) {
			assertEquals(Result.OK, dialog.open());
		}
		assertEquals("changed text", configuration.get("Test.Text"));
		assertFalse(configuration.getBoolean("Test.Flag"));
		assertEquals(7, configuration.getInteger("Test.Recent" + ConfigurationProperties.MAXSIZE_EXTENSION));
	}

	@Test
	public void testCancelWithoutChanges() throws Exception {
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps().on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel"))).start()) {
			assertEquals(Result.CANCELED, dialog.open());
		}
	}

	@Test
	public void testCloseWindow() throws Exception {
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps().on(ApplicationConfigurationDialog.class, DialogRobot.closeWindow()).start()) {
			assertEquals(Result.CANCELED, dialog.open());
		}
	}

	@Test
	public void testCancelWithChanges() throws Exception {
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.all(modifyValues(), DialogRobot.clickButton(LangResources.get("cancel"))))
				// Keep the changes: the dialog stays open
				.onOptionPane(DialogRobot.answerOptionPane(JOptionPane.NO_OPTION))
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				// Discard the changes
				.onOptionPane(DialogRobot.answerOptionPane(JOptionPane.YES_OPTION))
				.start()) {
			assertEquals(Result.CANCELED, dialog.open());
		}
		assertEquals("some text", configuration.get("Test.Text"));
		assertTrue(configuration.getBoolean("Test.Flag"));
	}

	@Test
	public void testShowProperties() throws Exception {
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("showSystemProperties")))
				.on(ShowDataDialog.class, DialogRobot.dispose())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("showRuntimeProperties")))
				.on(ShowDataDialog.class, DialogRobot.dispose())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("showSwingProperties")))
				.on(ShowDataDialog.class, DialogRobot.dispose())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.start()) {
			assertEquals(Result.CANCELED, dialog.open());
		}
	}

	@Test
	public void testCheckUpdateWithoutNewerVersion() throws Exception {
		createDialog(writeVersionIndex("1.2.3"));
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("checkUpdate")))
				// "No newer version" message of the application window
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.start()) {
			dialog.open();
		}
	}

	@Test
	public void testCheckUpdateWithError() throws Exception {
		configuration.set(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, "");
		createDialog("ftp://example.com/versions.json");
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("checkUpdate")))
				// Error message of the application window
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.start()) {
			dialog.open();
		}
	}

	@Test
	public void testCreateLinuxDesktopLink() throws Exception {
		assumeTrue(TestHelper.isLinux(), "Linux only");
		assumeTrue(System.getenv("XDG_DATA_HOME") == null, "The desktop link would be created in the real XDG_DATA_HOME");
		final File jarFile = TestHelper.createJar(new File(tempDirectory, "Config App.jar"), true, Map.of("a.txt", "a"));
		System.setProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR, jarFile.getAbsolutePath());

		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("createDesktopLink")))
				.onOptionPane(DialogRobot.answerOptionPane(JOptionPane.OK_OPTION))
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.start()) {
			dialog.open();
		}

		final File applicationsDirectory = new File(tempDirectory, ".local/share/applications");
		final File desktopFile = new File(applicationsDirectory, APPLICATION_NAME + ".desktop");
		assertTrue(desktopFile.exists());
		assertTrue(new File(applicationsDirectory, APPLICATION_NAME + ".png").exists());
		final String content = TestHelper.readTextFile(desktopFile);
		assertTrue(content.contains("Name=" + APPLICATION_NAME + "\n"), content);
		assertTrue(content.contains(" -jar \"" + jarFile.getAbsolutePath() + "\" %U"), content);
		assertTrue(content.contains("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"), content);
		assertTrue(content.contains("StartupWMClass=test-wm-class\n"), content);
		assertTrue(content.contains("Icon="), content);
	}

	@Test
	public void testCreateDesktopLinkWithoutKnownJar() throws Exception {
		assumeTrue(System.getenv(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR) == null);
		createDialog(null);
		try (DialogRobot robot = DialogRobot.steps()
				.on(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("createDesktopLink")))
				// The user is asked for the jar file and cancels
				.onFileChooser(DialogRobot.dispose())
				.onAgain(ApplicationConfigurationDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.start()) {
			dialog.open();
		}
	}

	@Test
	public void testCreateWindowsDesktopLinkFailsWithoutWscript() throws Exception {
		assumeTrue(!TestHelper.isWindows(), "On Windows a real desktop link would be created");
		createDialog(null);
		// "wscript" is not available, so an error message is shown (for both variants with and without java home)
		try (DialogRobot robot = DialogRobot.steps()
				.onOptionPane(DialogRobot.answerOptionPane(JOptionPane.OK_OPTION))
				.onOptionPane(DialogRobot.answerOptionPane(JOptionPane.OK_OPTION))
				.start()) {
			TestHelper.invokeMethod(dialog, "createWindowsDesktopLink", new Class<?>[] { String.class, String.class }, "C:\\Apps\\App.jar", "C:\\Java");
			TestHelper.invokeMethod(dialog, "createWindowsDesktopLink", new Class<?>[] { String.class, String.class }, "C:\\Apps\\App.jar", null);
		}
		// The icon was written to the application directory in the user's home
		assertTrue(new File(new File(tempDirectory, "." + APPLICATION_NAME), APPLICATION_NAME + ".ico").exists());
	}

	@Test
	public void testLanguageChangeInfo() throws Exception {
		createDialog(null);
		final Class<?>[] parameterTypes = new Class<?>[] { String.class, String.class };
		try (DialogRobot robot = DialogRobot.steps().onOptionPane(DialogRobot.answerOptionPane(JOptionPane.OK_OPTION)).start()) {
			TestHelper.invokeMethod(dialog, "comboValueSelected", parameterTypes, ConfigurationProperties.CONFIG_KEY_LANGUAGE, "de");
			// Same language again: no info
			TestHelper.invokeMethod(dialog, "comboValueSelected", parameterTypes, ConfigurationProperties.CONFIG_KEY_LANGUAGE, "de");
		}
		assertEquals("de", dialog.getLanguage());

		// Proxy type changes update the proxy URL field
		TestHelper.invokeMethod(dialog, "comboValueSelected", parameterTypes, ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, "WPAD");
		assertFalse(getValueComponents(dialog).get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL).isEnabled());
		TestHelper.invokeMethod(dialog, "comboValueSelected", parameterTypes, "Test.Choice", "c");
	}

	@Test
	public void testHelpButton() throws Exception {
		createDialog(null);
		final javax.swing.JButton helpButton = (javax.swing.JButton) TestHelper.invokeMethod(dialog, "createHelpButton", new Class<?>[] { String.class }, "Help text");
		assertEquals("Help text", helpButton.getToolTipText());
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			helpButton.doClick();
		}
	}

	@Test
	public void testSettersAndGetters() throws Exception {
		createDialog(null);
		final Map<String, JComponent> valueComponents = getValueComponents(dialog);

		assertEquals("en", dialog.getLanguage());
		dialog.setLanguage("de");
		assertEquals("de", dialog.getLanguage());

		assertEquals("Proxy-URL", dialog.getProxyConfigurationType());
		assertTrue(valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL).isEnabled());
		dialog.setProxyConfigurationType("None");
		assertEquals("None", dialog.getProxyConfigurationType());
		assertFalse(valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL).isEnabled());
		dialog.setProxyConfigurationType("System");
		assertFalse(valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL).isEnabled());
		dialog.setProxyConfigurationType("PAC-URL");
		assertTrue(valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL).isEnabled());

		dialog.setProxyUrl("http://other:3128");
		assertEquals("http://other:3128", dialog.getProxyUrl());
		assertEquals("http://other:3128", ((JTextField) valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL)).getText());
		dialog.setProxyUrl(null);
		assertEquals("", dialog.getProxyUrl());

		assertSame(dialog, dialog.withAvailableLanguages(Arrays.asList("en", "de", "fr")));
		assertEquals(Arrays.asList("en", "de", "fr"), dialog.getAvailableLanguages());
		assertEquals(3, ((DropDown) valueComponents.get(ConfigurationProperties.CONFIG_KEY_LANGUAGE)).getItemCount());
		dialog.setAvailableLanguages(null);
		assertTrue(dialog.getAvailableLanguages().isEmpty());

		// Values of checkboxes and other fields set programmatically
		final Class<?>[] parameterTypes = new Class<?>[] { String.class, String.class };
		TestHelper.invokeMethod(dialog, "setCurrentValue", parameterTypes, "Test.Flag", "false");
		assertFalse(((JCheckBox) valueComponents.get("Test.Flag")).isSelected());
		TestHelper.invokeMethod(dialog, "setCurrentValue", parameterTypes, "Test.Text", null);
		assertEquals("", ((JTextField) valueComponents.get("Test.Text")).getText());
		TestHelper.invokeMethod(dialog, "setCurrentValue", parameterTypes, "Test.Unknown", "value");

		// Not yet saved
		assertEquals("en", configuration.get(ConfigurationProperties.CONFIG_KEY_LANGUAGE));
		assertTrue(configuration.getBoolean("Test.Flag"));
	}

	@Test
	public void testDisplayValues() throws Exception {
		createDialog(null);
		final Class<?>[] parameterTypes = new Class<?>[] { String.class, String.class };
		assertEquals(LangResources.get("None"), TestHelper.invokeMethod(dialog, "toDisplayValue", parameterTypes, "Test.Choice", "none"));
		assertEquals("plain", TestHelper.invokeMethod(dialog, "toDisplayValue", parameterTypes, "Test.Choice", "plain"));
		final String yesText = (String) TestHelper.invokeMethod(dialog, "toDisplayValue", parameterTypes, "Test.Ask", YesNoAsk.YES.getStorageValue());
		final String noText = (String) TestHelper.invokeMethod(dialog, "toDisplayValue", parameterTypes, "Test.Ask", YesNoAsk.NO.getStorageValue());
		final String askText = (String) TestHelper.invokeMethod(dialog, "toDisplayValue", parameterTypes, "Test.Ask", YesNoAsk.ASK.getStorageValue());
		assertFalse(yesText.equals(noText));
		assertFalse(noText.equals(askText));

		assertEquals(YesNoAsk.ASK.getStorageValue(), TestHelper.invokeMethod(dialog, "fromDisplayValue", parameterTypes, "Test.Ask", askText));
		assertEquals("None", TestHelper.invokeMethod(dialog, "fromDisplayValue", parameterTypes, "Test.Unknown", LangResources.get("None")));
		assertEquals("other", TestHelper.invokeMethod(dialog, "fromDisplayValue", parameterTypes, "Test.Unknown", "other"));
		assertNull(TestHelper.invokeMethod(dialog, "fromDisplayValue", parameterTypes, "Test.Unknown", null));
	}

	@Test
	public void testViewportWidthPanel() throws Exception {
		createDialog(null);
		final JScrollPane scrollPane = DialogRobot.findComponent(dialog.getContentPane(), JScrollPane.class);
		final Scrollable valuesPanel = (Scrollable) scrollPane.getViewport().getView();
		assertNotNull(valuesPanel.getPreferredScrollableViewportSize());
		assertEquals(16, valuesPanel.getScrollableUnitIncrement(new Rectangle(0, 0, 100, 50), SwingConstants.VERTICAL, 1));
		assertEquals(50, valuesPanel.getScrollableBlockIncrement(new Rectangle(0, 0, 100, 50), SwingConstants.VERTICAL, 1));
		assertEquals(100, valuesPanel.getScrollableBlockIncrement(new Rectangle(0, 0, 100, 50), SwingConstants.HORIZONTAL, 1));
		assertTrue(valuesPanel.getScrollableTracksViewportWidth());
		valuesPanel.getScrollableTracksViewportHeight();
	}
}

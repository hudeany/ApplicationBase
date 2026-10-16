package de.soderer.applicationbase;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.swing.DropDown;
import de.soderer.swing.ModalDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.ShowDataDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.FileUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.SystemUtilities;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;

/**
 * Configuration dialog of an application.
 *
 * <p>
 * Shows every configuration value of the application (like the SWT variant):
 * values with a "maxsize" companion key get a spinner plus text field, values
 * with an "enum" companion key get a combo box, "true"/"false" values get a
 * checkbox and everything else a text field. Values with a "conf.KEY.help"
 * language resource get a help button.
 * </p>
 *
 * <p>
 * Changes are collected and only written to the configuration when the user
 * saves. {@link #open()} returns {@link Result#OK} after saving, otherwise
 * {@link Result#CANCELED}.
 * </p>
 */
public class ApplicationConfigurationDialog extends ModalDialog<Result> {
	private static final long serialVersionUID = 422519084107817691L;

	/** Language of the root language file without language suffix ("LanguageProperties.properties") */
	private static final String DEFAULT_LANGUAGE_CODE = "en";

	private static final List<String> DEFAULT_PROXY_CONFIGURATION_TYPES = Utilities.getList("None", "System", "Proxy-URL", "WPAD", "PAC-URL");

	private static final int MAX_INITIAL_HEIGHT = 600;

	/** Application window, owner of the dialog and parent of an update started from the dialog */
	private final UpdateableGuiApplication updateParent;

	/** Name of the application, used in titles and for desktop link and icon file names */
	private final String applicationName;

	/** WM_CLASS of the application, used for the Linux desktop link (may be null) */
	private final String applicationClassName;

	/** Version of the application, shown in the title and used for the update check */
	private final Version applicationVersion;

	/** Build time of the application, shown as first row (may be null) */
	private final LocalDateTime applicationBuildTime;

	/** Configuration shown and changed by this dialog */
	private final ConfigurationProperties applicationConfiguration;

	/** Icon data (.ico) for the Windows desktop link (may be null) */
	private final byte[] iconImageDataWindows;

	/** Icon of the dialog and the Linux desktop link (may be null) */
	private final Image iconImageLinux;

	/** URL of the version information for the update check, blank for no update button */
	private final String versioninfoDownloadURL;

	/** Trusted CA certificates for the update signature check */
	private final String trustedUpdateCaCertificates;

	/** Application parameters passed to a restarted application after an update */
	private final List<String> appParameters;

	/** Changed values not yet written to the configuration (String or Boolean) */
	private final Map<String, Object> pendingChanges = new LinkedHashMap<>();

	/** Tracks whether any configuration field was modified by the user since the dialog was opened */
	private boolean configurationChanged = false;

	/** Suppresses change tracking and info messages while values are set programmatically */
	private boolean technicalChange = false;

	/** Input component per configuration key (text field, checkbox or drop down) */
	private final Map<String, JComponent> valueComponents = new HashMap<>();

	/** Languages offered for selection, if the configuration contains no enum definition for the language key */
	private List<String> availableLanguages = LangResources.getAvailableLanguages(DEFAULT_LANGUAGE_CODE);

	/** Language drop down filled from "availableLanguages" (null if the language has an enum definition in the configuration) */
	private DropDown availableLanguagesDropDown;

	/** Per drop down key: stored value -> text shown in the drop down */
	private final Map<String, Map<String, String>> dropDownDisplayValues = new HashMap<>();

	/** Last selected language, to show the restart info only when the language really changes */
	private String lastLanguageValue;

	/**
	 * Creates the configuration dialog.
	 *
	 * @param updateParent application window, owner of the dialog and parent of an
	 *            update started from the dialog
	 * @param applicationName name of the application
	 * @param applicationClassName WM_CLASS of the application, used for the Linux
	 *            desktop link, may be null
	 * @param applicationVersion version of the application
	 * @param applicationBuildTime build time of the application, may be null
	 * @param applicationConfiguration configuration to show and change
	 * @param iconImageDataWindows icon data (.ico) for the Windows desktop link,
	 *            may be null
	 * @param iconImageLinux icon of the dialog and the Linux desktop link, may be
	 *            null
	 * @param versioninfoDownloadURL URL of the version information for the update
	 *            check, blank for no update button
	 * @param trustedUpdateCaCertificates trusted CA certificates for the update
	 *            signature check
	 * @param appParameters application parameters passed to a restarted
	 *            application after an update
	 */
	public ApplicationConfigurationDialog(final UpdateableGuiApplication updateParent, final String applicationName, final String applicationClassName, final Version applicationVersion, final LocalDateTime applicationBuildTime, final ConfigurationProperties applicationConfiguration, final byte[] iconImageDataWindows, final Image iconImageLinux, final String versioninfoDownloadURL, final String trustedUpdateCaCertificates, final List<String> appParameters) {
		super(updateParent, applicationName + " (Version " + applicationVersion.toString() + ") " + LangResources.get("configuration"));

		this.updateParent = updateParent;
		this.applicationName = applicationName;
		this.applicationClassName = applicationClassName;
		this.applicationVersion = applicationVersion;
		this.applicationBuildTime = applicationBuildTime;
		this.applicationConfiguration = applicationConfiguration;
		this.iconImageDataWindows = iconImageDataWindows;
		this.iconImageLinux = iconImageLinux;
		this.versioninfoDownloadURL = versioninfoDownloadURL;
		this.trustedUpdateCaCertificates = trustedUpdateCaCertificates;
		this.appParameters = appParameters;

		lastLanguageValue = applicationConfiguration.get(ConfigurationProperties.CONFIG_KEY_LANGUAGE);

		returnValue = Result.CANCELED;

		if (iconImageLinux != null) {
			setIconImage(iconImageLinux);
		}

		final int margin = 5;

		final JPanel mainPanel = new JPanel(new BorderLayout(margin, margin));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(margin, margin, margin, margin));

		final JScrollPane valuesScrollPane = new JScrollPane(createConfigurationValuesPanel());
		valuesScrollPane.setBorder(BorderFactory.createEmptyBorder());
		valuesScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		mainPanel.add(valuesScrollPane, BorderLayout.CENTER);

		mainPanel.add(createButtonPanel(), BorderLayout.SOUTH);

		setContentPane(mainPanel);

		// Closing via the window decoration behaves like the cancel button (asks for unsaved changes)
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent event) {
				cancel();
			}
		});

		setResizable(true);
		pack();
		setMinimumSize(new Dimension(500, 300));
		setSize(Math.max(500, getWidth()), Math.max(300, Math.min(getHeight(), MAX_INITIAL_HEIGHT)));
		setLocationRelativeTo(updateParent);
	}

	/**
	 * The list of all configuration values: one row per key, labels left
	 * aligned in the first column (like the SWT variant).
	 */
	private JPanel createConfigurationValuesPanel() {
		final JPanel valuesPanel = new JPanel(new GridBagLayout());

		int row = 0;

		if (applicationBuildTime != null) {
			addLabel(valuesPanel, row, LangResources.get("buildTime"));
			final JTextField buildTimeText = new JTextField(DateUtilities.formatDate(DateUtilities.YYYY_MM_DD_HHMMSS, applicationBuildTime));
			buildTimeText.setEditable(false);
			valuesPanel.add(buildTimeText, createValueConstraints(row, 1, 2));
			row++;
		}

		technicalChange = true;
		try {
			for (final String key : Utilities.asSortedList(applicationConfiguration.getKeyList())) {
				if (key.endsWith(ConfigurationProperties.MAXSIZE_EXTENSION) || key.endsWith(ConfigurationProperties.ENUM_EXTENSION) || ConfigurationProperties.isInternalKey(key)) {
					continue;
				}

				String description = key;
				if (LangResources.existsKey("conf." + key) && Utilities.isNotBlank(LangResources.get("conf." + key))) {
					description = LangResources.get("conf." + key);
				}
				addLabel(valuesPanel, row, description);

				final String value = applicationConfiguration.get(key);
				if (applicationConfiguration.containsKey(key + ConfigurationProperties.MAXSIZE_EXTENSION)) {
					final String maxSizeKey = key + ConfigurationProperties.MAXSIZE_EXTENSION;
					final int maxSize = Math.max(1, Math.min(50, applicationConfiguration.getInteger(maxSizeKey)));
					final JSpinner maxSizeSpinner = new JSpinner(new SpinnerNumberModel(maxSize, 1, 50, 1));
					((JSpinner.DefaultEditor) maxSizeSpinner.getEditor()).getTextField().setColumns(3);
					maxSizeSpinner.addChangeListener(event -> registerChange(maxSizeKey, maxSizeSpinner.getValue().toString()));
					valuesPanel.add(maxSizeSpinner, createValueConstraints(row, 1, 1));

					final JTextField valueTextField = createTextField(key, value);
					valuesPanel.add(valueTextField, createValueConstraints(row, 2, 1));
				} else if (applicationConfiguration.containsKey(key + ConfigurationProperties.ENUM_EXTENSION)) {
					valuesPanel.add(createDropDown(key, Utilities.splitAndTrimList(applicationConfiguration.get(key + ConfigurationProperties.ENUM_EXTENSION)), value), createValueConstraints(row, 1, 2));
				} else if (ConfigurationProperties.CONFIG_KEY_LANGUAGE.equalsIgnoreCase(key)) {
					// No enum definition in the configuration: offer the languages known to the application
					availableLanguagesDropDown = createDropDown(key, availableLanguages, value);
					valuesPanel.add(availableLanguagesDropDown, createValueConstraints(row, 1, 2));
				} else if (ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE.equalsIgnoreCase(key)) {
					valuesPanel.add(createDropDown(key, DEFAULT_PROXY_CONFIGURATION_TYPES, value), createValueConstraints(row, 1, 2));
				} else if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
					final JCheckBox checkBox = new JCheckBox();
					checkBox.setSelected(Utilities.interpretAsBool(value));
					checkBox.addItemListener(event -> registerChange(key, checkBox.isSelected()));
					valueComponents.put(key, checkBox);
					valuesPanel.add(checkBox, createValueConstraints(row, 1, 2));
				} else {
					valuesPanel.add(createTextField(key, value), createValueConstraints(row, 1, 2));
				}

				if (LangResources.existsKey("conf." + key + ".help") && Utilities.isNotBlank(LangResources.get("conf." + key + ".help"))) {
					valuesPanel.add(createHelpButton(LangResources.get("conf." + key + ".help")), createHelpButtonConstraints(row));
				}

				row++;
			}
		} finally {
			technicalChange = false;
		}

		updateProxyUrlEnabledStatus();

		// Filler below the last row, keeps all rows at the top
		final GridBagConstraints fillerConstraints = new GridBagConstraints();
		fillerConstraints.gridx = 0;
		fillerConstraints.gridy = row;
		fillerConstraints.gridwidth = 4;
		fillerConstraints.weighty = 1;
		fillerConstraints.fill = GridBagConstraints.BOTH;
		valuesPanel.add(new JPanel(), fillerConstraints);

		return valuesPanel;
	}

	private static void addLabel(final JPanel valuesPanel, final int row, final String text) {
		final GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.WEST;
		labelConstraints.insets = new Insets(2, 2, 2, 8);
		valuesPanel.add(new JLabel(text), labelConstraints);
	}

	private static GridBagConstraints createValueConstraints(final int row, final int column, final int columnSpan) {
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = column;
		constraints.gridy = row;
		constraints.gridwidth = columnSpan;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(2, 0, 2, 4);
		// Only the value column (column 2) takes the extra width, so labels and spinners stay compact on the left
		if (column + columnSpan > 2) {
			constraints.weightx = 1;
			constraints.fill = GridBagConstraints.HORIZONTAL;
		}
		return constraints;
	}

	private static GridBagConstraints createHelpButtonConstraints(final int row) {
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 3;
		constraints.gridy = row;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new Insets(2, 0, 2, 2);
		return constraints;
	}

	private JTextField createTextField(final String key, final String value) {
		final JTextField textField = new JTextField(value == null ? "" : value, 20);
		textField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(final DocumentEvent event) {
				registerChange(key, textField.getText());
			}

			@Override
			public void removeUpdate(final DocumentEvent event) {
				registerChange(key, textField.getText());
			}

			@Override
			public void changedUpdate(final DocumentEvent event) {
				// Attribute changes only, no text change
			}
		});
		valueComponents.put(key, textField);
		return textField;
	}

	/**
	 * Drop down for a value with a fixed set of options. The options are shown
	 * with a display text (see {@link #toDisplayValue(String, String)}), but
	 * stored with their original value.
	 */
	private DropDown createDropDown(final String key, final List<String> options, final String value) {
		final DropDown dropDown = new DropDown();
		dropDown.setAllowCustomValues(false);
		fillDropDown(key, dropDown, options, value);
		dropDown.addActionListener(event -> {
			// getText() returns null while the entered text matches no option
			final String displayValue = dropDown.getText();
			if (displayValue != null) {
				comboValueSelected(key, fromDisplayValue(key, displayValue));
			}
		});
		valueComponents.put(key, dropDown);
		return dropDown;
	}

	private void fillDropDown(final String key, final DropDown dropDown, final List<String> options, final String value) {
		final Map<String, String> displayValues = new LinkedHashMap<>();
		for (final String option : options) {
			displayValues.put(option, toDisplayValue(key, option));
		}

		String selectedValue = null;
		if (Utilities.isBlank(value)) {
			if (!options.isEmpty()) {
				selectedValue = options.get(0);
			}
		} else {
			for (final String option : options) {
				if (option.equalsIgnoreCase(value)) {
					selectedValue = option;
					break;
				}
			}
			if (selectedValue == null) {
				// Keep a configured value that is not part of the options visible (and unchanged)
				selectedValue = value;
				displayValues.put(value, toDisplayValue(key, value));
			}
		}

		dropDownDisplayValues.put(key, displayValues);
		dropDown.setItems(new ArrayList<>(displayValues.values()));
		dropDown.setText(selectedValue == null ? "" : displayValues.get(selectedValue));
	}

	private void comboValueSelected(final String key, final String newValue) {
		registerChange(key, newValue);

		if (technicalChange) {
			return;
		}

		if (ConfigurationProperties.CONFIG_KEY_LANGUAGE.equalsIgnoreCase(key)) {
			final boolean valueUnchanged = lastLanguageValue == null ? newValue == null : lastLanguageValue.equals(newValue);
			if (!valueUnchanged) {
				lastLanguageValue = newValue;
				JOptionPane.showMessageDialog(this, LangResources.get("languageChangeRequiresRestart"), applicationName + " " + LangResources.get("configuration"), JOptionPane.INFORMATION_MESSAGE);
			}
		} else if (ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE.equalsIgnoreCase(key)) {
			updateProxyUrlEnabledStatus();
		}
	}

	/**
	 * The proxy URL is only relevant for the proxy types that use it.
	 */
	private void updateProxyUrlEnabledStatus() {
		final JComponent proxyUrlComponent = valueComponents.get(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL);
		if (proxyUrlComponent != null && valueComponents.containsKey(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE)) {
			final String proxyType = getProxyConfigurationType() == null ? "" : getProxyConfigurationType().replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
			proxyUrlComponent.setEnabled(!"none".equals(proxyType) && !"system".equals(proxyType) && !"wpad".equals(proxyType));
		}
	}

	/**
	 * Text shown in a drop down for a stored value: "None" in the user's
	 * language, language codes with their name (e.g. "Deutsch (de)") if a
	 * language resource exists for the code, otherwise the value itself.
	 */
	private static String toDisplayValue(final String key, final String value) {
		if ("None".equalsIgnoreCase(value)) {
			return LangResources.get("None");
		} else if (ConfigurationProperties.CONFIG_KEY_LANGUAGE.equalsIgnoreCase(key) && Utilities.isNotBlank(value) && LangResources.existsKey(value) && Utilities.isNotBlank(LangResources.get(value))) {
			return LangResources.get(value) + " (" + value + ")";
		} else {
			return value;
		}
	}

	/**
	 * Stored value for a text shown in a drop down (reverse of
	 * {@link #toDisplayValue(String, String)}).
	 */
	private String fromDisplayValue(final String key, final String displayValue) {
		final Map<String, String> displayValues = dropDownDisplayValues.get(key);
		if (displayValues != null) {
			for (final Entry<String, String> entry : displayValues.entrySet()) {
				if (entry.getValue().equals(displayValue)) {
					return entry.getKey();
				}
			}
		}
		return displayValue != null && displayValue.equals(LangResources.get("None")) ? "None" : displayValue;
	}

	private JButton createHelpButton(final String helpText) {
		final JButton helpButton = new JButton();
		try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("images/icons/question.png")) {
			if (inputStream != null) {
				helpButton.setIcon(new ImageIcon(inputStream.readAllBytes()));
			} else {
				helpButton.setText("?");
			}
		} catch (@SuppressWarnings("unused") final IOException e) {
			helpButton.setText("?");
		}
		helpButton.setMargin(new Insets(1, 1, 1, 1));
		helpButton.setToolTipText(helpText);
		helpButton.addActionListener(e -> new QuestionDialog(this, applicationName, helpText, LangResources.get("ok")).open());
		return helpButton;
	}

	private void registerChange(final String key, final Object value) {
		pendingChanges.put(key, value);
		if (!technicalChange) {
			configurationChanged = true;
		}
	}

	private JPanel createButtonPanel() {
		final JPanel buttonPanel = new JPanel(new GridBagLayout());

		final List<JButton> buttons = new ArrayList<>();

		final JButton saveButton = new JButton(LangResources.get("save"));
		saveButton.addActionListener(e -> {
			applyPendingChanges();
			returnValue = Result.OK;
			dispose();
		});
		buttons.add(saveButton);

		final JButton createDesktopLinkButton = new JButton(LangResources.get("createDesktopLink"));
		createDesktopLinkButton.addActionListener(e -> createDesktopLink());
		buttons.add(createDesktopLinkButton);

		final JButton showSystemPropertiesButton = new JButton(LangResources.get("showSystemProperties"));
		showSystemPropertiesButton.addActionListener(e -> showProperties(LangResources.get("systemProperties"), Utilities.getSystemPropertiesMap()));
		buttons.add(showSystemPropertiesButton);

		final JButton showRuntimePropertiesButton = new JButton(LangResources.get("showRuntimeProperties"));
		showRuntimePropertiesButton.addActionListener(e -> showProperties(LangResources.get("runtimeProperties"), SystemUtilities.getRuntimeProperties(true)));
		buttons.add(showRuntimePropertiesButton);

		// Swing counterpart of the SWT variant's "SWT properties"
		final JButton showSwingPropertiesButton = new JButton(LangResources.get("showSwingProperties"));
		showSwingPropertiesButton.addActionListener(e -> showProperties(LangResources.get("swingProperties"), getSwingProperties()));
		buttons.add(showSwingPropertiesButton);

		if (Utilities.isNotBlank(versioninfoDownloadURL)) {
			final JButton updateButton = new JButton(LangResources.get("checkUpdate"));
			updateButton.addActionListener(e -> checkUpdate());
			buttons.add(updateButton);
		}

		final JButton cancelButton = new JButton(LangResources.get("cancel"));
		cancelButton.addActionListener(e -> cancel());
		buttons.add(cancelButton);

		// Two equally wide columns; with an odd number of buttons the last one spans both columns
		for (int i = 0; i < buttons.size(); i++) {
			final GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = i % 2;
			constraints.gridy = i / 2;
			constraints.weightx = 1;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.insets = new Insets(2, 2, 2, 2);
			if (i == buttons.size() - 1 && i % 2 == 0) {
				constraints.gridwidth = 2;
			}
			buttonPanel.add(buttons.get(i), constraints);
		}

		getRootPane().setDefaultButton(saveButton);

		return buttonPanel;
	}

	private void cancel() {
		if (configurationChanged) {
			final int result = JOptionPane.showConfirmDialog(this, LangResources.get("discardUnsavedConfigurationChanges"), applicationName + " " + LangResources.get("configuration"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (result != JOptionPane.YES_OPTION) {
				// Keep the dialog open, discard nothing
				return;
			}
		}
		returnValue = Result.CANCELED;
		dispose();
	}

	private void applyPendingChanges() {
		for (final Entry<String, Object> entry : pendingChanges.entrySet()) {
			final Object value = entry.getValue();
			if (value instanceof Boolean) {
				applicationConfiguration.set(entry.getKey(), (Boolean) value);
			} else {
				applicationConfiguration.set(entry.getKey(), (String) value);
			}
		}
	}

	private void showProperties(final String title, final Map<String, String> properties) {
		final Map<String, String> displayProperties = new LinkedHashMap<>();
		for (final Entry<String, String> entry : properties.entrySet()) {
			final String value = entry.getValue() == null ? "" : entry.getValue();
			displayProperties.put(entry.getKey(), value.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r"));
		}
		new ShowDataDialog(this, applicationName + " " + title, Utilities.getStringFromMap(displayProperties, "\n", " = ", true)).withResizable(true).open();
	}

	/**
	 * Look and feel, toolkit and screen information, useful for analyzing
	 * display problems (e.g. HiDPI scaling on Linux).
	 */
	private static Map<String, String> getSwingProperties() {
		final Map<String, String> properties = new LinkedHashMap<>();
		properties.put("LookAndFeel", UIManager.getLookAndFeel() == null ? "" : UIManager.getLookAndFeel().getName() + " (" + UIManager.getLookAndFeel().getClass().getName() + ")");
		properties.put("SystemLookAndFeel", UIManager.getSystemLookAndFeelClassName());
		properties.put("Toolkit", Toolkit.getDefaultToolkit().getClass().getName());
		try {
			properties.put("ScreenResolution (dpi)", Integer.toString(Toolkit.getDefaultToolkit().getScreenResolution()));
		} catch (final Exception e) {
			properties.put("ScreenResolution (dpi)", e.getClass().getSimpleName());
		}
		properties.put("Headless", Boolean.toString(GraphicsEnvironment.isHeadless()));
		if (!GraphicsEnvironment.isHeadless()) {
			final GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
			properties.put("Screens", Integer.toString(screens.length));
			for (int i = 0; i < screens.length; i++) {
				final GraphicsConfiguration configuration = screens[i].getDefaultConfiguration();
				properties.put("Screen " + (i + 1), configuration.getBounds().width + "x" + configuration.getBounds().height
						+ " at " + configuration.getBounds().x + "," + configuration.getBounds().y
						+ ", scale " + configuration.getDefaultTransform().getScaleX() + "x" + configuration.getDefaultTransform().getScaleY());
			}
		}
		return properties;
	}

	private void checkUpdate() {
		try {
			// A configuration without proxy settings means a direct connection
			final String proxyConfigurationType = Utilities.isBlank(getProxyConfigurationType()) ? "None" : getProxyConfigurationType();
			ApplicationUpdateUtilities.executeUpdate(updateParent, versioninfoDownloadURL, new ProxyConfiguration(ProxyConfigurationType.getFromString(proxyConfigurationType), getProxyUrl()), applicationName, applicationVersion, trustedUpdateCaCertificates, null, null, appParameters, true, false);
		} catch (final Exception e) {
			new QuestionDialog(this, applicationName + " ERROR", "ERROR:\n" + e.getMessage()).withBackgroundColor(SwingColor.LightRed).open();
		}
	}

	private void createDesktopLink() {
		try {
			String applicationJarFileName = SystemUtilities.getCurrentlyRunningJarFilePath();
			if (Utilities.isBlank(applicationJarFileName)) {
				applicationJarFileName = openJarFilePathDialog();
			}

			if (Utilities.isBlank(applicationJarFileName)) {
				return;
			}

			final String javaHome = System.getProperty("java.home");

			if (SystemUtilities.isWindowsSystem()) {
				createWindowsDesktopLink(applicationJarFileName, javaHome);
			} else if (SystemUtilities.isLinuxSystem()) {
				createLinuxDesktopLink(applicationJarFileName, javaHome);
			}
		} catch (final Exception e) {
			JOptionPane.showMessageDialog(this, LangResources.get("errorMessage", e.getMessage()), LangResources.get("createDesktopLink"), JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Creates the desktop link on Windows via a VBScript. The desktop folder is
	 * determined by Windows itself ("SpecialFolders"), because it may be
	 * redirected (e.g. to OneDrive) or localized.
	 */
	private void createWindowsDesktopLink(final String applicationJarFileName, final String javaHome) throws Exception {
		final String userHomeApplicationDirectory = System.getProperty("user.home") + File.separator + "." + applicationName;
		final String iconPath = userHomeApplicationDirectory + File.separator + applicationName + ".ico";
		if (!new File(userHomeApplicationDirectory).exists()) {
			new File(userHomeApplicationDirectory).mkdirs();
		}
		if (new File(iconPath).exists()) {
			new File(iconPath).delete();
		}

		// Only reference the icon in the link, if it really exists, otherwise Windows reports the missing icon file
		boolean iconAvailable = false;
		if (iconImageDataWindows != null && iconImageDataWindows.length > 0) {
			FileUtilities.write(new File(iconPath), iconImageDataWindows);
			iconAvailable = new File(iconPath).isFile();
		}

		final File tempScriptFile = File.createTempFile(applicationName.replaceAll("[^A-Za-z0-9_.-]", "_") + "_createlink_", ".vbs");
		final File resultFile = File.createTempFile(applicationName.replaceAll("[^A-Za-z0-9_.-]", "_") + "_createlink_", ".txt");

		final StringBuilder data = new StringBuilder();
		data.append("Set shell = CreateObject(\"WScript.Shell\")\n");
		data.append("Set fso = CreateObject(\"Scripting.FileSystemObject\")\n");
		data.append("linkPath = fso.BuildPath(shell.SpecialFolders(\"Desktop\"), " + toVbsString(applicationName + ".lnk") + ")\n");
		data.append("If fso.FileExists(linkPath) Then fso.DeleteFile linkPath, True\n");
		data.append("Set shortcut = shell.CreateShortcut(linkPath)\n");
		data.append("shortcut.Description = " + toVbsString(applicationName) + "\n");
		if (Utilities.isNotBlank(javaHome)) {
			data.append("shortcut.TargetPath = " + toVbsString(javaHome + "\\bin\\javaw.exe") + "\n");
			// JVM options of the currently running process must be placed before "-jar", everything after the jar file path is passed to the application
			final StringBuilder commandLineArguments = new StringBuilder();
			for (final String jvmArgument : getJvmArgumentsForDesktopLink()) {
				commandLineArguments.append(quoteWindowsCommandLineArgument(jvmArgument)).append(" ");
			}
			commandLineArguments.append("-jar ").append(quoteWindowsCommandLineArgument(applicationJarFileName));
			data.append("shortcut.Arguments = " + toVbsString(commandLineArguments.toString()) + "\n");
		} else {
			data.append("shortcut.TargetPath = " + toVbsString(applicationJarFileName) + "\n");
		}
		if (iconAvailable) {
			data.append("shortcut.IconLocation = " + toVbsString(iconPath + ",0") + "\n");
		} else if (Utilities.isNotBlank(javaHome)) {
			// Fallback: icon of javaw.exe instead of a reference to a missing file
			data.append("shortcut.IconLocation = " + toVbsString(javaHome + "\\bin\\javaw.exe,0") + "\n");
		}
		data.append("shortcut.Save\n");
		// Report the link path back as UTF-16 (TristateTrue), so paths with umlauts survive
		data.append("Set resultFile = fso.OpenTextFile(" + toVbsString(resultFile.getAbsolutePath()) + ", 2, True, -1)\n");
		data.append("resultFile.Write linkPath\n");
		data.append("resultFile.Close\n");

		try {
			// WScript reads scripts as ANSI or as UTF-16LE with BOM, but not as UTF-8 (paths with umlauts would break)
			FileUtilities.writeStringToFile(tempScriptFile, "\uFEFF" + data.toString(), StandardCharsets.UTF_16LE);

			// Wait for the script, so the link exists before the success message and the script is not deleted while still in use
			final Process process = new ProcessBuilder("wscript", "//B", tempScriptFile.getAbsolutePath())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			final int exitCode = process.waitFor();

			String desktopLinkFilePath = null;
			if (resultFile.length() > 0) {
				desktopLinkFilePath = new String(Files.readAllBytes(resultFile.toPath()), StandardCharsets.UTF_16).trim();
			}

			if (exitCode == 0 && Utilities.isNotBlank(desktopLinkFilePath) && new File(desktopLinkFilePath).exists()) {
				JOptionPane.showMessageDialog(this, LangResources.get("desktopLinkWasCreatedAt", desktopLinkFilePath), LangResources.get("createDesktopLink"), JOptionPane.PLAIN_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(this, LangResources.get("errorMessage", "wscript exit code " + exitCode), LangResources.get("createDesktopLink"), JOptionPane.ERROR_MESSAGE);
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			JOptionPane.showMessageDialog(this, LangResources.get("errorMessage", e.getMessage()), LangResources.get("createDesktopLink"), JOptionPane.ERROR_MESSAGE);
		} catch (final IOException e) {
			JOptionPane.showMessageDialog(this, LangResources.get("errorMessage", e.getMessage()), LangResources.get("createDesktopLink"), JOptionPane.ERROR_MESSAGE);
		} finally {
			tempScriptFile.delete();
			resultFile.delete();
		}
	}

	/**
	 * VBScript string literal: quotes are escaped by doubling them.
	 */
	private static String toVbsString(final String value) {
		return "\"" + value.replace("\"", "\"\"").replace("\r", "").replace("\n", " ") + "\"";
	}

	/**
	 * Creates the launcher on Linux in "~/.local/share/applications" (XDG
	 * standard, created if missing), where it shows up in the application menu
	 * of the desktop environment. The localized desktop folder (e.g.
	 * "~/Schreibtisch") is not used. The link starts the same Java runtime with
	 * the same JVM options as the running application.
	 */
	private void createLinuxDesktopLink(final String applicationJarFileName, final String javaHome) throws Exception {
		final String dataHome = Utilities.isNotBlank(System.getenv("XDG_DATA_HOME")) ? System.getenv("XDG_DATA_HOME") : System.getProperty("user.home") + "/.local/share";
		final File applicationsDirectory = new File(dataHome, "applications");
		if (!applicationsDirectory.isDirectory() && !applicationsDirectory.mkdirs()) {
			throw new IOException("Cannot create directory " + applicationsDirectory.getAbsolutePath());
		}

		// Only path separators are replaced, so the file name stays the same as for links created by former versions
		final String fileBaseName = applicationName.replaceAll("[/\\\\]", "_");
		final File desktopLinkFile = new File(applicationsDirectory, fileBaseName + ".desktop");
		final File iconFile = new File(applicationsDirectory, fileBaseName + ".png");
		if (desktopLinkFile.exists()) {
			desktopLinkFile.delete();
		}
		if (iconFile.exists()) {
			iconFile.delete();
		}

		final StringBuilder data = new StringBuilder();
		data.append("[Desktop Entry]\n");
		// Line breaks would start a new key in the .desktop file
		data.append("Name=" + applicationName.replaceAll("[\\r\\n]+", " ") + "\n");

		if (iconImageLinux != null) {
			ImageIO.write(toBufferedImage(iconImageLinux), "png", iconFile);
			data.append("Icon=" + iconFile.getAbsolutePath() + "\n");
		}

		final String javaBinary = Utilities.isNotBlank(javaHome) && new File(javaHome, "bin/java").canExecute() ? new File(javaHome, "bin/java").getAbsolutePath() : "java";
		final StringBuilder exec = new StringBuilder(quoteDesktopExecArgument(javaBinary));
		final List<String> jvmArguments = getJvmArgumentsForDesktopLink();
		// JVM option "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED" allows the application to be pinned in the Linux Gnome applications dock.
		final String addOpensX11 = "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED";
		if (!jvmArguments.contains(addOpensX11)) {
			jvmArguments.add(0, addOpensX11);
		}
		// JVM options must be placed before "-jar", everything after the jar file path is passed to the application.
		for (final String jvmArgument : jvmArguments) {
			exec.append(' ').append(quoteDesktopExecArgument(jvmArgument));
		}
		// %U-parameter allows drag and drop of files on starter bar in unity
		exec.append(" -jar ").append(quoteDesktopExecArgument(applicationJarFileName)).append(" %U");
		data.append("Exec=" + exec + "\n");
		data.append("Terminal=false\n");
		data.append("Type=Application\n");

		if (applicationClassName != null) {
			// StartupWMClass must match the WM_CLASS set by the main application, so the launcher can group all windows of the same application
			data.append("StartupWMClass=" + applicationClassName + "\n");
		}

		try {
			FileUtilities.writeStringToFile(desktopLinkFile, data.toString(), StandardCharsets.UTF_8);

			desktopLinkFile.setExecutable(true);

			JOptionPane.showMessageDialog(this, LangResources.get("desktopLinkWasCreatedAt", desktopLinkFile.getAbsolutePath()), LangResources.get("createDesktopLink"), JOptionPane.PLAIN_MESSAGE);
		} catch (final IOException e) {
			JOptionPane.showMessageDialog(this, LangResources.get("errorMessage", e.getMessage()), LangResources.get("createDesktopLink"), JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Excluded JVM arguments are added by IDEs or debuggers or are set by the java launcher itself
	 * and must not be copied into a desktop link.
	 */
	private static final String[] EXCLUDED_JVM_ARGUMENT_PREFIXES = new String[] {
			"-agentlib:",
			"-agentpath:",
			"-javaagent:",
			"-Xdebug",
			"-Xrunjdwp",
			"-Djava.class.path=",
			"-Dsun.java.command=",
			"-Dsun.java.launcher",
			"-Djdk.module.main"
	};

	/**
	 * JVM arguments of the currently running process (e.g. -Xmx, -D..., --add-opens),
	 * so that an application started via desktop link runs with the same settings.
	 */
	private static List<String> getJvmArgumentsForDesktopLink() {
		final List<String> jvmArguments = new ArrayList<>();
		try {
			for (final String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
				if (Utilities.isNotBlank(argument) && !isExcludedJvmArgument(argument)) {
					jvmArguments.add(argument);
				}
			}
		} catch (@SuppressWarnings("unused") final RuntimeException | LinkageError e) {
			// Module java.management may be missing in a reduced runtime image (jlink), so the link is created without JVM arguments
		}
		return jvmArguments;
	}

	private static boolean isExcludedJvmArgument(final String argument) {
		for (final String excludedPrefix : EXCLUDED_JVM_ARGUMENT_PREFIXES) {
			if (argument.startsWith(excludedPrefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Quotes a single argument according to the Windows command line parsing rules (CommandLineToArgvW / MSVC runtime),
	 * which are also used by java.exe and javaw.exe.
	 */
	private static String quoteWindowsCommandLineArgument(final String argument) {
		if (!argument.isEmpty() && argument.indexOf(' ') < 0 && argument.indexOf('\t') < 0 && argument.indexOf('"') < 0) {
			return argument;
		}

		final StringBuilder quotedArgument = new StringBuilder("\"");
		int pendingBackslashes = 0;
		for (final char character : argument.toCharArray()) {
			if (character == '\\') {
				pendingBackslashes++;
			} else if (character == '"') {
				// Backslashes before a quote must be doubled and the quote itself escaped
				quotedArgument.append("\\".repeat(pendingBackslashes * 2 + 1)).append('"');
				pendingBackslashes = 0;
			} else {
				quotedArgument.append("\\".repeat(pendingBackslashes)).append(character);
				pendingBackslashes = 0;
			}
		}
		// Trailing backslashes must be doubled, so that they do not escape the closing quote
		quotedArgument.append("\\".repeat(pendingBackslashes * 2)).append('"');
		return quotedArgument.toString();
	}

	/**
	 * Quotes a single argument for the "Exec" key of a Linux .desktop file.
	 * Within the quotes the characters '"', '`', '$' and the backslash must be escaped by a
	 * backslash, then backslashes must be escaped once more, because the value of
	 * the key is a string with its own escape rules. Field codes are introduced
	 * by '%', so a literal '%' must be doubled.
	 */
	private static String quoteDesktopExecArgument(final String argument) {
		final StringBuilder quotedArgument = new StringBuilder("\"");
		for (final char character : argument.toCharArray()) {
			if (character == '\\') {
				quotedArgument.append("\\\\\\\\");
			} else if (character == '"' || character == '`' || character == '$') {
				quotedArgument.append("\\\\").append(character);
			} else if (character == '%') {
				quotedArgument.append("%%");
			} else {
				quotedArgument.append(character);
			}
		}
		return quotedArgument.append('"').toString();
	}

	/**
	 * ImageIO can only write RenderedImages, but e.g. ImageIcon.getImage() returns
	 * a toolkit image, so any other image type is drawn into a BufferedImage.
	 */
	private static BufferedImage toBufferedImage(final Image image) {
		if (image instanceof BufferedImage) {
			return (BufferedImage) image;
		}

		final ImageIcon loadedImage = new ImageIcon(image); // Waits until the image data is completely loaded
		final BufferedImage bufferedImage = new BufferedImage(Math.max(1, loadedImage.getIconWidth()), Math.max(1, loadedImage.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D graphics = bufferedImage.createGraphics();
		try {
			graphics.drawImage(loadedImage.getImage(), 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return bufferedImage;
	}

	private String openJarFilePathDialog() {
		final JFileChooser fileChooser = new JFileChooser();
		fileChooser.setDialogTitle(LangResources.get("createDesktopLink"));
		if (JFileChooser.APPROVE_OPTION == fileChooser.showOpenDialog(this)) {
			return fileChooser.getSelectedFile().getAbsolutePath();
		} else {
			return null;
		}
	}

	/**
	 * Current (possibly not yet saved) value of a configuration key.
	 */
	private String getCurrentValue(final String key) {
		if (pendingChanges.containsKey(key)) {
			final Object value = pendingChanges.get(key);
			return value == null ? null : value.toString();
		} else {
			return applicationConfiguration.get(key);
		}
	}

	/**
	 * Sets the value shown for a key, which is saved together with the user's
	 * changes.
	 */
	private void setCurrentValue(final String key, final String value) {
		technicalChange = true;
		try {
			final JComponent component = valueComponents.get(key);
			if (component instanceof DropDown) {
				// Refill, so a value that is not part of the options is added and shown
				final Map<String, String> displayValues = dropDownDisplayValues.get(key);
				final List<String> options = displayValues == null ? new ArrayList<>() : new ArrayList<>(displayValues.keySet());
				fillDropDown(key, (DropDown) component, options, value);
			} else if (component instanceof JTextField) {
				((JTextField) component).setText(value == null ? "" : value);
			} else if (component instanceof JCheckBox) {
				((JCheckBox) component).setSelected(Utilities.interpretAsBool(value));
			}
			registerChange(key, value == null ? "" : value);
		} finally {
			technicalChange = false;
		}
		updateProxyUrlEnabledStatus();
	}

	/**
	 * Returns the language shown in the dialog (possibly not yet saved).
	 *
	 * @return language code, may be null
	 */
	public String getLanguage() {
		return getCurrentValue(ConfigurationProperties.CONFIG_KEY_LANGUAGE);
	}

	/**
	 * Sets the language shown in the dialog. It is written to the configuration
	 * together with the user's changes when the user saves.
	 *
	 * @param language language code
	 */
	public void setLanguage(final String language) {
		lastLanguageValue = language;
		setCurrentValue(ConfigurationProperties.CONFIG_KEY_LANGUAGE, language);
	}

	/**
	 * Returns the proxy configuration type shown in the dialog (possibly not yet
	 * saved).
	 *
	 * @return proxy configuration type (e.g. "None", "System", "Proxy-URL"), may be
	 *         null
	 */
	public String getProxyConfigurationType() {
		return getCurrentValue(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE);
	}

	/**
	 * Sets the proxy configuration type shown in the dialog. It is written to the
	 * configuration together with the user's changes when the user saves.
	 *
	 * @param proxyConfigurationType proxy configuration type
	 */
	public void setProxyConfigurationType(final String proxyConfigurationType) {
		setCurrentValue(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_CONFIGURATION_TYPE, proxyConfigurationType);
	}

	/**
	 * Returns the proxy URL shown in the dialog (possibly not yet saved).
	 *
	 * @return proxy URL, may be null
	 */
	public String getProxyUrl() {
		return getCurrentValue(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL);
	}

	/**
	 * Sets the proxy URL shown in the dialog. It is written to the configuration
	 * together with the user's changes when the user saves.
	 *
	 * @param proxyUrl proxy URL
	 */
	public void setProxyUrl(final String proxyUrl) {
		setCurrentValue(ApplicationConfigurationProperties.CONFIG_KEY_PROXY_URL, proxyUrl);
	}

	/**
	 * Returns the languages offered for selection, if the configuration contains
	 * no enum definition for the language key.
	 *
	 * @return language codes
	 */
	public List<String> getAvailableLanguages() {
		return availableLanguages;
	}

	/**
	 * Sets the languages offered for selection, if the configuration itself
	 * contains no enum definition for the language key.
	 *
	 * @param availableLanguages language codes, null for none
	 */
	public void setAvailableLanguages(final List<String> availableLanguages) {
		this.availableLanguages = availableLanguages == null ? new ArrayList<>() : availableLanguages;

		if (availableLanguagesDropDown != null) {
			technicalChange = true;
			try {
				fillDropDown(ConfigurationProperties.CONFIG_KEY_LANGUAGE, availableLanguagesDropDown, this.availableLanguages, getLanguage());
			} finally {
				technicalChange = false;
			}
		}
	}

	/**
	 * Sets the languages offered for selection and returns this dialog for
	 * chaining.
	 *
	 * @param availableLanguagesValue language codes, null for none
	 * @return this dialog for chaining
	 */
	public ApplicationConfigurationDialog withAvailableLanguages(final List<String> availableLanguagesValue) {
		setAvailableLanguages(availableLanguagesValue);
		return this;
	}
}

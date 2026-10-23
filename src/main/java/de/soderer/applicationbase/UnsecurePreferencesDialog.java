package de.soderer.applicationbase;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableModel;

import de.soderer.swing.ModalDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

/**
 * Modal dialog to manage named preference entries (e.g. connection settings)
 * stored unencrypted in an {@link UnsecureDataStore} file.
 *
 * <p>
 * The caller sets the current data entry via
 * {@link #setCurrentDataEntry(Object)} before opening the dialog; its class
 * determines which entries are listed. The user can load an entry (afterwards
 * available via {@link #getCurrentDataEntry()}), store the current entry under a
 * new or an existing name, or delete entries. After storing or deleting, the
 * current data entry is null.
 * </p>
 */
public class UnsecurePreferencesDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = -2855354859482098916L;

	/** File the preference entries are loaded from and saved to */
	private final File preferencesFile;

	/** Data store with all preference entries, null until loaded or first created */
	private transient UnsecureDataStore unsecureDataStore = null;

	/** Data entry to store, or the loaded entry after loading; its class determines the listed entries */
	private transient Object currentDataEntry;

	/** Button loading the selected entry */
	private final JButton loadButton;

	/** Button storing the current data entry under the name entered in newNameField */
	private final JButton createButton;

	/** Button overwriting the selected entry with the current data entry */
	private final JButton updateButton;

	/** Button deleting the selected entries */
	private final JButton deleteButton;

	/** Input field for the name of a new entry */
	private final JTextField newNameField;

	/** List of the names of all stored entries of the current data entry's class */
	private final JTable preferencesTable;

	/**
	 * Creates an unsecure preferences dialog.
	 *
	 * @param parent owner window, may be null
	 * @param title title of the dialog
	 * @param text tooltip text of the name field and the entry list
	 * @param unsecurePreferencesFile file of the data store
	 * @param textButton_Load text of the load button
	 * @param textButton_Create text of the create button
	 * @param textButton_Update text of the update button
	 * @param textButton_Delete text of the delete button
	 * @param textButton_Save text of the save button
	 * @param textButton_Cancel text of the cancel button
	 */
	public UnsecurePreferencesDialog(final Window parent, final String title, final String text, final File unsecurePreferencesFile,
			final String textButton_Load, final String textButton_Create, final String textButton_Update, final String textButton_Delete,
			final String textButton_Save, final String textButton_Cancel) {
		super(parent, title);

		preferencesFile = unsecurePreferencesFile;

		setResizable(false);

		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		final JPanel interimPanel = new JPanel();
		interimPanel.setLayout(new BoxLayout(interimPanel, BoxLayout.LINE_AXIS));

		interimPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		final JPanel comboPanel = new JPanel();
		comboPanel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
		comboPanel.setLayout(new BoxLayout(comboPanel, BoxLayout.PAGE_AXIS));

		newNameField = new JTextField();
		newNameField.setToolTipText(text);
		newNameField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(final DocumentEvent event) {
				createButton.setEnabled(StringUtilities.isNotBlank(newNameField.getText()));
			}

			@Override
			public void removeUpdate(final DocumentEvent event) {
				createButton.setEnabled(StringUtilities.isNotBlank(newNameField.getText()));
			}

			@Override
			public void changedUpdate(final DocumentEvent event) {
				createButton.setEnabled(StringUtilities.isNotBlank(newNameField.getText()));
			}
		});
		comboPanel.add(newNameField);

		preferencesTable = new JTable(new DefaultTableModel(0, 1) {
			private static final long serialVersionUID = 2304318826575460731L;

			@Override
			public boolean isCellEditable(final int rowIndex, final int columnIndex) {
				// Entry names must not be changed here: a renamed row would load nothing
				return false;
			}
		});
		preferencesTable.setBorder(BorderFactory.createLineBorder(Color.GRAY));
		preferencesTable.setToolTipText(text);
		// Anonymous class instead of a lambda: the buttons are final fields assigned later in the constructor
		preferencesTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(final ListSelectionEvent event) {
				loadButton.setEnabled(preferencesTable.getSelectedRows().length == 1);
				updateButton.setEnabled(preferencesTable.getSelectedRows().length == 1);
				deleteButton.setEnabled(preferencesTable.getSelectedRows().length > 0);
			}
		});
		preferencesTable.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent event) {
				if (event.getClickCount() >= 2) {
					final int rowIndex = preferencesTable.rowAtPoint(event.getPoint());
					if (rowIndex >= 0) {
						loadEntry(getEntryName(rowIndex));
					}
				}
			}
		});
		comboPanel.add(preferencesTable);

		interimPanel.add(comboPanel);

		interimPanel.add(Box.createRigidArea(new Dimension(5, 0)));

		panel.add(interimPanel);

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		// Button Panel
		final JPanel buttonPanel1 = new JPanel();
		buttonPanel1.setLayout(new BoxLayout(buttonPanel1, BoxLayout.LINE_AXIS));

		buttonPanel1.add(Box.createRigidArea(new Dimension(5, 0)));

		loadButton = new JButton(textButton_Load);
		loadButton.setEnabled(false);
		loadButton.addActionListener(event -> {
			if (preferencesTable.getSelectedRows().length == 1) {
				loadEntry(getEntryName(preferencesTable.getSelectedRows()[0]));
			}
		});
		buttonPanel1.add(loadButton);

		buttonPanel1.add(Box.createRigidArea(new Dimension(5, 0)));

		createButton = new JButton(textButton_Create);
		createButton.setEnabled(false);
		createButton.addActionListener(event -> {
			if (StringUtilities.isNotBlank(newNameField.getText())) {
				getOrCreateDataStore().addEntry(newNameField.getText(), currentDataEntry);
				saveAndClose();
			}
		});
		buttonPanel1.add(createButton);

		buttonPanel1.add(Box.createRigidArea(new Dimension(5, 0)));

		updateButton = new JButton(textButton_Update);
		updateButton.setEnabled(false);
		updateButton.addActionListener(event -> {
			if (preferencesTable.getSelectedRows().length == 1) {
				getOrCreateDataStore().addEntry(getEntryName(preferencesTable.getSelectedRows()[0]), currentDataEntry);
				saveAndClose();
			}
		});
		buttonPanel1.add(updateButton);

		buttonPanel1.add(Box.createRigidArea(new Dimension(5, 0)));

		deleteButton = new JButton(textButton_Delete);
		deleteButton.setEnabled(false);
		deleteButton.addActionListener(event -> {
			if (preferencesTable.getSelectedRows().length > 0) {
				// Snapshot of the names first: removing rows changes the selection indexes
				final List<String> entryNames = new ArrayList<>();
				for (final int rowIndex : preferencesTable.getSelectedRows()) {
					entryNames.add(getEntryName(rowIndex));
				}
				final UnsecureDataStore dataStore = getOrCreateDataStore();
				for (final String entryName : entryNames) {
					dataStore.removeEntry(currentDataEntry.getClass(), entryName);
				}
				saveAndClose();
			}
		});
		buttonPanel1.add(deleteButton);

		buttonPanel1.add(Box.createRigidArea(new Dimension(5, 0)));

		final JPanel buttonPanel2 = new JPanel();
		buttonPanel2.setLayout(new BoxLayout(buttonPanel2, BoxLayout.LINE_AXIS));

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton saveButton = new JButton(textButton_Save);
		saveButton.addActionListener(event -> {
			{
				final UnsecureDataStore dataStore = getOrCreateDataStore();
				if (StringUtilities.isNotBlank(newNameField.getText())) {
					dataStore.addEntry(newNameField.getText(), currentDataEntry);
				} else if (preferencesTable.getSelectedRows().length == 1) {
					dataStore.addEntry(getEntryName(preferencesTable.getSelectedRows()[0]), currentDataEntry);
				}
				saveAndClose();
			}
		});
		buttonPanel2.add(saveButton);

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		final JButton cancelButton = new JButton(textButton_Cancel);
		cancelButton.addActionListener(event -> {
			currentDataEntry = null;
			dispose();
		});
		buttonPanel2.add(cancelButton);

		buttonPanel2.add(Box.createRigidArea(new Dimension(5, 0)));

		panel.add(buttonPanel1);

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		panel.add(buttonPanel2);

		panel.add(Box.createRigidArea(new Dimension(0, 5)));

		add(panel);

		pack();

		setLocationRelativeTo(parent);

		// requestFocus() before the dialog is visible has no effect
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(final WindowEvent event) {
				newNameField.requestFocusInWindow();
			}
		});
	}

	private String getEntryName(final int rowIndex) {
		return (String) preferencesTable.getModel().getValueAt(rowIndex, 0);
	}

	private void loadEntry(final String entryName) {
		currentDataEntry = unsecureDataStore.getEntry(currentDataEntry.getClass(), entryName);
		dispose();
	}

	private UnsecureDataStore getOrCreateDataStore() {
		if (unsecureDataStore == null) {
			unsecureDataStore = new UnsecureDataStore();
		}
		return unsecureDataStore;
	}

	/**
	 * Saves the data store and closes the dialog. An error is shown if saving
	 * fails.
	 */
	private void saveAndClose() {
		try {
			getOrCreateDataStore().save(preferencesFile);
		} catch (final Exception e) {
			e.printStackTrace();
			new QuestionDialog(this, getTitle(), LangResources.get("cannotSaveKeystore") + "\n" + e.getMessage(), LangResources.get("ok")).withBackgroundColor(SwingColor.LightRed).open();
		}
		currentDataEntry = null;
		dispose();
	}

	/**
	 * Sets the data entry to store. Must be set before {@link #open()}, because
	 * its class determines which entries are listed.
	 *
	 * @param dataEntry data entry, not null
	 */
	public void setCurrentDataEntry(final Object dataEntry) {
		currentDataEntry = dataEntry;
	}

	/**
	 * Returns the current data entry: the loaded entry after loading, null after
	 * storing, deleting or canceling.
	 *
	 * @return current data entry, may be null
	 */
	public Object getCurrentDataEntry() {
		return currentDataEntry;
	}

	/**
	 * Loads the preferences file and shows the dialog.
	 *
	 * @return always true
	 * @throws IllegalStateException if no current data entry was set
	 */
	@Override
	public Boolean open() {
		if (currentDataEntry == null) {
			throw new IllegalStateException("setCurrentDataEntry() must be called before open()");
		}

		try {
			if (preferencesFile.exists()) {
				unsecureDataStore = new UnsecureDataStore();
				unsecureDataStore.load(preferencesFile);

				if (unsecureDataStore != null) {
					final DefaultTableModel tableModel = (DefaultTableModel) preferencesTable.getModel();
					for (final String entryName : unsecureDataStore.getEntryNames(currentDataEntry.getClass())) {
						tableModel.addRow(new Object[] { entryName });
					}

					pack();

					setVisible(true);
				}
			} else {
				setVisible(true);
			}
		} catch (final Exception e) {
			new QuestionDialog(getOwner(), "ERROR", "ERROR:\n" + e.getMessage()).withBackgroundColor(SwingColor.LightRed).open();
		}
		return true;
	}
}

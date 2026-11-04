package de.soderer.applicationbase.modern;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import de.soderer.applicationbase.UnsecureDataStore;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialog;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;

/**
 * Modal dialog in the {@link ModernUi} design to manage named preference entries (e.g. connection settings)
 * stored unencrypted in an {@link UnsecureDataStore} file. Same API as
 * {@link de.soderer.applicationbase.UnsecurePreferencesDialog}.
 *
 * <p>
 * The caller sets the current data entry via {@link #setCurrentDataEntry(Object)} before opening the dialog; its
 * class determines which entries are listed. The user can load an entry (afterwards available via
 * {@link #getCurrentDataEntry()}), store the current entry under a new or an existing name, or delete entries.
 * After storing or deleting, the current data entry is null.
 * </p>
 */
public class UnsecurePreferencesDialog extends ModalDialog<Boolean> {
	/** Serialization version */
	private static final long serialVersionUID = -2855354859482098916L;

	/** Width of the name field and the entry list */
	private static final int LIST_WIDTH = 360;

	/** Height of the entry list */
	private static final int LIST_HEIGHT = 200;

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
	 * @param parent                  owner window, may be null
	 * @param title                   title of the dialog
	 * @param text                    hint text above the name field, also tooltip of the name field and the
	 *                                entry list
	 * @param unsecurePreferencesFile file of the data store
	 * @param textButton_Load         text of the load button
	 * @param textButton_Create       text of the create button
	 * @param textButton_Update       text of the update button
	 * @param textButton_Delete       text of the delete button
	 * @param textButton_Save         text of the save button
	 * @param textButton_Cancel       text of the cancel button
	 */
	public UnsecurePreferencesDialog(final Window parent, final String title, final String text, final File unsecurePreferencesFile,
			final String textButton_Load, final String textButton_Create, final String textButton_Update, final String textButton_Delete,
			final String textButton_Save, final String textButton_Cancel) {
		super(parent, title, IconKind.FILE);

		preferencesFile = unsecurePreferencesFile;

		setResizable(false);

		// Top: optional hint and the name field for a new entry with its create button
		final JPanel newEntryPanel = new JPanel(new BorderLayout(8, 6));
		newEntryPanel.setOpaque(false);
		if (StringUtilities.isNotBlank(text)) {
			final JLabel hintLabel = ModernUi.createMutedLabel("<html><body style='width:" + LIST_WIDTH + "px'>" + ModernUi.escapeHtml(text).replace("\n", "<br>") + "</body></html>");
			newEntryPanel.add(hintLabel, BorderLayout.NORTH);
		}

		newNameField = new JTextField();
		newNameField.setToolTipText(text);
		ModernUi.styleTextField(newNameField, false);
		newEntryPanel.add(newNameField, BorderLayout.CENTER);

		createButton = new Button(textButton_Create, IconKind.PLUS, ButtonStyle.SECONDARY);
		createButton.setEnabled(false);
		createButton.addActionListener(event -> {
			if (StringUtilities.isNotBlank(newNameField.getText())) {
				getOrCreateDataStore().addEntry(newNameField.getText(), currentDataEntry);
				saveAndClose();
			}
		});
		newEntryPanel.add(createButton, BorderLayout.EAST);

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
		// Enter in the name field creates the entry, like a click on the create button
		newNameField.addActionListener(event -> {
			if (createButton.isEnabled()) {
				createButton.doClick();
			}
		});

		getContentArea().add(newEntryPanel, BorderLayout.NORTH);

		// Center: list of the stored entries
		preferencesTable = new JTable(new DefaultTableModel(0, 1) {
			private static final long serialVersionUID = 2304318826575460731L;

			@Override
			public boolean isCellEditable(final int rowIndex, final int columnIndex) {
				// Entry names must not be changed here: a renamed row would load nothing
				return false;
			}
		}) {
			private static final long serialVersionUID = 4920357138846201544L;

			@Override
			public Component prepareRenderer(final TableCellRenderer renderer, final int row, final int column) {
				final Component component = super.prepareRenderer(renderer, row, column);
				if (!isRowSelected(row)) {
					component.setBackground(ModernUi.getStripedRowBackground(row));
				}
				if (component instanceof final JComponent jComponent) {
					jComponent.setBorder(BorderFactory.createEmptyBorder(0, 9, 0, 9));
				}
				return component;
			}
		};
		ModernUi.styleTable(preferencesTable);
		// A single column needs no header and no grid
		preferencesTable.setTableHeader(null);
		preferencesTable.setShowGrid(false);
		preferencesTable.setIntercellSpacing(new Dimension(0, 0));
		preferencesTable.setFillsViewportHeight(true);
		preferencesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		preferencesTable.setToolTipText(text);
		preferencesTable.getSelectionModel().addListSelectionListener(event -> checkButtonStatus());
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

		final JScrollPane tableScrollPane = new JScrollPane(preferencesTable);
		ModernUi.styleScrollPane(tableScrollPane, ModernUi.CARD);
		tableScrollPane.setPreferredSize(new Dimension(LIST_WIDTH, LIST_HEIGHT));

		final JPanel listPanel = new JPanel(new BorderLayout(0, 8));
		listPanel.setOpaque(false);
		listPanel.add(tableScrollPane, BorderLayout.CENTER);

		// Below the list: actions on the selected entries
		final JPanel entryButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		entryButtonPanel.setOpaque(false);
		entryButtonPanel.setBorder(BorderFactory.createEmptyBorder(0, -8, 0, 0));

		loadButton = new Button(textButton_Load, IconKind.IMPORT, ButtonStyle.SECONDARY);
		loadButton.setEnabled(false);
		loadButton.addActionListener(event -> {
			if (preferencesTable.getSelectedRows().length == 1) {
				loadEntry(getEntryName(preferencesTable.getSelectedRows()[0]));
			}
		});
		entryButtonPanel.add(loadButton);

		updateButton = new Button(textButton_Update, IconKind.EDIT, ButtonStyle.SECONDARY);
		updateButton.setEnabled(false);
		updateButton.addActionListener(event -> {
			if (preferencesTable.getSelectedRows().length == 1) {
				getOrCreateDataStore().addEntry(getEntryName(preferencesTable.getSelectedRows()[0]), currentDataEntry);
				saveAndClose();
			}
		});
		entryButtonPanel.add(updateButton);

		deleteButton = new Button(textButton_Delete, IconKind.TRASH, ButtonStyle.DANGEROUS);
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
		entryButtonPanel.add(deleteButton);

		listPanel.add(entryButtonPanel, BorderLayout.SOUTH);

		getContentArea().add(listPanel, BorderLayout.CENTER);

		// Button bar: save under the entered or selected name, or cancel
		addButton(textButton_Save, IconKind.CHECK, ButtonStyle.PRIMARY, event -> {
			final UnsecureDataStore dataStore = getOrCreateDataStore();
			if (StringUtilities.isNotBlank(newNameField.getText())) {
				dataStore.addEntry(newNameField.getText(), currentDataEntry);
			} else if (preferencesTable.getSelectedRows().length == 1) {
				dataStore.addEntry(getEntryName(preferencesTable.getSelectedRows()[0]), currentDataEntry);
			}
			saveAndClose();
		});

		addButton(textButton_Cancel, null, ButtonStyle.SECONDARY, event -> {
			currentDataEntry = null;
			dispose();
		});

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

	/**
	 * Enables the entry buttons according to the current selection.
	 */
	private void checkButtonStatus() {
		final int selectedRowCount = preferencesTable.getSelectedRows().length;
		loadButton.setEnabled(selectedRowCount == 1);
		updateButton.setEnabled(selectedRowCount == 1);
		deleteButton.setEnabled(selectedRowCount > 0);
	}

	/**
	 * Returns the name of an entry of the list.
	 *
	 * @param rowIndex row index of the entry
	 * @return name of the entry
	 */
	private String getEntryName(final int rowIndex) {
		return (String) preferencesTable.getModel().getValueAt(rowIndex, 0);
	}

	/**
	 * Loads an entry as the current data entry and closes the dialog.
	 *
	 * @param entryName name of the entry
	 */
	private void loadEntry(final String entryName) {
		currentDataEntry = getOrCreateDataStore().getEntry(currentDataEntry.getClass(), entryName);
		dispose();
	}

	/**
	 * Returns the data store, creating an empty one if there is none yet.
	 *
	 * @return data store, never null
	 */
	private UnsecureDataStore getOrCreateDataStore() {
		if (unsecureDataStore == null) {
			unsecureDataStore = new UnsecureDataStore();
		}
		return unsecureDataStore;
	}

	/**
	 * Saves the data store and closes the dialog. An error is shown if saving fails.
	 */
	private void saveAndClose() {
		try {
			getOrCreateDataStore().save(preferencesFile);
		} catch (final Exception e) {
			e.printStackTrace();
			new QuestionDialog(this, getTitle(), LangResources.get("cannotSaveKeystore") + "\n" + e.getMessage(), LangResources.get("ok")).withDialogStyle(DialogStyle.ERROR).open();
		}
		currentDataEntry = null;
		dispose();
	}

	/**
	 * Sets the data entry to store. Must be set before {@link #open()}, because its class determines which
	 * entries are listed.
	 *
	 * @param dataEntry data entry, not null
	 */
	public void setCurrentDataEntry(final Object dataEntry) {
		currentDataEntry = dataEntry;
	}

	/**
	 * Returns the current data entry: the loaded entry after loading, null after storing, deleting or canceling.
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

				final DefaultTableModel tableModel = (DefaultTableModel) preferencesTable.getModel();
				for (final String entryName : unsecureDataStore.getEntryNames(currentDataEntry.getClass())) {
					tableModel.addRow(new Object[] { entryName });
				}

				pack();
			}
			setVisible(true);
		} catch (final Exception e) {
			new QuestionDialog(getOwner(), "ERROR", "ERROR:\n" + e.getMessage()).withDialogStyle(DialogStyle.ERROR).open();
		}
		return true;
	}
}

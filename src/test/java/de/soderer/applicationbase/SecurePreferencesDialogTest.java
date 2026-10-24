package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.swing.CredentialsDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.utilities.WrongPasswordException;

/**
 * GUI tests of {@link SecurePreferencesDialog}. The dialogs are operated by a {@link DialogRobot}. Skipped in
 * headless environments.
 */
public class SecurePreferencesDialogTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final String PASSWORD = "secret";

	private File tempDirectory;
	private File preferencesFile;
	private SecurePreferencesDialog dialog;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(DialogRobot.isGuiAvailable(), DialogRobot.GUI_TESTS_DISABLED_MESSAGE);
		tempDirectory = TestHelper.createTempDirectory("SecurePreferencesDialogTest");
		preferencesFile = new File(tempDirectory, "preferences.secpref");
	}

	@AfterEach
	public void tearDown() {
		if (dialog != null) {
			dialog.dispose();
		}
		TestHelper.deleteRecursively(tempDirectory);
	}

	private SecurePreferencesDialog createDialog(final File file) {
		dialog = new SecurePreferencesDialog(null, "Secure preferences", "Tooltip", file, "Load", "Create", "Update", "Delete", "Save", "Cancel", "Enter password", "Password", "OK", "Abort");
		return dialog;
	}

	private void createPreferencesFile(final String... entryNames) throws Exception {
		final SecureDataStore store = new SecureDataStore();
		for (int i = 0; i < entryNames.length; i++) {
			store.addEntry(entryNames[i], new TestDataEntry(entryNames[i], i));
		}
		store.save(preferencesFile, PASSWORD.toCharArray());
	}

	private SecureDataStore loadPreferencesFile() throws Exception {
		final SecureDataStore store = new SecureDataStore();
		store.load(preferencesFile, PASSWORD.toCharArray());
		return store;
	}

	@Test
	public void testPassword() {
		createDialog(preferencesFile);
		assertNull(dialog.getPassword());

		final char[] password = PASSWORD.toCharArray();
		dialog.setPassword(password);
		// The password is copied in both directions
		password[0] = 'X';
		final char[] returnedPassword = dialog.getPassword();
		assertArrayEquals(PASSWORD.toCharArray(), returnedPassword);
		returnedPassword[0] = 'Y';
		assertArrayEquals(PASSWORD.toCharArray(), dialog.getPassword());

		dialog.setPassword(null);
		assertNull(dialog.getPassword());
	}

	@Test
	public void testOpenWithoutDataEntry() {
		createDialog(preferencesFile);
		assertThrows(IllegalStateException.class, dialog::open);
	}

	@Test
	public void testCreateEntryInNewFile() throws Exception {
		createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("new", 42));
		try (DialogRobot robot = DialogRobot.steps()
				.on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("first"), DialogRobot.clickButton("Create")))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword(PASSWORD), DialogRobot.clickDefaultButton()))
				.start()) {
			assertTrue(dialog.open());
		}
		assertNull(dialog.getCurrentDataEntry());
		assertArrayEquals(PASSWORD.toCharArray(), dialog.getPassword());
		assertEquals(new TestDataEntry("new", 42), loadPreferencesFile().getEntry(TestDataEntry.class, "first"));
	}

	@Test
	public void testCreateEntryPasswordCanceled() throws Exception {
		createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("new", 42));
		try (DialogRobot robot = DialogRobot.steps()
				.on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("first"), DialogRobot.clickButton("Create")))
				.on(CredentialsDialog.class, DialogRobot.dispose())
				// The preferences dialog stays open, nothing was saved
				.onAgain(SecurePreferencesDialog.class, DialogRobot.clickButton("Cancel"))
				.start()) {
			dialog.open();
		}
		assertFalse(preferencesFile.exists());
		assertNull(dialog.getCurrentDataEntry());
	}

	@Test
	public void testLoadEntryWithKnownPassword() throws Exception {
		createPreferencesFile("a", "b");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("b"), DialogRobot.clickButton("Load"))).start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("b", 1), dialog.getCurrentDataEntry());
	}

	@Test
	public void testLoadEntryAsksForPassword() throws Exception {
		createPreferencesFile("a", "b");
		createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("wrong"), DialogRobot.clickDefaultButton()))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword(PASSWORD), DialogRobot.clickDefaultButton()))
				.on(SecurePreferencesDialog.class, DialogRobot.doubleClickTableRow("a"))
				.start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("a", 0), dialog.getCurrentDataEntry());
		assertArrayEquals(PASSWORD.toCharArray(), dialog.getPassword());
	}

	@Test
	public void testPasswordDialogCanceled() throws Exception {
		createPreferencesFile("a");
		createDialog(preferencesFile);
		final TestDataEntry currentDataEntry = new TestDataEntry("current", 1);
		dialog.setCurrentDataEntry(currentDataEntry);
		try (DialogRobot robot = DialogRobot.steps().on(CredentialsDialog.class, DialogRobot.dispose()).start()) {
			assertTrue(dialog.open());
		}
		// The dialog was never shown
		assertEquals(currentDataEntry, dialog.getCurrentDataEntry());
		assertNull(dialog.getPassword());
	}

	@Test
	public void testUpdateEntry() throws Exception {
		createPreferencesFile("a", "b");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry("changed", 99));
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("a"), DialogRobot.clickButton("Update"))).start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("changed", 99), loadPreferencesFile().getEntry(TestDataEntry.class, "a"));
		// Saved with the same password
		assertArrayEquals(PASSWORD.toCharArray(), dialog.getPassword());
	}

	@Test
	public void testDeleteEntries() throws Exception {
		createPreferencesFile("a", "b", "c");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry());
		final AtomicBoolean loadButtonEnabledForMultipleRows = new AtomicBoolean(true);
		final AtomicBoolean deleteButtonEnabledForMultipleRows = new AtomicBoolean(false);
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, window -> {
			DialogRobot.selectTableRows(0, 1).accept(window);
			loadButtonEnabledForMultipleRows.set(DialogRobot.findButton(window, "Load").isEnabled());
			deleteButtonEnabledForMultipleRows.set(DialogRobot.findButton(window, "Delete").isEnabled());
			DialogRobot.clickButton("Delete").accept(window);
		}).start()) {
			dialog.open();
		}
		assertFalse(loadButtonEnabledForMultipleRows.get());
		assertTrue(deleteButtonEnabledForMultipleRows.get());
		assertEquals(1, loadPreferencesFile().getEntryNames(TestDataEntry.class).size());
	}

	@Test
	public void testSaveWithNewNameAndSelectedEntry() throws Exception {
		createPreferencesFile("a");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry("saved", 5));
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("second"), DialogRobot.clickButton("Save"))).start()) {
			dialog.open();
		}
		assertEquals(Set.of("a", "second"), loadPreferencesFile().getEntryNames(TestDataEntry.class));

		final SecurePreferencesDialog secondDialog = createDialog(preferencesFile);
		secondDialog.setPassword(PASSWORD.toCharArray());
		secondDialog.setCurrentDataEntry(new TestDataEntry("overwritten", 6));
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("a"), DialogRobot.clickButton("Save"))).start()) {
			secondDialog.open();
		}
		assertEquals(new TestDataEntry("overwritten", 6), loadPreferencesFile().getEntry(TestDataEntry.class, "a"));
	}

	@Test
	public void testSaveWithoutNameAndSelection() throws Exception {
		createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("saved", 5));
		try (DialogRobot robot = DialogRobot.steps()
				.on(SecurePreferencesDialog.class, DialogRobot.clickButton("Save"))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword(PASSWORD), DialogRobot.clickDefaultButton()))
				.start()) {
			dialog.open();
		}
		assertTrue(loadPreferencesFile().getEntryNames(TestDataEntry.class).isEmpty());
		assertThrows(WrongPasswordException.class, () -> new SecureDataStore().load(preferencesFile, "other".toCharArray()));
	}

	@Test
	public void testCancel() throws Exception {
		createPreferencesFile("a");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry("current", 1));
		try (DialogRobot robot = DialogRobot.steps().on(SecurePreferencesDialog.class, DialogRobot.clickButton("Cancel")).start()) {
			dialog.open();
		}
		assertNull(dialog.getCurrentDataEntry());
	}

	@Test
	public void testDamagedFile() throws Exception {
		TestHelper.writeTextFile(preferencesFile, "damaged");
		createDialog(preferencesFile);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			assertTrue(dialog.open());
		}
		// The password is reset after an error
		assertNull(dialog.getPassword());
	}

	@Test
	public void testSaveError() throws Exception {
		final File fileInMissingDirectory = new File(new File(tempDirectory, "missing"), "preferences.secpref");
		createDialog(fileInMissingDirectory);
		dialog.setPassword(PASSWORD.toCharArray());
		dialog.setCurrentDataEntry(new TestDataEntry("x", 1));
		try (DialogRobot robot = DialogRobot.steps()
				.on(SecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("first"), DialogRobot.clickButton("Create")))
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.start()) {
			dialog.open();
		}
		assertFalse(fileInMissingDirectory.exists());
		assertNull(dialog.getCurrentDataEntry());
	}
}

package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JTextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.swing.QuestionDialog;

/**
 * GUI tests of {@link UnsecurePreferencesDialog}. The dialog is operated by a {@link DialogRobot}. Skipped in
 * headless environments.
 */
@SuppressWarnings("static-method")
public class UnsecurePreferencesDialogTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;
	private File preferencesFile;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(DialogRobot.isGuiAvailable(), DialogRobot.GUI_TESTS_DISABLED_MESSAGE);
		tempDirectory = TestHelper.createTempDirectory("UnsecurePreferencesDialogTest");
		preferencesFile = new File(tempDirectory, "preferences.pref");
	}

	@AfterEach
	public void tearDown() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	private UnsecurePreferencesDialog createDialog(final File file) {
		return new UnsecurePreferencesDialog(null, "Preferences", "Tooltip", file, "Load", "Create", "Update", "Delete", "Save", "Cancel");
	}

	private void createPreferencesFile(final String... entryNames) throws Exception {
		final UnsecureDataStore store = new UnsecureDataStore();
		for (int i = 0; i < entryNames.length; i++) {
			store.addEntry(entryNames[i], new TestDataEntry(entryNames[i], i));
		}
		store.save(preferencesFile);
	}

	private UnsecureDataStore loadPreferencesFile() throws Exception {
		final UnsecureDataStore store = new UnsecureDataStore();
		store.load(preferencesFile);
		return store;
	}

	@Test
	public void testOpenWithoutDataEntry() {
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		try {
			assertThrows(IllegalStateException.class, dialog::open);
		} finally {
			dialog.dispose();
		}
	}

	@Test
	public void testCreateEntryInNewFile() throws Exception {
		final AtomicBoolean createButtonEnabledWithName = new AtomicBoolean(false);
		final AtomicBoolean createButtonEnabledWithoutName = new AtomicBoolean(true);
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("new", 42));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, window -> {
			DialogRobot.enterText("").accept(window);
			createButtonEnabledWithoutName.set(DialogRobot.findButton(window, "Create").isEnabled());
			DialogRobot.enterText("first").accept(window);
			createButtonEnabledWithName.set(DialogRobot.findButton(window, "Create").isEnabled());
			DialogRobot.clickButton("Create").accept(window);
		}).start()) {
			assertTrue(dialog.open());
		}
		assertTrue(createButtonEnabledWithName.get());
		assertFalse(createButtonEnabledWithoutName.get());
		assertNull(dialog.getCurrentDataEntry());
		assertEquals(new TestDataEntry("new", 42), loadPreferencesFile().getEntry(TestDataEntry.class, "first"));
	}

	@Test
	public void testLoadEntry() throws Exception {
		createPreferencesFile("a", "b", "c");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("b"), DialogRobot.clickButton("Load"))).start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("b", 1), dialog.getCurrentDataEntry());
	}

	@Test
	public void testLoadEntryByDoubleClick() throws Exception {
		createPreferencesFile("a", "b");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.doubleClickTableRow("a")).start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("a", 0), dialog.getCurrentDataEntry());
	}

	@Test
	public void testUpdateEntry() throws Exception {
		createPreferencesFile("a", "b");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("changed", 99));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("a"), DialogRobot.clickButton("Update"))).start()) {
			dialog.open();
		}
		assertNull(dialog.getCurrentDataEntry());
		assertEquals(new TestDataEntry("changed", 99), loadPreferencesFile().getEntry(TestDataEntry.class, "a"));
		assertEquals(new TestDataEntry("b", 1), loadPreferencesFile().getEntry(TestDataEntry.class, "b"));
	}

	@Test
	public void testDeleteEntries() throws Exception {
		createPreferencesFile("a", "b", "c");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRows(0, 1), DialogRobot.clickButton("Delete"))).start()) {
			dialog.open();
		}
		assertEquals(1, loadPreferencesFile().getEntryNames(TestDataEntry.class).size());
	}

	@Test
	public void testSaveWithNewName() throws Exception {
		createPreferencesFile("a");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("saved", 5));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("second"), DialogRobot.clickButton("Save"))).start()) {
			dialog.open();
		}
		assertEquals(Set.of("a", "second"), loadPreferencesFile().getEntryNames(TestDataEntry.class));
	}

	@Test
	public void testSaveSelectedEntry() throws Exception {
		createPreferencesFile("a", "b");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("saved", 5));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.selectTableRow("b"), DialogRobot.clickButton("Save"))).start()) {
			dialog.open();
		}
		assertEquals(new TestDataEntry("saved", 5), loadPreferencesFile().getEntry(TestDataEntry.class, "b"));
	}

	@Test
	public void testSaveWithoutNameAndSelection() throws Exception {
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("saved", 5));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, DialogRobot.clickButton("Save")).start()) {
			dialog.open();
		}
		// An empty store is written
		assertTrue(preferencesFile.exists());
		assertTrue(loadPreferencesFile().getEntryNames(TestDataEntry.class).isEmpty());
	}

	@Test
	public void testCancel() throws Exception {
		createPreferencesFile("a");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry("current", 1));
		try (DialogRobot robot = DialogRobot.steps().on(UnsecurePreferencesDialog.class, window -> {
			// Changes of the name field are not saved on cancel
			DialogRobot.findComponent(window, JTextField.class).setText("unsaved");
			DialogRobot.clickButton("Cancel").accept(window);
		}).start()) {
			dialog.open();
		}
		assertNull(dialog.getCurrentDataEntry());
		assertEquals(Set.of("a"), loadPreferencesFile().getEntryNames(TestDataEntry.class));
	}

	@Test
	public void testDamagedFile() throws Exception {
		TestHelper.writeTextFile(preferencesFile, "{ damaged");
		final UnsecurePreferencesDialog dialog = createDialog(preferencesFile);
		dialog.setCurrentDataEntry(new TestDataEntry());
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			assertTrue(dialog.open());
		}
	}

	@Test
	public void testSaveError() throws Exception {
		final File fileInMissingDirectory = new File(new File(tempDirectory, "missing"), "preferences.pref");
		final UnsecurePreferencesDialog dialog = createDialog(fileInMissingDirectory);
		dialog.setCurrentDataEntry(new TestDataEntry("x", 1));
		try (DialogRobot robot = DialogRobot.steps()
				.on(UnsecurePreferencesDialog.class, DialogRobot.all(DialogRobot.enterText("first"), DialogRobot.clickButton("Create")))
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.start()) {
			dialog.open();
		}
		assertFalse(fileInMissingDirectory.exists());
		assertNull(dialog.getCurrentDataEntry());
	}
}

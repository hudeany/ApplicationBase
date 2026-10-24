package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("static-method")
public class UnsecureDataStoreTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("UnsecureDataStoreTest");
	}

	@AfterEach
	public void tearDown() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	@Test
	public void testSaveAndLoad() throws Exception {
		final File storeFile = new File(tempDirectory, "test.pref");
		final UnsecureDataStore storeForSave = new UnsecureDataStore();
		storeForSave.addEntry("first", new TestDataEntry("a", 1));
		storeForSave.addEntry("second", new TestDataEntry("b", 2));
		storeForSave.addEntry("text", "Some text äöü");
		storeForSave.save(storeFile);

		assertTrue(storeFile.exists());
		// No temporary file is left over
		assertEquals(1, tempDirectory.listFiles().length);

		final UnsecureDataStore storeForLoad = new UnsecureDataStore();
		storeForLoad.load(storeFile);
		assertEquals(Set.of("first", "second"), storeForLoad.getEntryNames(TestDataEntry.class));
		assertEquals(new TestDataEntry("a", 1), storeForLoad.getEntry(TestDataEntry.class, "first"));
		assertEquals(new TestDataEntry("b", 2), storeForLoad.getEntry(TestDataEntry.class, "second"));
		assertEquals("Some text äöü", storeForLoad.getEntry(String.class, "text"));
		assertEquals("Some text äöü", storeForLoad.getEntry("text"));
	}

	@Test
	public void testSaveReplacesExistingFile() throws Exception {
		final File storeFile = new File(tempDirectory, "test.pref");
		TestHelper.writeTextFile(storeFile, "old content");

		final UnsecureDataStore store = new UnsecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 7));
		store.save(storeFile);

		final UnsecureDataStore loadedStore = new UnsecureDataStore();
		loadedStore.load(storeFile);
		assertEquals(new TestDataEntry("x", 7), loadedStore.getEntry(TestDataEntry.class, "entry"));
	}

	@Test
	public void testSaveEmptyStore() throws Exception {
		final File storeFile = new File(tempDirectory, "empty.pref");
		new UnsecureDataStore().save(storeFile);

		final UnsecureDataStore loadedStore = new UnsecureDataStore();
		loadedStore.load(storeFile);
		assertTrue(loadedStore.getEntryNames(TestDataEntry.class).isEmpty());
	}

	@Test
	public void testSaveWithoutFile() {
		final Exception exception = assertThrows(Exception.class, () -> new UnsecureDataStore().save(null));
		assertEquals("UnsecureDataStore file is undefined", exception.getMessage());
	}

	@Test
	public void testSaveIntoMissingDirectory() {
		final File storeFile = new File(new File(tempDirectory, "missing"), "test.pref");
		final UnsecureDataStore store = new UnsecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 1));
		assertThrows(Exception.class, () -> store.save(storeFile));
		assertFalse(storeFile.exists());
	}

	@Test
	public void testFailedSaveKeepsExistingFileAndRemovesTemporaryFile() throws Exception {
		final File storeFile = new File(tempDirectory, "test.pref");
		final UnsecureDataStore validStore = new UnsecureDataStore();
		validStore.addEntry("entry", new TestDataEntry("valid", 1));
		validStore.save(storeFile);
		final String originalContent = TestHelper.readTextFile(storeFile);

		final TestDataEntry.CyclicEntry cyclicEntry = new TestDataEntry.CyclicEntry();
		cyclicEntry.self = cyclicEntry;
		final UnsecureDataStore invalidStore = new UnsecureDataStore();
		invalidStore.addEntry("cyclic", cyclicEntry);
		assertThrows(Exception.class, () -> invalidStore.save(storeFile));

		assertEquals(originalContent, TestHelper.readTextFile(storeFile));
		assertEquals(1, tempDirectory.listFiles().length, "Temporary file must be removed");
	}

	@Test
	public void testLoadWithoutFile() {
		final Exception exception = assertThrows(Exception.class, () -> new UnsecureDataStore().load(null));
		assertEquals("UnsecureDataStore file is undefined", exception.getMessage());
	}

	@Test
	public void testLoadMissingFile() {
		final File missingFile = new File(tempDirectory, "missing.pref");
		final Exception exception = assertThrows(Exception.class, () -> new UnsecureDataStore().load(missingFile));
		assertTrue(exception.getMessage().startsWith("UnsecureDataStore file does not exist"));
	}

	@Test
	public void testLoadCorruptFile() throws Exception {
		final File storeFile = TestHelper.writeTextFile(new File(tempDirectory, "corrupt.pref"), "{ \"a\": 1 }");
		final Exception exception = assertThrows(Exception.class, () -> new UnsecureDataStore().load(storeFile));
		assertEquals("UnsecureDataStore is corrupt", exception.getMessage());
	}

	@Test
	public void testLoadFileWithUnexpectedData() throws Exception {
		final File storeFile = TestHelper.writeTextFile(new File(tempDirectory, "unexpected.pref"), "[ 1, 2 ]");
		final Exception exception = assertThrows(Exception.class, () -> new UnsecureDataStore().load(storeFile));
		assertTrue(exception.getMessage().startsWith("Unexpected JSON data in UnsecureDataStore"), exception.getMessage());
	}

	@Test
	public void testFailedLoadKeepsCurrentEntries() throws Exception {
		final File storeFile = TestHelper.writeTextFile(new File(tempDirectory, "corrupt.pref"), "[ \"x\" ]");
		final UnsecureDataStore store = new UnsecureDataStore();
		store.addEntry("entry", new TestDataEntry("keep", 1));
		assertThrows(Exception.class, () -> store.load(storeFile));
		assertEquals(new TestDataEntry("keep", 1), store.getEntry(TestDataEntry.class, "entry"));
	}

	@Test
	public void testGetEntryNames() {
		final UnsecureDataStore store = new UnsecureDataStore();
		assertTrue(store.getEntryNames(TestDataEntry.class).isEmpty());

		store.addEntry("a", new TestDataEntry("a", 1));
		final Set<String> entryNames = store.getEntryNames(TestDataEntry.class);
		assertEquals(Set.of("a"), entryNames);

		// The returned set is a live view
		store.addEntry("b", new TestDataEntry("b", 2));
		assertEquals(Set.of("a", "b"), entryNames);
	}

	@Test
	public void testGetEntry() {
		final UnsecureDataStore store = new UnsecureDataStore();
		assertNull(store.getEntry(TestDataEntry.class, "a"));
		assertNull(store.getEntry("a"));

		store.addEntry("a", new TestDataEntry("a", 1));
		assertEquals(new TestDataEntry("a", 1), store.getEntry(TestDataEntry.class, "a"));
		assertNull(store.getEntry(TestDataEntry.class, "unknown"));
		assertEquals(new TestDataEntry("a", 1), store.getEntry("a"));
		assertNull(store.getEntry("unknown"));
		assertNull(store.getEntry(String.class, "a"));
	}

	@Test
	public void testAddEntry() {
		final UnsecureDataStore store = new UnsecureDataStore();
		assertThrows(IllegalArgumentException.class, () -> store.addEntry("a", null));

		store.addEntry("a", new TestDataEntry("a", 1));
		store.addEntry("a", new TestDataEntry("a", 2));
		assertEquals(new TestDataEntry("a", 2), store.getEntry(TestDataEntry.class, "a"));
		assertEquals(1, store.getEntryNames(TestDataEntry.class).size());
	}

	@Test
	public void testRemoveEntry() {
		final UnsecureDataStore store = new UnsecureDataStore();
		// Removing from an unknown class does nothing
		store.removeEntry(TestDataEntry.class, "a");

		store.addEntry("a", new TestDataEntry("a", 1));
		store.addEntry("b", new TestDataEntry("b", 2));
		store.removeEntry(TestDataEntry.class, "a");
		store.removeEntry(TestDataEntry.class, "unknown");
		assertEquals(Set.of("b"), store.getEntryNames(TestDataEntry.class));
	}

	@Test
	public void testRemoveEntriesByEntryName() {
		final UnsecureDataStore store = new UnsecureDataStore();
		store.addEntry("a", new TestDataEntry("a", 1));
		store.addEntry("a", "text a");
		store.addEntry("b", "text b");

		store.removeEntriesByEntryName("a");

		assertTrue(store.getEntryNames(TestDataEntry.class).isEmpty());
		assertEquals(Set.of("b"), store.getEntryNames(String.class));
		assertNull(store.getEntry("a"));
	}

	@Test
	public void testRemoveEntriesByEntryClass() {
		final UnsecureDataStore store = new UnsecureDataStore();
		store.addEntry("a", new TestDataEntry("a", 1));
		store.addEntry("b", new TestDataEntry("b", 2));
		store.addEntry("c", "text");

		store.removeEntriesByEntryClass(TestDataEntry.class);

		assertTrue(store.getEntryNames(TestDataEntry.class).isEmpty());
		assertEquals("text", store.getEntry("c"));
	}
}

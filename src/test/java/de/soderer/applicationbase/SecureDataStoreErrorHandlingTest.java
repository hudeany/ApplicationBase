package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.utilities.WrongPasswordException;

@SuppressWarnings("static-method")
public class SecureDataStoreErrorHandlingTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("SecureDataStoreErrorHandlingTest");
	}

	@AfterEach
	public void tearDown() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	@Test
	public void testSaveAndLoadWithoutPassword() throws Exception {
		final File storeFile = new File(tempDirectory, "nopassword.secpref");
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 3));
		store.save(storeFile);

		// The file is encrypted, so the plain text is not contained
		assertTrue(!new String(Files.readAllBytes(storeFile.toPath()), "ISO-8859-1").contains("TestDataEntry"));

		final SecureDataStore loadedStore = new SecureDataStore();
		loadedStore.load(storeFile);
		assertEquals(new TestDataEntry("x", 3), loadedStore.getEntry(TestDataEntry.class, "entry"));

		// Null password is the same as the empty password
		final SecureDataStore loadedStoreWithEmptyPassword = new SecureDataStore();
		loadedStoreWithEmptyPassword.load(storeFile, new char[0]);
		assertEquals(new TestDataEntry("x", 3), loadedStoreWithEmptyPassword.getEntry(TestDataEntry.class, "entry"));
	}

	@Test
	public void testPasswordIsClearedAfterSaveAndLoad() throws Exception {
		final File storeFile = new File(tempDirectory, "password.secpref");
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 3));

		final char[] savePassword = "secret".toCharArray();
		store.save(storeFile, savePassword);
		assertArrayEquals(new char[6], savePassword);

		final char[] loadPassword = "secret".toCharArray();
		new SecureDataStore().load(storeFile, loadPassword);
		assertArrayEquals(new char[6], loadPassword);
	}

	@Test
	public void testPasswordIsClearedOnErrors() {
		final char[] savePassword = "secret".toCharArray();
		final Exception saveException = assertThrows(Exception.class, () -> new SecureDataStore().save(null, savePassword));
		assertEquals("SecureDataStore file is undefined", saveException.getMessage());
		assertArrayEquals(new char[6], savePassword);

		final char[] loadPassword = "secret".toCharArray();
		final Exception loadException = assertThrows(Exception.class, () -> new SecureDataStore().load(null, loadPassword));
		assertEquals("SecureDataStore file is undefined", loadException.getMessage());
		assertArrayEquals(new char[6], loadPassword);
	}

	@Test
	public void testSaveWithoutFile() {
		final Exception exception = assertThrows(Exception.class, () -> new SecureDataStore().save(null));
		assertEquals("SecureDataStore file is undefined", exception.getMessage());
	}

	@Test
	public void testLoadWithoutFile() {
		final Exception exception = assertThrows(Exception.class, () -> new SecureDataStore().load(null));
		assertEquals("SecureDataStore file is undefined", exception.getMessage());
	}

	@Test
	public void testLoadMissingFile() {
		final File missingFile = new File(tempDirectory, "missing.secpref");
		final Exception exception = assertThrows(Exception.class, () -> new SecureDataStore().load(missingFile, "pw".toCharArray()));
		assertTrue(exception.getMessage().startsWith("SecureDataStore file does not exist"));
	}

	@Test
	public void testLoadFileTooShortForSalt() throws Exception {
		final File storeFile = new File(tempDirectory, "short.secpref");
		Files.write(storeFile.toPath(), new byte[10]);
		final Exception exception = assertThrows(Exception.class, () -> new SecureDataStore().load(storeFile));
		assertEquals("Cannot read password salt prefix: Data is too short", exception.getMessage());
	}

	@Test
	public void testLoadFileTooShortForInitializationVector() throws Exception {
		final File storeFile = new File(tempDirectory, "short.secpref");
		Files.write(storeFile.toPath(), new byte[20]);
		final Exception exception = assertThrows(Exception.class, () -> new SecureDataStore().load(storeFile));
		assertEquals("Cannot read initialization vector prefix: Data is too short", exception.getMessage());
	}

	@Test
	public void testLoadManipulatedFile() throws Exception {
		final File storeFile = new File(tempDirectory, "manipulated.secpref");
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 3));
		store.save(storeFile, "pw".toCharArray());

		final byte[] data = Files.readAllBytes(storeFile.toPath());
		// Flip a bit of the encrypted data (after salt and IV), so the authentication tag does not match anymore
		data[data.length - 20] ^= 0x01;
		Files.write(storeFile.toPath(), data);

		assertThrows(WrongPasswordException.class, () -> new SecureDataStore().load(storeFile, "pw".toCharArray()));
	}

	@Test
	public void testWrongPasswordKeepsCurrentEntries() throws Exception {
		final File storeFile = new File(tempDirectory, "store.secpref");
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("stored", 1));
		store.save(storeFile, "right".toCharArray());

		final SecureDataStore otherStore = new SecureDataStore();
		otherStore.addEntry("entry", new TestDataEntry("current", 2));
		assertThrows(WrongPasswordException.class, () -> otherStore.load(storeFile, "wrong".toCharArray()));
		assertEquals(new TestDataEntry("current", 2), otherStore.getEntry(TestDataEntry.class, "entry"));
	}

	@Test
	public void testFailedSaveKeepsExistingFile() throws Exception {
		final File storeFile = new File(tempDirectory, "store.secpref");
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("stored", 1));
		store.save(storeFile, "pw".toCharArray());
		final byte[] originalData = Files.readAllBytes(storeFile.toPath());

		final TestDataEntry.CyclicEntry cyclicEntry = new TestDataEntry.CyclicEntry();
		cyclicEntry.self = cyclicEntry;
		final SecureDataStore invalidStore = new SecureDataStore();
		invalidStore.addEntry("cyclic", cyclicEntry);
		assertThrows(Exception.class, () -> invalidStore.save(storeFile, "pw".toCharArray()));

		assertArrayEquals(originalData, Files.readAllBytes(storeFile.toPath()));
		assertEquals(1, tempDirectory.listFiles().length, "Temporary file must be removed");
	}

	@Test
	public void testClear() {
		SecureDataStore.clear(null);

		final byte[] data = new byte[] { 1, 2, 3 };
		SecureDataStore.clear(data);
		assertArrayEquals(new byte[3], data);
	}

	@Test
	public void testSaveEncryptsWithRandomSalt() throws Exception {
		final SecureDataStore store = new SecureDataStore();
		store.addEntry("entry", new TestDataEntry("x", 1));
		final File storeFile1 = new File(tempDirectory, "store1.secpref");
		final File storeFile2 = new File(tempDirectory, "store2.secpref");
		store.save(storeFile1, "pw".toCharArray());
		store.save(storeFile2, "pw".toCharArray());

		final byte[] data1 = Files.readAllBytes(storeFile1.toPath());
		final byte[] data2 = Files.readAllBytes(storeFile2.toPath());
		assertTrue(!Arrays.equals(Arrays.copyOf(data1, 28), Arrays.copyOf(data2, 28)), "Salt and IV must be random");

		final SecureDataStore loadedStore = new SecureDataStore();
		loadedStore.load(storeFile2, "pw".toCharArray());
		assertNull(loadedStore.getEntry(TestDataEntry.class, "unknown"));
		assertEquals(new TestDataEntry("x", 1), loadedStore.getEntry("entry"));
	}
}

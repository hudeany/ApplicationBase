package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.swing.CredentialsDialog;
import de.soderer.swing.QuestionDialog;

/**
 * GUI tests of {@link SecureDataGuiApplication}. The password dialogs are operated by a {@link DialogRobot}.
 * Skipped in headless environments.
 */
@SuppressWarnings("static-method")
public class SecureDataGuiApplicationTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;
	private File keystoreFile;
	private SecureDataGuiApplication application;

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(DialogRobot.isGuiAvailable(), DialogRobot.GUI_TESTS_DISABLED_MESSAGE);
		tempDirectory = TestHelper.createTempDirectory("SecureDataGuiApplicationTest");
		keystoreFile = new File(tempDirectory, "keystore.secpref");
		application = new SecureDataGuiApplication(keystoreFile);
	}

	@AfterEach
	public void tearDown() {
		if (application != null) {
			application.dispose();
		}
		TestHelper.deleteRecursively(tempDirectory);
	}

	private void createKeystore(final String password, final String entryName, final Object value) throws Exception {
		final SecureDataStore store = new SecureDataStore();
		store.addEntry(entryName, value);
		store.save(keystoreFile, password == null ? null : password.toCharArray());
	}

	private TestDataEntry loadEntry(final String password, final String entryName) throws Exception {
		final SecureDataStore store = new SecureDataStore();
		store.load(keystoreFile, password == null ? null : password.toCharArray());
		return store.getEntry(TestDataEntry.class, entryName);
	}

	@Test
	public void testWithoutKeystore() throws Exception {
		final SecureDataGuiApplication applicationWithoutKeystore = new SecureDataGuiApplication();
		try {
			assertFalse(applicationWithoutKeystore.keystoreExists());
			assertNull(applicationWithoutKeystore.getKeystoreValue(TestDataEntry.class, "entry"));
			assertFalse(applicationWithoutKeystore.saveKeyStoreValue("entry", new TestDataEntry("x", 1)));
		} finally {
			applicationWithoutKeystore.dispose();
		}
	}

	@Test
	public void testMissingKeystoreFile() {
		assertFalse(application.keystoreExists());
		assertNull(application.getKeystoreValue(TestDataEntry.class, "entry"));
	}

	@Test
	public void testSaveValueInNewKeystore() throws Exception {
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton()))
				.start()) {
			assertTrue(application.saveKeyStoreValue("entry", new TestDataEntry("x", 1)));
		}
		assertTrue(application.keystoreExists());
		assertEquals(new TestDataEntry("x", 1), loadEntry("secret", "entry"));

		// The password is remembered, so no dialog is shown anymore
		try (DialogRobot robot = DialogRobot.steps().start()) {
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
			assertTrue(application.saveKeyStoreValue("second", new TestDataEntry("y", 2)));
		}
		assertEquals(new TestDataEntry("y", 2), loadEntry("secret", "second"));
	}

	@Test
	public void testSaveValueInNewKeystoreInNewDirectory() throws Exception {
		final File nestedKeystoreFile = new File(new File(tempDirectory, "sub" + File.separator + "dir"), "keystore.secpref");
		final SecureDataGuiApplication nestedApplication = new SecureDataGuiApplication(nestedKeystoreFile);
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.clickDefaultButton()))
				.start()) {
			assertTrue(nestedApplication.saveKeyStoreValue("entry", new TestDataEntry("x", 1)));
		} finally {
			nestedApplication.dispose();
		}
		assertTrue(nestedKeystoreFile.exists());
	}

	@Test
	public void testSaveValueInNewKeystoreCanceled() throws Exception {
		try (DialogRobot robot = DialogRobot.steps().on(CredentialsDialog.class, DialogRobot.dispose()).start()) {
			assertFalse(application.saveKeyStoreValue("entry", new TestDataEntry("x", 1)));
		}
		assertFalse(keystoreFile.exists());
	}

	@Test
	public void testSaveValueWithUncreatableDirectory() throws Exception {
		final File blockingFile = TestHelper.writeTextFile(new File(tempDirectory, "blocker"), "x");
		final SecureDataGuiApplication blockedApplication = new SecureDataGuiApplication(new File(new File(blockingFile, "sub"), "keystore.secpref"));
		try {
			assertThrows(IOException.class, () -> blockedApplication.saveKeyStoreValue("entry", new TestDataEntry("x", 1)));
		} finally {
			blockedApplication.dispose();
		}
	}

	@Test
	public void testKeystoreWithEmptyPassword() throws Exception {
		createKeystore(null, "entry", new TestDataEntry("x", 1));

		// The empty password is tried first, so no dialog is needed
		try (DialogRobot robot = DialogRobot.steps().start()) {
			assertTrue(application.keystoreExists());
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
			assertNull(application.getKeystoreValue(TestDataEntry.class, "unknown"));
			assertTrue(application.saveKeyStoreValue("second", new TestDataEntry("y", 2)));
		}
		// Saved with the password it was opened with
		assertEquals(new TestDataEntry("y", 2), loadEntry(null, "second"));
	}

	@Test
	public void testKeystoreWithPassword() throws Exception {
		createKeystore("secret", "entry", new TestDataEntry("x", 1));

		// A wrong password is asked again, the right one is remembered
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("wrong"), DialogRobot.clickDefaultButton()))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton()))
				.start()) {
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
		}

		try (DialogRobot robot = DialogRobot.steps().start()) {
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
		}
	}

	@Test
	public void testKeystoreWithPasswordNotRemembered() throws Exception {
		createKeystore("secret", "entry", new TestDataEntry("x", 1));

		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.setCheckboxes(false), DialogRobot.clickDefaultButton()))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.clickDefaultButton()))
				.start()) {
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
			// Asked again, because the password was not remembered
			assertTrue(application.saveKeyStoreValue("second", new TestDataEntry("y", 2)));
		}
		// Saved with the password it was opened with
		assertEquals(new TestDataEntry("y", 2), loadEntry("secret", "second"));
	}

	@Test
	public void testKeystorePasswordDialogCanceled() throws Exception {
		createKeystore("secret", "entry", new TestDataEntry("x", 1));
		final byte[] originalData = Files.readAllBytes(keystoreFile.toPath());

		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.dispose())
				.on(CredentialsDialog.class, DialogRobot.dispose())
				.start()) {
			assertNull(application.getKeystoreValue(TestDataEntry.class, "entry"));
			// The existing keystore is not overwritten by a new one
			assertFalse(application.saveKeyStoreValue("second", new TestDataEntry("y", 2)));
		}
		assertArrayEquals(originalData, Files.readAllBytes(keystoreFile.toPath()));
	}

	@Test
	public void testRememberedPasswordOutdated() throws Exception {
		createKeystore("secret", "entry", new TestDataEntry("x", 1));
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton()))
				.start()) {
			assertEquals(new TestDataEntry("x", 1), application.getKeystoreValue(TestDataEntry.class, "entry"));
		}

		// Keystore changed by someone else with another password
		createKeystore("other", "entry", new TestDataEntry("z", 9));
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("other"), DialogRobot.clickDefaultButton()))
				.start()) {
			assertEquals(new TestDataEntry("z", 9), application.getKeystoreValue(TestDataEntry.class, "entry"));
		}
	}

	@Test
	public void testDamagedKeystore() throws Exception {
		Files.write(keystoreFile.toPath(), new byte[] { 1, 2, 3 });
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			assertNull(application.getKeystoreValue(TestDataEntry.class, "entry"));
		}
	}

	@Test
	public void testDamagedKeystoreAfterPasswordEntry() throws Exception {
		createKeystore("secret", "entry", new TestDataEntry("x", 1));
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, window -> {
					// The file gets damaged while the user enters the password
					try {
						Files.write(keystoreFile.toPath(), new byte[] { 1, 2, 3 });
					} catch (final IOException e) {
						throw new RuntimeException(e);
					}
					DialogRobot.all(DialogRobot.enterPassword("secret"), DialogRobot.clickDefaultButton()).accept(window);
				})
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.start()) {
			assertNull(application.getKeystoreValue(TestDataEntry.class, "entry"));
		}
	}
}

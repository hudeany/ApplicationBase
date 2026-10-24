package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.swing.CredentialsDialog;
import de.soderer.swing.ProgressDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Version;

/**
 * GUI tests of {@link UpdateableGuiApplication}. The dialogs are operated by a {@link DialogRobot}. Skipped in
 * headless environments.
 */
@SuppressWarnings("static-method")
public class UpdateableGuiApplicationTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;
	private File keystoreFile;
	private TestApplication application;

	/**
	 * Application which supports the daily update check
	 */
	private static class TestApplication extends UpdateableGuiApplication {
		private static final long serialVersionUID = 1L;

		Boolean dailyUpdateCheck = null;
		final transient List<Boolean> dailyUpdateCheckStatusList = new ArrayList<>();

		TestApplication(final File keystoreFile) throws Exception {
			super("TestApp", new Version(1, 0, 0), keystoreFile);
		}

		@Override
		protected Boolean isDailyUpdateCheckActivated() {
			return dailyUpdateCheck;
		}

		@Override
		protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
			dailyUpdateCheckStatusList.add(checkboxStatus);
		}
	}

	@BeforeEach
	public void setUp() throws Exception {
		assumeTrue(DialogRobot.isGuiAvailable(), DialogRobot.GUI_TESTS_DISABLED_MESSAGE);
		tempDirectory = TestHelper.createTempDirectory("UpdateableGuiApplicationTest");
		keystoreFile = new File(tempDirectory, "keystore.secpref");
		application = new TestApplication(keystoreFile);
	}

	@AfterEach
	public void tearDown() {
		if (application != null) {
			application.dispose();
		}
		TestHelper.deleteRecursively(tempDirectory);
	}

	private void setProgressDialog() throws Exception {
		TestHelper.setField(application, "updateProgressDialog", new ProgressDialog<>(application, "Progress", null));
	}

	@Test
	public void testConstructorValidation() {
		assertThrows(Exception.class, () -> new UpdateableGuiApplication(" ", new Version(1, 0, 0), null));
		assertThrows(Exception.class, () -> new UpdateableGuiApplication("TestApp", null, null));
	}

	@Test
	public void testDefaultDailyUpdateCheck() throws Exception {
		final UpdateableGuiApplication plainApplication = new UpdateableGuiApplication("TestApp", new Version(1, 0, 0), null);
		try {
			assertNull(plainApplication.isDailyUpdateCheckActivated());
			plainApplication.setDailyUpdateCheckStatus(true);
		} finally {
			plainApplication.dispose();
		}
	}

	@Test
	public void testAskForUpdateWithoutNewVersion() throws Exception {
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			assertFalse(application.askForUpdate(null));
		}
		assertEquals(List.of(Boolean.FALSE), application.dailyUpdateCheckStatusList);
	}

	@Test
	public void testAskForUpdateWithoutNewVersionWithDailyUpdateCheck() throws Exception {
		application.dailyUpdateCheck = Boolean.FALSE;
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.all(DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton())).start()) {
			assertFalse(application.askForUpdate(null));
		}
		assertEquals(List.of(Boolean.TRUE), application.dailyUpdateCheckStatusList);
	}

	@Test
	public void testAskForUpdateAccepted() throws Exception {
		application.dailyUpdateCheck = Boolean.TRUE;
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickButton(LangResources.get("yes"))).start()) {
			assertTrue(application.askForUpdate(new Version(2, 0, 0)));
		}
		assertEquals(List.of(Boolean.TRUE), application.dailyUpdateCheckStatusList);
	}

	@Test
	public void testAskForUpdateDeclined() throws Exception {
		try (DialogRobot robot = DialogRobot.steps()
				.on(QuestionDialog.class, DialogRobot.clickButton(LangResources.get("no")))
				.on(QuestionDialog.class, DialogRobot.dispose())
				.start()) {
			assertFalse(application.askForUpdate(new Version(2, 0, 0)));
			// Closed without answer
			assertFalse(application.askForUpdate(new Version(2, 0, 0)));
		}
	}

	@Test
	public void testShowUpdateError() throws Exception {
		setProgressDialog();
		try (DialogRobot robot = DialogRobot.steps().on(QuestionDialog.class, DialogRobot.clickDefaultButton()).start()) {
			application.showUpdateError("Update failed");
		}
		assertNull(TestHelper.getField(application, "updateProgressDialog"));
	}

	@Test
	public void testShowUpdateDone() throws Exception {
		try (DialogRobot robot = DialogRobot.steps()
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.on(QuestionDialog.class, DialogRobot.clickDefaultButton())
				.start()) {
			application.showUpdateDone(null, null, 0, null);

			setProgressDialog();
			application.showUpdateDone(LocalDateTime.now(), LocalDateTime.now(), 10, "B");
		}
		assertNull(TestHelper.getField(application, "updateProgressDialog"));
	}

	@Test
	public void testShowUpdateProgressAndDownloadEnd() throws Exception {
		// Without progress dialog nothing happens
		application.showUpdateProgress(LocalDateTime.now(), 100, 10, "B");
		application.showUpdateDownloadEnd(LocalDateTime.now(), LocalDateTime.now(), 100, "B");

		setProgressDialog();
		application.showUpdateProgress(LocalDateTime.now(), 100, 10, "B");
		application.showUpdateDownloadEnd(LocalDateTime.now(), LocalDateTime.now(), 100, "B");
		assertNull(TestHelper.getField(application, "updateProgressDialog"));
	}

	@Test
	public void testDownloadWithProgressDialog() throws Exception {
		final byte[] content = "update".getBytes(StandardCharsets.UTF_8);
		final File localUpdateFile = new File(tempDirectory, "update.jar");
		Files.write(localUpdateFile.toPath(), content);
		final File downloadTempFile = new File(tempDirectory, "temp_TestApp.jar");

		// The progress dialog starts the copy worker when it is shown and closes itself when the worker is done
		try (DialogRobot robot = DialogRobot.steps().on(ProgressDialog.class, window -> {
			// Nothing to do, the dialog closes itself
		}).start()) {
			final boolean result = (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "getNewApplicationVersionFile",
					new Class<?>[] { ApplicationUpdateParent.class, String.class, char[].class, String.class, ProxyConfiguration.class, File.class },
					application, null, null, localUpdateFile.getAbsolutePath(), null, downloadTempFile);
			assertTrue(result);
		}
		assertArrayEquals(content, Files.readAllBytes(downloadTempFile.toPath()));
	}

	@Test
	public void testAskForRetryWhileApplicationInUse() throws Exception {
		setProgressDialog();
		try (DialogRobot robot = DialogRobot.steps()
				.on(QuestionDialog.class, DialogRobot.clickButton(LangResources.get("retry")))
				.on(QuestionDialog.class, DialogRobot.clickButton(LangResources.get("cancel")))
				.on(QuestionDialog.class, DialogRobot.dispose())
				.start()) {
			assertTrue(application.askForRetryWhileApplicationInUse("In use"));
			assertNull(TestHelper.getField(application, "updateProgressDialog"));
			assertFalse(application.askForRetryWhileApplicationInUse("In use"));
			assertFalse(application.askForRetryWhileApplicationInUse("In use"));
		}
	}

	@Test
	public void testAcquireCredentials() throws Exception {
		final UpdateableGuiApplication applicationWithoutKeystore = new UpdateableGuiApplication("TestApp", new Version(1, 0, 0), null);
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterText("alice"), DialogRobot.enterPassword("secret"), DialogRobot.clickDefaultButton()))
				.on(CredentialsDialog.class, DialogRobot.dispose())
				.start()) {
			final Credentials credentials = applicationWithoutKeystore.acquireCredentials("Enter credentials", true, true, true);
			assertEquals("alice", credentials.getUsername());
			assertArrayEquals("secret".toCharArray(), credentials.getPassword());

			assertNull(applicationWithoutKeystore.acquireCredentials("Enter credentials", true, true, false));
		} finally {
			applicationWithoutKeystore.dispose();
		}
	}

	@Test
	public void testAcquireCredentialsRememberedInKeystore() throws Exception {
		// Credentials dialog with "remember" checked, then the new keystore's password dialog
		try (DialogRobot robot = DialogRobot.steps()
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterText("alice"), DialogRobot.enterPassword("secret"), DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton()))
				.on(CredentialsDialog.class, DialogRobot.all(DialogRobot.enterPassword("keystorePassword"), DialogRobot.setCheckboxes(true), DialogRobot.clickDefaultButton()))
				.start()) {
			final Credentials credentials = application.acquireCredentials("Enter credentials", true, true, true);
			assertEquals("alice", credentials.getUsername());
		}
		assertTrue(keystoreFile.exists());

		// Next first request takes the credentials from the keystore (the keystore password is remembered)
		try (DialogRobot robot = DialogRobot.steps().start()) {
			final Credentials credentials = application.acquireCredentials("Enter credentials", true, true, true);
			assertEquals("alice", credentials.getUsername());
			assertArrayEquals("secret".toCharArray(), credentials.getPassword());
		}

		// A repeated request (e.g. after wrong credentials) asks the user again
		try (DialogRobot robot = DialogRobot.steps().on(CredentialsDialog.class, DialogRobot.dispose()).start()) {
			assertNull(application.acquireCredentials("Enter credentials", true, true, false));
		}
	}
}

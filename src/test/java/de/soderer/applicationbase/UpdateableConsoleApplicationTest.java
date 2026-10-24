package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.utilities.LangResources;
import de.soderer.utilities.Version;

/**
 * Tests of {@link UpdateableConsoleApplication}. Interactive input is not possible in unit tests, so the tests
 * which need the absence of a console are skipped, if the test runner provides one.
 */
@SuppressWarnings("static-method")
public class UpdateableConsoleApplicationTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private PrintStream originalOut;
	private PrintStream originalErr;
	private ByteArrayOutputStream capturedOut;
	private ByteArrayOutputStream capturedErr;
	private PrintStream capturingOut;
	private PrintStream capturingErr;

	@BeforeEach
	public void setUp() {
		originalOut = System.out;
		originalErr = System.err;
		capturedOut = new ByteArrayOutputStream();
		capturedErr = new ByteArrayOutputStream();
		capturingOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
		capturingErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8);
		System.setOut(capturingOut);
		System.setErr(capturingErr);
	}

	@AfterEach
	public void tearDown() {
		System.setOut(originalOut);
		System.setErr(originalErr);
		capturingOut.close();
		capturingErr.close();
	}

	private String getOut() {
		return capturedOut.toString(StandardCharsets.UTF_8);
	}

	private String getErr() {
		return capturedErr.toString(StandardCharsets.UTF_8);
	}

	private static UpdateableConsoleApplication createApplication() {
		return new UpdateableConsoleApplication("TestApp", new Version(1, 2, 3));
	}

	@Test
	public void testConstructorValidation() {
		assertThrows(IllegalArgumentException.class, () -> new UpdateableConsoleApplication(null, new Version(1, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> new UpdateableConsoleApplication(" ", new Version(1, 0, 0)));
		assertThrows(IllegalArgumentException.class, () -> new UpdateableConsoleApplication("TestApp", null));
	}

	@Test
	public void testAskForUpdateWithoutNewVersion() throws Exception {
		assertFalse(createApplication().askForUpdate(null));
		assertTrue(getOut().contains(LangResources.get("noNewerVersion", "TestApp", "1.2.3")), getOut());
	}

	@Test
	public void testAskForUpdateWithoutConsole() {
		assumeTrue(System.console() == null, "Test needs a runner without console");
		final Exception exception = assertThrows(Exception.class, () -> createApplication().askForUpdate(new Version(2, 0, 0)));
		assertEquals("Couldn't get Console instance", exception.getMessage());
	}

	@Test
	public void testAcquireCredentialsWithoutConsole() {
		assumeTrue(System.console() == null, "Test needs a runner without console");
		final Exception exception = assertThrows(Exception.class, () -> createApplication().acquireCredentials("text", true, true, true));
		assertEquals("Couldn't get Console instance", exception.getMessage());
	}

	@Test
	public void testShowUpdateError() {
		assumeTrue(System.console() == null, "Test needs a runner without console (would wait for enter)");
		createApplication().showUpdateError("Some update error");
		assertTrue(getErr().contains("Some update error"), getErr());
	}

	@Test
	public void testShowUpdateProgress() {
		createApplication().showUpdateProgress(LocalDateTime.now().minusSeconds(5), 1000, 500, "B");
		assertFalse(getOut().isEmpty());
	}

	@Test
	public void testShowUpdateDone() {
		createApplication().showUpdateDone(LocalDateTime.now(), LocalDateTime.now(), 1000, "B");
		assertTrue(getOut().contains(LangResources.get("updateDone")), getOut());
	}

	@Test
	public void testShowUpdateDownloadStartAndEnd() {
		final UpdateableConsoleApplication application = createApplication();
		application.showUpdateDownloadStart(null);
		application.showUpdateDownloadEnd(LocalDateTime.now(), LocalDateTime.now(), 1000, "B");
		assertTrue(getOut().isEmpty());
	}

	@Test
	public void testAskForRetryWhileApplicationInUseWithoutConsole() {
		assumeTrue(System.console() == null, "Test needs a runner without console");
		assertFalse(createApplication().askForRetryWhileApplicationInUse("Application is in use"));
		assertTrue(getErr().contains("Application is in use"), getErr());
	}
}

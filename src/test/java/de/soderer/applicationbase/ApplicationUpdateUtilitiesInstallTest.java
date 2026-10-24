package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.utilities.LangResources;
import de.soderer.utilities.SystemUtilities;
import de.soderer.utilities.Version;

/**
 * Tests of the update installation of {@link ApplicationUpdateUtilities}.
 *
 * <p>
 * A successful installation exits the JVM, so every test is designed to fail before the updater would be started:
 * either no trusted CA certificate is given (and unsafe updates are not allowed), or a CA certificate resource is
 * given that does not exist, so the downloaded file never passes the signature check.
 * </p>
 */
@SuppressWarnings("static-method")
public class ApplicationUpdateUtilitiesInstallTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final String APPLICATION_NAME = "TestApp";
	/** Not existing resource: the signature check of a downloaded update always fails */
	private static final String MISSING_CA_CERTIFICATE = "missing-test-ca-certificate.pem";

	private File tempDirectory;
	private File applicationJar;
	private File updateJar;
	private String originalRunningJarProperty;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("ApplicationUpdateUtilitiesInstallTest").getCanonicalFile();
		applicationJar = TestHelper.createJar(new File(tempDirectory, APPLICATION_NAME + ".jar"), true, Map.of("version.txt", "1.0.0"));
		updateJar = TestHelper.createJar(new File(tempDirectory, "update.jar"), true, Map.of("version.txt", "2.0.0"));
		originalRunningJarProperty = System.getProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);
		System.clearProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);
	}

	@AfterEach
	public void tearDown() {
		if (originalRunningJarProperty == null) {
			System.clearProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR);
		} else {
			System.setProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR, originalRunningJarProperty);
		}
		ApplicationUpdateUtilities.DEBUGGING_UPDATER = false;
		ApplicationUpdateUtilities.DEBUGGING_RESTARTER = false;
		TestHelper.deleteRecursively(tempDirectory);
	}

	private void setRunningJar(final File jarFile) {
		System.setProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR, jarFile.getAbsolutePath());
	}

	private File writeVersionIndex(final String version, final String downloadUrl) throws Exception {
		return TestHelper.writeTextFile(new File(tempDirectory, "versions.json"), "{ \"" + APPLICATION_NAME + "\": { \"version\": \"" + version + "\", \"downloadUrl\": \"" + downloadUrl.replace("\\", "\\\\") + "\" } }");
	}

	private static String toFileUrl(final File file) {
		return "file://" + file.toURI().getRawPath();
	}

	private File getDownloadTempFile() {
		return new File(tempDirectory, "temp_" + applicationJar.getName());
	}

	private static boolean noRunningJarEnvironmentVariable() {
		return System.getenv(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR) == null;
	}

	/**
	 * Runs code with captured System.err output.
	 */
	private static String captureStandardError(final TestHelperRunnable runnable) throws Exception {
		final PrintStream originalError = System.err;
		final ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
		try (PrintStream capturingStream = new PrintStream(errorOutput, true, StandardCharsets.UTF_8)) {
			System.setErr(capturingStream);
			runnable.run();
		} finally {
			System.setErr(originalError);
		}
		return errorOutput.toString(StandardCharsets.UTF_8);
	}

	@FunctionalInterface
	private interface TestHelperRunnable {
		void run() throws Exception;
	}

	// ===== executeUpdate =====

	@Test
	public void testExecuteUpdateWithoutIndexLocation() throws Exception {
		assertEquals("Invalid version index location", assertThrows(Exception.class,
				() -> ApplicationUpdateUtilities.executeUpdate(null, "", null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false)).getMessage());

		final TestUpdateParent updateParent = new TestUpdateParent();
		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, null, null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(1, updateParent.errors.size());
		assertEquals(LangResources.get("updateErrorVersionCheck", "Invalid version index location"), updateParent.errors.get(0));
	}

	@Test
	public void testExecuteUpdateWithInvalidIndex() throws Exception {
		final File indexFile = TestHelper.writeTextFile(new File(tempDirectory, "versions.json"), "[]");
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(1, updateParent.errors.size());
	}

	@Test
	public void testExecuteUpdateWithoutNewerVersion() throws Exception {
		final File indexFile = writeVersionIndex("1.0.0", updateJar.getAbsolutePath());
		assertTrue(ApplicationUpdateUtilities.executeUpdate(null, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));

		// The update parent is informed that there is no newer version
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertTrue(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(Arrays.asList((Version) null), updateParent.askedVersions);
		assertTrue(updateParent.errors.isEmpty());

		// A confirmation without newer version installs nothing
		final TestUpdateParent confirmingUpdateParent = new TestUpdateParent();
		confirmingUpdateParent.updateAnswer = true;
		assertTrue(ApplicationUpdateUtilities.executeUpdate(confirmingUpdateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertTrue(confirmingUpdateParent.errors.isEmpty());
		assertEquals(0, confirmingUpdateParent.downloadStartCount);
	}

	@Test
	public void testExecuteUpdateDeclinedByUser() throws Exception {
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = false;
		assertTrue(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(Arrays.asList(new Version(2, 0, 0)), updateParent.askedVersions);
		assertEquals(0, updateParent.downloadStartCount);
	}

	@Test
	public void testExecuteUpdateQuestionFails() throws Exception {
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateException = new Exception("No console");
		assertTrue(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(Arrays.asList("Update error:\nNo console"), updateParent.errors);
	}

	@Test
	public void testExecuteUpdateWithoutTrustedCertificate() throws Exception {
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;
		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, false));
		assertEquals(1, updateParent.errors.size());
		assertTrue(updateParent.errors.get(0).contains("Trusted CA certificate file name is missing"), updateParent.getErrorsText());
	}

	@Test
	public void testExecuteUpdateWithoutParentFails() throws Exception {
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		// A given username skips the confirmation
		final String errorOutput = captureStandardError(() -> {
			final Exception exception = assertThrows(Exception.class,
					() -> ApplicationUpdateUtilities.executeUpdate(null, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, "user", null, null, false, false));
			assertEquals("Update to version 2.0.0 was not successful, see error output", exception.getMessage());
		});
		assertTrue(errorOutput.contains("Trusted CA certificate file name is missing"), errorOutput);
	}

	@Test
	public void testExecuteUpdateRefusesUnsafeHttpUpdate() throws Exception {
		final File indexFile = writeVersionIndex("2.0.0", "http://example.com/TestApp.jar");
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;
		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), null, null, null, null, false, true));
		assertTrue(updateParent.errors.get(0).contains("Updates without signature check are only allowed via https or from local files"), updateParent.getErrorsText());
	}

	@Test
	public void testExecuteUpdateWithoutRunningJar() throws Exception {
		assumeTrue(noRunningJarEnvironmentVariable());
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;
		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), MISSING_CA_CERTIFICATE, null, null, null, false, false));
		assertTrue(updateParent.errors.get(0).contains("Current running jar file was not found"), updateParent.getErrorsText());
	}

	@Test
	public void testExecuteUpdateDownloadsAndRejectsUnverifiedFile() throws Exception {
		setRunningJar(applicationJar);
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;

		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), MISSING_CA_CERTIFICATE, null, null, Arrays.asList("arg"), true, false));

		assertEquals(1, updateParent.downloadStartCount);
		assertEquals(1, updateParent.errors.size());
		assertTrue(updateParent.errors.get(0).contains("Trusted CA certificate is missing"), updateParent.getErrorsText());
		assertEquals(0, updateParent.doneCount);
		// The rejected download is removed and the application jar is unchanged
		assertFalse(getDownloadTempFile().exists());
		assertEquals("1.0.0", readJarEntry(applicationJar, "version.txt"));
	}

	@Test
	public void testExecuteUpdateDownloadCanceled() throws Exception {
		setRunningJar(applicationJar);
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;
		updateParent.downloadStartAction = worker -> worker.cancel();

		assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), MISSING_CA_CERTIFICATE, null, null, null, false, false));
		assertEquals(Arrays.asList(LangResources.get("canceledByUser")), updateParent.errors);
		assertFalse(getDownloadTempFile().exists());
	}

	@Test
	public void testExecuteUpdateWhileApplicationInUse() throws Exception {
		assumeTrue(ForeignJarProcess.isSupported(), "Foreign processes are only started on POSIX systems");
		setRunningJar(applicationJar);
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		try (ForeignJarProcess foreignProcess = new ForeignJarProcess(applicationJar.getAbsolutePath(), null)) {
			final TestUpdateParent updateParent = new TestUpdateParent();
			updateParent.updateAnswer = true;
			// Retry once, then cancel
			updateParent.retryAnswers.add(Boolean.TRUE);
			updateParent.retryAnswers.add(Boolean.FALSE);

			assertFalse(ApplicationUpdateUtilities.executeUpdate(updateParent, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), MISSING_CA_CERTIFICATE, null, null, null, false, false));

			assertEquals(2, updateParent.retryMessages.size());
			assertTrue(updateParent.retryMessages.get(0).contains(Long.toString(foreignProcess.getProcessId())), updateParent.retryMessages.get(0));
			// Canceled by the user: no download and no error message
			assertEquals(0, updateParent.downloadStartCount);
			assertTrue(updateParent.errors.isEmpty(), updateParent.getErrorsText());
		}
	}

	@Test
	public void testExecuteUpdateWithoutParentWhileApplicationInUse() throws Exception {
		assumeTrue(ForeignJarProcess.isSupported(), "Foreign processes are only started on POSIX systems");
		setRunningJar(applicationJar);
		final File indexFile = writeVersionIndex("2.0.0", updateJar.getAbsolutePath());
		try (ForeignJarProcess foreignProcess = new ForeignJarProcess(applicationJar.getAbsolutePath(), null)) {
			final String errorOutput = captureStandardError(() -> assertThrows(Exception.class,
					() -> ApplicationUpdateUtilities.executeUpdate(null, toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0), MISSING_CA_CERTIFICATE, "user", null, null, false, false)));
			assertTrue(errorOutput.contains(Long.toString(foreignProcess.getProcessId())), errorOutput);
		}
	}

	// ===== updateNonInteractiveApplication =====

	@Test
	public void testUpdateNonInteractiveApplicationWithoutLocation() {
		final Exception exception = assertThrows(Exception.class,
				() -> ApplicationUpdateUtilities.updateNonInteractiveApplication(TestUpdateParent.class, " ", null, MISSING_CA_CERTIFICATE, null, null, null, null, false));
		assertTrue(exception.getMessage().contains("Mandatory updateFileLocation is empty"), exception.getMessage());
	}

	@Test
	public void testUpdateNonInteractiveApplicationWithoutTrustedCertificate() {
		final Exception exception = assertThrows(Exception.class,
				() -> ApplicationUpdateUtilities.updateNonInteractiveApplication(TestUpdateParent.class, updateJar.getAbsolutePath(), null, null, null, null, null, null, false));
		assertTrue(exception.getMessage().contains("Trusted CA certificate file name is missing"), exception.getMessage());
	}

	@Test
	public void testUpdateNonInteractiveApplicationRejectsUnverifiedFile() throws Exception {
		setRunningJar(applicationJar);
		final Exception exception = assertThrows(Exception.class,
				() -> ApplicationUpdateUtilities.updateNonInteractiveApplication(TestUpdateParent.class, updateJar.getAbsolutePath(), null, MISSING_CA_CERTIFICATE, null, null, Arrays.asList("-Xmx1g"), Arrays.asList("arg"), true));
		assertTrue(exception.getMessage().contains("Trusted CA certificate is missing"), exception.getMessage());
		assertFalse(getDownloadTempFile().exists());
	}

	@Test
	public void testUpdateNonInteractiveApplicationWhileApplicationInUse() throws Exception {
		assumeTrue(ForeignJarProcess.isSupported(), "Foreign processes are only started on POSIX systems");
		setRunningJar(applicationJar);
		try (ForeignJarProcess foreignProcess = new ForeignJarProcess(applicationJar.getAbsolutePath(), null)) {
			final Exception exception = assertThrows(Exception.class,
					() -> ApplicationUpdateUtilities.updateNonInteractiveApplication(TestUpdateParent.class, updateJar.getAbsolutePath(), null, MISSING_CA_CERTIFICATE, null, null, null, null, false));
			assertTrue(exception.getMessage().contains(Long.toString(foreignProcess.getProcessId())), exception.getMessage());
			assertFalse(getDownloadTempFile().exists());
		}
	}

	// ===== Running jar and class loader =====

	private static String findRunningJarFilePath(final Class<?> applicationClass) throws Exception {
		return (String) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "findRunningJarFilePath", new Class<?>[] { Class.class }, applicationClass);
	}

	@Test
	public void testFindRunningJarFilePathFromSystemProperty() throws Exception {
		setRunningJar(applicationJar);
		assertEquals(applicationJar.getAbsolutePath(), findRunningJarFilePath(null));

		System.setProperty(SystemUtilities.SYSTEM_PARAMETER_NAME_CURRENT_RUNNING_JAR, new File(tempDirectory, "missing.jar").getAbsolutePath());
		assertEquals("Current running jar file was not found", assertThrows(Exception.class, () -> findRunningJarFilePath(null)).getMessage());
	}

	@Test
	public void testFindRunningJarFilePathFromClass() throws Exception {
		assumeTrue(noRunningJarEnvironmentVariable());
		assertEquals("Current running jar file was not found", assertThrows(Exception.class, () -> findRunningJarFilePath(null)).getMessage());
		// Class loaded from a directory, not from a jar
		assertEquals("Current running jar file was not found", assertThrows(Exception.class, () -> findRunningJarFilePath(JarProbe.class)).getMessage());

		// Class loaded from a jar in a directory with a blank (percent-encoded in the class resource URL)
		final File jarDirectory = new File(tempDirectory, "directory with blank");
		jarDirectory.mkdirs();
		final File probeJar = new File(jarDirectory, "probe.jar");
		try (InputStream classInputStream = JarProbe.class.getResourceAsStream("JarProbe.class");
				OutputStream outputStream = Files.newOutputStream(probeJar.toPath());
				JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
			jarOutputStream.putNextEntry(new JarEntry(JarProbe.class.getName().replace('.', '/') + ".class"));
			classInputStream.transferTo(jarOutputStream);
			jarOutputStream.closeEntry();
		}
		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { probeJar.toURI().toURL() }, null)) {
			final Class<?> probeClassFromJar = classLoader.loadClass(JarProbe.class.getName());
			assertEquals(probeJar.getAbsolutePath(), findRunningJarFilePath(probeClassFromJar));
		}
	}

	private static ClassLoader getApplicationClassLoader(final Class<?> applicationClass) throws Exception {
		return (ClassLoader) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "getApplicationClassLoader", new Class<?>[] { Class.class }, applicationClass);
	}

	@Test
	public void testGetApplicationClassLoader() throws Exception {
		assertSame(ApplicationUpdateUtilitiesInstallTest.class.getClassLoader(), getApplicationClassLoader(ApplicationUpdateUtilitiesInstallTest.class));
		// Classes of the boot class loader have no class loader
		assertSame(Thread.currentThread().getContextClassLoader(), getApplicationClassLoader(String.class));
		assertSame(Thread.currentThread().getContextClassLoader(), getApplicationClassLoader(null));

		final ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(null);
			assertSame(ApplicationUpdateUtilities.class.getClassLoader(), getApplicationClassLoader(null));
		} finally {
			Thread.currentThread().setContextClassLoader(originalContextClassLoader);
		}
	}

	private static void checkUpdateSourceIsAllowed(final String updateFileLocation, final String trustedCaCertificateFileNames, final boolean allowUnsafeUpdates) throws Exception {
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "checkUpdateSourceIsAllowed", new Class<?>[] { String.class, String.class, boolean.class }, updateFileLocation, trustedCaCertificateFileNames, allowUnsafeUpdates);
	}

	@Test
	public void testCheckUpdateSourceIsAllowed() throws Exception {
		// Signed updates are allowed from everywhere
		checkUpdateSourceIsAllowed("http://example.com/TestApp.jar", "ca.pem", false);
		checkUpdateSourceIsAllowed("https://example.com/TestApp.jar", "ca.pem", true);

		assertEquals("Trusted CA certificate file name is missing", assertThrows(Exception.class, () -> checkUpdateSourceIsAllowed("https://example.com/TestApp.jar", null, false)).getMessage());
		assertEquals("Updates without signature check are only allowed via https or from local files",
				assertThrows(Exception.class, () -> checkUpdateSourceIsAllowed(" HTTP://example.com/TestApp.jar", "", true)).getMessage());

		checkUpdateSourceIsAllowed("https://example.com/TestApp.jar", null, true);
		checkUpdateSourceIsAllowed("/opt/update/TestApp.jar", null, true);
		checkUpdateSourceIsAllowed("file:///opt/update/TestApp.jar", null, true);
	}

	// ===== Updater command and fallback scripts =====

	private static String[] createUpdaterCommand(final String jarFilePath, final File downloadTempFile, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate) throws Exception {
		return (String[]) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "createUpdaterCommand", new Class<?>[] { String.class, File.class, List.class, List.class, boolean.class },
				jarFilePath, downloadTempFile, jvmParameters, appParameters, restartAfterUpdate);
	}

	private static boolean isUpdaterBinaryAvailable() throws Exception {
		try (InputStream inputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Updater.jar")) {
			return inputStream != null;
		}
	}

	@Test
	public void testCreateUpdaterCommand() throws Exception {
		assumeTrue(isUpdaterBinaryAvailable(), "Updater.jar resource is needed");
		final File updaterFile = new File(tempDirectory, "Updater_" + applicationJar.getName() + ".jar");
		// A leftover of a previous update is replaced
		TestHelper.writeTextFile(updaterFile, "leftover");

		final List<String> command = Arrays.asList(createUpdaterCommand(applicationJar.getAbsolutePath(), getDownloadTempFile(), Arrays.asList("-Xmx1g"), Arrays.asList("arg1", "arg 2"), false));

		assertEquals("-jar", command.get(1));
		assertEquals(updaterFile.getAbsolutePath(), command.get(2));
		assertEquals(Arrays.asList(applicationJar.getAbsolutePath(), getDownloadTempFile().getAbsolutePath(), "-norestart", "-jvmParameter", "-Xmx1g", "-appParameter", "arg1", "arg 2"), command.subList(3, command.size()));
		assertTrue(updaterFile.length() > "leftover".length(), "Updater binary must be extracted");
	}

	@Test
	public void testCreateUpdaterCommandForDebugging() throws Exception {
		assumeTrue(isUpdaterBinaryAvailable(), "Updater.jar resource is needed");
		ApplicationUpdateUtilities.DEBUGGING_UPDATER = true;
		final List<String> command = Arrays.asList(createUpdaterCommand(applicationJar.getAbsolutePath(), getDownloadTempFile(), null, new ArrayList<>(), true));
		assertEquals(Arrays.asList("-log", "-stepwait", applicationJar.getAbsolutePath(), getDownloadTempFile().getAbsolutePath()), command.subList(3, command.size()));
	}

	@Test
	public void testCreateUpdaterCommandWithUndeletableLeftover() throws Exception {
		assumeTrue(isUpdaterBinaryAvailable(), "Updater.jar resource is needed");
		// A non empty directory cannot be deleted like a file
		final File updaterFile = new File(tempDirectory, "Updater_" + applicationJar.getName() + ".jar");
		updaterFile.mkdirs();
		TestHelper.writeTextFile(new File(updaterFile, "blocker.txt"), "blocker");

		final Exception exception = assertThrows(Exception.class, () -> createUpdaterCommand(applicationJar.getAbsolutePath(), getDownloadTempFile(), null, null, true));
		assertTrue(exception.getMessage().startsWith("Cannot remove leftover updater file"), exception.getMessage());
	}

	private static String[] createUpdateBatchFile(final String jarFilePath, final File downloadTempFile, final boolean restart, final List<String> jvmParameters, final List<String> appParameters) throws Exception {
		return (String[]) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "createUpdateBatchFile", new Class<?>[] { String.class, File.class, boolean.class, List.class, List.class },
				jarFilePath, downloadTempFile, restart, jvmParameters, appParameters);
	}

	@Test
	public void testCreateUpdateBatchFile() throws Exception {
		final String[] command = createUpdateBatchFile(applicationJar.getAbsolutePath(), getDownloadTempFile(), true, Arrays.asList("-Xmx1g"), Arrays.asList("it's", "100%"));
		final File scriptFile = new File(command[command.length - 1]);
		assertTrue(scriptFile.exists());
		final String script = TestHelper.readTextFile(scriptFile);

		if (SystemUtilities.isWindowsSystem()) {
			assertArrayEquals(new String[] { "cmd", "/c", "start", "/B", scriptFile.getAbsolutePath() }, command);
			assertTrue(script.startsWith("@echo off\r\n"));
			assertTrue(script.contains("del \"" + applicationJar.getAbsolutePath() + "\""), script);
			assertTrue(script.contains(" -jar \"" + applicationJar.getAbsolutePath() + "\" \"it's\" \"100%%\""), script);
		} else {
			assertArrayEquals(new String[] { "sh", scriptFile.getAbsolutePath() }, command);
			assertTrue(script.startsWith("#!/bin/sh\n"));
			assertTrue(script.contains("mv '" + getDownloadTempFile().getAbsolutePath() + "' '" + applicationJar.getAbsolutePath() + "'"), script);
			assertTrue(script.contains(" '-Xmx1g' -jar '" + applicationJar.getAbsolutePath() + "' 'it'\\''s' '100%'"), script);
			assertTrue(script.contains("rm -f '" + scriptFile.getAbsolutePath() + "'"), script);
		}
	}

	@Test
	public void testCreateUpdateBatchFileWithoutRestart() throws Exception {
		final String[] command = createUpdateBatchFile(applicationJar.getAbsolutePath(), getDownloadTempFile(), false, null, null);
		final String script = TestHelper.readTextFile(new File(command[command.length - 1]));
		assertFalse(script.contains(" -jar "), script);
	}

	@Test
	public void testCreateRestartBatchFile() throws Exception {
		final String[] command = ApplicationUpdateUtilities.createRestartBatchFile(applicationJar.getAbsolutePath(), "-param value");
		final File scriptFile = new File(command[command.length - 1]);
		assertTrue(scriptFile.getName().startsWith("restart_" + applicationJar.getName()));
		final String script = TestHelper.readTextFile(scriptFile);
		if (SystemUtilities.isWindowsSystem()) {
			assertTrue(script.contains("-jar \"" + applicationJar.getAbsolutePath() + "\" -param value\r\n"), script);
		} else {
			assertTrue(script.contains("-jar '" + applicationJar.getAbsolutePath() + "' -param value\n"), script);
			assertTrue(script.contains("sleep 10"), script);
		}

		// An existing script is replaced
		final String[] commandWithoutParameter = ApplicationUpdateUtilities.createRestartBatchFile(applicationJar.getAbsolutePath(), null);
		final String scriptWithoutParameter = TestHelper.readTextFile(new File(commandWithoutParameter[commandWithoutParameter.length - 1]));
		assertFalse(scriptWithoutParameter.contains("-param value"), scriptWithoutParameter);
	}

	@Test
	public void testCreateRestartBatchFileInMissingDirectory() {
		final File jarInMissingDirectory = new File(new File(tempDirectory, "missing"), "TestApp.jar");
		final Exception exception = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.createRestartBatchFile(jarInMissingDirectory.getAbsolutePath(), null));
		assertTrue(exception.getMessage().startsWith("Cannot write file"), exception.getMessage());
	}

	@Test
	public void testQuoting() throws Exception {
		assertEquals("\"C:\\Program Files\\App 100%%.jar\"", TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "quoteForBatchFile", new Class<?>[] { String.class }, "C:\\Program Files\\App 100%.jar"));
		assertEquals("'/opt/it'\\''s/App.jar'", TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "quoteForShellScript", new Class<?>[] { String.class }, "/opt/it's/App.jar"));

		final Class<?>[] joinQuotedParameterTypes = new Class<?>[] { List.class, boolean.class };
		assertEquals("", TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "joinQuoted", joinQuotedParameterTypes, null, true));
		assertEquals(" \"a\" \"b c\"", TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "joinQuoted", joinQuotedParameterTypes, Arrays.asList("a", "b c"), true));
		assertEquals(" 'a' 'b c'", TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "joinQuoted", joinQuotedParameterTypes, Arrays.asList("a", "b c"), false));
	}

	@Test
	public void testAddParameters() throws Exception {
		final Class<?>[] parameterTypes = new Class<?>[] { List.class, List.class, List.class };

		final List<String> withoutParameters = new ArrayList<>();
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "addParameters", parameterTypes, withoutParameters, null, new ArrayList<>());
		assertTrue(withoutParameters.isEmpty());

		final List<String> withParameters = new ArrayList<>();
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "addParameters", parameterTypes, withParameters, Arrays.asList("-Xmx1g"), Arrays.asList("a", "b"));
		assertEquals(Arrays.asList("-jvmParameter", "-Xmx1g", "-appParameter", "a", "b"), withParameters);

		final List<String> appParametersOnly = new ArrayList<>();
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "addParameters", parameterTypes, appParametersOnly, new ArrayList<>(), Arrays.asList("a"));
		assertEquals(Arrays.asList("-appParameter", "a"), appParametersOnly);
	}

	@Test
	public void testGetJavaBinPath() throws Exception {
		final String javaBinPath = (String) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "getJavaBinPath", new Class<?>[0]);
		assertNotNull(javaBinPath);
		assertFalse(javaBinPath.isBlank());
	}

	// ===== Small helpers =====

	@Test
	public void testWriteFile() throws Exception {
		final File file = new File(tempDirectory, "written.txt");
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "writeFile", new Class<?>[] { File.class, String.class }, file, "äöü");
		assertEquals("äöü", TestHelper.readTextFile(file));

		final File fileInMissingDirectory = new File(new File(tempDirectory, "missing"), "written.txt");
		final Exception exception = assertThrows(Exception.class, () -> TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "writeFile", new Class<?>[] { File.class, String.class }, fileInMissingDirectory, "x"));
		assertEquals("Cannot write file " + fileInMissingDirectory.getAbsolutePath(), exception.getMessage());
	}

	@Test
	public void testDeleteQuietly() throws Exception {
		final Class<?>[] parameterTypes = new Class<?>[] { File.class };
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "deleteQuietly", parameterTypes, (Object) null);
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "deleteQuietly", parameterTypes, new File(tempDirectory, "missing.txt"));

		final File file = TestHelper.writeTextFile(new File(tempDirectory, "delete.txt"), "x");
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "deleteQuietly", parameterTypes, file);
		assertFalse(file.exists());

		final File nonEmptyDirectory = new File(tempDirectory, "nonEmpty");
		nonEmptyDirectory.mkdirs();
		TestHelper.writeTextFile(new File(nonEmptyDirectory, "content.txt"), "x");
		final String errorOutput = captureStandardError(() -> TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "deleteQuietly", parameterTypes, nonEmptyDirectory));
		assertTrue(errorOutput.contains("Cannot delete file"), errorOutput);
		assertTrue(nonEmptyDirectory.exists());
	}

	@Test
	public void testShowUpdateError() throws Exception {
		final Class<?>[] parameterTypes = new Class<?>[] { ApplicationUpdateParent.class, String.class };
		final TestUpdateParent updateParent = new TestUpdateParent();
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "showUpdateError", parameterTypes, updateParent, "Error text");
		assertEquals(Arrays.asList("Error text"), updateParent.errors);

		final String errorOutput = captureStandardError(() -> TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "showUpdateError", parameterTypes, null, "Error without parent"));
		assertTrue(errorOutput.contains("Error without parent"), errorOutput);
	}

	@Test
	public void testAskForUpdate() throws Exception {
		final Class<?>[] parameterTypes = new Class<?>[] { ApplicationUpdateParent.class, Version.class };
		assertEquals(Boolean.FALSE, TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "askForUpdate", parameterTypes, null, new Version(2, 0, 0)));

		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.updateAnswer = true;
		assertEquals(Boolean.TRUE, TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "askForUpdate", parameterTypes, updateParent, new Version(2, 0, 0)));
	}

	@Test
	public void testRestartWithInvalidJarPath() throws Exception {
		// The restarter file cannot be created for this name, so the restart fails and the JVM keeps running
		final String errorOutput = captureStandardError(() -> ApplicationUpdateUtilities.restart("in\u0000valid.jar", null, null));
		assertTrue(errorOutput.contains("Update error while updating to new version"), errorOutput);
	}

	@Test
	public void testRemoveUpdateLeftovers() throws Exception {
		// Without a known running jar nothing is done
		if (noRunningJarEnvironmentVariable()) {
			ApplicationUpdateUtilities.removeUpdateLeftovers();
		}

		// Unique jar name, so no restarter files of other applications are touched
		final File uniqueApplicationJar = TestHelper.createJar(new File(tempDirectory, "LeftoverTestApp" + System.nanoTime() + ".jar"), true, Map.of("a.txt", "a"));
		setRunningJar(uniqueApplicationJar);
		final File updaterFile = TestHelper.writeTextFile(new File(tempDirectory, "Updater_" + uniqueApplicationJar.getName() + ".jar"), "x");
		final File downloadFile = TestHelper.writeTextFile(new File(tempDirectory, "temp_" + uniqueApplicationJar.getName()), "x");
		final File restarterFile = Files.createTempFile("Restarter_" + uniqueApplicationJar.getName() + "_", ".jar").toFile();
		final File otherTempFile = Files.createTempFile("Restarter_" + uniqueApplicationJar.getName() + "_", ".txt").toFile();
		try {
			ApplicationUpdateUtilities.removeUpdateLeftovers();

			assertFalse(updaterFile.exists());
			assertFalse(downloadFile.exists());
			assertFalse(restarterFile.exists());
			assertTrue(otherTempFile.exists());
			assertTrue(uniqueApplicationJar.exists());
		} finally {
			restarterFile.delete();
			otherTempFile.delete();
		}
	}

	private static String readJarEntry(final File jarFile, final String entryName) throws Exception {
		try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
			try (InputStream inputStream = jar.getInputStream(jar.getEntry(entryName))) {
				return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
	}
}

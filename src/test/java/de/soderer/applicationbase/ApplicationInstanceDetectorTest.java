package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("static-method")
public class ApplicationInstanceDetectorTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private File tempDirectory;
	private File jarFile;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("ApplicationInstanceDetectorTest").getCanonicalFile();
		jarFile = TestHelper.createJar(new File(tempDirectory, "TestApp.jar"), true, Map.of("test.txt", "test"));
	}

	@AfterEach
	public void tearDown() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	@Test
	public void testNoJarFilePath() {
		assertTrue(ApplicationInstanceDetector.getOtherProcessesUsingJarFile(null).isEmpty());
	}

	@Test
	public void testInvalidJarFilePath() {
		assertTrue(ApplicationInstanceDetector.getOtherProcessesUsingJarFile("invalid\u0000path.jar").isEmpty());
	}

	@Test
	public void testJarFileNotInUse() {
		assertTrue(ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath()).isEmpty());
		assertTrue(ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath(), (String[]) null).isEmpty());
		assertTrue(ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath(), "Updater_", "Restarter_").isEmpty());
	}

	@Test
	public void testOwnProcessIsIgnored() {
		// The test JVM itself uses its class path jars, but it is never reported as another process
		final List<Long> processIds = ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath());
		assertFalse(processIds.contains(ProcessHandle.current().pid()));
	}

	@Test
	public void testOtherProcessWithAbsolutePath() throws Exception {
		assumeTrue(ForeignJarProcess.isSupported(), "Foreign processes are only started on POSIX systems");
		try (ForeignJarProcess foreignProcess = new ForeignJarProcess(jarFile.getAbsolutePath(), null)) {
			final List<Long> processIds = ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath());
			assertTrue(processIds.contains(foreignProcess.getProcessId()), "Expected process " + foreignProcess.getProcessId() + " in " + processIds);

			// Processes whose jar file name starts with an ignored prefix are not counted
			final List<Long> processIdsWithIgnoredPrefix = ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath(), "TestApp");
			assertFalse(processIdsWithIgnoredPrefix.contains(foreignProcess.getProcessId()));

			// Other jar files are not affected
			final File otherJarFile = TestHelper.createJar(new File(tempDirectory, "OtherApp.jar"), true, Map.of("test.txt", "test"));
			assertFalse(ApplicationInstanceDetector.getOtherProcessesUsingJarFile(otherJarFile.getAbsolutePath()).contains(foreignProcess.getProcessId()));
		}
	}

	@Test
	public void testOtherProcessWithRelativePath() throws Exception {
		assumeTrue(ForeignJarProcess.isSupported(), "Foreign processes are only started on POSIX systems");
		try (ForeignJarProcess foreignProcess = new ForeignJarProcess(jarFile.getName(), tempDirectory)) {
			final List<Long> processIds = ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFile.getAbsolutePath());
			assertTrue(processIds.contains(foreignProcess.getProcessId()), "Expected process " + foreignProcess.getProcessId() + " in " + processIds);
		}
	}

	@Test
	public void testUsesJarFileWithAbsolutePath() {
		assertTrue(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", jarFile.getAbsolutePath()), null, jarFile));
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", new File(tempDirectory, "Other.jar").getAbsolutePath()), null, jarFile));
	}

	@Test
	public void testUsesJarFileWithinClassPath() {
		final String classPath = "lib" + File.separator + "other.jar" + File.pathSeparator + jarFile.getAbsolutePath() + File.pathSeparator + "classes";
		assertTrue(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-cp", classPath, "de.test.Main"), null, jarFile));
	}

	@Test
	public void testUsesJarFileWithRelativePath() {
		final Path workingDirectory = tempDirectory.toPath();
		assertTrue(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", jarFile.getName()), workingDirectory, jarFile));
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", "Other.jar"), workingDirectory, jarFile));

		// Working directory unknown: the file name decides
		assertTrue(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", jarFile.getName()), null, jarFile));
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", "Other.jar"), null, jarFile));
	}

	@Test
	public void testUsesJarFileFileNameCaseSensitivity() {
		final boolean caseInsensitiveFileSystem = TestHelper.isWindows() || System.getProperty("os.name", "").toLowerCase().contains("mac");
		assertEquals(caseInsensitiveFileSystem, ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", "testapp.JAR"), null, jarFile));
	}

	@Test
	public void testUsesJarFileWithMissingFile() {
		// The jar file does not exist anymore, so the normalized paths are compared
		final File missingJarFile = new File(new File(tempDirectory, "missing"), "Missing.jar");
		final String pathWithDots = new File(new File(new File(tempDirectory, "missing"), ".."), "missing" + File.separator + "Missing.jar").getAbsolutePath();
		assertTrue(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", pathWithDots), null, missingJarFile));
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "-jar", new File(tempDirectory, "Other.jar").getAbsolutePath()), null, missingJarFile));
	}

	@Test
	public void testUsesJarFileIgnoresOtherArguments() {
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("java", "", "-Xmx1g", "TestApp.txt"), null, jarFile));
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList(), null, jarFile));
		// Invalid path (NUL character) is no jar file
		assertFalse(ApplicationInstanceDetector.usesJarFile(Arrays.asList("in\u0000valid.jar"), null, jarFile));
	}

	@Test
	public void testSplitWindowsCommandLine() {
		assertEquals(Arrays.asList("C:\\Program Files\\Java\\bin\\java.exe", "-jar", "C:\\My Apps\\App.jar", "arg"),
				ApplicationInstanceDetector.splitWindowsCommandLine("\"C:\\Program Files\\Java\\bin\\java.exe\" -jar \"C:\\My Apps\\App.jar\"  arg"));
		assertEquals(Arrays.asList("a", "b", "c"), ApplicationInstanceDetector.splitWindowsCommandLine("  a\tb   c  "));
		assertEquals(Arrays.asList("x", "", "y"), ApplicationInstanceDetector.splitWindowsCommandLine("x \"\" y"));
		assertEquals(Arrays.asList("ab c"), ApplicationInstanceDetector.splitWindowsCommandLine("a\"b c\""));
		assertTrue(ApplicationInstanceDetector.splitWindowsCommandLine("").isEmpty());
		assertTrue(ApplicationInstanceDetector.splitWindowsCommandLine("   ").isEmpty());
	}

	@Test
	public void testProcessCommandLine() {
		final ApplicationInstanceDetector.ProcessCommandLine processCommandLine = new ApplicationInstanceDetector.ProcessCommandLine(42, Arrays.asList("java"), tempDirectory.toPath());
		assertEquals(42, processCommandLine.processId);
		assertEquals(Arrays.asList("java"), processCommandLine.arguments);
		assertEquals(tempDirectory.toPath(), processCommandLine.workingDirectory);
	}
}

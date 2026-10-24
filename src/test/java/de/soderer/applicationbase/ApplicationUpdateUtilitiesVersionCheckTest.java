package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.ServerSocket;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.network.NetworkUtilities;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.utilities.SystemUtilities;
import de.soderer.utilities.Tuple;
import de.soderer.utilities.Version;

/**
 * Tests of the version check of {@link ApplicationUpdateUtilities} with local JSON files and a local http server.
 */
@SuppressWarnings("static-method")
public class ApplicationUpdateUtilitiesVersionCheckTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final String APPLICATION_NAME = "TestApp";

	private File tempDirectory;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("ApplicationUpdateUtilitiesVersionCheckTest");
	}

	@AfterEach
	public void tearDown() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	private static String toFileUrl(final File file) {
		return "file://" + file.toURI().getRawPath();
	}

	private File writeIndexFile(final String fileName, final String content) throws Exception {
		return TestHelper.writeTextFile(new File(tempDirectory, fileName), content);
	}

	private static String getExpectedSystemName() {
		if (SystemUtilities.isWindowsSystem()) {
			return "windows";
		} else if (SystemUtilities.isLinuxSystem()) {
			return "linux";
		} else {
			return "unix";
		}
	}

	private static String getExpectedBitMode() {
		return System.getProperty("os.arch") != null && System.getProperty("os.arch").contains("64") ? "64" : "32";
	}

	@Test
	public void testEmptyIndexLocation() {
		assertEquals("Invalid version index location", assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(null, null, APPLICATION_NAME, new Version(1, 0, 0))).getMessage());
		assertEquals("Invalid version index location", assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable("", null, APPLICATION_NAME, new Version(1, 0, 0))).getMessage());
	}

	@Test
	public void testUnsupportedIndexLocation() throws Exception {
		final Exception ftpException = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable("ftp://example.com/versions.json", null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(ftpException.getMessage().startsWith("Unsupported version index location"), ftpException.getMessage());

		final File xmlFile = writeIndexFile("versions.xml", "<ApplicationVersions/>");
		final Exception xmlException = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(xmlFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(xmlException.getMessage().startsWith("Unsupported version index location"), xmlException.getMessage());
	}

	@Test
	public void testNewerVersionInJsonFile() throws Exception {
		final File indexFile = writeIndexFile("versions.json", "{ \"OtherApp\": { \"version\": \"9.9.9\", \"downloadUrl\": \"https://example.com/Other.jar\" }, "
				+ "\"" + APPLICATION_NAME + "\": { \"version\": \"2.1.0\", \"downloadUrl\": \"  https://example.com/TestApp.jar  \" } }");

		final Tuple<Version, String> result = ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0));
		assertNotNull(result);
		assertEquals(new Version(2, 1, 0), result.getFirst());
		assertEquals("https://example.com/TestApp.jar", result.getSecond());
	}

	@Test
	public void testPlaceholdersAreReplaced() throws Exception {
		// The index location itself contains a placeholder
		final File indexFile = writeIndexFile("versions_" + getExpectedSystemName() + ".json", "{ \"" + APPLICATION_NAME + "\": { \"version\": \"2.0.0\", "
				+ "\"downloadUrl\": \"https://example.com/<system>/<bitmode>/TestApp.jar?time=<time_seconds>\" } }");
		final String indexLocation = toFileUrl(indexFile).replace("versions_" + getExpectedSystemName(), "versions_<system>");

		final long startSeconds = Instant.now().getEpochSecond();
		final Tuple<Version, String> result = ApplicationUpdateUtilities.checkForNewVersionAvailable(indexLocation, null, APPLICATION_NAME, new Version(1, 0, 0));
		final long endSeconds = Instant.now().getEpochSecond();

		assertNotNull(result);
		final String expectedPrefix = "https://example.com/" + getExpectedSystemName() + "/" + getExpectedBitMode() + "/TestApp.jar?time=";
		assertTrue(result.getSecond().startsWith(expectedPrefix), result.getSecond());
		final long timeSeconds = Long.parseLong(result.getSecond().substring(expectedPrefix.length()));
		assertTrue(timeSeconds >= startSeconds && timeSeconds <= endSeconds);
	}

	@Test
	public void testNoNewerVersionInJsonFile() throws Exception {
		final File indexFile = writeIndexFile("versions.json", "{ \"" + APPLICATION_NAME + "\": { \"version\": \"1.0.0\", \"downloadUrl\": \"https://example.com/TestApp.jar\" } }");
		assertNull(ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertNull(ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 2, 0)));
	}

	@Test
	public void testNewerVersionWithoutDownloadUrl() throws Exception {
		final File indexFileWithoutUrl = writeIndexFile("versions1.json", "{ \"" + APPLICATION_NAME + "\": { \"version\": \"2.0.0\" } }");
		assertNull(ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFileWithoutUrl), null, APPLICATION_NAME, new Version(1, 0, 0)));

		final File indexFileWithBlankUrl = writeIndexFile("versions2.json", "{ \"" + APPLICATION_NAME + "\": { \"version\": \"2.0.0\", \"downloadUrl\": \"  \" } }");
		assertNull(ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFileWithBlankUrl), null, APPLICATION_NAME, new Version(1, 0, 0)));
	}

	@Test
	public void testJsonFileWithoutVersion() throws Exception {
		final File indexFile = writeIndexFile("versions.json", "{ \"" + APPLICATION_NAME + "\": { \"downloadUrl\": \"https://example.com/TestApp.jar\" } }");
		final Exception exception = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(exception.getMessage().startsWith("No version found for application 'TestApp'"), exception.getMessage());
	}

	@Test
	public void testJsonFileWithInvalidVersion() throws Exception {
		final File indexFile = writeIndexFile("versions.json", "{ \"" + APPLICATION_NAME + "\": { \"version\": \"not a version\", \"downloadUrl\": \"https://example.com/TestApp.jar\" } }");
		final Exception exception = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(exception.getMessage().startsWith("Invalid version index found at location"), exception.getMessage());
	}

	@Test
	public void testJsonFileWithoutApplication() throws Exception {
		final File indexFile = writeIndexFile("versions.json", "{ \"OtherApp\": { \"version\": \"2.0.0\" }, \"" + APPLICATION_NAME + "Second\": { \"version\": \"2.0.0\" } }");
		final Exception exception = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(exception.getMessage().contains("Version index entry for application 'TestApp' not found"), exception.getMessage());

		final File indexFileWithSimpleValue = writeIndexFile("versions2.json", "{ \"" + APPLICATION_NAME + "\": \"2.0.0\" }");
		final Exception simpleValueException = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(indexFileWithSimpleValue), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(simpleValueException.getMessage().contains("not found"), simpleValueException.getMessage());
	}

	@Test
	public void testInvalidJsonFile() throws Exception {
		final File arrayIndexFile = writeIndexFile("array.json", "[ 1, 2, 3 ]");
		final Exception arrayException = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(arrayIndexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertTrue(arrayException.getMessage().startsWith("Invalid version index found at location"), arrayException.getMessage());

		final File brokenIndexFile = writeIndexFile("broken.json", "{ \"TestApp\": ");
		assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(brokenIndexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));

		final File missingIndexFile = new File(tempDirectory, "missing.json");
		assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(toFileUrl(missingIndexFile), null, APPLICATION_NAME, new Version(1, 0, 0)));
	}

	@Test
	public void testJsonIndexViaHttp() throws Exception {
		assumeTrue(NetworkUtilities.checkForNetworkConnection(), "Network connection needed");
		try (TestHttpServer server = TestHttpServer.builder()
				.withResponse("/versions.json", 200, "{ \"" + APPLICATION_NAME + "\": { \"version\": \"3.0.0\", \"downloadUrl\": \"https://example.com/TestApp.jar\" } }").start()) {
			final Tuple<Version, String> result = ApplicationUpdateUtilities.checkForNewVersionAvailable(server.getBaseUrl() + "/versions.json", null, APPLICATION_NAME, new Version(1, 0, 0));
			assertNotNull(result);
			assertEquals(new Version(3, 0, 0), result.getFirst());
			assertEquals("https://example.com/TestApp.jar", result.getSecond());

			// Same with an explicit direct proxy configuration
			final Tuple<Version, String> resultWithProxyConfiguration = ApplicationUpdateUtilities.checkForNewVersionAvailable(server.getBaseUrl() + "/versions.json", new ProxyConfiguration(ProxyConfigurationType.None), APPLICATION_NAME, new Version(1, 0, 0));
			assertNotNull(resultWithProxyConfiguration);
			assertEquals(new Version(3, 0, 0), resultWithProxyConfiguration.getFirst());
		}
	}

	@Test
	public void testJsonIndexViaHttpNotFound() throws Exception {
		assumeTrue(NetworkUtilities.checkForNetworkConnection(), "Network connection needed");
		try (TestHttpServer server = TestHttpServer.builder().withResponse("/versions.json", 404, "Not found").start()) {
			assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(server.getBaseUrl() + "/versions.json", null, APPLICATION_NAME, new Version(1, 0, 0)));
		}
	}

	@Test
	public void testUnreachableHttpIndex() throws Exception {
		assumeTrue(NetworkUtilities.checkForNetworkConnection(), "Network connection needed");
		final int closedPort;
		try (ServerSocket serverSocket = new ServerSocket(0)) {
			closedPort = serverSocket.getLocalPort();
		}
		final String indexLocation = "http://127.0.0.1:" + closedPort + "/versions.json";

		final Exception exception = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(indexLocation, null, APPLICATION_NAME, new Version(1, 0, 0)));
		assertEquals("error.missingInternetConnection: " + indexLocation, exception.getMessage());

		final Exception exceptionWithProxy = assertThrows(Exception.class, () -> ApplicationUpdateUtilities.checkForNewVersionAvailable(indexLocation, new ProxyConfiguration(ProxyConfigurationType.None), APPLICATION_NAME, new Version(1, 0, 0)));
		assertEquals("error.missingInternetConnection (using ProxyConfiguration None): " + indexLocation, exceptionWithProxy.getMessage());
	}

	@SuppressWarnings("unchecked")
	private static Tuple<Version, String> readXmlVersionIndex(final String indexLocation) throws Exception {
		return (Tuple<Version, String>) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "readXmlVersionIndex", new Class<?>[] { String.class, ProxyConfiguration.class, String.class }, indexLocation, null, APPLICATION_NAME);
	}

	@Test
	public void testXmlVersionIndex() throws Exception {
		try (TestHttpServer server = TestHttpServer.builder()
				.withResponse("/valid.xml", 200, "<?xml version=\"1.0\"?><ApplicationVersions><OtherApp version=\"5.0.0\">x</OtherApp><" + APPLICATION_NAME + " version=\"2.3.4\">https://example.com/TestApp.jar</" + APPLICATION_NAME + "></ApplicationVersions>")
				.withResponse("/empty.xml", 200, "<ApplicationVersions><" + APPLICATION_NAME + "/></ApplicationVersions>")
				.withResponse("/missing.xml", 200, "<ApplicationVersions><OtherApp version=\"5.0.0\">x</OtherApp></ApplicationVersions>")
				.withResponse("/doctype.xml", 200, "<?xml version=\"1.0\"?><!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]><ApplicationVersions><" + APPLICATION_NAME + " version=\"1.0.0\">&xxe;</" + APPLICATION_NAME + "></ApplicationVersions>")
				.withResponse("/broken.xml", 200, "<ApplicationVersions>").start()) {
			final Tuple<Version, String> validResult = readXmlVersionIndex(server.getBaseUrl() + "/valid.xml");
			assertEquals(new Version(2, 3, 4), validResult.getFirst());
			assertEquals("https://example.com/TestApp.jar", validResult.getSecond());

			final Tuple<Version, String> emptyResult = readXmlVersionIndex(server.getBaseUrl() + "/empty.xml");
			assertNull(emptyResult.getFirst());
			assertNull(emptyResult.getSecond());

			assertEquals("error.cannotFindUpdateVersionData", assertThrows(Exception.class, () -> readXmlVersionIndex(server.getBaseUrl() + "/missing.xml")).getMessage());

			// DTDs and external entities are refused (XXE)
			final Exception doctypeException = assertThrows(Exception.class, () -> readXmlVersionIndex(server.getBaseUrl() + "/doctype.xml"));
			assertTrue(doctypeException.getMessage().startsWith("Invalid version index found at location"), doctypeException.getMessage());

			assertThrows(Exception.class, () -> readXmlVersionIndex(server.getBaseUrl() + "/broken.xml"));
		}
	}

	@SuppressWarnings("unchecked")
	private static Tuple<Version, String> readGithubRelease(final String indexLocation) throws Exception {
		return (Tuple<Version, String>) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "readGithubRelease", new Class<?>[] { String.class, ProxyConfiguration.class, String.class }, indexLocation, null, APPLICATION_NAME);
	}

	@Test
	public void testGithubRelease() throws Exception {
		final String assets = "\"assets\": [ { \"name\": \"TestApp-sources.jar\", \"browser_download_url\": \"https://example.com/TestApp-sources.jar\" }, "
				+ "{ \"name\": \"README.md\", \"browser_download_url\": \"https://example.com/README.md\" }, "
				+ "{ \"browser_download_url\": \"https://example.com/unnamed\" }, "
				+ "{ \"name\": \"TestApp.jar\", \"browser_download_url\": \"https://example.com/TestApp.jar\" } ]";
		try (TestHttpServer server = TestHttpServer.builder()
				.withResponse("/valid", 200, "{ \"name\": \"TestApp 2.0.0\", \"tag_name\": \"2.0.0\", " + assets + " }")
				.withResponse("/unnamed", 200, "{ \"tag_name\": \"2.0.1\", " + assets + " }")
				.withResponse("/wrongName", 200, "{ \"name\": \"OtherApp 2.0.0\", \"tag_name\": \"2.0.0\", " + assets + " }")
				.withResponse("/noTag", 200, "{ \"name\": \"TestApp\", " + assets + " }")
				.withResponse("/noAssets", 200, "{ \"name\": \"TestApp\", \"tag_name\": \"2.0.0\" }")
				.withResponse("/noJarAsset", 200, "{ \"name\": \"TestApp\", \"tag_name\": \"2.0.0\", \"assets\": [ { \"name\": \"TestApp-javadoc.jar\", \"browser_download_url\": \"https://example.com/doc.jar\" } ] }")
				.withResponse("/array", 200, "[]").start()) {
			final Tuple<Version, String> validResult = readGithubRelease(server.getBaseUrl() + "/valid");
			assertEquals(new Version(2, 0, 0), validResult.getFirst());
			assertEquals("https://example.com/TestApp.jar", validResult.getSecond());

			// The release name is optional
			final Tuple<Version, String> unnamedResult = readGithubRelease(server.getBaseUrl() + "/unnamed");
			assertEquals(new Version(2, 0, 1), unnamedResult.getFirst());

			assertTrue(assertThrows(Exception.class, () -> readGithubRelease(server.getBaseUrl() + "/wrongName")).getMessage().contains("Invalid github release data found for application 'TestApp'"));
			assertTrue(assertThrows(Exception.class, () -> readGithubRelease(server.getBaseUrl() + "/noTag")).getMessage().contains("has no tag name"));
			assertTrue(assertThrows(Exception.class, () -> readGithubRelease(server.getBaseUrl() + "/noAssets")).getMessage().contains("has no assets"));
			assertTrue(assertThrows(Exception.class, () -> readGithubRelease(server.getBaseUrl() + "/noJarAsset")).getMessage().contains("has no '.jar' asset"));
			assertTrue(assertThrows(Exception.class, () -> readGithubRelease(server.getBaseUrl() + "/array")).getMessage().contains("Invalid github release data found at location"));
		}
	}

	private static boolean isApplicationJarAssetName(final String assetName) throws Exception {
		return (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "isApplicationJarAssetName", new Class<?>[] { String.class }, assetName);
	}

	@Test
	public void testIsApplicationJarAssetName() throws Exception {
		assertFalse(isApplicationJarAssetName(null));
		assertFalse(isApplicationJarAssetName("TestApp.zip"));
		assertFalse(isApplicationJarAssetName("TestApp-sources.jar"));
		assertFalse(isApplicationJarAssetName("TestApp_sources.jar"));
		assertFalse(isApplicationJarAssetName("TestApp-javadoc.JAR"));
		assertFalse(isApplicationJarAssetName("TestApp_javadoc.jar"));
		assertTrue(isApplicationJarAssetName("TestApp.jar"));
		assertTrue(isApplicationJarAssetName("TestApp-2.0.0.JAR"));
	}

	private static String replacePlaceholders(final String value) throws Exception {
		return (String) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "replacePlaceholders", new Class<?>[] { String.class }, value);
	}

	@Test
	public void testReplacePlaceholders() throws Exception {
		assertNull(replacePlaceholders(null));
		assertEquals("https://example.com/plain.jar", replacePlaceholders("https://example.com/plain.jar"));
		assertEquals(getExpectedSystemName() + "_" + getExpectedBitMode(), replacePlaceholders("<system>_<bitmode>"));
		final long seconds = Long.parseLong(replacePlaceholders("<time_seconds>"));
		assertTrue(Math.abs(Instant.now().getEpochSecond() - seconds) < 5);
	}

	@Test
	public void testReplacePlaceholdersWith32BitArchitecture() throws Exception {
		final String originalArchitecture = System.getProperty("os.arch");
		try {
			System.setProperty("os.arch", "x86");
			assertEquals("32", replacePlaceholders("<bitmode>"));
		} finally {
			System.setProperty("os.arch", originalArchitecture);
		}
	}
}

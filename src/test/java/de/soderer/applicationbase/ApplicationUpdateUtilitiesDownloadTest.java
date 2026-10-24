package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.UserError;
import de.soderer.utilities.worker.WorkerParentSimple;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * Tests of the download and copy of update files by {@link ApplicationUpdateUtilities}, with a local http server.
 */
@SuppressWarnings("static-method")
public class ApplicationUpdateUtilitiesDownloadTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static final byte[] UPDATE_FILE_CONTENT = "Update file content".getBytes(StandardCharsets.UTF_8);

	private File tempDirectory;
	private File downloadTempFile;
	private TestHttpServer server;

	@BeforeEach
	public void setUp() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("ApplicationUpdateUtilitiesDownloadTest");
		downloadTempFile = new File(tempDirectory, "temp_TestApp.jar");
		server = TestHttpServer.builder()
				.withResponse("/TestApp.jar", 200, UPDATE_FILE_CONTENT)
				.withResponse("/unauthorized.jar", 401, "Unauthorized")
				.withResponse("/missing.jar", 404, "Not found")
				.withHandler("/protected.jar", requestUri -> {
					if ("user=alice&pw=secret".equals(requestUri.getQuery())) {
						return new TestHttpServer.Response(200, UPDATE_FILE_CONTENT);
					} else {
						return new TestHttpServer.Response(401, "Unauthorized");
					}
				}).start();
	}

	@AfterEach
	public void tearDown() {
		server.close();
		TestHelper.deleteRecursively(tempDirectory);
	}

	private boolean getNewApplicationVersionFile(final ApplicationUpdateParent updateParent, final String username, final char[] password, final String updateFileLocation) throws Exception {
		return (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "getNewApplicationVersionFile",
				new Class<?>[] { ApplicationUpdateParent.class, String.class, char[].class, String.class, ProxyConfiguration.class, File.class },
				updateParent, username, password, updateFileLocation, null, downloadTempFile);
	}

	private boolean getNewNonInteractiveApplicationVersionFile(final String username, final char[] password, final String updateFileLocation, final ProxyConfiguration proxyConfiguration) throws Exception {
		return (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "getNewNonInteractiveApplicationVersionFile",
				new Class<?>[] { String.class, char[].class, String.class, ProxyConfiguration.class, File.class },
				username, password, updateFileLocation, proxyConfiguration, downloadTempFile);
	}

	private static boolean runWorker(final ApplicationUpdateParent updateParent, final WorkerSimple<Boolean> worker) throws Exception {
		return (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "runWorker", new Class<?>[] { ApplicationUpdateParent.class, WorkerSimple.class }, updateParent, worker);
	}

	private void assertDownloaded() throws Exception {
		assertTrue(downloadTempFile.exists());
		assertArrayEquals(UPDATE_FILE_CONTENT, Files.readAllBytes(downloadTempFile.toPath()));
	}

	private File createLocalUpdateFile() throws Exception {
		final File localUpdateFile = new File(tempDirectory, "local_update.jar");
		Files.write(localUpdateFile.toPath(), UPDATE_FILE_CONTENT);
		return localUpdateFile;
	}

	// ===== Interactive download =====

	@Test
	public void testInteractiveDownload() throws Exception {
		// An old leftover download file is replaced
		Files.write(downloadTempFile.toPath(), "old".getBytes(StandardCharsets.UTF_8));

		final TestUpdateParent updateParent = new TestUpdateParent();
		assertTrue(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/TestApp.jar"));
		assertDownloaded();
		assertEquals(1, updateParent.downloadStartCount);
		assertTrue(updateParent.progressCount > 0);
		assertTrue(updateParent.errors.isEmpty(), updateParent.getErrorsText());
	}

	@Test
	public void testInteractiveDownloadWithGivenCredentials() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertTrue(getNewApplicationVersionFile(updateParent, "alice", "secret".toCharArray(), server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertDownloaded();
		assertTrue(updateParent.credentialRequests.isEmpty());
	}

	@Test
	public void testInteractiveDownloadAsksForUsernameAndPassword() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.credentialsAnswers.add(new Credentials("alice", "secret".toCharArray()));
		assertTrue(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertDownloaded();
		assertEquals(1, updateParent.credentialRequests.size());
		assertArrayEquals(new boolean[] { true, true, true }, updateParent.credentialRequests.get(0));
		assertEquals(LangResources.get("enterDownloadCredentials"), updateParent.credentialTexts.get(0));
	}

	@Test
	public void testInteractiveDownloadAsksForUsernameOnly() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.credentialsAnswers.add(new Credentials("alice"));
		assertTrue(getNewApplicationVersionFile(updateParent, null, "secret".toCharArray(), server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertDownloaded();
		assertArrayEquals(new boolean[] { true, false, true }, updateParent.credentialRequests.get(0));
	}

	@Test
	public void testInteractiveDownloadAsksForPasswordOnly() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.credentialsAnswers.add(new Credentials("secret".toCharArray()));
		assertTrue(getNewApplicationVersionFile(updateParent, "alice", null, server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertDownloaded();
		assertArrayEquals(new boolean[] { false, true, true }, updateParent.credentialRequests.get(0));
	}

	@Test
	public void testInteractiveDownloadCredentialsCanceled() throws Exception {
		final String[] locations = new String[] {
				server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>",
				server.getBaseUrl() + "/protected.jar?user=<username>",
				server.getBaseUrl() + "/protected.jar?pw=<password>" };
		for (final String location : locations) {
			final TestUpdateParent updateParent = new TestUpdateParent();
			assertFalse(getNewApplicationVersionFile(updateParent, null, null, location));
			assertEquals(1, updateParent.errors.size());
			assertEquals(LangResources.get("canceledByUser"), updateParent.errors.get(0));
			assertFalse(downloadTempFile.exists());
		}
	}

	@Test
	public void testInteractiveDownloadEmptyCredentials() throws Exception {
		final TestUpdateParent bothParent = new TestUpdateParent();
		bothParent.credentialsAnswers.add(new Credentials("alice", new char[0]));
		assertFalse(getNewApplicationVersionFile(bothParent, null, null, server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertEquals(LangResources.get("usernameAndPasswordRequired"), bothParent.errors.get(0));

		final TestUpdateParent usernameParent = new TestUpdateParent();
		usernameParent.credentialsAnswers.add(new Credentials(""));
		assertFalse(getNewApplicationVersionFile(usernameParent, null, null, server.getBaseUrl() + "/protected.jar?user=<username>"));
		assertEquals(LangResources.get("usernameRequired"), usernameParent.errors.get(0));

		final TestUpdateParent passwordParent = new TestUpdateParent();
		passwordParent.credentialsAnswers.add(new Credentials(new char[0]));
		assertFalse(getNewApplicationVersionFile(passwordParent, null, null, server.getBaseUrl() + "/protected.jar?pw=<password>"));
		assertEquals(LangResources.get("passwordRequired"), passwordParent.errors.get(0));
	}

	@Test
	public void testInteractiveDownloadRetriesAfterWrongCredentials() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.credentialsAnswers.add(new Credentials("alice", "secret".toCharArray()));
		// The preconfigured credentials are wrong, so the user is asked for other credentials
		assertTrue(getNewApplicationVersionFile(updateParent, "bob", "wrong".toCharArray(), server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertDownloaded();
		assertEquals(1, updateParent.errors.size());
		assertTrue(updateParent.errors.get(0).startsWith("Update error:"), updateParent.getErrorsText());
		assertEquals(1, updateParent.credentialRequests.size());
		// Not the first request anymore
		assertArrayEquals(new boolean[] { true, true, false }, updateParent.credentialRequests.get(0));
	}

	@Test
	public void testInteractiveDownloadRetryCanceled() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.credentialsAnswers.add(new Credentials("bob", "wrong".toCharArray()));
		assertFalse(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>"));
		assertEquals(2, updateParent.credentialRequests.size());
		assertEquals(2, updateParent.errors.size());
		assertEquals(LangResources.get("canceledByUser"), updateParent.errors.get(1));
	}

	@Test
	public void testInteractiveDownloadUnauthorizedWithoutPlaceholders() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertFalse(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/unauthorized.jar"));
		assertEquals(1, updateParent.errors.size());
		assertTrue(updateParent.credentialRequests.isEmpty());
	}

	@Test
	public void testInteractiveDownloadServerError() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertFalse(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/missing.jar"));
		assertEquals(1, updateParent.errors.size());
		assertTrue(updateParent.errors.get(0).startsWith("Update error:"), updateParent.getErrorsText());
		assertFalse(downloadTempFile.exists());
	}

	@Test
	public void testInteractiveDownloadCanceled() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.downloadStartAction = WorkerSimple::cancel;
		assertFalse(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/TestApp.jar"));
		assertEquals(LangResources.get("canceledByUser"), updateParent.errors.get(0));
	}

	@Test
	public void testInteractiveDownloadStartedByParent() throws Exception {
		final TestUpdateParent updateParent = new TestUpdateParent();
		// Like a modal progress dialog, the parent runs the worker itself, so it must not be started again
		updateParent.downloadStartAction = WorkerSimple::run;
		assertTrue(getNewApplicationVersionFile(updateParent, null, null, server.getBaseUrl() + "/TestApp.jar"));
		assertDownloaded();
	}

	@Test
	public void testInteractiveLocalCopy() throws Exception {
		final File localUpdateFile = createLocalUpdateFile();
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertTrue(getNewApplicationVersionFile(updateParent, null, null, localUpdateFile.getAbsolutePath()));
		assertDownloaded();

		Files.delete(downloadTempFile.toPath());
		assertTrue(getNewApplicationVersionFile(updateParent, null, null, "file://" + localUpdateFile.toURI().getRawPath()));
		assertDownloaded();
	}

	@Test
	public void testInteractiveLocalCopyCanceled() throws Exception {
		final File localUpdateFile = createLocalUpdateFile();
		final TestUpdateParent updateParent = new TestUpdateParent();
		updateParent.downloadStartAction = WorkerSimple::cancel;
		assertFalse(getNewApplicationVersionFile(updateParent, null, null, localUpdateFile.getAbsolutePath()));
		assertEquals(LangResources.get("canceledByUser"), updateParent.errors.get(0));
	}

	@Test
	public void testInteractiveLocalCopyOfMissingFile() {
		final TestUpdateParent updateParent = new TestUpdateParent();
		assertThrows(Exception.class, () -> getNewApplicationVersionFile(updateParent, null, null, new File(tempDirectory, "missing.jar").getAbsolutePath()));
		assertFalse(downloadTempFile.exists());
	}

	// ===== Non-interactive download =====

	@Test
	public void testNonInteractiveDownload() throws Exception {
		assertTrue(getNewNonInteractiveApplicationVersionFile(null, null, server.getBaseUrl() + "/TestApp.jar", null));
		assertDownloaded();
	}

	@Test
	public void testNonInteractiveDownloadWithProxyConfiguration() throws Exception {
		assertTrue(getNewNonInteractiveApplicationVersionFile(null, null, server.getBaseUrl() + "/TestApp.jar", new ProxyConfiguration(ProxyConfigurationType.None)));
		assertDownloaded();
	}

	@Test
	public void testNonInteractiveDownloadWithCredentials() throws Exception {
		assertTrue(getNewNonInteractiveApplicationVersionFile("alice", "secret".toCharArray(), server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>", null));
		assertDownloaded();
	}

	@Test
	public void testNonInteractiveDownloadMissingCredentials() {
		final String location = server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>";
		assertEquals("Requiring username and password for update", assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile(null, null, location, null)).getMessage());
		assertEquals("Requiring username for update", assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile(null, "pw".toCharArray(), location, null)).getMessage());
		assertEquals("Requiring password for update", assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile("alice", null, location, null)).getMessage());
	}

	@Test
	public void testNonInteractiveDownloadUnauthorized() {
		final Exception exception = assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile("bob", "wrong".toCharArray(), server.getBaseUrl() + "/protected.jar?user=<username>&pw=<password>", null));
		assertTrue(exception.getMessage().startsWith("Update error while downloading new version:"), exception.getMessage());
		assertFalse(downloadTempFile.exists());
	}

	@Test
	public void testNonInteractiveDownloadServerError() {
		final Exception exception = assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile(null, null, server.getBaseUrl() + "/missing.jar", null));
		assertTrue(exception.getMessage().startsWith("Update error:"), exception.getMessage());
	}

	@Test
	public void testNonInteractiveLocalCopy() throws Exception {
		final File localUpdateFile = createLocalUpdateFile();
		assertTrue(getNewNonInteractiveApplicationVersionFile(null, null, localUpdateFile.getAbsolutePath(), null));
		assertDownloaded();
	}

	@Test
	public void testNonInteractiveLocalCopyOfMissingFile() {
		assertThrows(Exception.class, () -> getNewNonInteractiveApplicationVersionFile(null, null, new File(tempDirectory, "missing.jar").getAbsolutePath(), null));
	}

	// ===== Worker handling =====

	private static WorkerSimple<Boolean> createWorker(final Boolean result, final Exception exception) {
		return new WorkerSimple<>(null) {
			@Override
			public Boolean work() throws Exception {
				if (exception != null) {
					throw exception;
				}
				return result;
			}
		};
	}

	@Test
	public void testRunWorkerSuccess() throws Exception {
		assertTrue(runWorker(null, createWorker(Boolean.TRUE, null)));
		assertTrue(runWorker(new TestUpdateParent(), createWorker(Boolean.TRUE, null)));
	}

	@Test
	public void testRunWorkerUnsuccessful() {
		assertEquals("Download was not successful", assertThrows(Exception.class, () -> runWorker(null, createWorker(Boolean.FALSE, null))).getMessage());
	}

	@Test
	public void testRunWorkerCanceled() throws Exception {
		final WorkerSimple<Boolean> worker = createWorker(Boolean.TRUE, null);
		worker.cancel();
		assertFalse(runWorker(null, worker));
	}

	@Test
	public void testRunWorkerUserError() {
		final UserError userError = new UserError("Test user error");
		assertSame(userError, assertThrows(UserError.class, () -> runWorker(null, createWorker(null, userError))));
	}

	@Test
	public void testRunWorkerError() {
		final Exception error = new Exception("Test error");
		final ExecutionException executionException = assertThrows(ExecutionException.class, () -> runWorker(null, createWorker(null, error)));
		assertSame(error, executionException.getCause());
	}

	@Test
	public void testWorkerParentAdapter() throws Exception {
		final WorkerParentSimple adapterWithoutParent = (WorkerParentSimple) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "createWorkerParentAdapter", new Class<?>[] { ApplicationUpdateParent.class }, (Object) null);
		adapterWithoutParent.receiveUnlimitedProgressSignal();
		adapterWithoutParent.receiveProgressSignal(LocalDateTime.now(), 10, 5, "B");
		adapterWithoutParent.receiveDoneSignal(LocalDateTime.now(), LocalDateTime.now(), 10, "B", null);
		adapterWithoutParent.changeTitle("title");
		assertTrue(adapterWithoutParent.cancel());

		final TestUpdateParent updateParent = new TestUpdateParent();
		final WorkerParentSimple adapter = (WorkerParentSimple) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "createWorkerParentAdapter", new Class<?>[] { ApplicationUpdateParent.class }, updateParent);
		adapter.receiveUnlimitedProgressSignal();
		adapter.receiveProgressSignal(LocalDateTime.now(), 10, 5, "B");
		adapter.receiveDoneSignal(LocalDateTime.now(), LocalDateTime.now(), 10, "B", null);
		adapter.changeTitle("title");
		assertTrue(adapter.cancel());
		assertEquals(1, updateParent.progressCount);
		assertEquals(1, updateParent.downloadEndCount);
	}
}

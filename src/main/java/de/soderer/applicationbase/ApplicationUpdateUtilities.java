package de.soderer.applicationbase;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSigner;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import de.soderer.json.JsonArray;
import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;
import de.soderer.network.HttpUtilities;
import de.soderer.network.NetworkUtilities;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.SystemUtilities;
import de.soderer.utilities.Tuple;
import de.soderer.utilities.UserError;
import de.soderer.utilities.UserError.Reason;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.FileCopyWorker;
import de.soderer.utilities.worker.FileDownloadWorker;
import de.soderer.utilities.worker.WorkerParentSimple;
import de.soderer.utilities.worker.WorkerSimple;
import de.soderer.utilities.xml.XmlUtilities;

/**
 * Checks for, downloads and installs new versions of the running application jar.
 * <p>
 * versionIndexFileLocation and the download location of the update file may include placeholders:<br>
 * &nbsp;&nbsp;&lt;system&gt; : replaced by "windows", "linux" or "unix"<br>
 * &nbsp;&nbsp;&lt;bitmode&gt; : replaced by "32" or "64"<br>
 * &nbsp;&nbsp;&lt;time_seconds&gt; : current timestamp in seconds (used by sourceforge.net)
 * <p>
 * The download location of the update file may additionally include:<br>
 * &nbsp;&nbsp;&lt;username&gt; : given username or interactive username dialog<br>
 * &nbsp;&nbsp;&lt;password&gt; : given password or interactive password dialog
 * <p>
 * Supported version index locations are the JSON or XML index on soderer.de, the "latest release" API of
 * GitHub ("api.github.com"), any other http(s) URL providing the JSON index format, and local "file://" JSON
 * files. A downloaded update is only installed if its jar signature is trusted by one of the given CA
 * certificates, unless unsafe updates are explicitly allowed (only for https and local update files).
 * <p>
 * An update is refused while the application jar is used by another running process, e.g. the application started
 * a second time in another window (see {@link ApplicationInstanceDetector}), because the jar file cannot be replaced
 * then (Windows) or the other instance would fail when loading classes from the replaced jar (Linux, macOS).
 */
public class ApplicationUpdateUtilities {
	/**
	 * Starts the updater with logging and step-by-step waits, for debugging.
	 */
	public static boolean DEBUGGING_UPDATER = false;
	/**
	 * Starts the restarter with logging, for debugging.
	 */
	public static boolean DEBUGGING_RESTARTER = false;

	/**
	 * Return value of InputStream read methods at the end of the stream.
	 */
	public static final int EOF = -1;

	/** Timeout for establishing http connections of the version check */
	private static final int CONNECT_TIMEOUT_MILLIS = 30_000;

	/** Timeout for reading data of the version check */
	private static final int READ_TIMEOUT_MILLIS = 60_000;

	/** Name prefix of the restarter jar files in the temp directory */
	private static final String RESTARTER_FILE_PREFIX = "Restarter_";

	/** Name prefix of the updater jar file next to the application jar */
	private static final String UPDATER_FILE_PREFIX = "Updater_";

	/** Name prefix of the downloaded update file next to the application jar */
	private static final String DOWNLOAD_TEMP_FILE_PREFIX = "temp_";

	private ApplicationUpdateUtilities() {
		// Utility class with static methods only
	}

	/**
	 * Checks for a new version and, after confirmation, downloads and installs it.
	 * The confirmation is skipped if a new version is available and a username is given (e.g. for automatic updates).
	 * On success with a new version the application exits, so the updater can replace its jar file.
	 *
	 * @param updateParent callbacks for user interaction, may be null for non-interactive use
	 * @param versionIndexFileLocation location of the version index (see class description)
	 * @param proxyConfiguration proxy to use for http requests, may be null for a direct connection
	 * @param applicationName name of the application in the version index
	 * @param applicationVersion currently running version
	 * @param trustedCaCertificateFileNames comma separated class path resources of the CA certificates, which the update jar's signature must be trusted by
	 * @param username username for "&lt;username&gt;" in the download URL, may be null
	 * @param password password for "&lt;password&gt;" in the download URL, may be null
	 * @param appParameters parameters for restarting the application after the update, may be null
	 * @param restartAfterUpdate true to restart the application after the update
	 * @param allowUnsafeUpdates true to allow updates without signature check (only via https or local files), if no trusted CA certificates are given
	 * @return true if the check (and a possible update) was executed without error, false if an error was shown via updateParent or the user canceled the update
	 * @throws Exception if an error occurred and no updateParent is given to show it
	 */
	public static boolean executeUpdate(
			final ApplicationUpdateParent updateParent,
			final String versionIndexFileLocation,
			final ProxyConfiguration proxyConfiguration,
			final String applicationName,
			final Version applicationVersion,
			final String trustedCaCertificateFileNames,
			final String username,
			final char[] password,
			final List<String> appParameters,
			final boolean restartAfterUpdate,
			final boolean allowUnsafeUpdates) throws Exception {
		try {
			if (Utilities.isEmpty(versionIndexFileLocation)) {
				throw new Exception("Invalid version index location");
			}

			final Tuple<Version, String> newVersionAvailable = checkForNewVersionAvailable(versionIndexFileLocation, proxyConfiguration, applicationName, applicationVersion);
			if ((newVersionAvailable != null && username != null)
					|| askForUpdate(updateParent, newVersionAvailable != null ? newVersionAvailable.getFirst() : null)) {
				if (newVersionAvailable == null) {
					// Nothing to install (an ApplicationUpdateParent must not confirm a missing version)
					return true;
				}

				final List<String> jvmParameters = SystemUtilities.getCurrentJvmParameters();
				// On success the application exits within updateApplication()
				final boolean updated = updateApplication(updateParent, newVersionAvailable.getSecond(), proxyConfiguration, trustedCaCertificateFileNames, username, password, jvmParameters, appParameters, restartAfterUpdate, allowUnsafeUpdates);
				if (!updated) {
					if (updateParent == null) {
						throw new Exception("Update to version " + newVersionAvailable.getFirst() + " was not successful, see error output");
					}
					return false;
				}
			}
			return true;
		} catch (final Exception e) {
			if (updateParent != null) {
				showUpdateError(updateParent, LangResources.get("updateErrorVersionCheck", e.getMessage()));
				return false;
			} else {
				throw e;
			}
		}
	}

	/**
	 * Checks whether the version index offers a newer version than the running one.
	 *
	 * @param versionIndexFileLocation location of the version index (see class description)
	 * @param proxyConfiguration proxy to use for http requests, may be null for a direct connection
	 * @param applicationName name of the application in the version index
	 * @param applicationVersion currently running version
	 * @return the newer version and its download location (with placeholders replaced), or null if no newer version is available
	 * @throws Exception if the version index is not reachable or invalid
	 */
	public static Tuple<Version, String> checkForNewVersionAvailable(final String versionIndexFileLocation, final ProxyConfiguration proxyConfiguration, final String applicationName, final Version applicationVersion) throws Exception {
		if (Utilities.isEmpty(versionIndexFileLocation)) {
			throw new Exception("Invalid version index location");
		}

		final String indexLocation = replacePlaceholders(versionIndexFileLocation);
		final String indexLocationLowerCase = indexLocation.toLowerCase(Locale.ROOT);

		final Tuple<Version, String> availableVersionData;
		if (indexLocationLowerCase.startsWith("http://") || indexLocationLowerCase.startsWith("https://")) {
			// Network checks only for remote indexes, a local index must also work offline
			if (!NetworkUtilities.checkForNetworkConnection()) {
				throw new Exception("error.missingNetworkConnection");
			}
			if (!NetworkUtilities.ping(indexLocation, getProxy(proxyConfiguration, indexLocation))) {
				if (proxyConfiguration == null) {
					throw new Exception("error.missingInternetConnection: " + indexLocation);
				} else {
					throw new Exception("error.missingInternetConnection (using ProxyConfiguration " + proxyConfiguration.getProxyConfigurationType() + "): " + indexLocation);
				}
			}

			final String hostname = NetworkUtilities.getHostnameFromRequestString(indexLocation);
			if ("soderer.de".equalsIgnoreCase(hostname) && !indexLocationLowerCase.contains("json")) {
				availableVersionData = readXmlVersionIndex(indexLocation, proxyConfiguration, applicationName);
			} else if ("api.github.com".equalsIgnoreCase(hostname)) {
				availableVersionData = readGithubRelease(indexLocation, proxyConfiguration, applicationName);
			} else {
				// JSON index (soderer.de or any other server)
				try (InputStream inputStream = new BufferedInputStream(openConnection(indexLocation, proxyConfiguration).getInputStream())) {
					availableVersionData = readJsonVersionIndex(inputStream, indexLocation, applicationName);
				}
			}
		} else if (indexLocationLowerCase.startsWith("file://") && indexLocationLowerCase.endsWith(".json")) {
			try (InputStream inputStream = new BufferedInputStream(URI.create(indexLocation).toURL().openStream())) {
				availableVersionData = readJsonVersionIndex(inputStream, indexLocation, applicationName);
			}
		} else {
			throw new Exception("Unsupported version index location '" + indexLocation + "' (supported: http(s) URLs and \"file://\" JSON files)");
		}

		final Version availableVersion = availableVersionData.getFirst();
		final String updateFileLocation = availableVersionData.getSecond();
		if (availableVersion == null) {
			throw new Exception("No version found for application '" + applicationName + "' in version index '" + indexLocation + "'");
		}

		if (applicationVersion.compareTo(availableVersion) < 0 && updateFileLocation != null && updateFileLocation.trim().length() > 0) {
			return new Tuple<>(availableVersion, replacePlaceholders(updateFileLocation.trim()));
		} else {
			return null;
		}
	}

	private static Proxy getProxy(final ProxyConfiguration proxyConfiguration, final String location) throws Exception {
		return proxyConfiguration == null ? Proxy.NO_PROXY : proxyConfiguration.getProxy(location);
	}

	/**
	 * Opens an http connection with timeouts, so an unresponsive server cannot block the application forever.
	 */
	private static URLConnection openConnection(final String location, final ProxyConfiguration proxyConfiguration) throws Exception {
		final URLConnection urlConnection = URI.create(location).toURL().openConnection(getProxy(proxyConfiguration, location));
		urlConnection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		urlConnection.setReadTimeout(READ_TIMEOUT_MILLIS);
		urlConnection.setRequestProperty("Pragma", "no-cache");
		urlConnection.setRequestProperty("Cache-Control", "no-cache");
		return urlConnection;
	}

	/**
	 * Reads the JSON version index format: { "ApplicationName": { "version": "1.2.3", "downloadUrl": "..." }, ... }
	 */
	private static Tuple<Version, String> readJsonVersionIndex(final InputStream inputStream, final String indexLocation, final String applicationName) throws Exception {
		try (JsonReader jsonReader = new JsonReader(inputStream)) {
			final JsonNode jsonNode = jsonReader.read();
			if (jsonNode == null || !jsonNode.isJsonObject()) {
				throw new Exception("Invalid version index found at location '" + indexLocation + "'");
			}

			final JsonObject jsonObject = (JsonObject) jsonNode;
			final Object applicationEntry = jsonObject.get(applicationName);
			if (!(applicationEntry instanceof JsonObject)) {
				throw new Exception("Version index entry for application '" + applicationName + "' not found at location '" + indexLocation + "'");
			}

			final JsonObject applicationJsonObject = (JsonObject) applicationEntry;
			final Object versionValue = applicationJsonObject.getSimpleValue("version");
			final Object downloadUrlValue = applicationJsonObject.getSimpleValue("downloadUrl");
			try {
				return new Tuple<>(versionValue == null ? null : new Version(versionValue.toString()), downloadUrlValue == null ? null : downloadUrlValue.toString());
			} catch (final Exception e) {
				throw new Exception("Invalid application version entry at location '" + indexLocation + "'", e);
			}
		} catch (final Exception e) {
			throw new Exception("Invalid version index found at location '" + indexLocation + "':\n" + e.getMessage(), e);
		}
	}

	/**
	 * Reads the XML version index format of soderer.de: &lt;ApplicationVersions&gt;&lt;ApplicationName version="1.2.3"&gt;downloadUrl&lt;/ApplicationName&gt;...
	 */
	private static Tuple<Version, String> readXmlVersionIndex(final String indexLocation, final ProxyConfiguration proxyConfiguration, final String applicationName) throws Exception {
		final Document versionsDocument;
		try (InputStream inputStream = new BufferedInputStream(openConnection(indexLocation, proxyConfiguration).getInputStream())) {
			// Downloaded via the configured proxy and parsed without DTDs and external entities (XXE)
			final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
			documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			documentBuilderFactory.setXIncludeAware(false);
			documentBuilderFactory.setExpandEntityReferences(false);
			versionsDocument = documentBuilderFactory.newDocumentBuilder().parse(inputStream);
		} catch (final Exception e) {
			throw new Exception("Invalid version index found at location '" + indexLocation + "':\n" + e.getMessage(), e);
		}

		final Node startFileNameNode = XmlUtilities.getSingleXPathNode(versionsDocument, "ApplicationVersions/" + applicationName);
		if (startFileNameNode == null) {
			throw new Exception("error.cannotFindUpdateVersionData");
		}

		Version availableVersion = null;
		final Node versionNode = startFileNameNode.getAttributes().getNamedItem("version");
		if (versionNode != null && Utilities.isNotBlank(versionNode.getNodeValue())) {
			availableVersion = new Version(versionNode.getNodeValue());
		}

		String updateFileLocation = null;
		if (startFileNameNode.getFirstChild() != null && Utilities.isNotBlank(startFileNameNode.getFirstChild().getNodeValue())) {
			updateFileLocation = startFileNameNode.getFirstChild().getNodeValue();
		}

		return new Tuple<>(availableVersion, updateFileLocation);
	}

	/**
	 * Reads the "latest release" data of the GitHub API.
	 */
	private static Tuple<Version, String> readGithubRelease(final String indexLocation, final ProxyConfiguration proxyConfiguration, final String applicationName) throws Exception {
		try (JsonReader jsonReader = new JsonReader(new BufferedInputStream(openConnection(indexLocation, proxyConfiguration).getInputStream()))) {
			final JsonNode jsonNode = jsonReader.read();
			if (jsonNode == null || !jsonNode.isJsonObject()) {
				throw new Exception("Invalid github release data found at location '" + indexLocation + "'");
			}

			final JsonObject jsonObject = (JsonObject) jsonNode;
			final Object releaseName = jsonObject.getSimpleValue("name");
			// The release name is optional on GitHub, it is only checked if it is set
			if (releaseName != null && Utilities.isNotBlank(releaseName.toString()) && !releaseName.toString().trim().startsWith(applicationName)) {
				throw new Exception("Invalid github release data found for application '" + applicationName + "' was '" + releaseName + "'");
			}

			final Object tagName = jsonObject.getSimpleValue("tag_name");
			if (tagName == null || Utilities.isBlank(tagName.toString())) {
				throw new Exception("Github release for application '" + applicationName + "' has no tag name");
			}
			final Version availableVersion = new Version(tagName.toString());

			final JsonArray assets = (JsonArray) jsonObject.get("assets");
			if (assets == null) {
				throw new Exception("Github release for application '" + applicationName + "' has no assets");
			}
			String jarAssetDownloadUrl = null;
			for (int assetIndex = 0; jarAssetDownloadUrl == null; assetIndex++) {
				final JsonObject assetObject;
				try {
					assetObject = (JsonObject) assets.get(assetIndex);
				} catch (@SuppressWarnings("unused") final IndexOutOfBoundsException indexOutOfBoundsException) {
					// Reached the end of the assets list without finding a ".jar" asset
					break;
				}
				final Object assetName = assetObject.getSimpleValue("name");
				if (assetName != null && isApplicationJarAssetName(assetName.toString())) {
					final Object downloadUrl = assetObject.getSimpleValue("browser_download_url");
					jarAssetDownloadUrl = downloadUrl == null ? null : downloadUrl.toString();
				}
			}
			if (jarAssetDownloadUrl == null) {
				throw new Exception("Github release for application '" + applicationName + "' has no '.jar' asset");
			}

			return new Tuple<>(availableVersion, jarAssetDownloadUrl);
		} catch (final Exception e) {
			throw new Exception("Invalid version index found at location '" + indexLocation + "':\n" + e.getMessage(), e);
		}
	}

	private static boolean askForUpdate(final ApplicationUpdateParent updateParent, final Version availableNewVersion) {
		if (updateParent != null) {
			try {
				return updateParent.askForUpdate(availableNewVersion);
			} catch (final Exception e) {
				updateParent.showUpdateError("Update error:\n" + e.getMessage());
				return false;
			}
		} else {
			return false;
		}
	}

	/**
	 * Restarts the application by starting the embedded "Restarter.jar" and exiting the current JVM.
	 * Errors are written to System.err, in that case the current JVM keeps running.
	 *
	 * @param jarFilePath path of the application jar to restart
	 * @param jvmParameters JVM parameters for the restarted application, may be null
	 * @param appParameters application parameters for the restarted application, may be null
	 */
	public static void restart(final String jarFilePath, final List<String> jvmParameters, final List<String> appParameters) {
		try {
			final Path restarterFile;
			try (InputStream restarterInputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Restarter.jar")) {
				if (restarterInputStream == null) {
					throw new Exception("Restarter binary not available");
				}

				// Random file name, created exclusively and (on POSIX systems) only accessible by the current user:
				// a fixed name in the shared temp directory could be prepared or replaced by other local users
				restarterFile = Files.createTempFile(RESTARTER_FILE_PREFIX + new File(jarFilePath).getName() + "_", ".jar");
				Files.copy(restarterInputStream, restarterFile, StandardCopyOption.REPLACE_EXISTING);
			}

			final List<String> restarterCommandList = new ArrayList<>();
			restarterCommandList.add(getJavaBinPath());
			restarterCommandList.add("-jar");
			restarterCommandList.add(restarterFile.toAbsolutePath().toString());

			if (DEBUGGING_RESTARTER) {
				restarterCommandList.add("-log");
			}

			restarterCommandList.add(jarFilePath);

			addParameters(restarterCommandList, jvmParameters, appParameters);

			startDetached(restarterCommandList.toArray(new String[0]));

			Runtime.getRuntime().exit(0);
		} catch (final Exception e) {
			System.err.println("Update error while updating to new version:\n" + e.getMessage());
			e.printStackTrace();
			return;
		}
	}

	private static String getJavaBinPath() {
		final String javaBinPath = SystemUtilities.getJavaBinPath();
		if (Utilities.isNotBlank(javaBinPath)) {
			return javaBinPath;
		} else if (SystemUtilities.isWindowsSystem()) {
			return "java.exe";
		} else {
			return "java";
		}
	}

	private static void addParameters(final List<String> commandParts, final List<String> jvmParameters, final List<String> appParameters) {
		if (jvmParameters != null && jvmParameters.size() > 0) {
			commandParts.add("-jvmParameter");
			commandParts.addAll(jvmParameters);
		}

		if (appParameters != null && appParameters.size() > 0) {
			commandParts.add("-appParameter");
			commandParts.addAll(appParameters);
		}
	}

	/**
	 * Starts a process that keeps running after this JVM exits. Its output is discarded by the operating system,
	 * so it can never block on a full or closed pipe.
	 */
	private static void startDetached(final String[] command) throws Exception {
		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
		processBuilder.start();
	}

	private static boolean updateApplication(final ApplicationUpdateParent updateParent, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final String trustedCaCertificateFileNames, final String username, final char[] password, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate, final boolean allowUnsafeUpdates) {
		try {
			return installUpdate(updateParent, updateParent == null ? null : updateParent.getClass(), updateFileLocation, proxyConfiguration, trustedCaCertificateFileNames, username, password, jvmParameters, appParameters, restartAfterUpdate, allowUnsafeUpdates);
		} catch (final UserError e) {
			// An expected situation like an application file in use, the message tells the user what to do
			showUpdateError(updateParent, e.getMessage());
			return false;
		} catch (final Exception e) {
			if (updateParent != null) {
				// Without updateParent showUpdateError() itself writes to System.err, the message must not appear twice
				System.err.println("Update error while updating to new version:\n" + e.getMessage());
			}
			e.printStackTrace();

			showUpdateError(updateParent, "Update error while updating to new version:\n" + e.getMessage());
			return false;
		}
	}

	/**
	 * Downloads and installs the given update file without any user interaction. The update jar's signature is
	 * always checked. On success the application exits, so the updater can replace its jar file.
	 *
	 * @param applicationClass main class of the application, used to find the running jar file and the CA certificate resources
	 * @param updateFileLocation location of the update jar file (http(s) URL, "file://" URL or file path)
	 * @param proxyConfiguration proxy to use for http requests, may be null for a direct connection
	 * @param trustedCaCertificateFileNames comma separated class path resources of the CA certificates, which the update jar's signature must be trusted by
	 * @param username username for "&lt;username&gt;" in the download URL, may be null
	 * @param password password for "&lt;password&gt;" in the download URL, may be null
	 * @param jvmParameters JVM parameters for restarting the application, may be null
	 * @param appParameters application parameters for restarting the application, may be null
	 * @param restartAfterUpdate true to restart the application after the update
	 * @return only returns false if the download was canceled, on success the application exits
	 * @throws Exception if the download, the signature check or starting the updater failed
	 */
	public static boolean updateNonInteractiveApplication(final Class<? extends ApplicationUpdateParent> applicationClass, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final String trustedCaCertificateFileNames, final String username, final char[] password, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate) throws Exception {
		try {
			return installUpdate(null, applicationClass, updateFileLocation, proxyConfiguration, trustedCaCertificateFileNames, username, password, jvmParameters, appParameters, restartAfterUpdate, false);
		} catch (final Exception e) {
			throw new Exception("Update error while updating to new version:\n" + e.getMessage(), e);
		}
	}

	/**
	 * Downloads, verifies and installs an update. On success the application exits.
	 *
	 * @param updateParent interactive callbacks, null for a non-interactive update
	 * @param applicationClass class of the application, used to find the running jar and the CA certificate resources, may be null
	 * @return false if the download was canceled or failed with an error already shown to the user
	 */
	private static boolean installUpdate(final ApplicationUpdateParent updateParent, final Class<?> applicationClass, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final String trustedCaCertificateFileNames, final String username, final char[] password, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate, final boolean allowUnsafeUpdates) throws Exception {
		if (Utilities.isBlank(updateFileLocation)) {
			throw new Exception("Mandatory updateFileLocation is empty");
		}

		// Checked before the download, so no unsafe file is downloaded at all
		checkUpdateSourceIsAllowed(updateFileLocation, trustedCaCertificateFileNames, allowUnsafeUpdates);

		final String jarFilePath = findRunningJarFilePath(applicationClass);
		// Checked before the download, so the user does not wait for a download which cannot be installed
		checkApplicationNotInUseByOtherProcesses(jarFilePath);
		final File downloadTempFile = new File(new File(jarFilePath).getParentFile(), DOWNLOAD_TEMP_FILE_PREFIX + new File(jarFilePath).getName());

		boolean handedOverToUpdater = false;
		try {
			final boolean downloadSuccess;
			if (updateParent != null) {
				downloadSuccess = getNewApplicationVersionFile(updateParent, username, password, updateFileLocation, proxyConfiguration, downloadTempFile);
			} else {
				downloadSuccess = getNewNonInteractiveApplicationVersionFile(username, password, updateFileLocation, proxyConfiguration, downloadTempFile);
			}
			if (!downloadSuccess) {
				return false;
			}

			verifyUpdateFile(downloadTempFile, trustedCaCertificateFileNames, getApplicationClassLoader(applicationClass));

			// Checked again, another instance may have been started during the download
			checkApplicationNotInUseByOtherProcesses(jarFilePath);

			final String[] updaterCommand = createUpdaterCommand(jarFilePath, downloadTempFile, jvmParameters, appParameters, restartAfterUpdate);

			if (updateParent != null) {
				updateParent.showUpdateDone(null, null, 0, null);
			}

			startDetached(updaterCommand);
			handedOverToUpdater = true;
		} finally {
			if (!handedOverToUpdater) {
				// A canceled, failed or untrusted download must not stay next to the application jar
				deleteQuietly(downloadTempFile);
			}
		}

		Runtime.getRuntime().exit(0);
		return true;
	}

	/**
	 * Unsigned updates are only allowed if explicitly requested, and never via unencrypted http, where any
	 * man-in-the-middle could inject code.
	 */
	private static void checkUpdateSourceIsAllowed(final String updateFileLocation, final String trustedCaCertificateFileNames, final boolean allowUnsafeUpdates) throws Exception {
		if (Utilities.isNotEmpty(trustedCaCertificateFileNames)) {
			return;
		} else if (!allowUnsafeUpdates) {
			throw new Exception("Trusted CA certificate file name is missing");
		} else if (updateFileLocation.trim().toLowerCase(Locale.ROOT).startsWith("http://")) {
			throw new Exception("Updates without signature check are only allowed via https or from local files");
		}
	}

	/**
	 * Refuses the update, while the application jar is used by other running processes, e.g. by the application
	 * started a second time in another window. Updater and restarter processes of the application are not counted.
	 *
	 * @throws UserError with the ids of the other processes and a hint to close them
	 */
	private static void checkApplicationNotInUseByOtherProcesses(final String jarFilePath) throws UserError {
		final List<Long> otherProcessIds = ApplicationInstanceDetector.getOtherProcessesUsingJarFile(jarFilePath, UPDATER_FILE_PREFIX, RESTARTER_FILE_PREFIX);
		if (!otherProcessIds.isEmpty()) {
			final String jarFileName = new File(jarFilePath).getName();
			final String processIdsText = Utilities.join(otherProcessIds, ", ");
			if (LangResources.existsKey("updateErrorApplicationInUse")) {
				throw new UserError(LangResources.get("updateErrorApplicationInUse", jarFileName, processIdsText));
			} else {
				throw new UserError("The application file '" + jarFileName + "' is still in use by another running instance of the application (process id " + processIdsText + "), e.g. in another window."
						+ "\nThe update cannot be installed while the application file is in use."
						+ "\nPlease close all other windows of the application and start the update again.");
			}
		}
	}

	private static ClassLoader getApplicationClassLoader(final Class<?> applicationClass) {
		ClassLoader classLoader = applicationClass == null ? null : applicationClass.getClassLoader();
		if (classLoader == null) {
			classLoader = Thread.currentThread().getContextClassLoader();
		}
		if (classLoader == null) {
			classLoader = ApplicationUpdateUtilities.class.getClassLoader();
		}
		return classLoader;
	}

	private static String findRunningJarFilePath(final Class<?> applicationClass) throws Exception {
		String jarFilePath = SystemUtilities.getCurrentlyRunningJarFilePath();

		if (jarFilePath == null && applicationClass != null) {
			try {
				final String currentJarUrlPath = applicationClass.getResource(applicationClass.getSimpleName() + ".class").toString();
				// The class resource URL is percent-encoded (e.g. "%20" for spaces in the path),
				// so it must be decoded via URI/File instead of just stripping "jar:file:" as plain text
				final String jarFileUriPart = currentJarUrlPath.substring("jar:".length(), currentJarUrlPath.lastIndexOf("!"));
				jarFilePath = new File(URI.create(jarFileUriPart)).getAbsolutePath();
			} catch (@SuppressWarnings("unused") final Exception e) {
				jarFilePath = null;
			}
		}

		jarFilePath = Utilities.replaceUsersHome(jarFilePath);

		if (jarFilePath == null || !new File(jarFilePath).exists()) {
			throw new Exception("Current running jar file was not found");
		}
		return jarFilePath;
	}

	/**
	 * Checks the jar signature of the update file, if trusted CA certificates are given.
	 */
	private static void verifyUpdateFile(final File updateFile, final String trustedCaCertificateFileNames, final ClassLoader applicationClassLoader) throws Exception {
		if (Utilities.isEmpty(trustedCaCertificateFileNames)) {
			// Unsafe updates were explicitly allowed, see checkUpdateSourceIsAllowed()
			return;
		}

		final Collection<Certificate> trustedCertificates = new ArrayList<>();
		for (final String trustedCaCertificateFileName : Utilities.splitAndTrimList(trustedCaCertificateFileNames)) {
			try (InputStream trustedUpdateCertificatesStream = applicationClassLoader.getResourceAsStream(trustedCaCertificateFileName)) {
				if (trustedUpdateCertificatesStream != null) {
					for (final Certificate trustedCaCertificate : CertificateFactory.getInstance("X.509").generateCertificates(new BufferedInputStream(trustedUpdateCertificatesStream))) {
						trustedCertificates.add(trustedCaCertificate);
					}
				}
			} catch (final Exception e) {
				throw new Exception("Trusted CA certificate '" + trustedCaCertificateFileName + "' is not readable: " + e.getMessage(), e);
			}
		}

		if (trustedCertificates.size() == 0) {
			throw new Exception("Trusted CA certificate is missing");
		} else if (!verifyJarSignature(updateFile, trustedCertificates)) {
			throw new Exception("Signature of updatefile is invalid");
		}
	}

	/**
	 * Extracts the embedded "Updater.jar" next to the application jar and returns the command to start it, or
	 * creates a batch file / shell script as fallback, if the updater binary is not available.
	 */
	private static String[] createUpdaterCommand(final String jarFilePath, final File downloadTempFile, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate) throws Exception {
		try (InputStream updaterInputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Updater.jar")) {
			if (updaterInputStream == null) {
				return createUpdateBatchFile(jarFilePath, downloadTempFile, restartAfterUpdate, jvmParameters, appParameters);
			}

			final File updaterFile = new File(new File(jarFilePath).getParentFile(), UPDATER_FILE_PREFIX + new File(jarFilePath).getName() + ".jar");
			if (updaterFile.exists() && !updaterFile.delete()) {
				throw new Exception("Cannot remove leftover updater file '" + updaterFile.getAbsolutePath() + "' from a previous update, it may still be in use");
			}
			Files.copy(updaterInputStream, updaterFile.toPath());

			final List<String> commandParts = new ArrayList<>(List.of(getJavaBinPath(), "-jar", updaterFile.getAbsolutePath()));
			if (DEBUGGING_UPDATER) {
				commandParts.add("-log");
				commandParts.add("-stepwait");
			}
			commandParts.add(jarFilePath);
			commandParts.add(downloadTempFile.getAbsolutePath());
			if (!restartAfterUpdate) {
				commandParts.add("-norestart");
			}

			addParameters(commandParts, jvmParameters, appParameters);

			return commandParts.toArray(new String[0]);
		}
	}

	private static boolean getNewApplicationVersionFile(final ApplicationUpdateParent updateParent, final String username, final char[] password, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final File downloadTempFile) throws Exception {
		final String updateFileLocationLowerCase = updateFileLocation.toLowerCase(Locale.ROOT);
		if (updateFileLocationLowerCase.startsWith("http://") || updateFileLocationLowerCase.startsWith("https://")) {
			// Download file
			boolean retryDownload = true;
			boolean firstRequest = true;
			while (retryDownload) {
				String downloadUrlWithCredentials = updateFileLocation;

				// Only use preconfigured credentials in first try only
				if (firstRequest) {
					if (username != null) {
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<username>", HttpUtilities.urlEncode(username, StandardCharsets.UTF_8));
					}
					if (password != null) {
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<password>", HttpUtilities.urlEncode(new String(password), StandardCharsets.UTF_8));
					}
				}

				if (downloadUrlWithCredentials.contains("<username>") && downloadUrlWithCredentials.contains("<password>")) {
					final Credentials credentials = updateParent.aquireCredentials(LangResources.get("enterDownloadCredentials"), true, true, firstRequest);
					if (credentials == null) {
						updateParent.showUpdateError(LangResources.get("canceledByUser"));
						return false;
					} else if (Utilities.isEmpty(credentials.getUsername()) || Utilities.isEmpty(credentials.getPassword())) {
						updateParent.showUpdateError(LangResources.get("usernameAndPasswordRequired"));
						return false;
					} else {
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<username>", HttpUtilities.urlEncode(credentials.getUsername(), StandardCharsets.UTF_8));
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<password>", HttpUtilities.urlEncode(new String(credentials.getPassword()), StandardCharsets.UTF_8));
					}
				} else if (downloadUrlWithCredentials.contains("<username>")) {
					final Credentials credentials = updateParent.aquireCredentials(LangResources.get("enterDownloadUsername"), true, false, firstRequest);
					if (credentials == null) {
						updateParent.showUpdateError(LangResources.get("canceledByUser"));
						return false;
					} else if (Utilities.isEmpty(credentials.getUsername())) {
						updateParent.showUpdateError(LangResources.get("usernameRequired"));
						return false;
					} else {
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<username>", HttpUtilities.urlEncode(credentials.getUsername(), StandardCharsets.UTF_8));
					}
				} else if (downloadUrlWithCredentials.contains("<password>")) {
					final Credentials credentials = updateParent.aquireCredentials(LangResources.get("enterDownloadPassword"), false, true, firstRequest);
					if (credentials == null) {
						updateParent.showUpdateError(LangResources.get("canceledByUser"));
						return false;
					} else if (Utilities.isEmpty(credentials.getPassword())) {
						updateParent.showUpdateError(LangResources.get("passwordRequired"));
						return false;
					} else {
						downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<password>", HttpUtilities.urlEncode(new String(credentials.getPassword()), StandardCharsets.UTF_8));
					}
				}

				retryDownload = false;
				try {
					deleteQuietly(downloadTempFile);
					final boolean success = downloadUpdateFile(updateParent, downloadTempFile, downloadUrlWithCredentials, proxyConfiguration);
					if (!success) {
						// download stopped by user
						updateParent.showUpdateError(LangResources.get("canceledByUser"));
						return false;
					}
				} catch (final Exception e) {
					final UserError userError;
					if (e instanceof UserError) {
						userError = (UserError) e;
					} else if (e instanceof ExecutionException && e.getCause() instanceof UserError) {
						userError = (UserError) e.getCause();
					} else {
						updateParent.showUpdateError("Update error:\n" + e.getMessage());
						return false;
					}

					updateParent.showUpdateError("Update error:\n" + userError.getMessage());
					if (userError.getReason() == Reason.UnauthenticatedOrUnauthorized && (updateFileLocation.contains("<username>") || updateFileLocation.contains("<password>"))) {
						// Ask for credentials again
						retryDownload = true;
					} else {
						return false;
					}
				}

				firstRequest = false;
			}
		} else {
			// "file://" URL or local file path
			deleteQuietly(downloadTempFile);
			final boolean success = copyUpdateFile(updateParent, downloadTempFile, updateFileLocation);
			if (!success) {
				// download stopped by user
				updateParent.showUpdateError(LangResources.get("canceledByUser"));
				return false;
			}
		}

		return downloadTempFile.exists();
	}

	private static boolean getNewNonInteractiveApplicationVersionFile(final String username, final char[] password, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final File downloadTempFile) throws Exception {
		final String updateFileLocationLowerCase = updateFileLocation.toLowerCase(Locale.ROOT);
		if (updateFileLocationLowerCase.startsWith("http://") || updateFileLocationLowerCase.startsWith("https://")) {
			// Download file
			String downloadUrlWithCredentials = updateFileLocation;

			if (username != null) {
				downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<username>", HttpUtilities.urlEncode(username, StandardCharsets.UTF_8));
			}
			if (password != null) {
				downloadUrlWithCredentials = downloadUrlWithCredentials.replace("<password>", HttpUtilities.urlEncode(new String(password), StandardCharsets.UTF_8));
			}

			if (downloadUrlWithCredentials.contains("<username>") && downloadUrlWithCredentials.contains("<password>")) {
				throw new Exception("Requiring username and password for update");
			} else if (downloadUrlWithCredentials.contains("<username>")) {
				throw new Exception("Requiring username for update");
			} else if (downloadUrlWithCredentials.contains("<password>")) {
				throw new Exception("Requiring password for update");
			}

			try {
				deleteQuietly(downloadTempFile);
				final boolean success = downloadUpdateFile(null, downloadTempFile, downloadUrlWithCredentials, proxyConfiguration);
				if (!success) {
					throw new Exception("Download failed");
				}
			} catch (final UserError e) {
				throw new Exception("Update error while downloading new version:\n" + e.getMessage(), e);
			} catch (final Exception e) {
				if (e instanceof ExecutionException && e.getCause() instanceof UserError) {
					throw new Exception("Update error while downloading new version:\n" + e.getCause().getMessage(), e);
				} else {
					throw new Exception("Update error:\n" + e.getMessage(), e);
				}
			}
		} else {
			// "file://" URL or local file path
			deleteQuietly(downloadTempFile);
			final boolean success = copyUpdateFile(null, downloadTempFile, updateFileLocation);
			if (!success) {
				throw new Exception("Download failed");
			}
		}

		return downloadTempFile.exists();
	}

	/**
	 * Quotes a value for a Windows batch file: within double quotes only '%' has a special meaning (and
	 * Windows paths cannot contain double quotes).
	 */
	private static String quoteForBatchFile(final String value) {
		return "\"" + value.replace("%", "%%") + "\"";
	}

	/**
	 * Quotes a value for a POSIX shell script: within single quotes nothing has a special meaning, a single quote
	 * itself is written as '\''.
	 */
	private static String quoteForShellScript(final String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}

	private static String joinQuoted(final List<String> values, final boolean windows) {
		final StringBuilder result = new StringBuilder();
		if (values != null) {
			for (final String value : values) {
				result.append(' ').append(windows ? quoteForBatchFile(value) : quoteForShellScript(value));
			}
		}
		return result.toString();
	}

	private static String[] createUpdateBatchFile(final String jarFilePath, final File downloadTempFile, final boolean restartApplicationAfterUpdate, final List<String> jvmParameters, final List<String> appParameters) throws Exception {
		final String javaBinPath = getJavaBinPath();
		final File jarFile = new File(jarFilePath);
		if (SystemUtilities.isWindowsSystem()) {
			final File batchFile = new File(jarFile.getParentFile(), "batchUpdate_" + jarFile.getName() + ".cmd");
			final String jar = quoteForBatchFile(jarFilePath);
			final String tempFile = quoteForBatchFile(downloadTempFile.getAbsolutePath());
			writeFile(batchFile,
					"@echo off\r\n"
							+ "del " + jar + "\r\n"
							+ "if exist " + jar + " (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del " + jar + "\r\n"
							+ "if exist " + jar + " (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del " + jar + "\r\n"
							+ "if exist " + jar + " (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del " + jar + "\r\n"
							+ ")\r\n"
							+ ")\r\n"
							+ ")\r\n"
							+ "ren " + tempFile + " " + quoteForBatchFile(jarFile.getName()) + "\r\n"
							+ "if not exist " + tempFile + " (\r\n"
							+ (restartApplicationAfterUpdate ? quoteForBatchFile(javaBinPath) + joinQuoted(jvmParameters, true) + " -jar " + jar + joinQuoted(appParameters, true) + "\r\n" : "")
							+ "del " + quoteForBatchFile(batchFile.getAbsolutePath()) + "\r\n"
							+ ")\r\n");

			return new String[]{ "cmd", "/c", "start", "/B", batchFile.getAbsolutePath() };
		} else {
			// Linux, macOS and other POSIX systems
			final File batchFile = new File(jarFile.getParentFile(), "batchUpdate_" + jarFile.getName() + ".sh");
			final String jar = quoteForShellScript(jarFilePath);
			final String tempFile = quoteForShellScript(downloadTempFile.getAbsolutePath());
			writeFile(batchFile,
					"#!/bin/sh\n"
							+ "rm -f " + jar + "\n"
							+ "if [ -f " + jar + " ]; then\n"
							+ "sleep 3\n"
							+ "rm -f " + jar + "\n"
							+ "if [ -f " + jar + " ]; then\n"
							+ "sleep 3\n"
							+ "rm -f " + jar + "\n"
							+ "if [ -f " + jar + " ]; then\n"
							+ "sleep 3\n"
							+ "rm -f " + jar + "\n"
							+ "fi\n"
							+ "fi\n"
							+ "fi\n"
							+ "mv " + tempFile + " " + jar + "\n"
							+ "if ! [ -f " + tempFile + " ]; then\n"
							+ (restartApplicationAfterUpdate ? quoteForShellScript(javaBinPath) + joinQuoted(jvmParameters, false) + " -jar " + jar + joinQuoted(appParameters, false) + "\n" : "")
							+ "rm -f " + quoteForShellScript(batchFile.getAbsolutePath()) + "\n"
							+ "fi\n");

			return new String[]{ "sh", batchFile.getAbsolutePath() };
		}
	}

	/**
	 * Creates a batch file (Windows) or shell script (Linux, macOS) next to the application jar, which waits some seconds,
	 * starts the application again and deletes itself.
	 *
	 * @param jarFilePath path of the application jar to restart
	 * @param appParameter application parameters as one String, appended unchanged (the caller is responsible for
	 *            quoting them for the batch file or shell script), may be null
	 * @return command to execute the created file
	 * @throws Exception if the file cannot be written
	 */
	public static String[] createRestartBatchFile(final String jarFilePath, final String appParameter) throws Exception {
		final String javaBinPath = getJavaBinPath();
		final File jarFile = new File(jarFilePath);
		if (SystemUtilities.isWindowsSystem()) {
			final File batchFile = new File(jarFile.getParentFile(), "restart_" + jarFile.getName() + ".cmd");
			deleteQuietly(batchFile);
			writeFile(batchFile,
					"@echo off\r\n"
							// About 20 seconds, like the former ten "ping -n 3" calls
							+ "ping -n 21 127.0.0.1 >nul\r\n"
							+ quoteForBatchFile(javaBinPath) + " -jar " + quoteForBatchFile(jarFilePath) + (appParameter != null ? " " + appParameter : "") + "\r\n"
							+ "del " + quoteForBatchFile(batchFile.getAbsolutePath()) + "\r\n");

			return new String[]{ "cmd", "/c", "start", "/B", batchFile.getAbsolutePath() };
		} else {
			final File batchFile = new File(jarFile.getParentFile(), "restart_" + jarFile.getName() + ".sh");
			deleteQuietly(batchFile);
			writeFile(batchFile,
					"#!/bin/sh\n"
							+ "sleep 10\n"
							+ quoteForShellScript(javaBinPath) + " -jar " + quoteForShellScript(jarFilePath) + (appParameter != null ? " " + appParameter : "") + "\n"
							+ "rm -f " + quoteForShellScript(batchFile.getAbsolutePath()) + "\n");

			return new String[]{ "sh", batchFile.getAbsolutePath() };
		}
	}

	private static String replacePlaceholders(final String value) {
		if (value == null) {
			return null;
		}

		String result = value;
		if (result.contains("<system>")) {
			if (SystemUtilities.isWindowsSystem()) {
				result = result.replace("<system>", "windows");
			} else if (SystemUtilities.isLinuxSystem()) {
				result = result.replace("<system>", "linux");
			} else {
				result = result.replace("<system>", "unix");
			}
		}

		if (result.contains("<bitmode>")) {
			if (System.getProperty("os.arch") != null && System.getProperty("os.arch").contains("64")) {
				result = result.replace("<bitmode>", "64");
			} else {
				result = result.replace("<bitmode>", "32");
			}
		}

		if (result.contains("<time_seconds>")) {
			// Real epoch seconds (LocalDateTime.now() interpreted as UTC was off by the local time zone offset)
			result = result.replace("<time_seconds>", Long.toString(Instant.now().getEpochSecond()));
		}

		return result;
	}

	private static void writeFile(final File file, final String string) throws Exception {
		try {
			Files.write(file.toPath(), string.getBytes(StandardCharsets.UTF_8));
		} catch (final Exception e) {
			throw new Exception("Cannot write file " + file.getAbsolutePath(), e);
		}
	}

	private static void deleteQuietly(final File file) {
		if (file != null && file.exists() && !file.delete()) {
			System.err.println("Cannot delete file '" + file.getAbsolutePath() + "'");
		}
	}

	private static WorkerParentSimple createWorkerParentAdapter(final ApplicationUpdateParent updateParent) {
		return new WorkerParentSimple() {
			@Override
			public void receiveUnlimitedProgressSignal() {
				// Do nothing
			}

			@Override
			public void receiveProgressSignal(final LocalDateTime startTime, final long itemsToDo, final long itemsDone, final String itemsUnitSign) {
				if (updateParent != null) {
					updateParent.showUpdateProgress(startTime, itemsToDo, itemsDone, itemsUnitSign);
				}
			}

			@Override
			public void receiveDoneSignal(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign, final String resultText) {
				if (updateParent != null) {
					updateParent.showUpdateDownloadEnd(startTime, endTime, itemsDone, itemsUnitSign);
				}
			}

			@Override
			public boolean cancel() {
				// Do nothing
				return true;
			}

			@Override
			public void changeTitle(final String text) {
				// do nothing
			}
		};
	}

	/**
	 * Runs a download or copy worker and waits for it.
	 *
	 * <p>
	 * The update parent is informed first and may start the worker itself (e.g. a progress dialog starting it
	 * when it is shown). The worker is only started here, if it was not started by the update parent. Starting it
	 * before informing the parent made a progress dialog start the same worker a second time, so two threads
	 * wrote into the same download file.
	 * </p>
	 *
	 * @return false if the worker was canceled
	 */
	private static boolean runWorker(final ApplicationUpdateParent updateParent, final WorkerSimple<Boolean> worker) throws Exception {
		try {
			if (updateParent != null) {
				updateParent.showUpdateDownloadStart(worker);
			}

			if (worker.getStartTime() == null && !worker.isDone()) {
				final Thread workerThread = new Thread(worker, "ApplicationUpdate-Download");
				workerThread.setDaemon(true);
				workerThread.start();
			}

			while (!worker.isDone()) {
				// Wait for download process
				Thread.sleep(200);
			}

			if (worker.isCancelled()) {
				return false;
			}

			if (!worker.get()) {
				throw new Exception("Download was not successful");
			}

			return true;
		} catch (final Exception e) {
			if (e instanceof ExecutionException && e.getCause() instanceof UserError) {
				throw (UserError) e.getCause();
			} else {
				throw e;
			}
		}
	}

	private static boolean downloadUpdateFile(final ApplicationUpdateParent updateParent, final File downloadTempFile, final String downloadUrl, final ProxyConfiguration proxyConfiguration) throws Exception {
		final FileDownloadWorker fileDownloadWorker = new FileDownloadWorker(createWorkerParentAdapter(updateParent), downloadUrl, downloadTempFile).setProxyConfiguration(proxyConfiguration);
		return runWorker(updateParent, fileDownloadWorker);
	}

	private static boolean copyUpdateFile(final ApplicationUpdateParent updateParent, final File downloadTempFile, final String downloadFile) throws Exception {
		final FileCopyWorker fileCopyWorker = new FileCopyWorker(createWorkerParentAdapter(updateParent), downloadFile, downloadTempFile);
		return runWorker(updateParent, fileCopyWorker);
	}

	private static void showUpdateError(final ApplicationUpdateParent updateParent, final String errorMessage) {
		if (updateParent != null) {
			updateParent.showUpdateError(errorMessage);
		} else {
			// Linebreak to end progress display
			System.err.println("");
			System.err.println(errorMessage);
		}
	}

	/**
	 * Checks that all files of a jar (except the manifest and the signature files) are signed by a signer, whose
	 * complete certificate path (as contained in the signature) is currently valid and leads to one of the given
	 * trusted certificates. Revocation is not checked. A jar without any signed file is rejected.
	 * <p>
	 * The certificate path of each signer is validated as a whole. Validating single certificates of the
	 * signature would be insecure: the certificate list of a signature is assembled by matching names only, so
	 * an attacker could add a genuine intermediate CA certificate to a jar signed with his own key.
	 * </p>
	 *
	 * @param jarFile jar file to check
	 * @param trustedCertificates trusted CA certificates
	 * @return true if the jar is completely signed by a trusted certificate, false otherwise or if no trusted certificates are given
	 */
	public static boolean verifyJarSignature(final File jarFile, final Collection<? extends Certificate> trustedCertificates) {
		if (trustedCertificates == null || trustedCertificates.size() == 0) {
			return false;
		}

		try (JarFile jar = new JarFile(jarFile, true)) {
			final Manifest manifest = jar.getManifest();
			if (manifest == null) {
				throw new SecurityException("The jar file has no manifest, which contains the file signatures");
			}

			final Set<TrustAnchor> trustAnchors = new HashSet<>();
			for (final Certificate trustedRootCert : trustedCertificates) {
				trustAnchors.add(new TrustAnchor((X509Certificate) trustedRootCert, null));
			}
			// The same signer signs all entries, so each certificate path is only validated once
			final Map<CertPath, Boolean> validatedCertPaths = new HashMap<>();

			final byte[] buffer = new byte[4096];
			final Enumeration<JarEntry> jarEntriesEnumerator = jar.entries();
			int signedFileCount = 0;

			while (jarEntriesEnumerator.hasMoreElements()) {
				final JarEntry jarEntry = jarEntriesEnumerator.nextElement();

				try (InputStream jarEntryInputStream = jar.getInputStream(jarEntry)) {
					// Reading the jarEntry throws a SecurityException if signature/digest check fails.
					while (jarEntryInputStream.read(buffer, 0, buffer.length) != EOF) {
						// just read it
					}
				}

				// Every file must be signed, except for the manifest and the signature files themselves.
				// Other files in META-INF (e.g. "META-INF/versions/..." of multi-release jars, which may contain
				// executable classes) must be signed too.
				if (!jarEntry.isDirectory() && !isSignatureRelatedEntry(jarEntry.getName())) {
					final CodeSigner[] codeSigners = jarEntry.getCodeSigners();
					if (codeSigners == null || codeSigners.length == 0) {
						throw new SecurityException("The jar file contains unsigned files: " + jarEntry.getName());
					}

					boolean isSignedByTrustedSigner = false;
					for (final CodeSigner codeSigner : codeSigners) {
						final CertPath signerCertPath = codeSigner.getSignerCertPath();
						Boolean isValid = validatedCertPaths.get(signerCertPath);
						if (isValid == null) {
							isValid = validateCertPath(signerCertPath, trustAnchors);
							validatedCertPaths.put(signerCertPath, isValid);
						}
						if (isValid) {
							isSignedByTrustedSigner = true;
							break;
						}
					}

					if (!isSignedByTrustedSigner) {
						throw new SecurityException("The jar file contains untrusted signed files: " + jarEntry.getName());
					}
					signedFileCount++;
				}
			}

			if (signedFileCount == 0) {
				// A jar with only a manifest would replace the application by nothing
				throw new SecurityException("The jar file contains no signed files");
			}

			return true;
		} catch (final Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Validates a complete certificate path (signer certificate first) against the trust anchors, including the
	 * validity periods of all its certificates.
	 */
	private static boolean validateCertPath(final CertPath certPath, final Set<TrustAnchor> trustAnchors) {
		try {
			if (certPath == null || certPath.getCertificates().isEmpty() || !(certPath.getCertificates().get(0) instanceof X509Certificate)) {
				return false;
			}

			final X509Certificate signerCertificate = (X509Certificate) certPath.getCertificates().get(0);
			if (isTrustAnchorCertificate(signerCertificate, trustAnchors)) {
				// The signer certificate itself is trusted directly
				signerCertificate.checkValidity();
				return true;
			}

			// A trust anchor itself must not be part of the validated path
			final List<Certificate> pathCertificates = new ArrayList<>(certPath.getCertificates());
			while (pathCertificates.size() > 1 && isTrustAnchorCertificate(pathCertificates.get(pathCertificates.size() - 1), trustAnchors)) {
				pathCertificates.remove(pathCertificates.size() - 1);
			}

			final CertPath pathToValidate = CertificateFactory.getInstance("X.509").generateCertPath(pathCertificates);
			final PKIXParameters params = new PKIXParameters(trustAnchors);
			// Revocation checking (CRL/OCSP) is disabled deliberately: it would require live network access
			// (CRL/OCSP endpoints) during update verification, which may not be available in all deployment
			// environments. The validity periods of the certificates are checked by the validator.
			params.setRevocationEnabled(false);
			CertPathValidator.getInstance("PKIX").validate(pathToValidate, params);
			return true;
		} catch (@SuppressWarnings("unused") final Exception e) {
			return false;
		}
	}

	private static boolean isTrustAnchorCertificate(final Certificate certificate, final Set<TrustAnchor> trustAnchors) {
		for (final TrustAnchor trustAnchor : trustAnchors) {
			if (certificate.equals(trustAnchor.getTrustedCert())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Deletes the files left over from a previous update or restart of the running application: updater jar,
	 * restarter jars in the temp directory, an unfinished download and fallback scripts.
	 */
	public static void removeUpdateLeftovers() {
		final String jarFilePath = SystemUtilities.getCurrentlyRunningJarFilePath();
		if (Utilities.isNotBlank(jarFilePath)) {
			final File jarFile = new File(jarFilePath);
			final File jarDirectory = jarFile.getAbsoluteFile().getParentFile();

			deleteQuietly(new File(jarDirectory, UPDATER_FILE_PREFIX + jarFile.getName() + ".jar"));
			deleteQuietly(new File(jarDirectory, DOWNLOAD_TEMP_FILE_PREFIX + jarFile.getName()));

			// Restarter jars have random names (see restart()); files of other users or still running restarters are skipped
			final File tempDirectory = new File(System.getProperty("java.io.tmpdir"));
			final String restarterFilePrefix = RESTARTER_FILE_PREFIX + jarFile.getName();
			final File[] restarterFiles = tempDirectory.listFiles((directory, name) -> name.startsWith(restarterFilePrefix) && name.endsWith(".jar"));
			if (restarterFiles != null) {
				for (final File restarterFile : restarterFiles) {
					if (restarterFile.isFile() && Files.isWritable(restarterFile.toPath())) {
						restarterFile.delete();
					}
				}
			}
		}
	}

	/**
	 * Checks whether a jar entry is the manifest or a signature file, which are not signed themselves.
	 */
	private static boolean isSignatureRelatedEntry(final String entryName) {
		final String upperCaseName = entryName.toUpperCase(Locale.ROOT);
		if (!upperCaseName.startsWith("META-INF/") || upperCaseName.indexOf('/', "META-INF/".length()) >= 0) {
			// Only files directly inside META-INF can be signature related
			return false;
		}
		return "META-INF/MANIFEST.MF".equals(upperCaseName)
				|| "META-INF/INDEX.LIST".equals(upperCaseName)
				|| upperCaseName.endsWith(".SF")
				|| upperCaseName.endsWith(".DSA")
				|| upperCaseName.endsWith(".RSA")
				|| upperCaseName.endsWith(".EC")
				|| upperCaseName.startsWith("META-INF/SIG-");
	}

	/**
	 * Checks whether a GitHub release asset is the executable application jar itself.
	 * Classifier jars like "-sources.jar" or "-javadoc.jar" (also with "_" separator) are excluded,
	 * because the order of assets in GitHub's release API response is not guaranteed.
	 */
	private static boolean isApplicationJarAssetName(final String assetName) {
		if (assetName == null) {
			return false;
		}
		final String assetNameLowerCase = assetName.toLowerCase(Locale.ROOT);
		if (!assetNameLowerCase.endsWith(".jar")) {
			return false;
		}
		for (final String classifier : new String[] { "sources", "javadoc" }) {
			if (assetNameLowerCase.endsWith("-" + classifier + ".jar") || assetNameLowerCase.endsWith("_" + classifier + ".jar")) {
				return false;
			}
		}
		return true;
	}
}

package de.soderer.applicationbase;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.CodeSigner;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import de.soderer.utilities.IoUtilities;
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
import de.soderer.utilities.xml.XmlUtilities;

/**
 * Checks for, downloads and installs new versions of the running application jar.
 * <p>
 * versionIndexFileLocation may include placeholders:<br>
 * &nbsp;&nbsp;&lt;system&gt; : replaced by "windows" or "linux"<br>
 * &nbsp;&nbsp;&lt;bitmode&gt; : replaced by "32" or "64"<br>
 * &nbsp;&nbsp;&lt;username&gt; : interactive username dialog<br>
 * &nbsp;&nbsp;&lt;password&gt; : interactive password dialog<br>
 * &nbsp;&nbsp;&lt;time_seconds&gt; : current timestamp in seconds (used by sourceforge.net)
 * <p>
 * Supported version index locations are the JSON or XML index on soderer.de, the "latest release" API of
 * GitHub ("api.github.com"), any other http(s) URL providing the JSON index format, and local "file://" JSON
 * files. A downloaded update is only installed if its jar signature is trusted by one of the given CA
 * certificates, unless unsafe updates are explicitly allowed.
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
	 * @param allowUnsafeUpdates true to allow updates without signature check, if no trusted CA certificates are given
	 * @return true if the check (and a possible update) was executed without error, false if an error was shown via updateParent
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
				final List<String> jvmParameters = SystemUtilities.getCurrentJvmParameters();
				updateApplication(updateParent, newVersionAvailable != null ? newVersionAvailable.getSecond() : null, proxyConfiguration, trustedCaCertificateFileNames, username, password, jvmParameters, appParameters, restartAfterUpdate, allowUnsafeUpdates);
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
		try {
			if (Utilities.isEmpty(versionIndexFileLocation)) {
				throw new Exception("Invalid version index location");
			} else if (!NetworkUtilities.checkForNetworkConnection()) {
				throw new Exception("error.missingNetworkConnection");
			}

			Version availableVersion = null;
			String updateFileLocation = null;
			if (versionIndexFileLocation.toLowerCase().startsWith("http://") || versionIndexFileLocation.toLowerCase().startsWith("https://")) {
				if (!NetworkUtilities.ping(versionIndexFileLocation, proxyConfiguration == null ? Proxy.NO_PROXY : proxyConfiguration.getProxy(versionIndexFileLocation))) {
					if (proxyConfiguration == null) {
						throw new Exception("error.missingInternetConnection: " + versionIndexFileLocation);
					} else {
						throw new Exception("error.missingInternetConnection (using ProxyConfiguration " + proxyConfiguration.getProxyConfigurationType() + "): " + versionIndexFileLocation);
					}
				}
				final String hostname = NetworkUtilities.getHostnameFromRequestString(versionIndexFileLocation);
				if ("soderer.de".equalsIgnoreCase(hostname)) {
					if (versionIndexFileLocation.toLowerCase().contains("json")) {
						final URLConnection urlConnection = URI.create(versionIndexFileLocation).toURL().openConnection(proxyConfiguration == null ? Proxy.NO_PROXY : proxyConfiguration.getProxy(versionIndexFileLocation));
						urlConnection.setRequestProperty("Pragma", "no-cache");
						try (JsonReader jsonReader = new JsonReader(new BufferedInputStream(urlConnection.getInputStream()))) {
							final JsonNode jsonNode = jsonReader.read();
							if (!jsonNode.isJsonObject()) {
								throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "'");
							} else {
								final JsonObject jsonObject = (JsonObject) jsonNode;
								final JsonObject applicationJsonObject = (JsonObject) jsonObject.get(applicationName);
								if (applicationJsonObject == null) {
									throw new Exception("Version index not found at location '" + versionIndexFileLocation + "'");
								} else {
									try {
										availableVersion = new Version((String) applicationJsonObject.getSimpleValue("version"));
										updateFileLocation = (String) applicationJsonObject.getSimpleValue("downloadUrl");
									} catch (final Exception e) {
										throw new Exception("Invalid application version entry at location '" + versionIndexFileLocation + "'", e);
									}
								}
							}
						} catch (final Exception e) {
							throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "':\n" + e.getMessage(), e);
						}
					} else {
						Document versionsDocument = null;
						if (versionIndexFileLocation.toLowerCase().startsWith("http")) {
							versionsDocument = XmlUtilities.downloadAndParseXmlFile(versionIndexFileLocation, null);
						} else if (new File(versionIndexFileLocation).exists()) {
							versionsDocument = XmlUtilities.parseXmlFile(new File(versionIndexFileLocation));
						}

						if (versionsDocument == null) {
							throw new Exception("Version index not found at location '" + versionIndexFileLocation + "'");
						}

						final Node startFileNameNode = XmlUtilities.getSingleXPathNode(versionsDocument, "ApplicationVersions/" + applicationName);
						if (startFileNameNode == null) {
							throw new Exception("error.cannotFindUpdateVersionData");
						}

						final Node versionNode = startFileNameNode.getAttributes().getNamedItem("version");
						if (versionNode != null && Utilities.isNotBlank(versionNode.getNodeValue())) {
							availableVersion = new Version(versionNode.getNodeValue());
						}

						if (startFileNameNode.getFirstChild() != null && Utilities.isNotBlank(startFileNameNode.getFirstChild().getNodeValue())) {
							updateFileLocation = startFileNameNode.getFirstChild().getNodeValue();
						}
					}
				} else if ("api.github.com".equalsIgnoreCase(hostname)) {
					final URLConnection urlConnection = URI.create(versionIndexFileLocation).toURL().openConnection(proxyConfiguration == null ? Proxy.NO_PROXY : proxyConfiguration.getProxy(versionIndexFileLocation));
					urlConnection.setRequestProperty("Pragma", "no-cache");
					try (JsonReader jsonReader = new JsonReader(new BufferedInputStream(urlConnection.getInputStream()))) {
						final JsonNode jsonNode = jsonReader.read();
						if (!jsonNode.isJsonObject()) {
							throw new Exception("Invalid github release data found at location '" + versionIndexFileLocation + "'");
						} else {
							final JsonObject jsonObject = (JsonObject) jsonNode;
							final String githubApplicationNameAndVersion = (String) jsonObject.getSimpleValue("name");
							if (!githubApplicationNameAndVersion.trim().startsWith(applicationName)) {
								throw new Exception("Invalid github release data found for application '" + applicationName + "' was '" + githubApplicationNameAndVersion + "'");
							}
							availableVersion = new Version((String) jsonObject.getSimpleValue("tag_name"));

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
								final String assetName = (String) assetObject.getSimpleValue("name");
								if (isApplicationJarAssetName(assetName)) {
									jarAssetDownloadUrl = (String) assetObject.getSimpleValue("browser_download_url");
								}
							}
							if (jarAssetDownloadUrl == null) {
								throw new Exception("Github release for application '" + applicationName + "' has no '.jar' asset");
							}
							updateFileLocation = jarAssetDownloadUrl;
						}
					} catch (final Exception e) {
						throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "':\n" + e.getMessage(), e);
					}
				} else {
					final URLConnection urlConnection = URI.create(versionIndexFileLocation).toURL().openConnection(proxyConfiguration == null ? Proxy.NO_PROXY : proxyConfiguration.getProxy(versionIndexFileLocation));
					urlConnection.setRequestProperty("Pragma", "no-cache");
					try (JsonReader jsonReader = new JsonReader(new BufferedInputStream(urlConnection.getInputStream()))) {
						final JsonNode jsonNode = jsonReader.read();
						if (!jsonNode.isJsonObject()) {
							throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "'");
						} else {
							final JsonObject jsonObject = (JsonObject) jsonNode;
							final JsonObject applicationJsonObject = (JsonObject) jsonObject.get(applicationName);
							if (applicationJsonObject == null) {
								throw new Exception("Version index not found at location '" + versionIndexFileLocation + "'");
							} else {
								try {
									availableVersion = new Version((String) applicationJsonObject.getSimpleValue("version"));
									updateFileLocation = (String) applicationJsonObject.getSimpleValue("downloadUrl");
								} catch (final Exception e) {
									throw new Exception("Invalid application version entry at location '" + versionIndexFileLocation + "'", e);
								}
							}
						}
					} catch (final Exception e) {
						throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "':\n" + e.getMessage(), e);
					}
				}
			} else if (versionIndexFileLocation.toLowerCase().startsWith("file://") && versionIndexFileLocation.toLowerCase().endsWith(".json")) {
				final URL versionIndexFileURL = URI.create(versionIndexFileLocation).toURL();
				try (JsonReader jsonReader = new JsonReader(versionIndexFileURL.openStream())) {
					final JsonNode jsonNode = jsonReader.read();
					if (!jsonNode.isJsonObject()) {
						throw new Exception("Invalid version index found at location '" + versionIndexFileLocation + "'");
					} else {
						final JsonObject jsonObject = (JsonObject) jsonNode;
						final JsonObject applicationJsonObject = (JsonObject) jsonObject.get(applicationName);
						if (applicationJsonObject == null) {
							throw new Exception("Version index not found at location '" + versionIndexFileLocation + "'");
						} else {
							try {
								availableVersion = new Version((String) applicationJsonObject.getSimpleValue("version"));
								updateFileLocation = (String) applicationJsonObject.getSimpleValue("downloadUrl");
							} catch (final Exception e) {
								throw new Exception("Invalid application version entry at location '" + versionIndexFileLocation + "'", e);
							}
						}
					}
				}
			}

			if (applicationVersion.compareTo(availableVersion) < 0 && updateFileLocation != null && updateFileLocation.trim().length() > 0) {
				updateFileLocation = replacePlaceholders(updateFileLocation);
				return new Tuple<>(availableVersion, updateFileLocation);
			} else {
				updateFileLocation = null;
				return null;
			}
		} catch (final Exception e) {
			throw e;
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
			final File restarterFile = new File(System.getProperty("java.io.tmpdir"), "Restarter_" + new File(jarFilePath).getName() +".jar");
			try (InputStream restarterInputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Restarter.jar")) {
				if (restarterInputStream == null) {
					throw new Exception("Restarter binary not available");
				}

				if (restarterFile.exists() && !restarterFile.delete()) {
					throw new Exception("Cannot remove leftover restarter file '" + restarterFile.getAbsolutePath() + "', it may still be in use");
				}
				try (FileOutputStream restarterOut = new FileOutputStream(restarterFile)) {
					IoUtilities.copy(restarterInputStream, restarterOut);
				}
			}

			String javaBinPath = SystemUtilities.getJavaBinPath();
			if (Utilities.isBlank(javaBinPath)) {
				if (SystemUtilities.isWindowsSystem()) {
					javaBinPath = "java.exe";
				} else {
					javaBinPath = "java";
				}
			}

			final List<String> restarterCommandList = new ArrayList<>();
			restarterCommandList.add(javaBinPath);
			restarterCommandList.add("-jar");
			restarterCommandList.add(restarterFile.getAbsolutePath());

			if (DEBUGGING_RESTARTER) {
				restarterCommandList.add("-log");
			}

			restarterCommandList.add(jarFilePath);

			if (jvmParameters != null && jvmParameters.size() > 0) {
				restarterCommandList.add("-jvmParameter");
				for (final String token : jvmParameters) {
					restarterCommandList.add(token);
				}
			}

			if (appParameters != null && appParameters.size() > 0) {
				restarterCommandList.add("-appParameter");
				for (final String token : appParameters) {
					restarterCommandList.add(token);
				}
			}

			final String[] restarterCommand = restarterCommandList.toArray(new String[0]);

			final ProcessBuilder restarterProcessBuilder = new ProcessBuilder(restarterCommand);
			restarterProcessBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			restarterProcessBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);

			restarterProcessBuilder.start();

			Runtime.getRuntime().exit(0);
		} catch (final Exception e) {
			System.err.println("Update error while updating to new version:\n" + e.getMessage());
			e.printStackTrace();
			return;
		}
	}

	private static boolean updateApplication(final ApplicationUpdateParent updateParent, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final String trustedCaCertificateFileNames, final String username, final char[] password, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate, final boolean allowUnsafeUpdates) {
		try {
			if (Utilities.isBlank(updateFileLocation)) {
				throw new Exception("Mandatory updateFileLocation is empty");
			}

			String jarFilePath = SystemUtilities.getCurrentlyRunningJarFilePath();

			if (jarFilePath == null) {
				try {
					final String currentJarUrlPath = updateParent.getClass().getResource(updateParent.getClass().getSimpleName() + ".class").toString();
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

			final File downloadTempFile = new File(new File(jarFilePath).getParent() + File.separator + "temp_" + new File(jarFilePath).getName());

			final boolean downloadSuccess = getNewApplicationVersionFile(updateParent, username, password, updateFileLocation, proxyConfiguration, downloadTempFile);
			if (downloadSuccess) {
				if (Utilities.isNotEmpty(trustedCaCertificateFileNames)) {
					final ClassLoader applicationClassLoader = updateParent.getClass().getClassLoader();
					if (applicationClassLoader == null) {
						showUpdateError(updateParent, "Update error:\nApplications classloader is not readable");
						return false;
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
							showUpdateError(updateParent, "Update error:\nTrusted CA certificate '" + trustedCaCertificateFileName + "' is not readable: " + e.getMessage());
							return false;
						}
					}

					if (trustedCertificates.size() == 0) {
						showUpdateError(updateParent, "Update error:\nTrusted CA certificate is missing");
						return false;
					} else if (!verifyJarSignature(downloadTempFile, trustedCertificates)) {
						showUpdateError(updateParent, "Update error:\nSignature of updatefile is invalid");
						return false;
					}
				} else if (!allowUnsafeUpdates) {
					showUpdateError(updateParent, "Update error:\nTrusted CA certificate file name is missing");
					return false;
				}

				final String[] updaterCommand;
				if (updateParent != null) {
					try (final InputStream updaterInputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Updater.jar")) {
						if (updaterInputStream != null) {
							final File updaterFile = new File(new File(jarFilePath).getParent(), "Updater_" + new File(jarFilePath).getName() + ".jar");
							if (updaterFile.exists() && !updaterFile.delete()) {
								throw new Exception("Cannot remove leftover updater file '" + updaterFile.getAbsolutePath() + "' from a previous update, it may still be in use");
							}
							try (FileOutputStream updaterOut = new FileOutputStream(updaterFile)) {
								IoUtilities.copy(updaterInputStream, updaterOut);
							}

							String javaBinPath = SystemUtilities.getJavaBinPath();
							if (Utilities.isBlank(javaBinPath)) {
								if (SystemUtilities.isWindowsSystem()) {
									javaBinPath = "java.exe";
								} else {
									javaBinPath = "java";
								}
							}

							final List<String> commandParts = new ArrayList<>(List.of(javaBinPath, "-jar", updaterFile.getAbsolutePath()));
							if (DEBUGGING_UPDATER) {
								commandParts.add("-log");
								commandParts.add("-stepwait");
							}
							commandParts.add(jarFilePath);
							commandParts.add(downloadTempFile.getAbsolutePath());
							if (!restartAfterUpdate) {
								commandParts.add("-norestart");
							}

							if (jvmParameters != null && jvmParameters.size() > 0) {
								commandParts.add("-jvmParameter");
								for (final String token : jvmParameters) {
									commandParts.add(token);
								}
							}

							if (appParameters != null && appParameters.size() > 0) {
								commandParts.add("-appParameter");
								for (final String token : appParameters) {
									commandParts.add(token);
								}
							}

							updaterCommand = commandParts.toArray(new String[0]);
						} else {
							updaterCommand = createUpdateBatchFile(jarFilePath, downloadTempFile, restartAfterUpdate, null);
						}
					}
				} else {
					updaterCommand = createUpdateBatchFile(jarFilePath, downloadTempFile, restartAfterUpdate, null);
				}

				if (updateParent != null) {
					updateParent.showUpdateDone(null, null, 0, null);
				}

				if (updaterCommand != null) {
					final ProcessBuilder restarterProcessBuilder = new ProcessBuilder(updaterCommand);
					restarterProcessBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
					restarterProcessBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
					restarterProcessBuilder.start();

					Runtime.getRuntime().exit(0);
				}

				return true;
			} else {
				return false;
			}
		} catch (final Exception e) {
			System.err.println("Update error while updating to new version:\n" + e.getMessage());
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
	 * @return true if the update was prepared (the method only returns if no updater could be started), false if the download failed
	 * @throws Exception if the download, the signature check or starting the updater failed
	 */
	public static boolean updateNonInteractiveApplication(final Class<? extends ApplicationUpdateParent> applicationClass, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final String trustedCaCertificateFileNames, final String username, final char[] password, final List<String> jvmParameters, final List<String> appParameters, final boolean restartAfterUpdate) throws Exception {
		try {
			if (Utilities.isBlank(updateFileLocation)) {
				throw new Exception("Mandatory updateFileLocation is empty");
			}

			String jarFilePath = SystemUtilities.getCurrentlyRunningJarFilePath();

			if (jarFilePath == null) {
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

			final File downloadTempFile = new File(new File(jarFilePath).getParent() + File.separator + "temp_" + new File(jarFilePath).getName());

			final boolean downloadSuccess = getNewNonInteractiveApplicationVersionFile(username, password, updateFileLocation, proxyConfiguration, downloadTempFile);
			if (downloadSuccess) {
				if (Utilities.isNotEmpty(trustedCaCertificateFileNames)) {
					final ClassLoader applicationClassLoader = applicationClass.getClassLoader();
					if (applicationClassLoader == null) {
						throw new Exception("Update error:\nApplications classloader is not readable");
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
							throw new Exception("Update error:\nTrusted CA certificate '" + trustedCaCertificateFileName + "' is not readable: " + e.getMessage());
						}
					}

					if (trustedCertificates.size() == 0) {
						throw new Exception("Update error:\nTrusted CA certificate is missing");
					} else if (!verifyJarSignature(downloadTempFile, trustedCertificates)) {
						throw new Exception("Update error:\nSignature of updatefile is invalid");
					}
				} else {
					throw new Exception("Update error:\nTrusted CA certificate file name is missing");
				}

				final String[] updaterCommand;
				File updaterFile = null;
				try (InputStream updaterInputStream = ApplicationUpdateUtilities.class.getClassLoader().getResourceAsStream("Updater.jar")) {
					if (updaterInputStream != null) {
						updaterFile = new File(new File(jarFilePath).getParent(), "Updater_" + new File(jarFilePath).getName() +".jar");
						if (updaterFile.exists() && !updaterFile.delete()) {
							throw new Exception("Cannot remove leftover updater file '" + updaterFile.getAbsolutePath() + "' from a previous update, it may still be in use");
						}
						try (FileOutputStream updaterOut = new FileOutputStream(updaterFile)) {
							IoUtilities.copy(updaterInputStream, updaterOut);
						}
					}
				}
				if (updaterFile != null) {
					String javaBinPath = SystemUtilities.getJavaBinPath();
					if (Utilities.isBlank(javaBinPath)) {
						if (SystemUtilities.isWindowsSystem()) {
							javaBinPath = "java.exe";
						} else {
							javaBinPath = "java";
						}
					}

					final List<String> commandParts = new ArrayList<>(List.of(javaBinPath, "-jar", updaterFile.getAbsolutePath()));
					if (DEBUGGING_UPDATER) {
						commandParts.add("-log");
						commandParts.add("-stepwait");
					}
					commandParts.add(jarFilePath);
					commandParts.add(downloadTempFile.getAbsolutePath());
					if (!restartAfterUpdate) {
						commandParts.add("-norestart");
					}

					if (jvmParameters != null && jvmParameters.size() > 0) {
						commandParts.add("-jvmParameter");
						for (final String token : jvmParameters) {
							commandParts.add(token);
						}
					}

					if (appParameters != null && appParameters.size() > 0) {
						commandParts.add("-appParameter");
						for (final String token : appParameters) {
							commandParts.add(token);
						}
					}

					updaterCommand = commandParts.toArray(new String[0]);
				} else {
					updaterCommand = createUpdateBatchFile(jarFilePath, downloadTempFile, restartAfterUpdate, null);
				}

				if (updaterCommand != null) {
					Runtime.getRuntime().exec(updaterCommand, null, null);
					Runtime.getRuntime().exit(0);
				}

				return true;
			} else {
				return false;
			}
		} catch (final Exception e) {
			throw new Exception("Update error while updating to new version:\n" + e.getMessage(), e);
		}
	}

	private static boolean getNewApplicationVersionFile(final ApplicationUpdateParent updateParent, final String username, final char[] password, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final File downloadTempFile) throws Exception {
		if (updateFileLocation.toLowerCase().startsWith("http://") || updateFileLocation.toLowerCase().startsWith("https://")) {
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
					if (updateParent != null) {
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
					} else {
						showUpdateError(updateParent, LangResources.get("usernameAndPasswordRequired"));
						return false;
					}
				} else if (downloadUrlWithCredentials.contains("<username>")) {
					if (updateParent != null) {
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
					} else {
						showUpdateError(updateParent, LangResources.get("usernameRequired"));
						return false;
					}
				} else if (downloadUrlWithCredentials.contains("<password>")) {
					if (updateParent != null) {
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
					} else {
						showUpdateError(updateParent, LangResources.get("passwordRequired"));
						return false;
					}
				}

				retryDownload = false;
				try {
					if (downloadTempFile.exists()) {
						downloadTempFile.delete();
					}
					final boolean success = downloadUpdateFile(updateParent, downloadTempFile, downloadUrlWithCredentials, proxyConfiguration);
					if (!success) {
						// download stopped by user
						showUpdateError(updateParent, LangResources.get("canceledByUser"));
						return false;
					}
				} catch (final UserError e) {
					if (updateParent != null) {
						updateParent.showUpdateError("Update error:\n" + e.getMessage());

						if (e.getReason() == Reason.UnauthenticatedOrUnauthorized) {
							if (updateFileLocation.contains("<username>") || updateFileLocation.contains("<password>")) {
								retryDownload = true;
							} else {
								System.err.println("Update error:\nAuthentication error");
								return false;
							}
						} else {
							return false;
						}
					} else {
						System.err.println("Update error while downloading new version:\n" + e.getMessage());
						return false;
					}
				} catch (final Exception e) {
					if (e instanceof ExecutionException && e.getCause() instanceof UserError) {
						if (updateParent != null) {
							updateParent.showUpdateError("Update error:\n" + e.getCause().getMessage());

							if (((UserError) e.getCause()).getReason() == Reason.UnauthenticatedOrUnauthorized) {
								if (updateFileLocation.contains("<username>") || updateFileLocation.contains("<password>")) {
									retryDownload = true;
								} else {
									System.err.println("Update error:\nAuthentication error");
									return false;
								}
							} else {
								return false;
							}
						} else {
							System.err.println("Update error while downloading new version:\n" + e.getCause().getMessage());
							return false;
						}
					} else {
						showUpdateError(updateParent, "Update error:\n" + e.getMessage());
						return false;
					}
				}

				firstRequest = false;
			}
		} else if (updateFileLocation.toLowerCase().startsWith("file://")) {
			if (downloadTempFile.exists()) {
				downloadTempFile.delete();
			}
			final boolean success = copyUpdateFile(updateParent, downloadTempFile, updateFileLocation);
			if (!success) {
				// download stopped by user
				showUpdateError(updateParent, LangResources.get("canceledByUser"));
				return false;
			}
		} else {
			// Copy file
			if (downloadTempFile.exists()) {
				downloadTempFile.delete();
			}
			final boolean success = copyUpdateFile(updateParent, downloadTempFile, updateFileLocation);
			if (!success) {
				// download stopped by user
				showUpdateError(updateParent, LangResources.get("canceledByUser"));
				return false;
			}
		}

		return downloadTempFile.exists();
	}

	private static boolean getNewNonInteractiveApplicationVersionFile(final String username, final char[] password, final String updateFileLocation, final ProxyConfiguration proxyConfiguration, final File downloadTempFile) throws Exception {
		if (updateFileLocation.toLowerCase().startsWith("http")) {
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
				if (downloadTempFile.exists()) {
					downloadTempFile.delete();
				}
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
		} else if (updateFileLocation.toLowerCase().startsWith("file://")) {
			if (downloadTempFile.exists()) {
				downloadTempFile.delete();
			}
			final boolean success = copyUpdateFile(null, downloadTempFile, updateFileLocation);
			if (!success) {
				throw new Exception("Download failed");
			}
		} else {
			// Copy file
			if (downloadTempFile.exists()) {
				downloadTempFile.delete();
			}
			final boolean success = copyUpdateFile(null, downloadTempFile, updateFileLocation);
			if (!success) {
				throw new Exception("Download failed");
			}
		}

		return downloadTempFile.exists();
	}

	private static String[] createUpdateBatchFile(final String jarFilePath, final File downloadTempFile, final boolean restartApplicationAfterUpdate, final String appParameter) throws Exception {
		String javaBinPath = SystemUtilities.getJavaBinPath();
		if (Utilities.isBlank(javaBinPath)) {
			if (SystemUtilities.isWindowsSystem()) {
				javaBinPath = "java.exe";
			} else {
				javaBinPath = "java";
			}
		}
		if (SystemUtilities.isWindowsSystem()) {
			final File batchFile = new File(new File(jarFilePath).getParent() + File.separator + "batchUpdate_" + new File(jarFilePath).getName() + ".cmd");
			writeFile(batchFile,
					"@echo off\r\n"
							+ "del \"" + jarFilePath + "\"\r\n"
							+ "if exist \"" + jarFilePath + "\" (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del \"" + jarFilePath + "\"\r\n"
							+ "if exist \"" + jarFilePath + "\" (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del \"" + jarFilePath + "\"\r\n"
							+ "if exist \"" + jarFilePath + "\" (\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "del \"" + jarFilePath + "\"\r\n"
							+ ")\r\n"
							+ ")\r\n"
							+ ")\r\n"
							+ "ren \"" + downloadTempFile.getAbsolutePath() + "\" \"" + new File(jarFilePath).getName() + "\"\r\n"
							+ "if not exist \"" + downloadTempFile.getAbsolutePath() + "\" (\r\n"
							+ (restartApplicationAfterUpdate ? "\"" + javaBinPath + "\" -jar \"" + jarFilePath + "\"" + (appParameter != null ? " " + appParameter : "") + "\r\n" : "")
							+ "del \"" + batchFile.getAbsolutePath() + "\"\r\n"
							+ ")\r\n");

			return new String[]{ "cmd", "/c", "start", "/B", batchFile.getAbsolutePath() };
		} else if (SystemUtilities.isLinuxSystem()) {
			final File batchFile = new File(new File(jarFilePath).getParent() + File.separator + "batchUpdate_" + new File(jarFilePath).getName() + ".sh");
			writeFile(batchFile,
					"#!/bin/bash\n"
							+ "rm \"" + jarFilePath + "\"\n"
							+ "if [ -f \"" + jarFilePath + "\" ]; then\n"
							+ "sleep 3\n"
							+ "rm \"" + jarFilePath + "\"\n"
							+ "if [ -f \"" + jarFilePath + "\" ]; then\n"
							+ "sleep 3\n"
							+ "rm \"" + jarFilePath + "\"\n"
							+ "if [ -f \"" + jarFilePath + "\" ]; then\n"
							+ "sleep 3\n"
							+ "rm \"" + jarFilePath + "\"\n"
							+ "fi\n"
							+ "fi\n"
							+ "fi\n"
							+ "mv \"" + downloadTempFile.getAbsolutePath() + "\" \"" + jarFilePath + "\"\n"
							+ "if ! [ -f \"" + downloadTempFile.getAbsolutePath() + "\" ]; then\n"
							+ (restartApplicationAfterUpdate ? "\"" + javaBinPath + "\" -jar \"" + jarFilePath + "\"" + (appParameter != null ? " " + appParameter : "") + "\n": "")
							+ "rm \"" + batchFile.getAbsolutePath() + "\"\n"
							+ "fi\n");

			return new String[]{ "sh", batchFile.getAbsolutePath() };
		} else {
			return null;
		}
	}

	/**
	 * Creates a batch file (Windows) or shell script (Linux) next to the application jar, which waits some seconds,
	 * starts the application again and deletes itself.
	 *
	 * @param jarFilePath path of the application jar to restart
	 * @param appParameter application parameters as one String, may be null
	 * @return command to execute the created file, or null if the operating system is not supported
	 * @throws Exception if the file cannot be written
	 */
	public static String[] createRestartBatchFile(final String jarFilePath, final String appParameter) throws Exception {
		String javaBinPath = SystemUtilities.getJavaBinPath();
		if (Utilities.isBlank(javaBinPath)) {
			if (SystemUtilities.isWindowsSystem()) {
				javaBinPath = "java.exe";
			} else {
				javaBinPath = "java";
			}
		}
		if (SystemUtilities.isWindowsSystem()) {
			final File batchFile = new File(new File(jarFilePath).getParent() + File.separator + "restart_" + new File(jarFilePath).getName() + ".cmd");
			if (batchFile.exists()) {
				batchFile.delete();
			}
			writeFile(batchFile,
					"@echo off\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "ping -n 3 127.0.0.1 >nul\r\n"
							+ "\"" + javaBinPath + "\" -jar \"" + jarFilePath + "\"" + (appParameter != null ? " " + appParameter : "") + "\r\n"
							+ "del \"" + batchFile.getAbsolutePath() + "\"\r\n");

			return new String[]{ "cmd", "/c", "start", "/B", batchFile.getAbsolutePath() };
		} else if (SystemUtilities.isLinuxSystem()) {
			final File batchFile = new File(new File(jarFilePath).getParent() + File.separator + "restart_" + new File(jarFilePath).getName() + ".sh");
			if (batchFile.exists()) {
				batchFile.delete();
			}
			writeFile(batchFile,
					"#!/bin/bash\n"
							+ "sleep 10\n"
							+ "\"" + javaBinPath + "\" -jar \"" + jarFilePath + "\"" + (appParameter != null ? " " + appParameter : "") + "\n"
							+ "rm \"" + batchFile.getAbsolutePath() + "\"\n");

			return new String[]{ "sh", batchFile.getAbsolutePath() };
		} else {
			return null;
		}
	}

	private static String replacePlaceholders(String value) {
		if (value == null) {
			return null;
		} else {
			if (value.contains("<system>")) {
				if (SystemUtilities.isWindowsSystem()) {
					value = value.replace("<system>", "windows");
				} else if (SystemUtilities.isLinuxSystem()) {
					value = value.replace("<system>", "linux");
				}
			}

			if (value.contains("<bitmode>")) {
				if (System.getProperty("os.arch") != null && System.getProperty("os.arch").contains("64")) {
					value = value.replace("<bitmode>", "64");
				} else {
					value = value.replace("<bitmode>", "32");
				}
			}

			if (value.contains("<time_seconds>")) {
				value = value.replace("<time_seconds>", "" + (LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)));
			}

			return value;
		}
	}

	private static void writeFile(final File file, final String string) throws Exception {
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(string.getBytes(StandardCharsets.UTF_8));
		} catch (final Exception e) {
			throw new Exception("Cannot write file " + file.getAbsolutePath(), e);
		}
	}

	private static boolean downloadUpdateFile(final ApplicationUpdateParent updateParent, final File downloadTempFile, final String downloadUrl, final ProxyConfiguration proxyConfiguration) throws Exception {
		try {
			final WorkerParentSimple workerParentAdapter = new WorkerParentSimple() {
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
			final FileDownloadWorker fileDownloadWorker = new FileDownloadWorker(workerParentAdapter, downloadUrl, downloadTempFile).setProxyConfiguration(proxyConfiguration);
			new Thread(fileDownloadWorker).start();

			if (updateParent != null) {
				updateParent.showUpdateDownloadStart(fileDownloadWorker);
			}

			while (!fileDownloadWorker.isDone()) {
				// Wait for download process
				Thread.sleep(1000);
			}

			if (fileDownloadWorker.isCancelled()) {
				return false;
			}

			if (!fileDownloadWorker.get()) {
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

	private static boolean copyUpdateFile(final ApplicationUpdateParent updateParent, final File downloadTempFile, final String downloadFile) throws Exception {
		try {
			final WorkerParentSimple workerParentAdapter = new WorkerParentSimple() {
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

			final FileCopyWorker fileCopyWorker = new FileCopyWorker(workerParentAdapter, downloadFile, downloadTempFile);
			new Thread(fileCopyWorker).start();

			if (updateParent != null) {
				updateParent.showUpdateDownloadStart(fileCopyWorker);
			}

			while (!fileCopyWorker.isDone()) {
				// Wait for download process
				Thread.sleep(1000);
			}

			if (fileCopyWorker.isCancelled()) {
				return false;
			}

			if (!fileCopyWorker.get()) {
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
	 * trusted certificates. Revocation is not checked.
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
				}
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
	 * Deletes the updater and restarter jar files left over from a previous update or restart of the running application.
	 */
	public static void removeUpdateLeftovers() {
		final String jarFilePath = SystemUtilities.getCurrentlyRunningJarFilePath();
		if (Utilities.isNotBlank(jarFilePath)) {
			final File updaterFile = new File(new File(jarFilePath).getParent(), "Updater_"+ new File(jarFilePath).getName() +".jar");
			if (updaterFile.exists() && !updaterFile.delete()) {
				System.err.println("Cannot remove leftover updater file '" + updaterFile.getAbsolutePath() + "'");
			}
			final File restarterFile = new File(System.getProperty("java.io.tmpdir"), "Restarter_" + new File(jarFilePath).getName() +".jar");
			if (restarterFile.exists() && !restarterFile.delete()) {
				System.err.println("Cannot remove leftover restarter file '" + restarterFile.getAbsolutePath() + "'");
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
		final String assetNameLowerCase = assetName.toLowerCase();
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

package de.soderer.applicationbase;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Creates test certificates with the JDK's "keytool" and signs jar files with "jarsigner".
 * <ul>
 * <li>"ca": self-signed CA certificate</li>
 * <li>"signer": certificate issued by "ca"</li>
 * <li>"other": self-signed certificate, not related to "ca"</li>
 * <li>"expired": self-signed certificate, which is expired</li>
 * </ul>
 */
public class TestJarSigner {
	private static final String STORE_PASSWORD = "changeit";

	private final File directory;
	private final File keystoreFile;

	public TestJarSigner(final File directory) throws Exception {
		this.directory = directory;
		keystoreFile = new File(directory, "test.p12");

		keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test CA", "-ext", "bc:c", "-validity", "3650");
		keytool("-genkeypair", "-alias", "signer", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test Signer", "-validity", "3650");
		keytool("-certreq", "-alias", "signer", "-file", new File(directory, "signer.csr").getAbsolutePath());
		keytool("-gencert", "-alias", "ca", "-infile", new File(directory, "signer.csr").getAbsolutePath(), "-outfile", new File(directory, "signer.cer").getAbsolutePath(), "-validity", "3650");
		keytool("-importcert", "-noprompt", "-alias", "signer", "-file", new File(directory, "signer.cer").getAbsolutePath());
		keytool("-genkeypair", "-alias", "other", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Other", "-validity", "3650");
		keytool("-genkeypair", "-alias", "expired", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Expired", "-startdate", "-2y", "-validity", "30");

		for (final String alias : new String[] { "ca", "signer", "other", "expired" }) {
			keytool("-exportcert", "-rfc", "-alias", alias, "-file", getCertificateFile(alias).getAbsolutePath());
		}
	}

	/**
	 * Checks whether keytool and jarsigner are available (JDK, not only a JRE).
	 */
	public static boolean isAvailable() {
		return getJdkTool("keytool").canExecute() && getJdkTool("jarsigner").canExecute();
	}

	private static File getJdkTool(final String toolName) {
		final File binDirectory = new File(System.getProperty("java.home"), "bin");
		final File tool = new File(binDirectory, toolName);
		if (tool.exists()) {
			return tool;
		} else {
			return new File(binDirectory, toolName + ".exe");
		}
	}

	/**
	 * PEM file of the certificate of an alias.
	 */
	public File getCertificateFile(final String alias) {
		return new File(directory, alias + ".pem");
	}

	/**
	 * Certificate of an alias.
	 */
	public Certificate getCertificate(final String alias) throws Exception {
		try (InputStream inputStream = new FileInputStream(getCertificateFile(alias))) {
			return CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
		}
	}

	/**
	 * Signs a jar file with the key of an alias into a new jar file.
	 */
	public File sign(final File unsignedJar, final File signedJar, final String alias) throws Exception {
		execute(getJdkTool("jarsigner").getAbsolutePath(), "-keystore", keystoreFile.getAbsolutePath(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD,
				"-signedjar", signedJar.getAbsolutePath(), unsignedJar.getAbsolutePath(), alias);
		return signedJar;
	}

	private void keytool(final String... arguments) throws Exception {
		final List<String> command = new ArrayList<>();
		command.add(getJdkTool("keytool").getAbsolutePath());
		command.addAll(Arrays.asList(arguments));
		command.addAll(Arrays.asList("-keystore", keystoreFile.getAbsolutePath(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD));
		execute(command.toArray(new String[0]));
	}

	private void execute(final String... command) throws Exception {
		final ProcessBuilder processBuilder = new ProcessBuilder(command);
		processBuilder.directory(directory);
		processBuilder.redirectErrorStream(true);
		final Process process = processBuilder.start();
		process.getOutputStream().close();
		final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(60, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new Exception("Timeout of command: " + String.join(" ", command));
		} else if (process.exitValue() != 0) {
			throw new Exception("Command failed (" + process.exitValue() + "): " + String.join(" ", command) + "\n" + output);
		}
	}
}

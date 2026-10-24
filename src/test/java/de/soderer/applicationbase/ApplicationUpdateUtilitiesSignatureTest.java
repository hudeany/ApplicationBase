package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests of the jar signature check of {@link ApplicationUpdateUtilities}. The certificates are created with the JDK's
 * keytool and the jars are signed with jarsigner, so these tests are skipped on a plain JRE.
 */
@SuppressWarnings("static-method")
public class ApplicationUpdateUtilitiesSignatureTest {
	static {
		// No test opens a window, unless the GUI tests are enabled explicitly
		TestHelper.configureHeadless();
	}

	private static File tempDirectory;
	private static TestJarSigner jarSigner;
	private static File unsignedJar;
	private static File signedJar;

	@BeforeAll
	public static void setUpCertificates() throws Exception {
		tempDirectory = TestHelper.createTempDirectory("ApplicationUpdateUtilitiesSignatureTest");
		if (TestJarSigner.isAvailable()) {
			jarSigner = new TestJarSigner(tempDirectory);
			final Map<String, String> entries = new LinkedHashMap<>();
			entries.put("de/", "");
			entries.put("de/test/", "");
			entries.put("de/test/Main.txt", "main");
			entries.put("META-INF/versions/9/de/test/Main.txt", "versioned");
			unsignedJar = TestHelper.createJar(new File(tempDirectory, "unsigned.jar"), true, entries);
			signedJar = jarSigner.sign(unsignedJar, new File(tempDirectory, "signed.jar"), "signer");
		}
	}

	@AfterAll
	public static void tearDownCertificates() {
		TestHelper.deleteRecursively(tempDirectory);
	}

	@BeforeEach
	public void checkJarSigner() {
		assumeTrue(jarSigner != null, "keytool and jarsigner of a JDK are needed");
	}

	private static List<Certificate> certificates(final String... aliases) throws Exception {
		final List<Certificate> certificates = new ArrayList<>();
		for (final String alias : aliases) {
			certificates.add(jarSigner.getCertificate(alias));
		}
		return certificates;
	}

	@Test
	public void testNoTrustedCertificates() {
		assertEquals("No trusted certificates given", ApplicationUpdateUtilities.getJarSignatureProblem(signedJar, null));
		assertEquals("No trusted certificates given", ApplicationUpdateUtilities.getJarSignatureProblem(signedJar, Collections.emptyList()));
		assertFalse(ApplicationUpdateUtilities.verifyJarSignature(signedJar, null));
	}

	@Test
	public void testSignedByTrustedCa() throws Exception {
		assertNull(ApplicationUpdateUtilities.getJarSignatureProblem(signedJar, certificates("ca")));
		assertTrue(ApplicationUpdateUtilities.verifyJarSignature(signedJar, certificates("ca")));
		// Additional untrusted certificates do not matter
		assertTrue(ApplicationUpdateUtilities.verifyJarSignature(signedJar, certificates("other", "ca")));
	}

	@Test
	public void testSignerCertificateTrustedDirectly() throws Exception {
		assertNull(ApplicationUpdateUtilities.getJarSignatureProblem(signedJar, certificates("signer")));
	}

	@Test
	public void testSignedByUntrustedCertificate() throws Exception {
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(signedJar, certificates("other"));
		assertTrue(problem.startsWith("The jar file contains untrusted signed files"), problem);
		assertTrue(problem.contains("is not trusted"), problem);
		assertFalse(ApplicationUpdateUtilities.verifyJarSignature(signedJar, certificates("other")));
	}

	@Test
	public void testSignedWithExpiredCertificate() throws Exception {
		final File expiredJar = jarSigner.sign(unsignedJar, new File(tempDirectory, "expired.jar"), "expired");
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(expiredJar, certificates("expired"));
		assertTrue(problem.contains("certificate is not valid at"), problem);

		final String problemWithCa = ApplicationUpdateUtilities.getJarSignatureProblem(expiredJar, certificates("ca"));
		assertTrue(problemWithCa.contains("is not trusted"), problemWithCa);
	}

	@Test
	public void testUnsignedJar() throws Exception {
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(unsignedJar, certificates("ca"));
		assertTrue(problem.startsWith("The jar file contains unsigned files"), problem);
	}

	@Test
	public void testJarWithoutManifest() throws Exception {
		final File jarWithoutManifest = TestHelper.createJar(new File(tempDirectory, "nomanifest.jar"), false, Map.of("a.txt", "a"));
		assertEquals("The jar file has no manifest, which contains the file signatures", ApplicationUpdateUtilities.getJarSignatureProblem(jarWithoutManifest, certificates("ca")));
	}

	@Test
	public void testJarWithoutFiles() throws Exception {
		final File jarWithoutFiles = TestHelper.createJar(new File(tempDirectory, "nofiles.jar"), true, Map.of("emptydirectory/", ""));
		assertEquals("The jar file contains no signed files", ApplicationUpdateUtilities.getJarSignatureProblem(jarWithoutFiles, certificates("ca")));
	}

	@Test
	public void testMissingJarFile() throws Exception {
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(new File(tempDirectory, "missing.jar"), certificates("ca"));
		assertTrue(problem != null && problem.length() > 0);
	}

	@Test
	public void testInvalidJarFile() throws Exception {
		final File invalidJar = TestHelper.writeTextFile(new File(tempDirectory, "invalid.jar"), "no zip data");
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(invalidJar, certificates("ca"));
		assertTrue(problem != null && problem.length() > 0);
	}

	@Test
	public void testManipulatedSignedJar() throws Exception {
		final File manipulatedJar = copyJar(signedJar, new File(tempDirectory, "manipulated.jar"), "de/test/Main.txt", "manipulated", null);
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(manipulatedJar, certificates("ca"));
		assertTrue(problem.startsWith("SecurityException"), problem);
	}

	@Test
	public void testSignedJarWithAddedUnsignedFile() throws Exception {
		final File extendedJar = copyJar(signedJar, new File(tempDirectory, "extended.jar"), null, null, "de/test/Injected.txt");
		final String problem = ApplicationUpdateUtilities.getJarSignatureProblem(extendedJar, certificates("ca"));
		assertTrue(problem.startsWith("The jar file contains unsigned files"), problem);
		assertTrue(problem.contains("de/test/Injected.txt"), problem);
	}

	/**
	 * Copies a jar, optionally replacing the content of one entry and adding a new entry.
	 */
	private static File copyJar(final File sourceJar, final File targetJar, final String replacedEntryName, final String replacedContent, final String addedEntryName) throws Exception {
		try (JarFile jarFile = new JarFile(sourceJar, false);
				OutputStream outputStream = Files.newOutputStream(targetJar.toPath());
				JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
			final Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				final JarEntry entry = entries.nextElement();
				jarOutputStream.putNextEntry(new JarEntry(entry.getName()));
				if (entry.getName().equals(replacedEntryName)) {
					jarOutputStream.write(replacedContent.getBytes(StandardCharsets.UTF_8));
				} else {
					try (InputStream inputStream = jarFile.getInputStream(entry)) {
						inputStream.transferTo(jarOutputStream);
					}
				}
				jarOutputStream.closeEntry();
			}
			if (addedEntryName != null) {
				jarOutputStream.putNextEntry(new JarEntry(addedEntryName));
				jarOutputStream.write("injected".getBytes(StandardCharsets.UTF_8));
				jarOutputStream.closeEntry();
			}
		}
		return targetJar;
	}

	private static void verifyUpdateFile(final File updateFile, final String trustedCaCertificateFileNames, final ClassLoader classLoader) throws Exception {
		TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "verifyUpdateFile", new Class<?>[] { File.class, String.class, ClassLoader.class }, updateFile, trustedCaCertificateFileNames, classLoader);
	}

	@Test
	public void testVerifyUpdateFile() throws Exception {
		final File resourceDirectory = new File(tempDirectory, "resources");
		resourceDirectory.mkdirs();
		Files.copy(jarSigner.getCertificateFile("ca").toPath(), new File(resourceDirectory, "ca.pem").toPath());
		Files.copy(jarSigner.getCertificateFile("other").toPath(), new File(resourceDirectory, "other.pem").toPath());
		TestHelper.writeTextFile(new File(resourceDirectory, "invalid.pem"), "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----\n");

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { resourceDirectory.toURI().toURL() }, null)) {
			// Without trusted certificates no check is done (unsafe updates were allowed before)
			verifyUpdateFile(unsignedJar, null, classLoader);
			verifyUpdateFile(unsignedJar, "", classLoader);

			verifyUpdateFile(signedJar, "ca.pem", classLoader);
			verifyUpdateFile(signedJar, "other.pem, ca.pem, missing.pem", classLoader);

			final Exception untrustedException = assertThrows(Exception.class, () -> verifyUpdateFile(signedJar, "other.pem", classLoader));
			assertTrue(untrustedException.getMessage().startsWith("Signature of updatefile is invalid"), untrustedException.getMessage());

			final Exception unsignedException = assertThrows(Exception.class, () -> verifyUpdateFile(unsignedJar, "ca.pem", classLoader));
			assertTrue(unsignedException.getMessage().startsWith("Signature of updatefile is invalid"), unsignedException.getMessage());

			assertEquals("Trusted CA certificate is missing", assertThrows(Exception.class, () -> verifyUpdateFile(signedJar, "missing.pem", classLoader)).getMessage());

			final Exception invalidException = assertThrows(Exception.class, () -> verifyUpdateFile(signedJar, "invalid.pem", classLoader));
			assertTrue(invalidException.getMessage().startsWith("Trusted CA certificate 'invalid.pem' is not readable"), invalidException.getMessage());
		}
	}

	private static boolean isSignatureRelatedEntry(final String entryName) throws Exception {
		return (Boolean) TestHelper.invokeStatic(ApplicationUpdateUtilities.class, "isSignatureRelatedEntry", new Class<?>[] { String.class }, entryName);
	}

	@Test
	public void testIsSignatureRelatedEntry() throws Exception {
		assertTrue(isSignatureRelatedEntry("META-INF/MANIFEST.MF"));
		assertTrue(isSignatureRelatedEntry("meta-inf/manifest.mf"));
		assertTrue(isSignatureRelatedEntry("META-INF/INDEX.LIST"));
		assertTrue(isSignatureRelatedEntry("META-INF/SIGNER.SF"));
		assertTrue(isSignatureRelatedEntry("META-INF/SIGNER.DSA"));
		assertTrue(isSignatureRelatedEntry("META-INF/SIGNER.RSA"));
		assertTrue(isSignatureRelatedEntry("META-INF/SIGNER.EC"));
		assertTrue(isSignatureRelatedEntry("META-INF/SIG-TEST"));

		assertFalse(isSignatureRelatedEntry("META-INF/versions/9/Test.SF"));
		assertFalse(isSignatureRelatedEntry("META-INF/services/de.test.Service"));
		assertFalse(isSignatureRelatedEntry("META-INF/LICENSE.txt"));
		assertFalse(isSignatureRelatedEntry("de/test/MANIFEST.MF"));
		assertFalse(isSignatureRelatedEntry("Test.RSA"));
	}
}

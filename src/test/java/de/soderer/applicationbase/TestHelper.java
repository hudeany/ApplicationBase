package de.soderer.applicationbase;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Common helpers for the tests of this package: temporary files, simple jar files and access to private members.
 */
public final class TestHelper {
	private TestHelper() {
		// Utility class with static methods only
	}

	/**
	 * Creates a new temporary directory.
	 */
	public static File createTempDirectory(final String prefix) throws IOException {
		return Files.createTempDirectory(prefix).toFile();
	}

	/**
	 * Deletes a file or a directory with all its content. Errors are ignored.
	 */
	public static void deleteRecursively(final File file) {
		if (file == null || !file.exists()) {
			return;
		}
		try {
			Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(final Path path, final BasicFileAttributes attributes) throws IOException {
					Files.deleteIfExists(path);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(final Path directory, final IOException exception) throws IOException {
					Files.deleteIfExists(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (@SuppressWarnings("unused") final IOException e) {
			// Best effort cleanup only
		}
	}

	/**
	 * Writes a text file in UTF-8.
	 */
	public static File writeTextFile(final File file, final String content) throws IOException {
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/**
	 * Reads a text file in UTF-8.
	 */
	public static String readTextFile(final File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	/**
	 * Creates a jar file with a manifest (optional) and the given entries (entry name to content).
	 */
	public static File createJar(final File jarFile, final boolean withManifest, final Map<String, String> entries) throws IOException {
		try (OutputStream outputStream = Files.newOutputStream(jarFile.toPath());
				JarOutputStream jarOutputStream = withManifest ? new JarOutputStream(outputStream, createManifest()) : new JarOutputStream(outputStream)) {
			for (final Map.Entry<String, String> entry : entries.entrySet()) {
				jarOutputStream.putNextEntry(new JarEntry(entry.getKey()));
				if (!entry.getKey().endsWith("/")) {
					jarOutputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				}
				jarOutputStream.closeEntry();
			}
		}
		return jarFile;
	}

	private static Manifest createManifest() {
		final Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		return manifest;
	}

	/**
	 * Calls a private static method of a class. Exceptions thrown by the method itself are passed through unchanged.
	 */
	public static Object invokeStatic(final Class<?> classType, final String methodName, final Class<?>[] parameterTypes, final Object... arguments) throws Exception {
		final Method method = classType.getDeclaredMethod(methodName, parameterTypes);
		method.setAccessible(true);
		return invoke(method, null, arguments);
	}

	/**
	 * Calls a private method of an object. Exceptions thrown by the method itself are passed through unchanged.
	 */
	public static Object invokeMethod(final Object object, final String methodName, final Class<?>[] parameterTypes, final Object... arguments) throws Exception {
		Class<?> classType = object.getClass();
		while (classType != null) {
			try {
				final Method method = classType.getDeclaredMethod(methodName, parameterTypes);
				method.setAccessible(true);
				return invoke(method, object, arguments);
			} catch (@SuppressWarnings("unused") final NoSuchMethodException e) {
				classType = classType.getSuperclass();
			}
		}
		throw new NoSuchMethodException(methodName);
	}

	private static Object invoke(final Method method, final Object object, final Object... arguments) throws Exception {
		try {
			return method.invoke(object, arguments);
		} catch (final InvocationTargetException e) {
			if (e.getCause() instanceof Exception) {
				throw (Exception) e.getCause();
			} else if (e.getCause() instanceof Error) {
				throw (Error) e.getCause();
			} else {
				throw e;
			}
		}
	}

	/**
	 * Reads a private field of an object (also declared in a super class).
	 */
	public static Object getField(final Object object, final String fieldName) throws Exception {
		Class<?> classType = object.getClass();
		while (classType != null) {
			try {
				final Field field = classType.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field.get(object);
			} catch (@SuppressWarnings("unused") final NoSuchFieldException e) {
				classType = classType.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}

	/**
	 * Sets a private field of an object (also declared in a super class).
	 */
	public static void setField(final Object object, final String fieldName, final Object value) throws Exception {
		Class<?> classType = object.getClass();
		while (classType != null) {
			try {
				final Field field = classType.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(object, value);
				return;
			} catch (@SuppressWarnings("unused") final NoSuchFieldException e) {
				classType = classType.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}

	/**
	 * System property to enable the GUI tests (they need a display): -Dgui.tests=true
	 */
	public static final String GUI_TESTS_PROPERTY = "gui.tests";

	/**
	 * Forces the AWT headless mode, unless the GUI tests are enabled explicitly, so no test ever opens a window.
	 * Must be called before AWT is initialized, so every test class calls it in its static initializer.
	 */
	public static void configureHeadless() {
		if (!isGuiTestsEnabled()) {
			System.setProperty("java.awt.headless", "true");
		}
	}

	/**
	 * Whether the GUI tests are enabled by the system property {@link #GUI_TESTS_PROPERTY}.
	 */
	public static boolean isGuiTestsEnabled() {
		return Boolean.getBoolean(GUI_TESTS_PROPERTY);
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
	}

	public static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}
}

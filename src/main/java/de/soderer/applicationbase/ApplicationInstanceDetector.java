package de.soderer.applicationbase;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Detects other running processes of the operating system, which use an application jar file (e.g. the same
 * application started twice in different windows), by the command lines of the running processes. No files are
 * created for this.
 * <p>
 * On Linux and macOS the command lines are taken from {@link ProcessHandle}. On Windows ProcessHandle does not
 * provide the arguments of processes, so they are queried via PowerShell (Win32_Process) there, which only shows
 * the command lines of processes of the current user (without administrator rights).
 * </p>
 * <p>
 * A process uses the jar file, if one of its arguments (or one entry of a class path argument) denotes the jar file.
 * Relative paths are resolved against the working directory of the process on Linux. Where the working directory is
 * unknown (macOS, Windows), a relative path matches if it has the same file name as the jar file.
 * </p>
 */
public final class ApplicationInstanceDetector {
	/** Maximum time to wait for the process list on Windows */
	private static final long PROCESS_LIST_TIMEOUT_SECONDS = 30;

	private ApplicationInstanceDetector() {
		// Utility class with static methods only
	}

	/**
	 * Returns the ids of all other processes using the given jar file. Processes whose command line contains a jar
	 * file with one of the given name prefixes are ignored (e.g. the updater or restarter of the application, which
	 * get the application jar path as argument).
	 *
	 * @param jarFilePath path of the application jar
	 * @param ignoredJarFileNamePrefixes name prefixes of jar files whose processes are ignored, may be empty
	 * @return ids of other processes using the jar file, empty if there are none or the processes cannot be determined
	 */
	public static List<Long> getOtherProcessesUsingJarFile(final String jarFilePath, final String... ignoredJarFileNamePrefixes) {
		final List<Long> processIds = new ArrayList<>();
		if (jarFilePath == null) {
			return processIds;
		}

		final File jarFile;
		try {
			jarFile = new File(jarFilePath).getCanonicalFile();
		} catch (@SuppressWarnings("unused") final Exception e) {
			return processIds;
		}

		final long ownProcessId = ProcessHandle.current().pid();
		for (final ProcessCommandLine process : getProcessCommandLines()) {
			if (process.processId != ownProcessId
					&& !containsIgnoredJarFile(process.arguments, ignoredJarFileNamePrefixes)
					&& usesJarFile(process.arguments, process.workingDirectory, jarFile)) {
				processIds.add(process.processId);
			}
		}
		return processIds;
	}

	/**
	 * Checks whether one of the arguments (or one entry of a class path argument) denotes the jar file.
	 *
	 * @param arguments arguments of the process, the first one may be the executable
	 * @param workingDirectory working directory of the process, null if unknown
	 * @param jarFile canonical jar file
	 * @return true if the jar file is used
	 */
	static boolean usesJarFile(final List<String> arguments, final Path workingDirectory, final File jarFile) {
		for (final String argument : arguments) {
			for (final String argumentPart : argument.split(File.pathSeparator)) {
				if (denotesJarFile(argumentPart, workingDirectory, jarFile)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean denotesJarFile(final String pathString, final Path workingDirectory, final File jarFile) {
		if (pathString.isEmpty() || !pathString.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			return false;
		}

		final Path path;
		try {
			path = Paths.get(pathString);
		} catch (@SuppressWarnings("unused") final InvalidPathException e) {
			return false;
		}

		final Path absolutePath;
		if (path.isAbsolute()) {
			absolutePath = path;
		} else if (workingDirectory != null) {
			absolutePath = workingDirectory.resolve(path);
		} else {
			// Working directory unknown: the file name is the only available criterion
			return path.getFileName() != null && isSameFileName(path.getFileName().toString(), jarFile.getName());
		}

		try {
			return Files.isSameFile(absolutePath, jarFile.toPath());
		} catch (@SuppressWarnings("unused") final Exception e) {
			// E.g. a file which does not exist anymore
			return isSameFileName(absolutePath.normalize().toString(), jarFile.getPath());
		}
	}

	private static boolean isSameFileName(final String fileName1, final String fileName2) {
		// Windows and macOS file systems are case insensitive by default
		if (isWindows() || isMacOs()) {
			return fileName1.equalsIgnoreCase(fileName2);
		} else {
			return fileName1.equals(fileName2);
		}
	}

	private static boolean containsIgnoredJarFile(final List<String> arguments, final String... ignoredJarFileNamePrefixes) {
		if (ignoredJarFileNamePrefixes == null) {
			return false;
		}
		for (final String argument : arguments) {
			final String fileName = new File(argument).getName();
			for (final String ignoredPrefix : ignoredJarFileNamePrefixes) {
				if (fileName.toLowerCase(Locale.ROOT).endsWith(".jar") && fileName.startsWith(ignoredPrefix)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Command line of a running process
	 */
	static class ProcessCommandLine {
		final long processId;
		final List<String> arguments;
		final Path workingDirectory;

		ProcessCommandLine(final long processId, final List<String> arguments, final Path workingDirectory) {
			this.processId = processId;
			this.arguments = arguments;
			this.workingDirectory = workingDirectory;
		}
	}

	private static List<ProcessCommandLine> getProcessCommandLines() {
		if (isWindows()) {
			return getWindowsProcessCommandLines();
		} else {
			final List<ProcessCommandLine> processes = new ArrayList<>();
			ProcessHandle.allProcesses().forEach(processHandle -> {
				final Optional<String[]> arguments = processHandle.info().arguments();
				if (arguments.isPresent()) {
					final List<String> argumentList = new ArrayList<>(List.of(arguments.get()));
					processHandle.info().command().ifPresent(command -> argumentList.add(0, command));
					processes.add(new ProcessCommandLine(processHandle.pid(), argumentList, getLinuxWorkingDirectory(processHandle.pid())));
				}
			});
			return processes;
		}
	}

	/**
	 * Working directory of a process on Linux, null if unknown (other operating systems, processes of other users).
	 */
	private static Path getLinuxWorkingDirectory(final long processId) {
		try {
			final Path processWorkingDirectoryLink = Paths.get("/proc", Long.toString(processId), "cwd");
			if (Files.isSymbolicLink(processWorkingDirectoryLink)) {
				return Files.readSymbolicLink(processWorkingDirectoryLink);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Not readable
		}
		return null;
	}

	/**
	 * Reads the command lines of the running java processes on Windows via PowerShell. The script is given
	 * Base64 encoded, so it needs no quoting, and writes UTF-8, so paths with non ASCII characters are read correctly.
	 */
	private static List<ProcessCommandLine> getWindowsProcessCommandLines() {
		final List<ProcessCommandLine> processes = new ArrayList<>();
		try {
			final String script = "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
					+ "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
					+ "ForEach-Object { [string]$_.ProcessId + [char]9 + $_.CommandLine }";
			final String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

			final ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript);
			processBuilder.redirectErrorStream(true);
			final Process process = processBuilder.start();
			// PowerShell must not wait for input
			process.getOutputStream().close();

			// The output is read in its own thread, so a hanging PowerShell cannot block longer than the timeout
			final List<String> outputLines = Collections.synchronizedList(new ArrayList<>());
			final Thread outputReaderThread = new Thread(() -> {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						outputLines.add(line);
					}
				} catch (@SuppressWarnings("unused") final Exception e) {
					// Stream closed, e.g. after the timeout
				}
			}, "ApplicationInstanceDetector-ProcessList");
			outputReaderThread.setDaemon(true);
			outputReaderThread.start();

			if (!process.waitFor(PROCESS_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return processes;
			}
			outputReaderThread.join(TimeUnit.SECONDS.toMillis(PROCESS_LIST_TIMEOUT_SECONDS));

			synchronized (outputLines) {
				for (final String line : outputLines) {
					final int separatorIndex = line.indexOf('\t');
					if (separatorIndex > 0) {
						try {
							final long processId = Long.parseLong(line.substring(0, separatorIndex).trim());
							processes.add(new ProcessCommandLine(processId, splitWindowsCommandLine(line.substring(separatorIndex + 1)), null));
						} catch (@SuppressWarnings("unused") final NumberFormatException e) {
							// Not a process line, e.g. an error message
						}
					}
				}
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Processes cannot be determined, so no other instance is detected
		}
		return processes;
	}

	/**
	 * Splits a Windows command line into its arguments: blanks separate arguments, except within double quotes,
	 * which are removed.
	 *
	 * @param commandLine command line
	 * @return arguments
	 */
	static List<String> splitWindowsCommandLine(final String commandLine) {
		final List<String> arguments = new ArrayList<>();
		final StringBuilder currentArgument = new StringBuilder();
		boolean withinQuotes = false;
		boolean argumentStarted = false;
		for (final char character : commandLine.toCharArray()) {
			if (character == '"') {
				withinQuotes = !withinQuotes;
				argumentStarted = true;
			} else if ((character == ' ' || character == '\t') && !withinQuotes) {
				if (argumentStarted) {
					arguments.add(currentArgument.toString());
					currentArgument.setLength(0);
					argumentStarted = false;
				}
			} else {
				currentArgument.append(character);
				argumentStarted = true;
			}
		}
		if (argumentStarted) {
			arguments.add(currentArgument.toString());
		}
		return arguments;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
	}

	private static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	}
}

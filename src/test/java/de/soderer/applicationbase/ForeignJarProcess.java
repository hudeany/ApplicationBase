package de.soderer.applicationbase;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Starts a process (POSIX only), which is not a descendant of the test JVM and has a jar file path as argument of its
 * command line, like another running instance of an application would have. The process is a "sh -c 'sleep ...' jarPath",
 * started in the background by another shell, which exits immediately, so the process is reparented.
 */
public class ForeignJarProcess implements AutoCloseable {
	private final long processId;

	/**
	 * @param jarPathArgument jar path as given on the command line (absolute or relative)
	 * @param workingDirectory working directory of the process, may be null
	 */
	public ForeignJarProcess(final String jarPathArgument, final File workingDirectory) throws Exception {
		final ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", "sh -c 'sleep 60; true' \"$1\" > /dev/null 2>&1 < /dev/null & echo $!", "sh", jarPathArgument);
		if (workingDirectory != null) {
			processBuilder.directory(workingDirectory);
		}
		processBuilder.redirectErrorStream(true);
		final Process launcher = processBuilder.start();
		final String processIdText;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(launcher.getInputStream(), StandardCharsets.UTF_8))) {
			processIdText = reader.readLine();
		}
		if (!launcher.waitFor(10, TimeUnit.SECONDS)) {
			launcher.destroyForcibly();
			throw new Exception("Launcher shell did not exit");
		}
		processId = Long.parseLong(processIdText.trim());

		// Wait until the process is visible with its arguments
		final long endTime = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < endTime) {
			final Optional<ProcessHandle> processHandle = ProcessHandle.of(processId);
			if (processHandle.isPresent() && processHandle.get().info().arguments().isPresent()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new Exception("Foreign process " + processId + " did not start");
	}

	public long getProcessId() {
		return processId;
	}

	public static boolean isSupported() {
		return !TestHelper.isWindows();
	}

	@Override
	public void close() {
		ProcessHandle.of(processId).ifPresent(processHandle -> {
			processHandle.descendants().forEach(ProcessHandle::destroyForcibly);
			processHandle.destroyForcibly();
			try {
				processHandle.onExit().get(10, TimeUnit.SECONDS);
			} catch (@SuppressWarnings("unused") final Exception e) {
				// Best effort cleanup only
			}
		});
	}
}

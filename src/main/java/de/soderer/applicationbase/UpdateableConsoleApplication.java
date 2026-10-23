package de.soderer.applicationbase;

import java.awt.HeadlessException;
import java.io.Console;
import java.time.LocalDateTime;
import java.util.Locale;

import de.soderer.console.ConsoleType;
import de.soderer.console.ConsoleUtilities;
import de.soderer.console.PasswordConsoleInput;
import de.soderer.console.SimpleConsoleInput;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * {@link ApplicationUpdateParent} for console applications: asks for updates and credentials on the console and
 * shows the update progress there.
 */
public class UpdateableConsoleApplication implements ApplicationUpdateParent {
	private final String applicationName;
	private final Version applicationVersion;

	/**
	 * Creates the update parent for a console application.
	 *
	 * @param applicationName name of the application shown in messages
	 * @param applicationVersion currently running version of the application
	 * @throws HeadlessException never thrown, declared for compatibility
	 * @throws IllegalArgumentException if applicationName is blank or applicationVersion is null
	 */
	public UpdateableConsoleApplication(final String applicationName, final Version applicationVersion) throws HeadlessException {
		if (StringUtilities.isBlank(applicationName)) {
			throw new IllegalArgumentException("Invalid empty applicationName");
		} else if (applicationVersion == null) {
			throw new IllegalArgumentException("Invalid empty applicationVersion");
		}
		this.applicationName = applicationName;
		this.applicationVersion = applicationVersion;
	}

	/**
	 * Asks the user on the console whether to install a new version, or shows that there is no newer version.
	 * Answers starting with 'y', 'j', 'o' or 's' (yes in English, German, French, Spanish/Italian) are accepted.
	 *
	 * @param availableNewVersion available new version, or null if there is none
	 * @return true if the update should be installed
	 * @throws Exception if no console is available
	 */
	@Override
	public boolean askForUpdate(final Version availableNewVersion) throws Exception {
		if (availableNewVersion == null) {
			// Only an information, no input needed: works without an interactive console too (e.g. IDE, redirected output)
			System.out.println(LangResources.get("noNewerVersion", applicationName, applicationVersion.toString()));
			System.out.println();
			return false;
		} else {
			final Console console = System.console();
			if (console == null) {
				throw new Exception("Couldn't get Console instance");
			}
			ConsoleUtilities.clearScreen();

			System.out.println(LangResources.get("newApplicationVersion", availableNewVersion.toString(), applicationName, applicationVersion.toString()));
			final String input = new SimpleConsoleInput().withPrompt(LangResources.get("installUpdate") + " (" + LangResources.get("yN") + "): ").readInput();
			System.out.println();
			final String answer = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
			return answer.startsWith("y") || answer.startsWith("j") || answer.startsWith("o") || answer.startsWith("s");
		}
	}

	/**
	 * Asks the user on the console for username and/or password.
	 *
	 * @param text text explaining what the credentials are needed for (not shown on the console)
	 * @param acquireUsername whether a username is requested
	 * @param acquirePassword whether a password is requested
	 * @param firstRequest true for the first request
	 * @return the entered credentials, or null if nothing was entered
	 * @throws Exception if no console is available
	 */
	@Override
	public Credentials acquireCredentials(final String text, final boolean acquireUsername, final boolean acquirePassword, final boolean firstRequest) throws Exception {
		final Console console = System.console();
		if (console == null) {
			throw new Exception("Couldn't get Console instance");
		}

		String userName = null;
		char[] password = null;

		if (acquireUsername && acquirePassword) {
			userName = new SimpleConsoleInput().withPrompt(LangResources.get("enterUsername") + ": ").readInput();
			password = new PasswordConsoleInput().withPrompt(LangResources.get("enterPassword") + ": ").readInput();
		} else if (acquireUsername) {
			userName = new SimpleConsoleInput().withPrompt(LangResources.get("enterUsername") + ": ").readInput();
		} else if (acquirePassword) {
			password = new PasswordConsoleInput().withPrompt(LangResources.get("enterPassword") + ": ").readInput();
		}

		if (StringUtilities.isBlank(userName) && StringUtilities.isBlank(password)) {
			return null;
		} else {
			return new Credentials(userName, password);
		}
	}

	/**
	 * Prints an update error and waits for the user to press enter (if a console is available).
	 *
	 * @param errorText error text
	 */
	@Override
	public void showUpdateError(final String errorText) {
		System.err.println(errorText);
		final Console console = System.console();
		if (console != null) {
			System.out.println();
			System.out.println("Press enter to continue");
			System.console().readLine();
		}
	}

	/**
	 * Shows the download progress: on ANSI consoles in place, in test consoles (e.g. IDE) as new line each time,
	 * otherwise in place via carriage return.
	 *
	 * @param itemStart start time of the download
	 * @param itemsToDo total number of items (bytes)
	 * @param itemsDone number of items done
	 * @param itemsUnitSign unit sign of the items
	 */
	@Override
	public void showUpdateProgress(final LocalDateTime itemStart, final long itemsToDo, final long itemsDone, final String itemsUnitSign) {
		if (ConsoleUtilities.getConsoleType() == ConsoleType.ANSI) {
			ConsoleUtilities.saveCurrentCursorPosition();

			System.out.print(ConsoleUtilities.getConsoleProgressString(80 - 1, itemStart, itemsToDo, itemsDone, itemsUnitSign));

			ConsoleUtilities.moveCursorToSavedPosition();
		} else if (ConsoleUtilities.getConsoleType() == ConsoleType.TEST) {
			// Consoles without carriage return support (e.g. IDE): one line per progress update
			System.out.print(ConsoleUtilities.getConsoleProgressString(80 - 1, itemStart, itemsToDo, itemsDone, itemsUnitSign) + "\n");
		} else {
			System.out.print("\r" + ConsoleUtilities.getConsoleProgressString(80 - 1, itemStart, itemsToDo, itemsDone, itemsUnitSign) + "\r");
		}
	}

	/**
	 * Shows that the update is done.
	 *
	 * @param startTime start time of the update
	 * @param endTime end time of the update
	 * @param itemsDone number of items done
	 * @param itemsUnitSign unit sign of the items
	 */
	@Override
	public void showUpdateDone(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		System.out.println();
		System.out.println(LangResources.get("updateDone"));
		System.out.println();
	}

	/**
	 * Does nothing in console applications.
	 *
	 * @param worker download worker
	 */
	@Override
	public void showUpdateDownloadStart(final WorkerSimple<Boolean> worker) {
		// Do nothing
	}

	/**
	 * Does nothing in console applications.
	 *
	 * @param startTime start time of the download
	 * @param endTime end time of the download
	 * @param itemsDone number of items done
	 * @param itemsUnitSign unit sign of the items
	 */
	@Override
	public void showUpdateDownloadEnd(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		// Do nothing
	}

	/**
	 * Shows on the console, that the application file is in use by other running instances of the application, and
	 * asks whether to check again or to cancel the update. "1" or an answer starting like the retry text (e.g. "r" for
	 * "Retry") checks again, anything else cancels.
	 *
	 * @param message text naming the other processes and asking to close them
	 * @return true to check again, false to cancel the update (also if no interactive console is available)
	 */
	@Override
	public boolean askForRetryWhileApplicationInUse(final String message) {
		System.err.println(message);
		System.out.println();
		try {
			if (System.console() == null) {
				// No input possible (e.g. redirected input), so the update is canceled like before
				return false;
			} else {
				final String retryText = LangResources.get("retry");
				final String cancelText = LangResources.get("cancel");
				final String input = new SimpleConsoleInput().withPrompt("1 = " + retryText + ", 2 = " + cancelText + ": ").readInput();
				System.out.println();
				final String answer = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
				return "1".equals(answer) || (answer.length() > 0 && retryText.toLowerCase(Locale.ROOT).startsWith(answer) && !cancelText.toLowerCase(Locale.ROOT).startsWith(answer));
			}
		} catch (final Exception e) {
			System.err.println("Cannot ask user for retry: " + e.getMessage());
			return false;
		}
	}
}

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
import de.soderer.utilities.Utilities;
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
	 */
	public UpdateableConsoleApplication(final String applicationName, final Version applicationVersion) throws HeadlessException {
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
		final Console console = System.console();
		if (console == null) {
			throw new Exception("Couldn't get Console instance");
		}
		ConsoleUtilities.clearScreen();
		if (availableNewVersion == null) {
			System.out.println(LangResources.get("noNewerVersion", applicationName, applicationVersion.toString()));
			System.out.println();
			return false;
		} else {
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
	 * @param aquireUsername whether a username is requested
	 * @param aquirePassword whether a password is requested
	 * @param firstRequest true for the first request
	 * @return the entered credentials, or null if nothing was entered
	 * @throws Exception if no console is available
	 */
	@Override
	public Credentials aquireCredentials(final String text, final boolean aquireUsername, final boolean aquirePassword, final boolean firstRequest) throws Exception {
		final Console console = System.console();
		if (console == null) {
			throw new Exception("Couldn't get Console instance");
		}

		String userName = null;
		char[] password = null;

		if (aquireUsername && aquirePassword) {
			userName = new SimpleConsoleInput().withPrompt(LangResources.get("enterUsername") + ": ").readInput();
			password = new PasswordConsoleInput().withPrompt(LangResources.get("enterPassword") + ": ").readInput();
		} else if (aquireUsername) {
			userName = new SimpleConsoleInput().withPrompt(LangResources.get("enterUsername") + ": ").readInput();
		} else if (aquirePassword) {
			password = new PasswordConsoleInput().withPrompt(LangResources.get("enterPassword") + ": ").readInput();
		}

		if (Utilities.isBlank(userName) && Utilities.isBlank(password)) {
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
}

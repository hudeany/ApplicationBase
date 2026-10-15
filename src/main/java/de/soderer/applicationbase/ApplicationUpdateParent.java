package de.soderer.applicationbase;

import java.time.LocalDateTime;

import de.soderer.utilities.CredentialsParent;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * Callbacks of an application, which uses {@link ApplicationUpdateUtilities} to update itself, for showing the update
 * state and asking the user for decisions and credentials.
 */
public interface ApplicationUpdateParent extends CredentialsParent {
	/**
	 * Shows an error that occurred during the update check or update.
	 *
	 * @param errorText error message
	 */
	void showUpdateError(String errorText);

	/**
	 * Shows the progress of the update download.
	 *
	 * @param start start time of the download
	 * @param itemsToDo total number of items (e.g. bytes) to download, or a value below 1 if unknown
	 * @param itemsDone number of items already downloaded
	 * @param itemsUnitSign unit of the items for display, may be null
	 */
	void showUpdateProgress(LocalDateTime start, long itemsToDo, long itemsDone, String itemsUnitSign);

	/**
	 * Shows that the update was prepared and the application is going to be replaced. Called right before the
	 * application exits for the update, the parameters may be null or 0.
	 *
	 * @param startTime start time of the update, may be null
	 * @param endTime end time of the update, may be null
	 * @param itemsDone number of items processed
	 * @param itemsUnitSign unit of the items for display, may be null
	 */
	void showUpdateDone(LocalDateTime startTime, LocalDateTime endTime, long itemsDone, String itemsUnitSign);

	/**
	 * Asks the user whether to update to the available version.
	 *
	 * @param availableNewVersion version available for the update, or null if no newer version was found
	 * @return true to execute the update
	 * @throws Exception if the user could not be asked
	 */
	boolean askForUpdate(Version availableNewVersion) throws Exception;

	/**
	 * Shows that the download of the update file is starting.
	 *
	 * <p>
	 * The worker is not started yet. An implementation may start it itself (e.g. a modal progress dialog
	 * starting it when it is shown and blocking until it is done). If the worker was not started when this
	 * method returns, it is started by the caller afterwards. The worker must never be started twice.
	 * </p>
	 *
	 * @param worker worker executing the download, may be used to show its progress, to start or to cancel it
	 */
	void showUpdateDownloadStart(WorkerSimple<Boolean> worker);

	/**
	 * Shows that the download of the update file has finished.
	 *
	 * @param startTime start time of the download
	 * @param endTime end time of the download
	 * @param itemsDone number of items (e.g. bytes) downloaded
	 * @param itemsUnitSign unit of the items for display, may be null
	 */
	void showUpdateDownloadEnd(LocalDateTime startTime, LocalDateTime endTime, long itemsDone, String itemsUnitSign);
}

package de.soderer.applicationbase.modern;

import java.io.File;
import java.time.LocalDateTime;

import de.soderer.applicationbase.ApplicationUpdateParent;
import de.soderer.applicationbase.SecureDataGuiApplication;
import de.soderer.swing.modern.CredentialsDialog;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.ProgressDialog;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * Base class of GUI applications in the {@link ModernUi} design that can update themselves via
 * {@link de.soderer.applicationbase.ApplicationUpdateUtilities}. Same API as
 * {@link de.soderer.applicationbase.UpdateableGuiApplication}, but all update dialogs use the modern dialogs.
 *
 * <p>
 * Implements the user interaction of the update process with Swing dialogs: asking for the update, for download
 * credentials (optionally remembered in the keystore), showing the download progress, errors and the result.
 * </p>
 */
public class UpdateableGuiApplication extends SecureDataGuiApplication implements ApplicationUpdateParent {
	/** Serialization version */
	private static final long serialVersionUID = 3871040657215092416L;

	/** Name of the application, used in the titles of the update dialogs */
	private final String applicationName;

	/** Current version of the application, compared with the available version */
	private final Version applicationVersion;

	/** Progress dialog of a running update download, null while no download is running */
	private transient ProgressDialog<?> updateProgressDialog;

	/**
	 * Creates an updateable application frame.
	 *
	 * @param applicationName    name of the application, not blank
	 * @param applicationVersion current version of the application, not null
	 * @param keystoreFile       keystore file for remembered update credentials, may be null for no keystore
	 * @throws Exception if the application name is blank or the version is null
	 */
	public UpdateableGuiApplication(final String applicationName, final Version applicationVersion, final File keystoreFile) throws Exception {
		super(keystoreFile);

		if (StringUtilities.isBlank(applicationName)) {
			throw new Exception("Invalid empty applicationName");
		} else if (applicationVersion == null) {
			throw new Exception("Invalid empty applicationVersion");
		}

		this.applicationName = applicationName;
		this.applicationVersion = applicationVersion;
	}

	/**
	 * Returns the download credentials: on the first request the credentials remembered in the keystore, otherwise
	 * (or if none are remembered) the credentials entered by the user, who may let them be remembered.
	 *
	 * @param text            text of the credentials dialog
	 * @param acquireUsername true to ask for a username
	 * @param acquirePassword true to ask for a password
	 * @param firstRequest    true for the first request of this update, false after rejected credentials
	 * @return the credentials, null if the user canceled
	 * @throws Exception if the keystore cannot be read or written
	 */
	@Override
	public Credentials acquireCredentials(final String text, final boolean acquireUsername, final boolean acquirePassword, final boolean firstRequest) throws Exception {
		Credentials downloadCredentials = null;

		if (firstRequest && keystoreExists()) {
			downloadCredentials = getKeystoreValue(Credentials.class, "Update_Credentials");
		}

		if (downloadCredentials == null) {
			final CredentialsDialog downloadCredentialsDialog = new CredentialsDialog(this, "Update " + applicationName, text, acquireUsername, acquirePassword);
			downloadCredentialsDialog.setRememberCredentialsText(LangResources.get("rememberUpdateCredentials"));
			downloadCredentials = downloadCredentialsDialog.open();

			if (downloadCredentials != null && downloadCredentialsDialog.isRememberCredentials()) {
				saveKeyStoreValue("Update_Credentials", downloadCredentials);
			}
		}

		return downloadCredentials;
	}

	/**
	 * Closes the progress dialog of a running download and shows an error message.
	 *
	 * @param errorText text of the error
	 */
	@Override
	public void showUpdateError(final String errorText) {
		closeUpdateProgressDialog();

		new QuestionDialog(this, "Update " + applicationName + " Error", errorText).withDialogStyle(DialogStyle.ERROR).open();
	}

	/**
	 * Shows the download progress in the progress dialog, if one is open.
	 *
	 * @param itemStart     start time of the download
	 * @param itemsToDo     total number of items, 0 or below if unknown
	 * @param itemsDone     number of items done
	 * @param itemsUnitSign unit of the items
	 */
	@Override
	public void showUpdateProgress(final LocalDateTime itemStart, final long itemsToDo, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveProgressSignal(itemStart, itemsToDo, itemsDone, itemsUnitSign);
		}
	}

	/**
	 * Closes the progress dialog and tells the user that the update is done and the application restarts.
	 *
	 * @param startTime     start time of the update
	 * @param endTime       end time of the update
	 * @param itemsDone     number of items done
	 * @param itemsUnitSign unit of the items
	 */
	@Override
	public void showUpdateDone(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveDoneSignal(startTime, endTime, itemsDone, itemsUnitSign, null);
			updateProgressDialog = null;
		}

		new QuestionDialog(this, "Update " + applicationName, LangResources.get("updateDone"), LangResources.get("restartNow"))
			.withDialogStyle(DialogStyle.INFORMATION)
			.withBackgroundColor(ModernUi.SUCCESS_LIGHT)
			.open();
	}

	/**
	 * Tells the user that there is no newer version, or asks whether to install the available newer version. Both
	 * dialogs show the "daily update check" checkbox, if {@link #isDailyUpdateCheckActivated()} is not null.
	 *
	 * @param availableNewVersion newer version, null if there is none
	 * @return true if the user wants to install the newer version
	 * @throws Exception if the status of the daily update check cannot be stored
	 */
	@Override
	public boolean askForUpdate(final Version availableNewVersion) throws Exception {
		if (availableNewVersion == null) {
			final QuestionDialog questionDialog = new QuestionDialog(this, "Update " + applicationName, LangResources.get("noNewerVersion", applicationName, applicationVersion.toString()))
				.withDialogStyle(DialogStyle.INFORMATION);
			if (isDailyUpdateCheckActivated() != null) {
				questionDialog.setCheckboxText(LangResources.get("dailyUpdateCheck"));
				questionDialog.setCheckboxStatus(isDailyUpdateCheckActivated());
			}
			questionDialog.open();
			setDailyUpdateCheckStatus(questionDialog.getCheckboxStatus());
			return false;
		} else {
			final QuestionDialog questionDialog = new QuestionDialog(this, "Update " + applicationName, LangResources.get("newApplicationVersion", availableNewVersion.toString(), applicationName, applicationVersion.toString()) + "\n\n" + LangResources.get("installUpdate"), LangResources.get("yes"), LangResources.get("no"))
				.withDialogStyle(DialogStyle.QUESTION);
			if (isDailyUpdateCheckActivated() != null) {
				questionDialog.setCheckboxText(LangResources.get("dailyUpdateCheck"));
				questionDialog.setCheckboxStatus(isDailyUpdateCheckActivated());
			}
			final Integer buttonIndex = questionDialog.open();
			setDailyUpdateCheckStatus(questionDialog.getCheckboxStatus());
			return buttonIndex != null && buttonIndex == 0;
		}
	}

	/**
	 * Shows a modal progress dialog, which starts the worker when it is shown and blocks until the download is done
	 * or canceled.
	 *
	 * @param worker download worker, not started yet
	 */
	@Override
	public void showUpdateDownloadStart(final WorkerSimple<Boolean> worker) {
		updateProgressDialog = new ProgressDialog<>(this, "Update " + applicationName, null, worker);
		updateProgressDialog.open();
	}

	/**
	 * Closes the progress dialog of the download.
	 *
	 * @param startTime     start time of the download
	 * @param endTime       end time of the download
	 * @param itemsDone     number of items done
	 * @param itemsUnitSign unit of the items
	 */
	@Override
	public void showUpdateDownloadEnd(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveDoneSignal(startTime, endTime, itemsDone, itemsUnitSign, null);
			updateProgressDialog = null;
		}
	}

	/**
	 * Called with the status of the "daily update check" checkbox after the user was asked for an update.
	 * Subclasses may store it; does nothing by default.
	 *
	 * @param checkboxStatus true if the daily update check is activated
	 */
	protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
		// Do nothing by standard
	}

	/**
	 * Returns whether the daily update check is activated. The update dialogs only show the "daily update check"
	 * checkbox if this is not null.
	 *
	 * @return status of the daily update check, null (default) if not supported
	 */
	@SuppressWarnings("static-method")
	protected Boolean isDailyUpdateCheckActivated() {
		return null;
	}

	/**
	 * Shows that the application file is in use by other running instances of the application and lets the user
	 * check again after closing them, or cancel the update.
	 *
	 * @param message text naming the other processes and asking to close them
	 * @return true to check again, false to cancel the update
	 */
	@Override
	public boolean askForRetryWhileApplicationInUse(final String message) {
		// The download is done, its progress dialog must not stay open behind the question
		closeUpdateProgressDialog();

		// A warning, not an error: the user can solve it by closing the other instances
		final Integer buttonIndex = new QuestionDialog(this, "Update " + applicationName, message, LangResources.get("retry"), LangResources.get("cancel"))
			.withDialogStyle(DialogStyle.WARNING)
			.open();
		return buttonIndex != null && buttonIndex == 0;
	}

	/**
	 * Closes the progress dialog of a running download, if one is open.
	 */
	private void closeUpdateProgressDialog() {
		if (updateProgressDialog != null) {
			updateProgressDialog.dispose();
			updateProgressDialog = null;
		}
	}
}

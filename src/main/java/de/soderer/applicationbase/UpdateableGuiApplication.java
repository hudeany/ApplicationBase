package de.soderer.applicationbase;

import java.io.File;
import java.time.LocalDateTime;

import de.soderer.swing.CredentialsDialog;
import de.soderer.swing.ProgressDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * Base class of GUI applications that can update themselves via
 * {@link de.soderer.applicationbase.ApplicationUpdateUtilities}.
 *
 * <p>
 * Implements the user interaction of the update process with Swing dialogs:
 * asking for the update, for download credentials (optionally remembered in the
 * keystore), showing the download progress, errors and the result.
 * </p>
 */
public class UpdateableGuiApplication extends SecureDataGuiApplication implements ApplicationUpdateParent {
	private static final long serialVersionUID = -6286611962531947310L;

	/** Name of the application, used in the titles of the update dialogs */
	private final String applicationName;

	/** Current version of the application, compared with the available version */
	private final Version applicationVersion;

	/** Progress dialog of a running update download, null while no download is running */
	private ProgressDialog<?> updateProgressDialog;

	/**
	 * Creates an updateable application frame.
	 *
	 * @param applicationName name of the application, not blank
	 * @param applicationVersion current version of the application, not null
	 * @param keystoreFile keystore file for remembered update credentials, may be
	 *            null for no keystore
	 * @throws Exception if the application name is blank or the version is null
	 */
	public UpdateableGuiApplication(final String applicationName, final Version applicationVersion, final File keystoreFile) throws Exception {
		super(keystoreFile);

		if (Utilities.isBlank(applicationName)) {
			throw new Exception("Invalid empty applicationName");
		} else if (applicationVersion == null) {
			throw new Exception("Invalid empty applicationVersion");
		}

		this.applicationName = applicationName;
		this.applicationVersion = applicationVersion;
	}

	@Override
	public Credentials acquireCredentials(final String text, final boolean aquireUsername, final boolean aquirePassword, final boolean firstRequest) throws Exception {
		Credentials downloadCredentials = null;

		if (firstRequest && keystoreExists()) {
			downloadCredentials = getKeystoreValue(Credentials.class, "Update_Credentials");
		}

		if (downloadCredentials == null) {
			final CredentialsDialog downloadCredentialsDialog = new CredentialsDialog(this, "Update " + applicationName, text, aquireUsername, aquirePassword);
			downloadCredentialsDialog.setRememberCredentialsText(LangResources.get("rememberUpdateCredentials"));
			downloadCredentials = downloadCredentialsDialog.open();

			if (downloadCredentials != null && downloadCredentialsDialog.isRememberCredentials()) {
				saveKeyStoreValue("Update_Credentials", downloadCredentials);
			}
		}

		return downloadCredentials;
	}

	@Override
	public void showUpdateError(final String errorText) {
		if (updateProgressDialog != null) {
			updateProgressDialog.dispose();
			updateProgressDialog = null;
		}

		new QuestionDialog(this, "Update " + applicationName + " Error", errorText).withBackgroundColor(SwingColor.LightRed).open();
	}

	@Override
	public void showUpdateProgress(final LocalDateTime itemStart, final long itemsToDo, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveProgressSignal(itemStart, itemsToDo, itemsDone, itemsUnitSign);
		}
	}

	@Override
	public void showUpdateDone(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveDoneSignal(startTime, endTime, itemsDone, itemsUnitSign, null);
			updateProgressDialog = null;
		}

		new QuestionDialog(this, "Update " + applicationName, LangResources.get("updateDone"), LangResources.get("restartNow")).open();
	}

	@Override
	public boolean askForUpdate(final Version availableNewVersion) throws Exception {
		if (availableNewVersion == null) {
			final QuestionDialog questionDialog = new QuestionDialog(this, "Update " + applicationName, LangResources.get("noNewerVersion", applicationName, applicationVersion.toString()));
			if (isDailyUpdateCheckActivated() != null) {
				questionDialog.setCheckboxText(LangResources.get("dailyUpdateCheck"));
				questionDialog.setCheckboxStatus(isDailyUpdateCheckActivated());
			}
			questionDialog.open();
			setDailyUpdateCheckStatus(questionDialog.getCheckboxStatus());
			return false;
		} else {
			final QuestionDialog questionDialog = new QuestionDialog(this, "Update " + applicationName, LangResources.get("newApplicationVersion", availableNewVersion.toString(), applicationName, applicationVersion.toString()) + "\n\n" + LangResources.get("installUpdate"), LangResources.get("yes"), LangResources.get("no"));
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
	 * Shows a modal progress dialog, which starts the worker when it is shown and
	 * blocks until the download is done or canceled.
	 *
	 * @param worker download worker, not started yet
	 */
	@Override
	public void showUpdateDownloadStart(final WorkerSimple<Boolean> worker) {
		updateProgressDialog = new ProgressDialog<>(this, "Update " + applicationName, null, worker);
		updateProgressDialog.open();
	}

	@Override
	public void showUpdateDownloadEnd(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		if (updateProgressDialog != null) {
			updateProgressDialog.receiveDoneSignal(startTime, endTime, itemsDone, itemsUnitSign, null);
			updateProgressDialog = null;
		}
	}

	/**
	 * Called with the status of the "daily update check" checkbox after the user
	 * was asked for an update. Subclasses may store it; does nothing by default.
	 *
	 * @param checkboxStatus true if the daily update check is activated
	 */
	protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
		// Do nothing by standard
	}

	/**
	 * Returns whether the daily update check is activated. The update dialogs only
	 * show the "daily update check" checkbox if this is not null.
	 *
	 * @return status of the daily update check, null (default) if not supported
	 */
	@SuppressWarnings("static-method")
	protected Boolean isDailyUpdateCheckActivated() {
		return null;
	}
}

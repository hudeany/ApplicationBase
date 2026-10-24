package de.soderer.applicationbase;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import de.soderer.utilities.Credentials;
import de.soderer.utilities.Version;
import de.soderer.utilities.worker.WorkerSimple;

/**
 * {@link ApplicationUpdateParent} for tests: records all calls and gives preconfigured answers.
 */
public class TestUpdateParent implements ApplicationUpdateParent {
	public final List<String> errors = new ArrayList<>();
	public final List<Version> askedVersions = new ArrayList<>();
	public final List<String> credentialTexts = new ArrayList<>();
	public final List<boolean[]> credentialRequests = new ArrayList<>();
	public final List<String> retryMessages = new ArrayList<>();
	public int progressCount = 0;
	public int doneCount = 0;
	public int downloadStartCount = 0;
	public int downloadEndCount = 0;

	/** Answer of askForUpdate() */
	public boolean updateAnswer = false;
	/** Exception thrown by askForUpdate(), if set */
	public Exception updateException = null;
	/** Answers of acquireCredentials(), null if the deque is empty */
	public final Deque<Credentials> credentialsAnswers = new ArrayDeque<>();
	/** Answers of askForRetryWhileApplicationInUse(), false if the deque is empty */
	public final Deque<Boolean> retryAnswers = new ArrayDeque<>();
	/** Action executed in showUpdateDownloadStart(), may be null */
	public Consumer<WorkerSimple<Boolean>> downloadStartAction = null;

	@Override
	public Credentials acquireCredentials(final String text, final boolean acquireUsername, final boolean acquirePassword, final boolean firstRequest) throws Exception {
		credentialTexts.add(text);
		credentialRequests.add(new boolean[] { acquireUsername, acquirePassword, firstRequest });
		return credentialsAnswers.isEmpty() ? null : credentialsAnswers.removeFirst();
	}

	@Override
	public void showUpdateError(final String errorText) {
		errors.add(errorText);
	}

	@Override
	public void showUpdateProgress(final LocalDateTime start, final long itemsToDo, final long itemsDone, final String itemsUnitSign) {
		progressCount++;
	}

	@Override
	public void showUpdateDone(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		doneCount++;
	}

	@Override
	public boolean askForUpdate(final Version availableNewVersion) throws Exception {
		askedVersions.add(availableNewVersion);
		if (updateException != null) {
			throw updateException;
		}
		return updateAnswer;
	}

	@Override
	public void showUpdateDownloadStart(final WorkerSimple<Boolean> worker) {
		downloadStartCount++;
		if (downloadStartAction != null) {
			downloadStartAction.accept(worker);
		}
	}

	@Override
	public void showUpdateDownloadEnd(final LocalDateTime startTime, final LocalDateTime endTime, final long itemsDone, final String itemsUnitSign) {
		downloadEndCount++;
	}

	@Override
	public boolean askForRetryWhileApplicationInUse(final String message) {
		retryMessages.add(message);
		return !retryAnswers.isEmpty() && retryAnswers.removeFirst();
	}

	/**
	 * Joined error texts for assertion messages
	 */
	public String getErrorsText() {
		return String.join("\n---\n", errors);
	}
}

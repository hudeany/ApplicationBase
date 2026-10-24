package de.soderer.applicationbase;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.swing.JFrame;

import de.soderer.swing.CredentialsDialog;
import de.soderer.swing.QuestionDialog;
import de.soderer.swing.SwingColor;
import de.soderer.utilities.Credentials;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.WrongPasswordException;

/**
 * Base class of GUI applications that keep sensitive values (e.g. credentials)
 * in a password protected {@link SecureDataStore} keystore file.
 *
 * <p>
 * The keystore password is asked for via a {@link CredentialsDialog} when
 * needed (an empty password is tried first) and may be kept in memory for the
 * lifetime of the application if the user chooses so. An existing keystore is
 * always saved with the password it was opened with; a new keystore asks for
 * its password twice, so a typo cannot lock the user out.
 * </p>
 */
public class SecureDataGuiApplication extends JFrame {
	private static final long serialVersionUID = 1869492889654406822L;

	/** Keystore file for sensitive values, null for no keystore */
	private final File keystoreFile;

	/**
	 * Keystore password kept in memory for the lifetime of the application if the
	 * user chose so, null otherwise (transient: never serialized)
	 */
	private transient char[] rememberedKeystorePassword = null;

	/**
	 * Creates an application frame without keystore.
	 *
	 * @throws Exception declared for subclasses, not thrown here
	 */
	public SecureDataGuiApplication() throws Exception {
		this(null);
	}

	/**
	 * Creates an application frame.
	 *
	 * @param keystoreFile keystore file, may be null for no keystore
	 * @throws Exception declared for subclasses, not thrown here
	 */
	public SecureDataGuiApplication(final File keystoreFile) throws Exception {
		super();

		this.keystoreFile = keystoreFile;
	}

	/**
	 * Checks whether the keystore file exists.
	 *
	 * @return true if a keystore file is configured and exists
	 */
	protected boolean keystoreExists() {
		return keystoreFile != null && keystoreFile.exists();
	}

	/**
	 * Reads a value from the keystore. Asks the user for the keystore password if
	 * needed.
	 *
	 * @param <T> type of the value
	 * @param classType class of the value
	 * @param entryName name of the entry
	 * @return value, null if the keystore does not exist, could not be opened or
	 *         does not contain the entry
	 */
	protected <T> T getKeystoreValue(final Class<T> classType, final String entryName) {
		final OpenedKeystore openedKeystore = openKeystore();
		if (openedKeystore == null) {
			return null;
		}

		try {
			return openedKeystore.keystore.getEntry(classType, entryName);
		} finally {
			wipe(openedKeystore.password);
		}
	}

	/**
	 * Stores a value in the keystore, creating the keystore file if it does not
	 * exist yet.
	 *
	 * <p>
	 * An existing keystore is saved with the password it was opened with, so a
	 * typo cannot re-encrypt it with an unknown password. For a new keystore the
	 * user enters the password twice. Nothing is stored if the existing keystore
	 * cannot be opened or the user cancels the password dialog.
	 * </p>
	 *
	 * @param entryName name of the entry
	 * @param value value to store
	 * @return true if the value was stored, false if the user canceled or the
	 *         keystore could not be opened
	 * @throws Exception if the keystore cannot be saved
	 */
	protected boolean saveKeyStoreValue(final String entryName, final Object value) throws Exception {
		if (keystoreFile == null) {
			return false;
		}

		if (keystoreExists()) {
			final OpenedKeystore openedKeystore = openKeystore();
			if (openedKeystore == null) {
				// Keystore could not be opened (e.g. password dialog canceled): a new keystore would overwrite all existing entries
				return false;
			}

			try {
				openedKeystore.keystore.addEntry(entryName, value);
				// save() wipes the given array, so a copy is passed
				openedKeystore.keystore.save(keystoreFile, Arrays.copyOf(openedKeystore.password, openedKeystore.password.length));
				return true;
			} finally {
				wipe(openedKeystore.password);
			}
		} else {
			final File parentDirectory = keystoreFile.getAbsoluteFile().getParentFile();
			if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
				throw new IOException("Cannot create directory for keystore: " + parentDirectory.getAbsolutePath());
			}

			final CredentialsDialog keystoreCredentialsDialog = new CredentialsDialog(this, LangResources.get("window_title"), LangResources.get("enterKeystorePassword"), false, true);
			keystoreCredentialsDialog.setRememberCredentialsText(LangResources.get("keepPasswordInMemory"));
			// New keystore: the password must be entered twice
			keystoreCredentialsDialog.setPasswordConfirmation(true);
			final Credentials credentials = keystoreCredentialsDialog.open();
			if (credentials == null) {
				return false;
			}

			final char[] keystorePassword = credentials.getPassword() == null ? new char[0] : credentials.getPassword();
			try {
				final SecureDataStore secureDataStore = new SecureDataStore();
				secureDataStore.addEntry(entryName, value);
				// save() wipes the given array, but the password may still be remembered afterwards
				secureDataStore.save(keystoreFile, Arrays.copyOf(keystorePassword, keystorePassword.length));
				if (keystoreCredentialsDialog.isRememberCredentials()) {
					rememberPassword(keystorePassword);
				}
				return true;
			} finally {
				wipe(keystorePassword);
			}
		}
	}

	/**
	 * Opens the keystore: with the remembered password, with an empty password or
	 * with a password entered by the user.
	 *
	 * @return the opened keystore with a copy of its password (to be wiped by the
	 *         caller), null if the keystore does not exist, the user canceled or
	 *         the keystore could not be read
	 */
	private OpenedKeystore openKeystore() {
		if (keystoreFile == null || !keystoreFile.exists()) {
			return null;
		}

		// Try the remembered password, otherwise an empty password
		final char[] firstTryPassword = rememberedKeystorePassword != null ? Arrays.copyOf(rememberedKeystorePassword, rememberedKeystorePassword.length) : new char[0];
		try {
			final SecureDataStore keystore = new SecureDataStore();
			// load() wipes the given array, but the password is needed for saving the keystore again
			keystore.load(keystoreFile, Arrays.copyOf(firstTryPassword, firstTryPassword.length));
			return new OpenedKeystore(keystore, firstTryPassword);
		} catch (@SuppressWarnings("unused") final WrongPasswordException e) {
			wipe(firstTryPassword);
			if (rememberedKeystorePassword != null) {
				// Keystore was changed meanwhile, the remembered password is useless now
				wipe(rememberedKeystorePassword);
				rememberedKeystorePassword = null;
			}
		} catch (final Exception e) {
			wipe(firstTryPassword);
			showKeystoreError(e);
			return null;
		}

		while (true) {
			final CredentialsDialog keystoreCredentialsDialog = new CredentialsDialog(this, LangResources.get("window_title"), LangResources.get("enterKeystorePassword"), false, true);
			keystoreCredentialsDialog.setRememberCredentialsText(LangResources.get("keepPasswordInMemory"));
			final Credentials credentials = keystoreCredentialsDialog.open();
			if (credentials == null) {
				return null;
			}

			final char[] keystorePassword = credentials.getPassword() == null ? new char[0] : credentials.getPassword();
			try {
				final SecureDataStore keystore = new SecureDataStore();
				// load() wipes the given array, but the password is needed for saving the keystore again
				keystore.load(keystoreFile, Arrays.copyOf(keystorePassword, keystorePassword.length));

				if (keystoreCredentialsDialog.isRememberCredentials()) {
					rememberPassword(keystorePassword);
				}

				return new OpenedKeystore(keystore, keystorePassword);
			} catch (@SuppressWarnings("unused") final WrongPasswordException e) {
				// Wrong password: ask again
				wipe(keystorePassword);
			} catch (final Exception e) {
				// Any other problem (damaged file, I/O error) cannot be solved by entering the password again
				wipe(keystorePassword);
				showKeystoreError(e);
				return null;
			}
		}
	}

	private void rememberPassword(final char[] password) {
		wipe(rememberedKeystorePassword);
		rememberedKeystorePassword = Arrays.copyOf(password, password.length);
	}

	private void showKeystoreError(final Exception e) {
		new QuestionDialog(this, LangResources.get("error"), LangResources.get("error") + ":\n" + e.getClass().getSimpleName() + ": " + e.getMessage(), LangResources.get("ok"))
				.withBackgroundColor(SwingColor.LightRed)
				.open();
	}

	private static void wipe(final char[] value) {
		if (value != null) {
			Arrays.fill(value, '\0');
		}
	}

	/**
	 * Opened keystore together with the password it was opened with.
	 */
	private static final class OpenedKeystore {
		private final SecureDataStore keystore;
		private final char[] password;

		private OpenedKeystore(final SecureDataStore keystore, final char[] password) {
			this.keystore = keystore;
			this.password = password;
		}
	}
}

package de.soderer.applicationbase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import de.soderer.utilities.WrongPasswordException;

/**
 * Store for named objects like {@link UnsecureDataStore}, but saved to a password encrypted file.
 *
 * <p>
 * File format: 16 bytes random salt, 12 bytes random GCM initialization vector, followed by the AES-256-GCM encrypted
 * JSON data (including the 128 bit authentication tag). The AES key is derived from the password by
 * PBKDF2WithHmacSHA512 with 210000 iterations.
 * </p>
 *
 * <p>
 * Note: Without password (null) the empty password is used, which provides no confidentiality and no protection
 * against manipulation of the file.
 * </p>
 */
public class SecureDataStore extends UnsecureDataStore {
	private static final String SYMMETRIC_ENCRYPTION_METHOD = "AES/GCM/NoPadding";
	private static final int RANDOMSALTSIZE = 16;
	private static final int GCM_IV_SIZE = 12;
	private static final int GCM_TAG_LENGTH_BITS = 128;
	private static final int DERIVED_KEY_LENGTH_BITS = 256;
	private static final int PBKDF2_ITERATIONS = 210000;

	/**
	 * Creates an empty store.
	 */
	public SecureDataStore() {
		super();
	}

	/**
	 * Saves all entries encrypted with the empty password.
	 *
	 * @param storeFile file to write
	 * @throws Exception if the file is undefined or cannot be written
	 */
	@Override
	public void save(final File storeFile) throws Exception {
		save(storeFile, null);
	}

	/**
	 * Saves all entries encrypted with a password. The data is written to a temporary file first, which then
	 * replaces the store file, so an existing store file stays intact if writing fails.
	 *
	 * @param storeFile file to write
	 * @param password password for encryption, or null for the empty password. The array is cleared (filled with
	 *            zeros) afterwards, also if saving fails.
	 * @throws Exception if the file is undefined, an entry cannot be serialized or the file cannot be written
	 */
	public void save(final File storeFile, char[] password) throws Exception {
		try {
			if (storeFile == null) {
				throw new Exception("SecureDataStore file is undefined");
			} else {
				if (password == null) {
					password = "".toCharArray();
				}
				final char[] passwordToUse = password;
				saveViaTemporaryFile(storeFile, outputStream -> {
					byte[] salt = null;
					byte[] iv = null;
					byte[] keyBytes = null;
					try {
						final SecureRandom secureRandom = new SecureRandom();
						salt = new byte[RANDOMSALTSIZE];
						secureRandom.nextBytes(salt);
						outputStream.write(salt);

						iv = new byte[GCM_IV_SIZE];
						secureRandom.nextBytes(iv);
						outputStream.write(iv);

						keyBytes = stretchPassword(passwordToUse, salt);
						final SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
						final Cipher encryptCipher = Cipher.getInstance(SYMMETRIC_ENCRYPTION_METHOD);
						encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
						try (CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, encryptCipher)) {
							writeEntries(cipherOutputStream);
						}
					} finally {
						clear(salt);
						clear(iv);
						clear(keyBytes);
					}
				});
			}
		} finally {
			if (password != null) {
				Arrays.fill(password, '\u0000');
			}
		}
	}

	/**
	 * Loads all entries from a file encrypted with the empty password.
	 *
	 * @param storeFile file to read
	 * @throws Exception if the file is undefined, does not exist or contains invalid data
	 */
	@Override
	public void load(final File storeFile) throws Exception {
		load(storeFile, null);
	}

	/**
	 * Loads all entries from an encrypted file, replacing the current entries. If loading fails (e.g. wrong password),
	 * the current entries stay unchanged.
	 *
	 * @param storeFile file to read
	 * @param password password for decryption, or null for the empty password. The array is cleared (filled with
	 *            zeros) afterwards, also if loading fails.
	 * @throws WrongPasswordException if the password is wrong or the file was manipulated
	 * @throws Exception if the file is undefined, does not exist or contains invalid data
	 */
	public void load(final File storeFile, char[] password) throws Exception {
		try {
			if (storeFile == null) {
				throw new Exception("SecureDataStore file is undefined");
			} else if (!storeFile.exists()) {
				throw new Exception("SecureDataStore file does not exist: " + storeFile.getAbsolutePath());
			} else {
				if (password == null) {
					password = "".toCharArray();
				}
				byte[] salt = null;
				byte[] iv = null;
				byte[] keyBytes = null;
				try (FileInputStream inputStream = new FileInputStream(storeFile)) {
					salt = inputStream.readNBytes(RANDOMSALTSIZE);
					if (salt.length != RANDOMSALTSIZE) {
						throw new Exception("Cannot read password salt prefix: Data is too short");
					}

					iv = inputStream.readNBytes(GCM_IV_SIZE);
					if (iv.length != GCM_IV_SIZE) {
						throw new Exception("Cannot read initialization vector prefix: Data is too short");
					}

					keyBytes = stretchPassword(password, salt);
					final SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
					final Cipher decryptCipher = Cipher.getInstance(SYMMETRIC_ENCRYPTION_METHOD);
					decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
					final Map<String, Map<String, Object>> readEntries;
					try (CipherInputStream cipherInputStream = new CipherInputStream(inputStream, decryptCipher)) {
						readEntries = readEntries(cipherInputStream, "SecureDataStore");
					} catch (final IOException e) {
						if (e.getCause() != null && (e.getCause() instanceof AEADBadTagException || e.getCause() instanceof BadPaddingException)) {
							throw new WrongPasswordException();
						} else {
							throw e;
						}
					}
					// Only replace the current entries, if all data was decrypted and read successfully
					dataEntries = readEntries;
				} finally {
					clear(salt);
					clear(iv);
					clear(keyBytes);
				}
			}
		} finally {
			if (password != null) {
				Arrays.fill(password, '\u0000');
			}
		}
	}

	private static byte[] stretchPassword(final char[] password, final byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
		final String algorithm = "PBKDF2WithHmacSHA512";
		final PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, DERIVED_KEY_LENGTH_BITS);
		try {
			final SecretKeyFactory f = SecretKeyFactory.getInstance(algorithm);
			return f.generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	/**
	 * Overwrite all bytes of an array with zero, e.g. to remove key data from memory.
	 *
	 * @param array array to clear, may be null
	 */
	public static void clear(final byte[] array) {
		if (array != null) {
			Arrays.fill(array, (byte) 0);
		}
	}
}

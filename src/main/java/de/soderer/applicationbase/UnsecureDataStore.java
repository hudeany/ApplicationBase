package de.soderer.applicationbase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;
import de.soderer.json.JsonReader.JsonToken;
import de.soderer.json.JsonSerializer;
import de.soderer.json.JsonWriter;
import de.soderer.utilities.StringUtilities;

/**
 * Store for named objects, grouped by their class, which is saved to a plain (unencrypted) JSON file.
 * Objects are serialized with {@link JsonSerializer}. See {@link SecureDataStore} for an encrypted variant.
 *
 * <p>
 * Note: Loading a store file instantiates the classes named in that file, so only load files from trusted sources.
 * </p>
 */
public class UnsecureDataStore {
	/**
	 * First key is class name of entries grouped by their class name, second key is the entry name
	 */
	protected Map<String, Map<String, Object>> dataEntries = new HashMap<>();

	/**
	 * Creates an empty store.
	 */
	public UnsecureDataStore() {
		// Empty store
	}

	/**
	 * Saves all entries to a file. The data is written to a temporary file first, which then replaces the store file,
	 * so an existing store file stays intact if writing fails.
	 *
	 * @param storeFile file to write
	 * @throws Exception if the file is undefined, an entry cannot be serialized or the file cannot be written
	 */
	public void save(final File storeFile) throws Exception {
		if (storeFile == null) {
			throw new Exception("UnsecureDataStore file is undefined");
		} else {
			saveViaTemporaryFile(storeFile, outputStream -> writeEntries(outputStream));
		}
	}

	/**
	 * Loads all entries from a file, replacing the current entries. If loading fails, the current entries stay unchanged.
	 *
	 * @param storeFile file to read
	 * @throws Exception if the file is undefined, does not exist or contains invalid data
	 */
	public void load(final File storeFile) throws Exception {
		if (storeFile == null) {
			throw new Exception("UnsecureDataStore file is undefined");
		} else if (!storeFile.exists()) {
			throw new Exception("UnsecureDataStore file does not exist: " + storeFile.getAbsolutePath());
		} else {
			try (FileInputStream inputStream = new FileInputStream(storeFile)) {
				// Only replace the current entries, if all data was read successfully
				dataEntries = readEntries(inputStream, "UnsecureDataStore");
			}
		}
	}

	/**
	 * Writes all entries as JSON array to the stream. The stream is closed afterwards.
	 *
	 * @param outputStream stream to write to
	 * @throws Exception if an entry cannot be serialized or written
	 */
	protected void writeEntries(final OutputStream outputStream) throws Exception {
		try (JsonWriter jsonWriter = new JsonWriter(outputStream)) {
			jsonWriter.openJsonArray();
			for (final Entry<String, Map<String, Object>> entryMap : dataEntries.entrySet()) {
				for (final Entry<String, Object> entry : entryMap.getValue().entrySet()) {
					final JsonObject entryObject = new JsonObject();
					entryObject.add("class", entryMap.getKey());
					entryObject.add("name", entry.getKey());
					entryObject.add("value", JsonSerializer.serialize(entry.getValue(), false, false, false, true));
					jsonWriter.add(entryObject);
				}
			}
			jsonWriter.closeJsonArray();
		}
	}

	/**
	 * Reads entries written by {@link #writeEntries(OutputStream)} into a new map (the current entries are not changed).
	 *
	 * @param inputStream stream to read from
	 * @param storeTypeName name of the store type for error messages
	 * @return new map of entries
	 * @throws Exception if the data is invalid or cannot be read
	 */
	protected static Map<String, Map<String, Object>> readEntries(final InputStream inputStream, final String storeTypeName) throws Exception {
		final Map<String, Map<String, Object>> readEntries = new HashMap<>();
		try (JsonReader jsonReader = new JsonReader(inputStream)) {
			final JsonToken jsonToken = jsonReader.readNextToken();
			if (jsonToken != JsonToken.JsonArray_Open) {
				throw new Exception(storeTypeName + " is corrupt");
			} else {
				JsonNode nextJsonNode;
				while ((nextJsonNode = jsonReader.readNextJsonNode()) != null) {
					if (nextJsonNode.isJsonObject()) {
						final JsonObject entryObject = (JsonObject) nextJsonNode;
						final String className = (String) entryObject.getSimpleValue("class");
						final String entryName = (String) entryObject.getSimpleValue("name");
						readEntries.computeIfAbsent(className, k -> new HashMap<>()).put(entryName, JsonSerializer.deserialize((JsonObject) entryObject.get("value")));
					} else {
						throw new Exception("Unexpected JSON data in " + storeTypeName + ": " + nextJsonNode.getJsonDataType().getName());
					}
				}
			}
		}
		return readEntries;
	}

	/**
	 * Writer of the complete content of a store file
	 */
	@FunctionalInterface
	protected interface StoreContentWriter {
		/**
		 * Writes the complete content to the stream.
		 *
		 * @param outputStream stream to write to (may be closed by the writer)
		 * @throws Exception if the content cannot be written
		 */
		void write(OutputStream outputStream) throws Exception;
	}

	/**
	 * Writes a file via a temporary file in the same directory, which replaces the target file only after it was
	 * written completely. The temporary file is removed if writing fails.
	 *
	 * @param targetFile file to write
	 * @param contentWriter writer of the file content
	 * @throws Exception if the file cannot be written
	 */
	protected static void saveViaTemporaryFile(final File targetFile, final StoreContentWriter contentWriter) throws Exception {
		final File absoluteTargetFile = targetFile.getAbsoluteFile();
		final File temporaryFile = File.createTempFile("." + absoluteTargetFile.getName() + ".", ".tmp", absoluteTargetFile.getParentFile());
		boolean success = false;
		try {
			try (FileOutputStream outputStream = new FileOutputStream(temporaryFile)) {
				contentWriter.write(outputStream);
			}
			try {
				Files.move(temporaryFile.toPath(), absoluteTargetFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (@SuppressWarnings("unused") final AtomicMoveNotSupportedException e) {
				Files.move(temporaryFile.toPath(), absoluteTargetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			success = true;
		} finally {
			if (!success) {
				try {
					Files.deleteIfExists(temporaryFile.toPath());
				} catch (@SuppressWarnings("unused") final IOException e) {
					// Do nothing, the original exception is more important
				}
			}
		}
	}

	/**
	 * Names of all entries of a class.
	 *
	 * @param classType class of the entries
	 * @return live set of the entry names (changes of the store are reflected), empty if there are no entries of this class
	 */
	public Set<String> getEntryNames(final Class<?> classType) {
		if (dataEntries.get(classType.getName()) == null) {
			return new HashSet<>();
		} else {
			return dataEntries.get(classType.getName()).keySet();
		}
	}

	/**
	 * Entry of a class by its name.
	 *
	 * @param <T> type of the entry
	 * @param classType exact class of the entry (as it was added)
	 * @param entryName name of the entry
	 * @return the entry, or null if there is none
	 */
	public <T> T getEntry(final Class<T> classType, final String entryName) {
		if (dataEntries.get(classType.getName()) == null) {
			return null;
		}
		@SuppressWarnings("unchecked")
		final T returnValue = (T) dataEntries.get(classType.getName()).get(entryName);
		return returnValue;
	}

	/**
	 * Entry by its name, regardless of its class. If entries of several classes have this name, any of them is returned.
	 *
	 * @param entryName name of the entry
	 * @return the entry, or null if there is none
	 */
	public Object getEntry(final String entryName) {
		for (final Entry<String, Map<String, Object>> classEntry : dataEntries.entrySet()) {
			for (final Entry<String, Object> entry : classEntry.getValue().entrySet()) {
				if (StringUtilities.equals(entryName, entry.getKey())) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	/**
	 * Adds or replaces an entry. The entry is grouped by its runtime class.
	 *
	 * @param entryName name of the entry
	 * @param entryValue the entry, must not be null
	 */
	public void addEntry(final String entryName, final Object entryValue) {
		if (entryValue == null) {
			throw new IllegalArgumentException("Entry value must not be null");
		}
		dataEntries.computeIfAbsent(entryValue.getClass().getName(), k -> new HashMap<>()).put(entryName, entryValue);
	}

	/**
	 * Removes an entry of a class. Does nothing if there is no such entry.
	 *
	 * @param classType class of the entry
	 * @param entryName name of the entry
	 */
	public void removeEntry(final Class<?> classType, final String entryName) {
		final Map<String, Object> entriesOfClass = dataEntries.get(classType.getName());
		if (entriesOfClass != null) {
			entriesOfClass.remove(entryName);
		}
	}

	/**
	 * Removes all entries with this name, regardless of their class.
	 *
	 * @param entryName name of the entries
	 */
	public void removeEntriesByEntryName(final String entryName) {
		for (final Entry<String, Map<String, Object>> classEntry : dataEntries.entrySet()) {
			classEntry.getValue().remove(entryName);
		}
	}

	/**
	 * Removes all entries of a class.
	 *
	 * @param classType class of the entries
	 */
	public void removeEntriesByEntryClass(final Class<?> classType) {
		dataEntries.remove(classType.getName());
	}
}

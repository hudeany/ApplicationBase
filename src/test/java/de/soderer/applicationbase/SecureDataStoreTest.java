package de.soderer.applicationbase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.soderer.applicationbase.TestSecureDataStoreEntry.TestEnum;
import de.soderer.utilities.WrongPasswordException;
import de.soderer.utilities.code.ClassUtilities;

@SuppressWarnings("static-method")
public class SecureDataStoreTest {
	public static final String TEST_PASSWORD = "test@PasswordÄÖÜäöüß";

	public static final String TEST_ENTRYNAME = "entry Näme";

	@Test
	public void test() throws Exception {
		final File testStoreFile = File.createTempFile("test", ".secpref");
		try {
			final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
			final SecureDataStore testKeyStore_ForSave = new SecureDataStore();

			final TestSecureDataStoreEntry secureDataEntry = new TestSecureDataStoreEntry();
			secureDataEntry.valueBooleanPrimitive = true;
			secureDataEntry.valueBooleanPrimitiveArray = new boolean[] { true, false };
			secureDataEntry.valueBoolean = Boolean.TRUE;
			secureDataEntry.valueBooleanArray = new Boolean[] { Boolean.TRUE, Boolean.FALSE };
			secureDataEntry.valueBooleanSet = new HashSet<>(Arrays.asList(secureDataEntry.valueBooleanArray));
			secureDataEntry.valueBooleanList = new ArrayList<>(Arrays.asList(secureDataEntry.valueBooleanArray));
			secureDataEntry.valueBooleanCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueBooleanArray));
			secureDataEntry.valueBytePrimitive = Byte.MAX_VALUE;
			secureDataEntry.valueBytePrimitiveArray = new byte[] { Byte.MAX_VALUE, Byte.MIN_VALUE };
			secureDataEntry.valueByte = Byte.MAX_VALUE;
			secureDataEntry.valueByteArray = new Byte[] { Byte.MAX_VALUE, Byte.MIN_VALUE };
			secureDataEntry.valueByteSet = new HashSet<>(Arrays.asList(secureDataEntry.valueByteArray));
			secureDataEntry.valueByteList = new ArrayList<>(Arrays.asList(secureDataEntry.valueByteArray));
			secureDataEntry.valueByteCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueByteArray));
			secureDataEntry.valueShortPrimitive = Short.MAX_VALUE;
			secureDataEntry.valueShortPrimitiveArray = new short[] { Short.MAX_VALUE, Short.MIN_VALUE };
			secureDataEntry.valueShort = Short.MAX_VALUE;
			secureDataEntry.valueShortArray = new Short[] { Short.MAX_VALUE, Short.MIN_VALUE };
			secureDataEntry.valueShortSet = new HashSet<>(Arrays.asList(secureDataEntry.valueShortArray));
			secureDataEntry.valueShortList = new ArrayList<>(Arrays.asList(secureDataEntry.valueShortArray));
			secureDataEntry.valueShortCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueShortArray));
			secureDataEntry.valueIntegerPrimitive = Integer.MAX_VALUE;
			secureDataEntry.valueIntegerPrimitiveArray = new int[] { Integer.MAX_VALUE, Integer.MIN_VALUE };
			secureDataEntry.valueInteger = Integer.MAX_VALUE;
			secureDataEntry.valueIntegerArray = new Integer[] { Integer.MAX_VALUE, Integer.MIN_VALUE };
			secureDataEntry.valueIntegerSet = new HashSet<>(Arrays.asList(secureDataEntry.valueIntegerArray));
			secureDataEntry.valueIntegerList = new ArrayList<>(Arrays.asList(secureDataEntry.valueIntegerArray));
			secureDataEntry.valueIntegerCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueIntegerArray));
			secureDataEntry.valueLongPrimitive = Long.MAX_VALUE;
			secureDataEntry.valueLongPrimitiveArray = new long[] { Long.MAX_VALUE, Long.MIN_VALUE };
			secureDataEntry.valueLong = Long.MAX_VALUE;
			secureDataEntry.valueLongArray = new Long[] { Long.MAX_VALUE, Long.MIN_VALUE };
			secureDataEntry.valueLongSet = new HashSet<>(Arrays.asList(secureDataEntry.valueLongArray));
			secureDataEntry.valueLongList = new ArrayList<>(Arrays.asList(secureDataEntry.valueLongArray));
			secureDataEntry.valueLongCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueLongArray));
			secureDataEntry.valueFloatPrimitive = 1.1f;
			secureDataEntry.valueFloatPrimitiveArray = new float[] { Float.MAX_VALUE, Float.MIN_VALUE };
			secureDataEntry.valueFloat = 1.1f;
			secureDataEntry.valueFloatArray = new Float[] { Float.MAX_VALUE, Float.MIN_VALUE };
			secureDataEntry.valueFloatSet = new HashSet<>(Arrays.asList(secureDataEntry.valueFloatArray));
			secureDataEntry.valueFloatList = new ArrayList<>(Arrays.asList(secureDataEntry.valueFloatArray));
			secureDataEntry.valueFloatCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueFloatArray));
			secureDataEntry.valueDoublePrimitive = 1.2d;
			secureDataEntry.valueDoublePrimitiveArray = new double[] { Double.MAX_VALUE, Double.MIN_VALUE * Math.pow(10, 5) };
			secureDataEntry.valueDouble = 1.2d;
			secureDataEntry.valueDoubleArray = new Double[] { Double.MAX_VALUE, Double.MIN_VALUE * Math.pow(10, 5) };
			secureDataEntry.valueDoubleSet = new HashSet<>(Arrays.asList(secureDataEntry.valueDoubleArray));
			secureDataEntry.valueDoubleList = new ArrayList<>(Arrays.asList(secureDataEntry.valueDoubleArray));
			secureDataEntry.valueDoubleCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueDoubleArray));
			secureDataEntry.valueCharacterPrimitive = 'a';
			secureDataEntry.valueCharacterPrimitiveArray = new char[] { 'a', 'b' };
			secureDataEntry.valueCharacter = 'a';
			secureDataEntry.valueCharacterArray = new Character[] { 'a', 'b' };
			secureDataEntry.valueCharacterSet = new HashSet<>(Arrays.asList(secureDataEntry.valueCharacterArray));
			secureDataEntry.valueCharacterList = new ArrayList<>(Arrays.asList(secureDataEntry.valueCharacterArray));
			secureDataEntry.valueCharacterCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueCharacterArray));
			secureDataEntry.valueString = "abc";
			secureDataEntry.valueStringArray = new String[] { "abc", "def" };
			secureDataEntry.valueStringSet = new HashSet<>(Arrays.asList(secureDataEntry.valueStringArray));
			secureDataEntry.valueStringList = new ArrayList<>(Arrays.asList(secureDataEntry.valueStringArray));
			secureDataEntry.valueStringCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueStringArray));
			secureDataEntry.valueDate = now;
			secureDataEntry.valueDateArray = new LocalDateTime[] { now, now };
			secureDataEntry.valueDateSet = new HashSet<>(Arrays.asList(secureDataEntry.valueDateArray));
			secureDataEntry.valueDateList = new ArrayList<>(Arrays.asList(secureDataEntry.valueDateArray));
			secureDataEntry.valueDateCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueDateArray));
			secureDataEntry.valueTestEnum = TestEnum.Enum1;
			secureDataEntry.valueTestEnumArray = new TestEnum[] { TestEnum.Enum1, TestEnum.Enum2 };
			secureDataEntry.valueTestEnumSet = new HashSet<>(Arrays.asList(secureDataEntry.valueTestEnumArray));
			secureDataEntry.valueTestEnumList = new ArrayList<>(Arrays.asList(secureDataEntry.valueTestEnumArray));
			secureDataEntry.valueTestEnumCollection = new ArrayList<>(Arrays.asList(secureDataEntry.valueTestEnumArray));
			secureDataEntry.valueMapIntegerString = new HashMap<>();
			secureDataEntry.valueMapIntegerString.put(1, "abc");
			secureDataEntry.valueMapIntegerString.put(2, "def");
			secureDataEntry.testSecureDataStoreEntry = new TestSecureDataStoreEntry();

			testKeyStore_ForSave.addEntry(TEST_ENTRYNAME, secureDataEntry);
			testKeyStore_ForSave.save(testStoreFile, TEST_PASSWORD.toCharArray());

			final SecureDataStore testKeyStore_ForLoad = new SecureDataStore();
			testKeyStore_ForLoad.load(testStoreFile, TEST_PASSWORD.toCharArray());

			final Set<String> loadedSecureDataEntryNames = testKeyStore_ForLoad.getEntryNames(TestSecureDataStoreEntry.class);
			assertEquals(1, loadedSecureDataEntryNames.size());
			assertTrue(loadedSecureDataEntryNames.contains(TEST_ENTRYNAME));

			final TestSecureDataStoreEntry loadedSecureDataEntry = testKeyStore_ForLoad.getEntry(TestSecureDataStoreEntry.class, TEST_ENTRYNAME);
			assertTestSecureDataStoreEntryEquals(secureDataEntry, loadedSecureDataEntry);

			try {
				new SecureDataStore().load(testStoreFile, "abc".toCharArray());
				fail("Missing expected exception");
			} catch (final WrongPasswordException e) {
				assertNull(e.getMessage());
			} catch (final Exception e) {
				fail("Other than expected exception: " + e.getClass() + ": " + e.getMessage());
			}
		} finally {
			if (testStoreFile.exists()) {
				testStoreFile.delete();
			}
		}
	}

	private static void assertTestSecureDataStoreEntryEquals(final TestSecureDataStoreEntry expectedObject, final TestSecureDataStoreEntry actualObject) throws Exception {
		if (expectedObject != actualObject) {
			assertArrayEquals(expectedObject.valueBooleanPrimitiveArray, actualObject.valueBooleanPrimitiveArray);
			assertArrayEquals(expectedObject.valueBytePrimitiveArray, actualObject.valueBytePrimitiveArray);
			assertArrayEquals(expectedObject.valueShortPrimitiveArray, actualObject.valueShortPrimitiveArray);
			assertArrayEquals(expectedObject.valueIntegerPrimitiveArray, actualObject.valueIntegerPrimitiveArray);
			assertArrayEquals(expectedObject.valueLongPrimitiveArray, actualObject.valueLongPrimitiveArray);
			assertArrayEquals(expectedObject.valueFloatPrimitiveArray, actualObject.valueFloatPrimitiveArray);
			assertArrayEquals(expectedObject.valueDoublePrimitiveArray, actualObject.valueDoublePrimitiveArray);
			assertArrayEquals(expectedObject.valueCharacterPrimitiveArray, actualObject.valueCharacterPrimitiveArray);
			assertArrayEquals(expectedObject.valueStringArray, actualObject.valueStringArray);
			assertArrayEquals(expectedObject.valueDateArray, actualObject.valueDateArray);
			assertArrayEquals(expectedObject.valueTestEnumArray, actualObject.valueTestEnumArray);
			for (final Field field : ClassUtilities.getAllFields(TestSecureDataStoreEntry.class)) {
				field.setAccessible(true);
				if (field.getType() == TestSecureDataStoreEntry.class) {
					assertTestSecureDataStoreEntryEquals((TestSecureDataStoreEntry) field.get(expectedObject), (TestSecureDataStoreEntry) field.get(actualObject));
				} else if (!field.getType().isArray()) {
					if (field.getType().getComponentType() == TestSecureDataStoreEntry.class) {
						final TestSecureDataStoreEntry[] expectedArray = (TestSecureDataStoreEntry[]) field.get(expectedObject);
						final TestSecureDataStoreEntry[] actualArray = (TestSecureDataStoreEntry[]) field.get(actualObject);
						assertEquals(expectedArray.length, actualArray.length);
						for (int i = 0; i < expectedArray.length; i++) {
							assertTestSecureDataStoreEntryEquals(expectedArray[i], actualArray[i]);
						}
					} else {
						assertEquals(field.get(expectedObject), field.get(actualObject), field.getName());
					}
				}
			}
		}
	}
}

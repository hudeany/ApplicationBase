package de.soderer.applicationbase;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestSecureDataStoreEntry {
	public boolean valueBooleanPrimitive;
	public boolean[] valueBooleanPrimitiveArray;
	public Boolean valueBoolean;
	public Boolean[] valueBooleanArray;
	public Set<Boolean> valueBooleanSet;
	public List<Boolean> valueBooleanList;
	public Collection<Boolean> valueBooleanCollection;

	public byte valueBytePrimitive;
	public byte[] valueBytePrimitiveArray;
	public Byte valueByte;
	public Byte[] valueByteArray;
	public Set<Byte> valueByteSet;
	public List<Byte> valueByteList;
	public Collection<Byte> valueByteCollection;

	public short valueShortPrimitive;
	public short[] valueShortPrimitiveArray;
	public Short valueShort;
	public Short[] valueShortArray;
	public Set<Short> valueShortSet;
	public List<Short> valueShortList;
	public Collection<Short> valueShortCollection;

	public int valueIntegerPrimitive;
	public int[] valueIntegerPrimitiveArray;
	public Integer valueInteger;
	public Integer[] valueIntegerArray;
	public Set<Integer> valueIntegerSet;
	public List<Integer> valueIntegerList;
	public Collection<Integer> valueIntegerCollection;

	public long valueLongPrimitive;
	public long[] valueLongPrimitiveArray;
	public Long valueLong;
	public Long[] valueLongArray;
	public Set<Long> valueLongSet;
	public List<Long> valueLongList;
	public Collection<Long> valueLongCollection;

	public float valueFloatPrimitive;
	public float[] valueFloatPrimitiveArray;
	public Float valueFloat;
	public Float[] valueFloatArray;
	public Set<Float> valueFloatSet;
	public List<Float> valueFloatList;
	public Collection<Float> valueFloatCollection;

	public double valueDoublePrimitive;
	public double[] valueDoublePrimitiveArray;
	public Double valueDouble;
	public Double[] valueDoubleArray;
	public Set<Double> valueDoubleSet;
	public List<Double> valueDoubleList;
	public Collection<Double> valueDoubleCollection;

	public char valueCharacterPrimitive;
	public char[] valueCharacterPrimitiveArray;
	public Character valueCharacter;
	public Character[] valueCharacterArray;
	public Set<Character> valueCharacterSet;
	public List<Character> valueCharacterList;
	public Collection<Character> valueCharacterCollection;

	public String valueString;
	public String[] valueStringArray;
	public Set<String> valueStringSet;
	public List<String> valueStringList;
	public Collection<String> valueStringCollection;

	public LocalDateTime valueDate;
	public LocalDateTime[] valueDateArray;
	public Set<LocalDateTime> valueDateSet;
	public List<LocalDateTime> valueDateList;
	public Collection<LocalDateTime> valueDateCollection;

	public TestEnum valueTestEnum;
	public TestEnum[] valueTestEnumArray;
	public Set<TestEnum> valueTestEnumSet;
	public List<TestEnum> valueTestEnumList;
	public Collection<TestEnum> valueTestEnumCollection;

	public Map<Integer, String> valueMapIntegerString;

	public TestSecureDataStoreEntry testSecureDataStoreEntry;

	public enum TestEnum {
		Enum1,
		Enum2
	}
}

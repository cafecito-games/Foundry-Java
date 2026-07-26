package games.cafecito.foundry.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.types.Color;
import games.cafecito.foundry.types.FoundryArray;
import games.cafecito.foundry.types.FoundryDictionary;
import games.cafecito.foundry.types.PackedByteArray;
import games.cafecito.foundry.types.PackedColorArray;
import games.cafecito.foundry.types.PackedFloat32Array;
import games.cafecito.foundry.types.PackedFloat64Array;
import games.cafecito.foundry.types.PackedInt32Array;
import games.cafecito.foundry.types.PackedInt64Array;
import games.cafecito.foundry.types.PackedStringArray;
import games.cafecito.foundry.types.PackedVector2Array;
import games.cafecito.foundry.types.PackedVector3Array;
import games.cafecito.foundry.types.PackedVector4Array;
import games.cafecito.foundry.types.Variant;
import games.cafecito.foundry.types.VariantCodec;
import games.cafecito.foundry.types.VariantConversionException;
import games.cafecito.foundry.types.Vector2;
import games.cafecito.foundry.types.Vector3;
import games.cafecito.foundry.types.Vector4;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectionSemanticsTest {
    @Test
    void arrayAliasesShareStorageAndDuplicatesDetach() {
        FoundryArray<String> values = new FoundryArray<>(VariantCodec.STRING);
        values.add("first");
        FoundryArray<String> alias = new FoundryArray<>(values);
        FoundryArray<String> duplicate = values.duplicate();

        alias.set(0, "shared");
        duplicate.add("detached");

        assertEquals(List.of("shared"), values.toList());
        assertEquals(List.of("shared"), alias.toList());
        assertEquals(List.of("first", "detached"), duplicate.toList());
    }

    @Test
    void arrayCodecsValidateEveryMutationAndNilPolicy() {
        FoundryArray<String> strings = new FoundryArray<>(VariantCodec.STRING);
        strings.add("valid");

        assertThrows(NullPointerException.class, () -> strings.add(null));
        assertThrows(VariantConversionException.class, () -> strings.addVariant(Variant.of(1L)));

        FoundryArray<Variant> variants = FoundryArray.untyped();
        variants.add(Variant.nil());
        variants.addVariant(Variant.of("value"));
        assertEquals(List.of(Variant.nil(), Variant.of("value")), variants.toList());

        FoundryArray<String> nullable =
                new FoundryArray<>(VariantCodec.nullable(VariantCodec.STRING));
        nullable.add(null);
        nullable.addVariant(Variant.nil());
        assertEquals(2, nullable.size());
        assertEquals(null, nullable.get(0));
        assertTrue(VariantCodec.nullable(VariantCodec.STRING).acceptsNil());
    }

    @Test
    void arraysReportActionableBoundsAndReturnSnapshots() {
        FoundryArray<Long> values = new FoundryArray<>(VariantCodec.INTEGER);
        values.add(3L);
        List<Long> snapshot = values.toList();
        values.set(0, 4L);

        IndexOutOfBoundsException error =
                assertThrows(IndexOutOfBoundsException.class, () -> values.get(2));
        assertTrue(error.getMessage().contains("2"));
        assertTrue(error.getMessage().contains("1"));
        assertEquals(List.of(3L), snapshot);
    }

    @Test
    void dictionariesPreserveIterationOrderButCompareByContent() {
        FoundryDictionary<String, Long> first =
                new FoundryDictionary<>(VariantCodec.STRING, VariantCodec.INTEGER);
        first.put("one", 1L);
        first.put("two", 2L);
        FoundryDictionary<String, Long> alias = new FoundryDictionary<>(first);
        FoundryDictionary<String, Long> duplicate = first.duplicate();

        alias.put("three", 3L);
        duplicate.remove("one");

        assertEquals(List.of("one", "two", "three"), List.copyOf(first.toMap().keySet()));
        assertEquals(List.of("two"), List.copyOf(duplicate.toMap().keySet()));

        FoundryDictionary<String, Long> reordered =
                new FoundryDictionary<>(VariantCodec.STRING, VariantCodec.INTEGER);
        reordered.put("three", 3L);
        reordered.put("two", 2L);
        reordered.put("one", 1L);
        assertEquals(first, reordered);
        assertEquals(first.hashCode(), reordered.hashCode());
    }

    @Test
    void dictionaryCodecsValidateKeysAndValues() {
        FoundryDictionary<String, Long> values =
                new FoundryDictionary<>(VariantCodec.STRING, VariantCodec.INTEGER);

        assertThrows(NullPointerException.class, () -> values.put(null, 1L));
        assertThrows(
                VariantConversionException.class,
                () -> values.putVariants(Variant.of("key"), Variant.of("wrong")));
        assertEquals(Map.of(), values.toMap());
    }

    @Test
    void deepDuplicationDetachesNestedCollections() {
        FoundryArray<Variant> child = FoundryArray.untyped();
        child.add(Variant.of("before"));
        FoundryArray<Variant> parent = FoundryArray.untyped();
        parent.add(Variant.of(child));

        FoundryArray<Variant> shallow = parent.duplicate();
        FoundryArray<Variant> deep = parent.duplicateDeep();
        child.set(0, Variant.of("after"));

        FoundryArray<?> shallowChild = (FoundryArray<?>) shallow.get(0).value();
        FoundryArray<?> deepChild = (FoundryArray<?>) deep.get(0).value();
        assertEquals(Variant.of("after"), shallowChild.get(0));
        assertEquals(Variant.of("before"), deepChild.get(0));
    }

    @Test
    void primitivePackedArraysCopyInputsAndOutputs() {
        byte[] bytes = {1, 2};
        int[] ints = {3, 4};
        long[] longs = {5, 6};
        float[] floats = {7.0f, 8.0f};
        double[] doubles = {9.0d, 10.0d};
        PackedByteArray packedBytes = new PackedByteArray(bytes);
        PackedInt32Array packedInts = new PackedInt32Array(ints);
        PackedInt64Array packedLongs = new PackedInt64Array(longs);
        PackedFloat32Array packedFloats = new PackedFloat32Array(floats);
        PackedFloat64Array packedDoubles = new PackedFloat64Array(doubles);

        bytes[0] = 99;
        ints[0] = 99;
        longs[0] = 99;
        floats[0] = 99;
        doubles[0] = 99;
        byte[] bytesOut = packedBytes.toArray();
        bytesOut[1] = 99;

        assertArrayEquals(new byte[] {1, 2}, packedBytes.toArray());
        assertArrayEquals(new int[] {3, 4}, packedInts.toArray());
        assertArrayEquals(new long[] {5, 6}, packedLongs.toArray());
        assertArrayEquals(new float[] {7.0f, 8.0f}, packedFloats.toArray());
        assertArrayEquals(new double[] {9.0d, 10.0d}, packedDoubles.toArray());
    }

    @Test
    void objectPackedArraysCopyInputsAndOutputs() {
        String[] strings = {"a", "b"};
        Vector2[] vector2s = {new Vector2(1, 2)};
        Vector3[] vector3s = {new Vector3(1, 2, 3)};
        Vector4[] vector4s = {new Vector4(1, 2, 3, 4)};
        Color[] colors = {new Color(0.1, 0.2, 0.3, 1.0)};
        PackedStringArray packedStrings = new PackedStringArray(strings);
        PackedVector2Array packedVector2s = new PackedVector2Array(vector2s);
        PackedVector3Array packedVector3s = new PackedVector3Array(vector3s);
        PackedVector4Array packedVector4s = new PackedVector4Array(vector4s);
        PackedColorArray packedColors = new PackedColorArray(colors);

        strings[0] = "changed";
        vector2s[0] = new Vector2(9, 9);
        String[] stringsOut = packedStrings.toArray();
        stringsOut[1] = "changed";

        assertArrayEquals(new String[] {"a", "b"}, packedStrings.toArray());
        assertArrayEquals(new Vector2[] {new Vector2(1, 2)}, packedVector2s.toArray());
        assertArrayEquals(new Vector3[] {new Vector3(1, 2, 3)}, packedVector3s.toArray());
        assertArrayEquals(new Vector4[] {new Vector4(1, 2, 3, 4)}, packedVector4s.toArray());
        assertArrayEquals(new Color[] {new Color(0.1, 0.2, 0.3, 1.0)}, packedColors.toArray());
    }

    @Test
    void packedArraysUseContentEqualityAndCheckedBounds() {
        PackedInt32Array first = new PackedInt32Array(new int[] {1, 2});
        PackedInt32Array same = new PackedInt32Array(new int[] {1, 2});
        PackedInt32Array different = new PackedInt32Array(new int[] {2, 1});

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
        IndexOutOfBoundsException error =
                assertThrows(IndexOutOfBoundsException.class, () -> first.get(2));
        assertTrue(error.getMessage().contains("2"));
        assertTrue(error.getMessage().contains("size 2"));
    }
}

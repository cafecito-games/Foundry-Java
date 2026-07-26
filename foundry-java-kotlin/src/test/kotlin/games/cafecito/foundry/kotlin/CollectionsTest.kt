package games.cafecito.foundry.kotlin

import games.cafecito.foundry.types.Color
import games.cafecito.foundry.types.PackedByteArray
import games.cafecito.foundry.types.PackedColorArray
import games.cafecito.foundry.types.PackedFloat32Array
import games.cafecito.foundry.types.PackedFloat64Array
import games.cafecito.foundry.types.PackedInt32Array
import games.cafecito.foundry.types.PackedInt64Array
import games.cafecito.foundry.types.PackedStringArray
import games.cafecito.foundry.types.PackedVector2Array
import games.cafecito.foundry.types.PackedVector3Array
import games.cafecito.foundry.types.PackedVector4Array
import games.cafecito.foundry.types.VariantCodec
import games.cafecito.foundry.types.Vector2
import games.cafecito.foundry.types.Vector3
import games.cafecito.foundry.types.Vector4
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollectionsTest {
    @Test
    fun `generic collections preserve insertion order and copy inputs`() {
        val source = mutableListOf(1L, 2L)
        val array = source.toFoundryArray(VariantCodec.INTEGER)
        val entries = linkedMapOf("a" to 1L, "b" to 2L)
        val dictionary =
            entries.toFoundryDictionary(
                VariantCodec.STRING,
                VariantCodec.INTEGER,
            )

        source[0] = 9L
        entries["a"] = 9L

        assertEquals(listOf(1L, 2L), array.toKotlinList())
        assertEquals(linkedMapOf("a" to 1L, "b" to 2L), dictionary.toKotlinMap())
    }

    @Test
    fun `primitive packed arrays round trip through copied storage`() {
        val bytes = byteArrayOf(1, 2)
        val packedBytes = bytes.toPackedByteArray()
        bytes[0] = 9
        val copiedBytes = packedBytes.toByteArray()
        copiedBytes[1] = 9
        assertContentEquals(byteArrayOf(1, 2), packedBytes.toByteArray())

        val ints = intArrayOf(3, 4)
        val packedInts = ints.toPackedInt32Array()
        ints[0] = 9
        val copiedInts = packedInts.toIntArray()
        copiedInts[1] = 9
        assertContentEquals(intArrayOf(3, 4), packedInts.toIntArray())

        val longs = longArrayOf(5L, 6L)
        val packedLongs = longs.toPackedInt64Array()
        longs[0] = 9L
        val copiedLongs = packedLongs.toLongArray()
        copiedLongs[1] = 9L
        assertContentEquals(longArrayOf(5L, 6L), packedLongs.toLongArray())

        val floats = floatArrayOf(1.25f, 2.5f)
        val packedFloats = floats.toPackedFloat32Array()
        floats[0] = 9f
        val copiedFloats = packedFloats.toFloatArray()
        copiedFloats[1] = 9f
        assertContentEquals(floatArrayOf(1.25f, 2.5f), packedFloats.toFloatArray())

        val doubles = doubleArrayOf(3.25, 4.5)
        val packedDoubles = doubles.toPackedFloat64Array()
        doubles[0] = 9.0
        val copiedDoubles = packedDoubles.toDoubleArray()
        copiedDoubles[1] = 9.0
        assertContentEquals(doubleArrayOf(3.25, 4.5), packedDoubles.toDoubleArray())
    }

    @Test
    fun `typed packed arrays round trip lists and arrays through copied storage`() {
        val strings = mutableListOf("coffee", "tea")
        val packedStrings = strings.toPackedStringArray()
        strings[0] = "changed"
        val copiedStrings = packedStrings.toKotlinArray()
        copiedStrings[1] = "changed"
        assertEquals(listOf("coffee", "tea"), packedStrings.toKotlinList())
        assertEquals(
            listOf("coffee", "tea"),
            arrayOf("coffee", "tea").toPackedStringArray().toKotlinList(),
        )

        val vector2s = mutableListOf(Vector2(1.0, 2.0), Vector2(3.0, 4.0))
        val packedVector2s = vector2s.toPackedVector2Array()
        vector2s[0] = Vector2(9.0, 9.0)
        val copiedVector2s = packedVector2s.toKotlinArray()
        copiedVector2s[1] = Vector2(9.0, 9.0)
        assertEquals(
            listOf(Vector2(1.0, 2.0), Vector2(3.0, 4.0)),
            packedVector2s.toKotlinList(),
        )
        assertEquals(
            listOf(Vector2(1.0, 2.0)),
            arrayOf(Vector2(1.0, 2.0)).toPackedVector2Array().toKotlinList(),
        )

        val vector3s = mutableListOf(Vector3(1.0, 2.0, 3.0), Vector3(4.0, 5.0, 6.0))
        val packedVector3s = vector3s.toPackedVector3Array()
        vector3s[0] = Vector3(9.0, 9.0, 9.0)
        val copiedVector3s = packedVector3s.toKotlinArray()
        copiedVector3s[1] = Vector3(9.0, 9.0, 9.0)
        assertEquals(
            listOf(Vector3(1.0, 2.0, 3.0), Vector3(4.0, 5.0, 6.0)),
            packedVector3s.toKotlinList(),
        )
        assertEquals(
            listOf(Vector3(1.0, 2.0, 3.0)),
            arrayOf(Vector3(1.0, 2.0, 3.0)).toPackedVector3Array().toKotlinList(),
        )

        val vector4s =
            mutableListOf(
                Vector4(1.0, 2.0, 3.0, 4.0),
                Vector4(5.0, 6.0, 7.0, 8.0),
            )
        val packedVector4s = vector4s.toPackedVector4Array()
        vector4s[0] = Vector4(9.0, 9.0, 9.0, 9.0)
        val copiedVector4s = packedVector4s.toKotlinArray()
        copiedVector4s[1] = Vector4(9.0, 9.0, 9.0, 9.0)
        assertEquals(
            listOf(Vector4(1.0, 2.0, 3.0, 4.0), Vector4(5.0, 6.0, 7.0, 8.0)),
            packedVector4s.toKotlinList(),
        )
        assertEquals(
            listOf(Vector4(1.0, 2.0, 3.0, 4.0)),
            arrayOf(Vector4(1.0, 2.0, 3.0, 4.0))
                .toPackedVector4Array()
                .toKotlinList(),
        )

        val colors =
            mutableListOf(
                Color(0.1, 0.2, 0.3, 0.4),
                Color(0.5, 0.6, 0.7, 0.8),
            )
        val packedColors = colors.toPackedColorArray()
        colors[0] = Color(1.0, 1.0, 1.0, 1.0)
        val copiedColors = packedColors.toKotlinArray()
        copiedColors[1] = Color(1.0, 1.0, 1.0, 1.0)
        assertEquals(
            listOf(Color(0.1, 0.2, 0.3, 0.4), Color(0.5, 0.6, 0.7, 0.8)),
            packedColors.toKotlinList(),
        )
        assertEquals(
            listOf(Color(0.1, 0.2, 0.3, 0.4)),
            arrayOf(Color(0.1, 0.2, 0.3, 0.4)).toPackedColorArray().toKotlinList(),
        )
    }

    @Test
    fun `packed Java values convert back through every family`() {
        assertContentEquals(byteArrayOf(1), PackedByteArray(byteArrayOf(1)).toByteArray())
        assertContentEquals(intArrayOf(2), PackedInt32Array(intArrayOf(2)).toIntArray())
        assertContentEquals(longArrayOf(3), PackedInt64Array(longArrayOf(3)).toLongArray())
        assertContentEquals(floatArrayOf(4f), PackedFloat32Array(floatArrayOf(4f)).toFloatArray())
        assertContentEquals(
            doubleArrayOf(5.0),
            PackedFloat64Array(doubleArrayOf(5.0)).toDoubleArray(),
        )
        assertEquals(listOf("six"), PackedStringArray(arrayOf("six")).toKotlinList())
        assertEquals(
            listOf(Vector2(7.0, 8.0)),
            PackedVector2Array(arrayOf(Vector2(7.0, 8.0))).toKotlinList(),
        )
        assertEquals(
            listOf(Vector3(9.0, 10.0, 11.0)),
            PackedVector3Array(arrayOf(Vector3(9.0, 10.0, 11.0))).toKotlinList(),
        )
        assertEquals(
            listOf(Vector4(12.0, 13.0, 14.0, 15.0)),
            PackedVector4Array(arrayOf(Vector4(12.0, 13.0, 14.0, 15.0))).toKotlinList(),
        )
        assertEquals(
            listOf(Color(0.2, 0.4, 0.6, 0.8)),
            PackedColorArray(arrayOf(Color(0.2, 0.4, 0.6, 0.8))).toKotlinList(),
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `packed conversions preserve Java element validation`() {
        val invalid = arrayOfNulls<String>(1) as Array<String>

        assertFailsWith<NullPointerException> { invalid.toPackedStringArray() }
    }
}

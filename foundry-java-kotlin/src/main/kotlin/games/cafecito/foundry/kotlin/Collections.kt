@file:JvmName("FoundryCollections")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.types.Color
import games.cafecito.foundry.types.FoundryArray
import games.cafecito.foundry.types.FoundryDictionary
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

fun <T> Iterable<T>.toFoundryArray(codec: VariantCodec<T>): FoundryArray<T> =
    FoundryArray(codec).also { array -> forEach(array::add) }

fun <T> FoundryArray<T>.toKotlinList(): List<T> = toList()

fun <K, V> Map<K, V>.toFoundryDictionary(
    keyCodec: VariantCodec<K>,
    valueCodec: VariantCodec<V>,
): FoundryDictionary<K, V> =
    FoundryDictionary(keyCodec, valueCodec).also { dictionary ->
        forEach(dictionary::put)
    }

fun <K, V> FoundryDictionary<K, V>.toKotlinMap(): Map<K, V> = toMap()

fun ByteArray.toPackedByteArray(): PackedByteArray = PackedByteArray(this)

fun PackedByteArray.toByteArray(): ByteArray = toArray()

fun IntArray.toPackedInt32Array(): PackedInt32Array = PackedInt32Array(this)

fun PackedInt32Array.toIntArray(): IntArray = toArray()

fun LongArray.toPackedInt64Array(): PackedInt64Array = PackedInt64Array(this)

fun PackedInt64Array.toLongArray(): LongArray = toArray()

fun FloatArray.toPackedFloat32Array(): PackedFloat32Array = PackedFloat32Array(this)

fun PackedFloat32Array.toFloatArray(): FloatArray = toArray()

fun DoubleArray.toPackedFloat64Array(): PackedFloat64Array = PackedFloat64Array(this)

fun PackedFloat64Array.toDoubleArray(): DoubleArray = toArray()

@JvmName("stringIterableToPackedStringArray")
fun Iterable<String>.toPackedStringArray(): PackedStringArray = PackedStringArray(toList().toTypedArray())

@JvmName("stringArrayToPackedStringArray")
fun Array<out String>.toPackedStringArray(): PackedStringArray = PackedStringArray(map { it }.toTypedArray())

fun PackedStringArray.toKotlinList(): List<String> = toArray().toList()

fun PackedStringArray.toKotlinArray(): Array<String> = toArray()

@JvmName("vector2IterableToPackedVector2Array")
fun Iterable<Vector2>.toPackedVector2Array(): PackedVector2Array = PackedVector2Array(toList().toTypedArray())

@JvmName("vector2ArrayToPackedVector2Array")
fun Array<out Vector2>.toPackedVector2Array(): PackedVector2Array = PackedVector2Array(map { it }.toTypedArray())

fun PackedVector2Array.toKotlinList(): List<Vector2> = toArray().toList()

fun PackedVector2Array.toKotlinArray(): Array<Vector2> = toArray()

@JvmName("vector3IterableToPackedVector3Array")
fun Iterable<Vector3>.toPackedVector3Array(): PackedVector3Array = PackedVector3Array(toList().toTypedArray())

@JvmName("vector3ArrayToPackedVector3Array")
fun Array<out Vector3>.toPackedVector3Array(): PackedVector3Array = PackedVector3Array(map { it }.toTypedArray())

fun PackedVector3Array.toKotlinList(): List<Vector3> = toArray().toList()

fun PackedVector3Array.toKotlinArray(): Array<Vector3> = toArray()

@JvmName("vector4IterableToPackedVector4Array")
fun Iterable<Vector4>.toPackedVector4Array(): PackedVector4Array = PackedVector4Array(toList().toTypedArray())

@JvmName("vector4ArrayToPackedVector4Array")
fun Array<out Vector4>.toPackedVector4Array(): PackedVector4Array = PackedVector4Array(map { it }.toTypedArray())

fun PackedVector4Array.toKotlinList(): List<Vector4> = toArray().toList()

fun PackedVector4Array.toKotlinArray(): Array<Vector4> = toArray()

@JvmName("colorIterableToPackedColorArray")
fun Iterable<Color>.toPackedColorArray(): PackedColorArray = PackedColorArray(toList().toTypedArray())

@JvmName("colorArrayToPackedColorArray")
fun Array<out Color>.toPackedColorArray(): PackedColorArray = PackedColorArray(map { it }.toTypedArray())

fun PackedColorArray.toKotlinList(): List<Color> = toArray().toList()

fun PackedColorArray.toKotlinArray(): Array<Color> = toArray()

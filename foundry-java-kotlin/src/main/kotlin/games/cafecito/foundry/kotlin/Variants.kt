@file:JvmName("FoundryVariants")

package games.cafecito.foundry.kotlin

import games.cafecito.foundry.runtime.FoundryCallable
import games.cafecito.foundry.runtime.FoundryObject
import games.cafecito.foundry.runtime.FoundrySignal
import games.cafecito.foundry.types.Aabb
import games.cafecito.foundry.types.Basis
import games.cafecito.foundry.types.Color
import games.cafecito.foundry.types.NodePath
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
import games.cafecito.foundry.types.Plane
import games.cafecito.foundry.types.Projection
import games.cafecito.foundry.types.Quaternion
import games.cafecito.foundry.types.Rect2
import games.cafecito.foundry.types.Rect2i
import games.cafecito.foundry.types.Rid
import games.cafecito.foundry.types.StringName
import games.cafecito.foundry.types.Transform2D
import games.cafecito.foundry.types.Transform3D
import games.cafecito.foundry.types.Variant
import games.cafecito.foundry.types.VariantCodec
import games.cafecito.foundry.types.Vector2
import games.cafecito.foundry.types.Vector2i
import games.cafecito.foundry.types.Vector3
import games.cafecito.foundry.types.Vector3i
import games.cafecito.foundry.types.Vector4
import games.cafecito.foundry.types.Vector4i

inline fun <reified T : Any> variantCodec(): VariantCodec<T> = variantCodec(T::class.java)

inline fun <reified T : Any> T.toVariant(): Variant = variantCodec<T>().encode(this)

inline fun <reified T : Any> Variant.decode(): T = variantCodec<T>().decode(this)

fun <T : Any> VariantCodec<T>.nullable(): VariantCodec<T?> =
    object : VariantCodec<T?> {
        override fun encode(value: T?): Variant = value?.let(this@nullable::encode) ?: Variant.nil()

        override fun decode(value: Variant): T? =
            if (value.isNil) {
                null
            } else {
                this@nullable.decode(value)
            }

        override fun acceptsNil(): Boolean = true
    }

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> variantCodec(type: Class<T>): VariantCodec<T> {
    if (FoundryObject::class.java.isAssignableFrom(type)) {
        return objectCodec(type as Class<out FoundryObject>) as VariantCodec<T>
    }

    val codec: VariantCodec<*> =
        when (type) {
            Variant::class.java -> VariantCodec.VARIANT
            Boolean::class.java, Boolean::class.javaObjectType -> VariantCodec.BOOLEAN
            Long::class.java, Long::class.javaObjectType -> VariantCodec.INTEGER
            Double::class.java, Double::class.javaObjectType -> VariantCodec.FLOAT
            String::class.java -> VariantCodec.STRING
            Vector2::class.java -> VariantCodec.VECTOR2
            Vector2i::class.java -> VariantCodec.VECTOR2I
            Rect2::class.java -> VariantCodec.RECT2
            Rect2i::class.java -> VariantCodec.RECT2I
            Vector3::class.java -> VariantCodec.VECTOR3
            Vector3i::class.java -> VariantCodec.VECTOR3I
            Transform2D::class.java -> VariantCodec.TRANSFORM2D
            Vector4::class.java -> VariantCodec.VECTOR4
            Vector4i::class.java -> VariantCodec.VECTOR4I
            Plane::class.java -> VariantCodec.PLANE
            Quaternion::class.java -> VariantCodec.QUATERNION
            Aabb::class.java -> VariantCodec.AABB
            Basis::class.java -> VariantCodec.BASIS
            Transform3D::class.java -> VariantCodec.TRANSFORM3D
            Projection::class.java -> VariantCodec.PROJECTION
            Color::class.java -> VariantCodec.COLOR
            StringName::class.java -> VariantCodec.STRING_NAME
            NodePath::class.java -> VariantCodec.NODE_PATH
            Rid::class.java -> VariantCodec.RID
            FoundryCallable::class.java -> VariantCodec.CALLABLE
            FoundrySignal::class.java -> VariantCodec.SIGNAL
            PackedByteArray::class.java -> VariantCodec.PACKED_BYTE_ARRAY
            PackedInt32Array::class.java -> VariantCodec.PACKED_INT32_ARRAY
            PackedInt64Array::class.java -> VariantCodec.PACKED_INT64_ARRAY
            PackedFloat32Array::class.java -> VariantCodec.PACKED_FLOAT32_ARRAY
            PackedFloat64Array::class.java -> VariantCodec.PACKED_FLOAT64_ARRAY
            PackedStringArray::class.java -> VariantCodec.PACKED_STRING_ARRAY
            PackedVector2Array::class.java -> VariantCodec.PACKED_VECTOR2_ARRAY
            PackedVector3Array::class.java -> VariantCodec.PACKED_VECTOR3_ARRAY
            PackedColorArray::class.java -> VariantCodec.PACKED_COLOR_ARRAY
            PackedVector4Array::class.java -> VariantCodec.PACKED_VECTOR4_ARRAY
            else -> throw IllegalArgumentException("No canonical Variant codec for ${type.name}.")
        }
    return codec as VariantCodec<T>
}

private fun <T : FoundryObject> objectCodec(type: Class<T>): VariantCodec<T> =
    object : VariantCodec<T> {
        override fun encode(value: T): Variant = VariantCodec.OBJECT.encode(value)

        override fun decode(value: Variant): T {
            val decoded = VariantCodec.OBJECT.decode(value)
            require(type.isInstance(decoded)) {
                "Expected Foundry object ${type.name}, received ${decoded.javaClass.name}."
            }
            return type.cast(decoded)
        }
    }

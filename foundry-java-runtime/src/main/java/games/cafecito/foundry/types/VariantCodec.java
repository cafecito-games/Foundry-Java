package games.cafecito.foundry.types;

import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundrySignal;
import java.util.Objects;

/**
 * Defines the only accepted conversion between a typed Java collection element and a Variant.
 *
 * @param <T> Java element type
 */
public interface VariantCodec<T> {
    VariantCodec<Object> NIL =
            new VariantCodec<>() {
                @Override
                public Variant encode(Object value) {
                    if (value != null) {
                        throw new IllegalArgumentException("NIL codec accepts only null.");
                    }
                    return Variant.nil();
                }

                @Override
                public Object decode(Variant value) {
                    Objects.requireNonNull(value, "value");
                    if (!value.isNil()) {
                        throw new VariantConversionException(value.type(), VariantType.NIL);
                    }
                    return null;
                }

                @Override
                public boolean acceptsNil() {
                    return true;
                }
            };

    VariantCodec<Variant> VARIANT =
            new VariantCodec<>() {
                @Override
                public Variant encode(Variant value) {
                    return value == null ? Variant.nil() : value;
                }

                @Override
                public Variant decode(Variant value) {
                    return Objects.requireNonNull(value, "value");
                }

                @Override
                public boolean acceptsNil() {
                    return true;
                }
            };

    VariantCodec<Boolean> BOOLEAN = strict(VariantType.BOOLEAN, Variant::asBoolean);
    VariantCodec<Long> INTEGER = strict(VariantType.INTEGER, Variant::asLong);
    VariantCodec<Double> FLOAT = strict(VariantType.FLOAT, Variant::asDouble);
    VariantCodec<String> STRING = strict(VariantType.STRING, Variant::asString);
    VariantCodec<Vector2> VECTOR2 = strict(VariantType.VECTOR2, Variant::asVector2);
    VariantCodec<Vector2i> VECTOR2I = strict(VariantType.VECTOR2I, Variant::asVector2i);
    VariantCodec<Rect2> RECT2 = strict(VariantType.RECT2, Variant::asRect2);
    VariantCodec<Rect2i> RECT2I = strict(VariantType.RECT2I, Variant::asRect2i);
    VariantCodec<Vector3> VECTOR3 = strict(VariantType.VECTOR3, Variant::asVector3);
    VariantCodec<Vector3i> VECTOR3I = strict(VariantType.VECTOR3I, Variant::asVector3i);
    VariantCodec<Transform2D> TRANSFORM2D = strict(VariantType.TRANSFORM2D, Variant::asTransform2D);
    VariantCodec<Vector4> VECTOR4 = strict(VariantType.VECTOR4, Variant::asVector4);
    VariantCodec<Vector4i> VECTOR4I = strict(VariantType.VECTOR4I, Variant::asVector4i);
    VariantCodec<Plane> PLANE = strict(VariantType.PLANE, Variant::asPlane);
    VariantCodec<Quaternion> QUATERNION = strict(VariantType.QUATERNION, Variant::asQuaternion);
    VariantCodec<Aabb> AABB = strict(VariantType.AABB, Variant::asAabb);
    VariantCodec<Basis> BASIS = strict(VariantType.BASIS, Variant::asBasis);
    VariantCodec<Transform3D> TRANSFORM3D = strict(VariantType.TRANSFORM3D, Variant::asTransform3D);
    VariantCodec<Projection> PROJECTION = strict(VariantType.PROJECTION, Variant::asProjection);
    VariantCodec<Color> COLOR = strict(VariantType.COLOR, Variant::asColor);
    VariantCodec<StringName> STRING_NAME = strict(VariantType.STRING_NAME, Variant::asStringName);
    VariantCodec<NodePath> NODE_PATH = strict(VariantType.NODE_PATH, Variant::asNodePath);
    VariantCodec<Rid> RID = strict(VariantType.RID, Variant::asRid);
    VariantCodec<FoundryObject> OBJECT = strict(VariantType.OBJECT, Variant::asObject);
    VariantCodec<FoundryCallable> CALLABLE = strict(VariantType.CALLABLE, Variant::asCallable);
    VariantCodec<FoundrySignal> SIGNAL = strict(VariantType.SIGNAL, Variant::asSignal);
    VariantCodec<FoundryDictionary<?, ?>> DICTIONARY =
            strict(
                    VariantType.DICTIONARY,
                    value -> (FoundryDictionary<?, ?>) value.as(VariantType.DICTIONARY));
    VariantCodec<FoundryArray<?>> ARRAY =
            strict(VariantType.ARRAY, value -> (FoundryArray<?>) value.as(VariantType.ARRAY));
    VariantCodec<PackedByteArray> PACKED_BYTE_ARRAY =
            strict(
                    VariantType.PACKED_BYTE_ARRAY,
                    value -> (PackedByteArray) value.as(VariantType.PACKED_BYTE_ARRAY));
    VariantCodec<PackedInt32Array> PACKED_INT32_ARRAY =
            strict(
                    VariantType.PACKED_INT32_ARRAY,
                    value -> (PackedInt32Array) value.as(VariantType.PACKED_INT32_ARRAY));
    VariantCodec<PackedInt64Array> PACKED_INT64_ARRAY =
            strict(
                    VariantType.PACKED_INT64_ARRAY,
                    value -> (PackedInt64Array) value.as(VariantType.PACKED_INT64_ARRAY));
    VariantCodec<PackedFloat32Array> PACKED_FLOAT32_ARRAY =
            strict(
                    VariantType.PACKED_FLOAT32_ARRAY,
                    value -> (PackedFloat32Array) value.as(VariantType.PACKED_FLOAT32_ARRAY));
    VariantCodec<PackedFloat64Array> PACKED_FLOAT64_ARRAY =
            strict(
                    VariantType.PACKED_FLOAT64_ARRAY,
                    value -> (PackedFloat64Array) value.as(VariantType.PACKED_FLOAT64_ARRAY));
    VariantCodec<PackedStringArray> PACKED_STRING_ARRAY =
            strict(
                    VariantType.PACKED_STRING_ARRAY,
                    value -> (PackedStringArray) value.as(VariantType.PACKED_STRING_ARRAY));
    VariantCodec<PackedVector2Array> PACKED_VECTOR2_ARRAY =
            strict(
                    VariantType.PACKED_VECTOR2_ARRAY,
                    value -> (PackedVector2Array) value.as(VariantType.PACKED_VECTOR2_ARRAY));
    VariantCodec<PackedVector3Array> PACKED_VECTOR3_ARRAY =
            strict(
                    VariantType.PACKED_VECTOR3_ARRAY,
                    value -> (PackedVector3Array) value.as(VariantType.PACKED_VECTOR3_ARRAY));
    VariantCodec<PackedColorArray> PACKED_COLOR_ARRAY =
            strict(
                    VariantType.PACKED_COLOR_ARRAY,
                    value -> (PackedColorArray) value.as(VariantType.PACKED_COLOR_ARRAY));
    VariantCodec<PackedVector4Array> PACKED_VECTOR4_ARRAY =
            strict(
                    VariantType.PACKED_VECTOR4_ARRAY,
                    value -> (PackedVector4Array) value.as(VariantType.PACKED_VECTOR4_ARRAY));

    Variant encode(T value);

    T decode(Variant value);

    default boolean acceptsNil() {
        return false;
    }

    @SuppressWarnings("unchecked")
    static VariantCodec<Object> forType(VariantType type) {
        return (VariantCodec<Object>)
                switch (Objects.requireNonNull(type, "type")) {
                    case NIL -> NIL;
                    case BOOLEAN -> BOOLEAN;
                    case INTEGER -> INTEGER;
                    case FLOAT -> FLOAT;
                    case STRING -> STRING;
                    case VECTOR2 -> VECTOR2;
                    case VECTOR2I -> VECTOR2I;
                    case RECT2 -> RECT2;
                    case RECT2I -> RECT2I;
                    case VECTOR3 -> VECTOR3;
                    case VECTOR3I -> VECTOR3I;
                    case TRANSFORM2D -> TRANSFORM2D;
                    case VECTOR4 -> VECTOR4;
                    case VECTOR4I -> VECTOR4I;
                    case PLANE -> PLANE;
                    case QUATERNION -> QUATERNION;
                    case AABB -> AABB;
                    case BASIS -> BASIS;
                    case TRANSFORM3D -> TRANSFORM3D;
                    case PROJECTION -> PROJECTION;
                    case COLOR -> COLOR;
                    case STRING_NAME -> STRING_NAME;
                    case NODE_PATH -> NODE_PATH;
                    case RID -> RID;
                    case OBJECT -> OBJECT;
                    case CALLABLE -> CALLABLE;
                    case SIGNAL -> SIGNAL;
                    case DICTIONARY -> DICTIONARY;
                    case ARRAY -> ARRAY;
                    case PACKED_BYTE_ARRAY -> PACKED_BYTE_ARRAY;
                    case PACKED_INT32_ARRAY -> PACKED_INT32_ARRAY;
                    case PACKED_INT64_ARRAY -> PACKED_INT64_ARRAY;
                    case PACKED_FLOAT32_ARRAY -> PACKED_FLOAT32_ARRAY;
                    case PACKED_FLOAT64_ARRAY -> PACKED_FLOAT64_ARRAY;
                    case PACKED_STRING_ARRAY -> PACKED_STRING_ARRAY;
                    case PACKED_VECTOR2_ARRAY -> PACKED_VECTOR2_ARRAY;
                    case PACKED_VECTOR3_ARRAY -> PACKED_VECTOR3_ARRAY;
                    case PACKED_COLOR_ARRAY -> PACKED_COLOR_ARRAY;
                    case PACKED_VECTOR4_ARRAY -> PACKED_VECTOR4_ARRAY;
                };
    }

    static <T> VariantCodec<T> nullable(VariantCodec<T> delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new VariantCodec<>() {
            @Override
            public Variant encode(T value) {
                return value == null ? Variant.nil() : delegate.encode(value);
            }

            @Override
            public T decode(Variant value) {
                return Objects.requireNonNull(value, "value").isNil()
                        ? null
                        : delegate.decode(value);
            }

            @Override
            public boolean acceptsNil() {
                return true;
            }
        };
    }

    private static <T> VariantCodec<T> strict(
            VariantType type, java.util.function.Function<Variant, T> decoder) {
        return new VariantCodec<>() {
            @Override
            public Variant encode(T value) {
                return Variant.of(Objects.requireNonNull(value, type + " value"));
            }

            @Override
            public T decode(Variant value) {
                Objects.requireNonNull(value, "value");
                if (value.isNil()) {
                    throw new VariantConversionException(VariantType.NIL, type);
                }
                return decoder.apply(value);
            }
        };
    }
}

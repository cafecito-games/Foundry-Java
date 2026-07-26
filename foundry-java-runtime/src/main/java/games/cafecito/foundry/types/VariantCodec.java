package games.cafecito.foundry.types;

import java.util.Objects;

/**
 * Defines the only accepted conversion between a typed Java collection element and a Variant.
 *
 * @param <T> Java element type
 */
public interface VariantCodec<T> {
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
    VariantCodec<Vector3> VECTOR3 = strict(VariantType.VECTOR3, Variant::asVector3);
    VariantCodec<Vector4> VECTOR4 = strict(VariantType.VECTOR4, Variant::asVector4);
    VariantCodec<Color> COLOR = strict(VariantType.COLOR, Variant::asColor);
    VariantCodec<StringName> STRING_NAME = strict(VariantType.STRING_NAME, Variant::asStringName);
    VariantCodec<NodePath> NODE_PATH = strict(VariantType.NODE_PATH, Variant::asNodePath);
    VariantCodec<Rid> RID = strict(VariantType.RID, Variant::asRid);

    Variant encode(T value);

    T decode(Variant value);

    default boolean acceptsNil() {
        return false;
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

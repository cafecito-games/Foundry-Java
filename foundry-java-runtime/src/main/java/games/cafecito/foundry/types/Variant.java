package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable Java representation of one Foundry Variant value. */
public final class Variant {
    private static final Variant NIL = new Variant(VariantType.NIL, null);

    private final VariantType type;
    private final Object value;

    private Variant(VariantType type, Object value) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = value;
    }

    public static Variant nil() {
        return NIL;
    }

    /**
     * Converts a supported Java value without coercing between Foundry types.
     *
     * @param value a supported immutable Java value, or {@code null} for Nil
     * @return the corresponding Variant
     * @throws IllegalArgumentException when the Java type has no defined Variant representation
     */
    public static Variant of(Object value) {
        if (value == null) {
            return NIL;
        }
        if (value instanceof Variant variant) {
            return variant;
        }
        if (value instanceof Boolean booleanValue) {
            return new Variant(VariantType.BOOLEAN, booleanValue);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return new Variant(VariantType.INTEGER, ((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return new Variant(VariantType.FLOAT, ((Number) value).doubleValue());
        }
        if (value instanceof String) {
            return new Variant(VariantType.STRING, value);
        }
        if (value instanceof Vector2) {
            return new Variant(VariantType.VECTOR2, value);
        }
        if (value instanceof Vector3) {
            return new Variant(VariantType.VECTOR3, value);
        }
        if (value instanceof Vector4) {
            return new Variant(VariantType.VECTOR4, value);
        }
        if (value instanceof Color) {
            return new Variant(VariantType.COLOR, value);
        }
        if (value instanceof StringName) {
            return new Variant(VariantType.STRING_NAME, value);
        }
        if (value instanceof NodePath) {
            return new Variant(VariantType.NODE_PATH, value);
        }
        if (value instanceof Rid) {
            return new Variant(VariantType.RID, value);
        }
        if (value instanceof FoundryArray<?>) {
            return new Variant(VariantType.ARRAY, value);
        }
        if (value instanceof FoundryDictionary<?, ?>) {
            return new Variant(VariantType.DICTIONARY, value);
        }
        if (value instanceof PackedByteArray) {
            return new Variant(VariantType.PACKED_BYTE_ARRAY, value);
        }
        if (value instanceof PackedInt32Array) {
            return new Variant(VariantType.PACKED_INT32_ARRAY, value);
        }
        if (value instanceof PackedInt64Array) {
            return new Variant(VariantType.PACKED_INT64_ARRAY, value);
        }
        if (value instanceof PackedFloat32Array) {
            return new Variant(VariantType.PACKED_FLOAT32_ARRAY, value);
        }
        if (value instanceof PackedFloat64Array) {
            return new Variant(VariantType.PACKED_FLOAT64_ARRAY, value);
        }
        if (value instanceof PackedStringArray) {
            return new Variant(VariantType.PACKED_STRING_ARRAY, value);
        }
        if (value instanceof PackedVector2Array) {
            return new Variant(VariantType.PACKED_VECTOR2_ARRAY, value);
        }
        if (value instanceof PackedVector3Array) {
            return new Variant(VariantType.PACKED_VECTOR3_ARRAY, value);
        }
        if (value instanceof PackedColorArray) {
            return new Variant(VariantType.PACKED_COLOR_ARRAY, value);
        }
        if (value instanceof PackedVector4Array) {
            return new Variant(VariantType.PACKED_VECTOR4_ARRAY, value);
        }
        throw new IllegalArgumentException(
                "Unsupported Java value for Variant: " + value.getClass().getName());
    }

    public VariantType type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public boolean isNil() {
        return type == VariantType.NIL;
    }

    public boolean asBoolean() {
        return require(VariantType.BOOLEAN, Boolean.class);
    }

    public long asLong() {
        return require(VariantType.INTEGER, Long.class);
    }

    public int asInt() {
        return Math.toIntExact(asLong());
    }

    public double asDouble() {
        return require(VariantType.FLOAT, Double.class);
    }

    public float asFloat() {
        double source = asDouble();
        float narrowed = (float) source;
        if (!Double.isNaN(source) && (double) narrowed != source) {
            throw new ArithmeticException(
                    "FLOAT value cannot be represented losslessly as Java float: " + source);
        }
        return narrowed;
    }

    public String asString() {
        return require(VariantType.STRING, String.class);
    }

    public Vector2 asVector2() {
        return require(VariantType.VECTOR2, Vector2.class);
    }

    public Vector3 asVector3() {
        return require(VariantType.VECTOR3, Vector3.class);
    }

    public Vector4 asVector4() {
        return require(VariantType.VECTOR4, Vector4.class);
    }

    public Color asColor() {
        return require(VariantType.COLOR, Color.class);
    }

    public StringName asStringName() {
        return require(VariantType.STRING_NAME, StringName.class);
    }

    public NodePath asNodePath() {
        return require(VariantType.NODE_PATH, NodePath.class);
    }

    public Rid asRid() {
        return require(VariantType.RID, Rid.class);
    }

    private <T> T require(VariantType expected, Class<T> javaType) {
        if (type != expected) {
            throw new VariantConversionException(type, expected);
        }
        return javaType.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Variant variant) || type != variant.type) {
            return false;
        }
        if (type == VariantType.FLOAT) {
            double left = (Double) value;
            double right = (Double) variant.value;
            return left == right || (Double.isNaN(left) && Double.isNaN(right));
        }
        return Objects.equals(value, variant.value);
    }

    @Override
    public int hashCode() {
        if (type == VariantType.FLOAT) {
            double floatingValue = (Double) value;
            double normalized =
                    Double.isNaN(floatingValue)
                            ? Double.NaN
                            : (floatingValue == 0.0d ? 0.0d : floatingValue);
            return 31 * type.hashCode() + Double.hashCode(normalized);
        }
        return 31 * type.hashCode() + Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return isNil() ? "Variant[NIL]" : "Variant[" + type + ": " + value + "]";
    }
}

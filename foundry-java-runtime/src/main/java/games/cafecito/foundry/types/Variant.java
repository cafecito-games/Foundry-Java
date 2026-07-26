package games.cafecito.foundry.types;

import games.cafecito.foundry.runtime.FoundryCallable;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundrySignal;
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
        if (value instanceof Vector2i) {
            return new Variant(VariantType.VECTOR2I, value);
        }
        if (value instanceof Rect2) {
            return new Variant(VariantType.RECT2, value);
        }
        if (value instanceof Rect2i) {
            return new Variant(VariantType.RECT2I, value);
        }
        if (value instanceof Vector3) {
            return new Variant(VariantType.VECTOR3, value);
        }
        if (value instanceof Vector3i) {
            return new Variant(VariantType.VECTOR3I, value);
        }
        if (value instanceof Transform2D) {
            return new Variant(VariantType.TRANSFORM2D, value);
        }
        if (value instanceof Vector4) {
            return new Variant(VariantType.VECTOR4, value);
        }
        if (value instanceof Vector4i) {
            return new Variant(VariantType.VECTOR4I, value);
        }
        if (value instanceof Plane) {
            return new Variant(VariantType.PLANE, value);
        }
        if (value instanceof Quaternion) {
            return new Variant(VariantType.QUATERNION, value);
        }
        if (value instanceof Aabb) {
            return new Variant(VariantType.AABB, value);
        }
        if (value instanceof Basis) {
            return new Variant(VariantType.BASIS, value);
        }
        if (value instanceof Transform3D) {
            return new Variant(VariantType.TRANSFORM3D, value);
        }
        if (value instanceof Projection) {
            return new Variant(VariantType.PROJECTION, value);
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
        if (value instanceof FoundryObject) {
            return new Variant(VariantType.OBJECT, value);
        }
        if (value instanceof FoundryCallable) {
            return new Variant(VariantType.CALLABLE, value);
        }
        if (value instanceof FoundrySignal) {
            return new Variant(VariantType.SIGNAL, value);
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

    public static Variant ofObject(FoundryObject value) {
        return new Variant(VariantType.OBJECT, Objects.requireNonNull(value, "value"));
    }

    public static Variant ofCallable(FoundryCallable value) {
        return new Variant(VariantType.CALLABLE, Objects.requireNonNull(value, "value"));
    }

    public static Variant ofSignal(FoundrySignal value) {
        return new Variant(VariantType.SIGNAL, Objects.requireNonNull(value, "value"));
    }

    public VariantType type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public Object as(VariantType expectedType) {
        if (type != Objects.requireNonNull(expectedType, "expectedType")) {
            throw new VariantConversionException(type, expectedType);
        }
        return value;
    }

    public Variant copy() {
        return copy(false);
    }

    public Variant copy(boolean deep) {
        if (value instanceof FoundryArray<?> array) {
            return Variant.of(deep ? array.duplicateDeep() : array.duplicate());
        }
        if (value instanceof FoundryDictionary<?, ?> dictionary) {
            return Variant.of(deep ? dictionary.duplicateDeep() : dictionary.duplicate());
        }
        return this;
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

    public Vector2i asVector2i() {
        return require(VariantType.VECTOR2I, Vector2i.class);
    }

    public Rect2 asRect2() {
        return require(VariantType.RECT2, Rect2.class);
    }

    public Rect2i asRect2i() {
        return require(VariantType.RECT2I, Rect2i.class);
    }

    public Vector3 asVector3() {
        return require(VariantType.VECTOR3, Vector3.class);
    }

    public Vector3i asVector3i() {
        return require(VariantType.VECTOR3I, Vector3i.class);
    }

    public Transform2D asTransform2D() {
        return require(VariantType.TRANSFORM2D, Transform2D.class);
    }

    public Vector4 asVector4() {
        return require(VariantType.VECTOR4, Vector4.class);
    }

    public Vector4i asVector4i() {
        return require(VariantType.VECTOR4I, Vector4i.class);
    }

    public Plane asPlane() {
        return require(VariantType.PLANE, Plane.class);
    }

    public Quaternion asQuaternion() {
        return require(VariantType.QUATERNION, Quaternion.class);
    }

    public Aabb asAabb() {
        return require(VariantType.AABB, Aabb.class);
    }

    public Basis asBasis() {
        return require(VariantType.BASIS, Basis.class);
    }

    public Transform3D asTransform3D() {
        return require(VariantType.TRANSFORM3D, Transform3D.class);
    }

    public Projection asProjection() {
        return require(VariantType.PROJECTION, Projection.class);
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

    public FoundryObject asObject() {
        return require(VariantType.OBJECT, FoundryObject.class);
    }

    public FoundryCallable asCallable() {
        return require(VariantType.CALLABLE, FoundryCallable.class);
    }

    public FoundrySignal asSignal() {
        return require(VariantType.SIGNAL, FoundrySignal.class);
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

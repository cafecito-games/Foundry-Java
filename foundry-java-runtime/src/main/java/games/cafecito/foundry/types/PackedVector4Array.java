package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable Vector4 packed array with copied Java array boundaries. */
public final class PackedVector4Array implements PackedArray<Vector4> {
    private final Vector4[] values;

    public PackedVector4Array(Vector4[] values) {
        this.values = checkedCopy(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Vector4 get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Vector4 value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public Vector4[] toArray() {
        return values.clone();
    }

    private static Vector4[] checkedCopy(Vector4[] source) {
        Vector4[] copy = Objects.requireNonNull(source, "values").clone();
        for (Vector4 value : copy) {
            Objects.requireNonNull(value, "packed array element");
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedVector4Array array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}

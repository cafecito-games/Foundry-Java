package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable Vector3 packed array with copied Java array boundaries. */
public final class PackedVector3Array implements PackedArray<Vector3> {
    private final Vector3[] values;

    public PackedVector3Array(Vector3[] values) {
        this.values = checkedCopy(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Vector3 get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Vector3 value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public Vector3[] toArray() {
        return values.clone();
    }

    private static Vector3[] checkedCopy(Vector3[] source) {
        Vector3[] copy = Objects.requireNonNull(source, "values").clone();
        for (Vector3 value : copy) {
            Objects.requireNonNull(value, "packed array element");
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedVector3Array array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}

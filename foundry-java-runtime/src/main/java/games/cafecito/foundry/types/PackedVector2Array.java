package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable Vector2 packed array with copied Java array boundaries. */
public final class PackedVector2Array implements PackedArray<Vector2> {
    private final Vector2[] values;

    public PackedVector2Array(Vector2[] values) {
        this.values = checkedCopy(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Vector2 get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Vector2 value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public Vector2[] toArray() {
        return values.clone();
    }

    private static Vector2[] checkedCopy(Vector2[] source) {
        Vector2[] copy = Objects.requireNonNull(source, "values").clone();
        for (Vector2 value : copy) {
            Objects.requireNonNull(value, "packed array element");
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedVector2Array array
                        && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}

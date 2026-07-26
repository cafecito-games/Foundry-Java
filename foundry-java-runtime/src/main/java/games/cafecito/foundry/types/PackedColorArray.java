package games.cafecito.foundry.types;

import java.util.Arrays;
import java.util.Objects;

/** Mutable Color packed array with copied Java array boundaries. */
public final class PackedColorArray implements PackedArray<Color> {
    private final Color[] values;

    public PackedColorArray(Color[] values) {
        this.values = checkedCopy(values);
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public Color get(int index) {
        return values[FoundryArray.checkIndex(index, size())];
    }

    @Override
    public void set(int index, Color value) {
        values[FoundryArray.checkIndex(index, size())] = Objects.requireNonNull(value, "value");
    }

    public Color[] toArray() {
        return values.clone();
    }

    private static Color[] checkedCopy(Color[] source) {
        Color[] copy = Objects.requireNonNull(source, "values").clone();
        for (Color value : copy) {
            Objects.requireNonNull(value, "packed array element");
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PackedColorArray array && Arrays.equals(values, array.values));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}

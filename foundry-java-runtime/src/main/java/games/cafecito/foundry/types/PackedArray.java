package games.cafecito.foundry.types;

/** Common checked-index operations for specialized Foundry packed arrays. */
public interface PackedArray<T> {
    int size();

    T get(int index);

    void set(int index, T value);
}

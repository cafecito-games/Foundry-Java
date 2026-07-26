package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable axis-aligned bounding box. */
public record Aabb(Vector3 position, Vector3 size) {
    public Aabb {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(size, "size");
    }
}

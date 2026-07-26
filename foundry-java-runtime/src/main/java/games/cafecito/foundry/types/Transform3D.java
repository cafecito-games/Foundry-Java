package games.cafecito.foundry.types;

import java.util.Objects;

/** Immutable three-dimensional affine transform. */
public record Transform3D(Basis basis, Vector3 origin) {
    public Transform3D {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(origin, "origin");
    }
}

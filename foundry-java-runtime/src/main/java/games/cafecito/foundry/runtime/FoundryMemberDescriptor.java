package games.cafecito.foundry.runtime;

import java.util.Objects;

/** Immutable generated description of one exported Java member. */
public record FoundryMemberDescriptor(
        String kind, String foundryName, String javaName, String signature) {
    public FoundryMemberDescriptor {
        kind = requireText(kind, "kind");
        foundryName = requireText(foundryName, "foundryName");
        javaName = requireText(javaName, "javaName");
        signature = requireText(signature, "signature");
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}

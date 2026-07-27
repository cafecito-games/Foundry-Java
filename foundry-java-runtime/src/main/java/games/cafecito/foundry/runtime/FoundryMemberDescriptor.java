package games.cafecito.foundry.runtime;

import java.util.Objects;

/** Immutable generated description of one exported Java member. */
public record FoundryMemberDescriptor(
        String kind,
        String foundryName,
        String javaName,
        String signature,
        FoundryMemberDetails details) {
    /**
     * Preserves the original descriptor constructor used by generated format-2 registries.
     *
     * @param kind generated member kind
     * @param foundryName exported Foundry name
     * @param javaName backing Java name
     * @param signature canonical Java-facing signature
     */
    public FoundryMemberDescriptor(
            String kind, String foundryName, String javaName, String signature) {
        this(kind, foundryName, javaName, signature, FoundryMemberDetails.none());
    }

    public FoundryMemberDescriptor {
        kind = requireText(kind, "kind");
        foundryName = requireText(foundryName, "foundryName");
        javaName = requireText(javaName, "javaName");
        signature = requireText(signature, "signature");
        details = Objects.requireNonNull(details, "details");
        validateDetails(kind, details);
    }

    private static void validateDetails(String kind, FoundryMemberDetails details) {
        boolean empty = details == FoundryMemberDetails.none();
        switch (kind) {
            case "constant" -> {
                if (!(details instanceof FoundryConstantDetails)) {
                    throw new IllegalArgumentException(
                            "constant members require FoundryConstantDetails.");
                }
            }
            case "property" -> {
                if (!empty && !(details instanceof FoundryPropertyDetails)) {
                    throw new IllegalArgumentException(
                            "property members require FoundryPropertyDetails.");
                }
            }
            case "method", "override", "signal" -> {
                if (!empty) {
                    throw new IllegalArgumentException(
                            kind + " members cannot declare typed details.");
                }
            }
            default -> {
                if (!empty) {
                    throw new IllegalArgumentException(
                            "Unknown member kind " + kind + " cannot declare typed details.");
                }
            }
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}

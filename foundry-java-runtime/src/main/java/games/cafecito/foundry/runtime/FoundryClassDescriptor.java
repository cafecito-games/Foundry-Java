package games.cafecito.foundry.runtime;

import java.util.List;
import java.util.Objects;

/** Immutable generated description of one Java extension class. */
public record FoundryClassDescriptor(
        String javaName,
        String foundryName,
        String baseName,
        String initializationLevel,
        List<String> after,
        FoundryExtensionAccess access,
        List<FoundryMemberDescriptor> members) {
    public FoundryClassDescriptor {
        javaName = requireText(javaName, "javaName");
        foundryName = requireText(foundryName, "foundryName");
        baseName = requireText(baseName, "baseName");
        initializationLevel = requireText(initializationLevel, "initializationLevel");
        after = List.copyOf(Objects.requireNonNull(after, "after"));
        if (after.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("after entries must not be blank.");
        }
        access = Objects.requireNonNull(access, "access");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}

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
        baseName = requireEngineClassName(requireText(baseName, "baseName"));
        initializationLevel = requireText(initializationLevel, "initializationLevel");
        after = List.copyOf(Objects.requireNonNull(after, "after"));
        if (after.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("after entries must not be blank.");
        }
        access = Objects.requireNonNull(access, "access");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    /**
     * The engine resolves an extension class parent through its own class database, which is keyed by
     * unqualified engine class names. A qualified Java binding type name can never resolve there, so
     * it is rejected here rather than at engine registration, where the class is silently dropped.
     */
    private static String requireEngineClassName(String baseName) {
        if (baseName.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "baseName "
                            + baseName
                            + " must be an engine class name, not a qualified Java type name.");
        }
        return baseName;
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }
}

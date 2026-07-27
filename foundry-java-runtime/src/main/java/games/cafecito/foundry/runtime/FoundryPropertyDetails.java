package games.cafecito.foundry.runtime;

import java.util.Objects;

/** Exact accessor, index, and grouping metadata for one generated class property. */
public record FoundryPropertyDetails(
        String getter,
        String setter,
        int index,
        String groupName,
        String groupPrefix,
        String subgroupName,
        String subgroupPrefix)
        implements FoundryMemberDetails {
    public FoundryPropertyDetails {
        getter = requireText(getter, "getter");
        setter = optionalText(setter, "setter");
        if (index < -1) {
            throw new IllegalArgumentException("index must be -1 or non-negative.");
        }
        groupName = optionalText(groupName, "groupName");
        groupPrefix = optionalText(groupPrefix, "groupPrefix");
        subgroupName = optionalText(subgroupName, "subgroupName");
        subgroupPrefix = optionalText(subgroupPrefix, "subgroupPrefix");
        requireNamedPrefix(groupName, groupPrefix, "group");
        requireNamedPrefix(subgroupName, subgroupPrefix, "subgroup");
    }

    /**
     * Reports whether the generated property omits a setter.
     *
     * @return {@code true} for a read-only property
     */
    public boolean readOnly() {
        return setter.isEmpty();
    }

    private static void requireNamedPrefix(String name, String prefix, String label) {
        if (name.isEmpty() && !prefix.isEmpty()) {
            throw new IllegalArgumentException(
                    label + "Prefix requires a non-empty " + label + "Name.");
        }
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return checked;
    }

    private static String optionalText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (!checked.isEmpty() && checked.isBlank()) {
            throw new IllegalArgumentException(name + " must be empty or non-blank.");
        }
        return checked;
    }
}

package games.cafecito.foundry.runtime;

import java.util.Objects;

/** Exact signed integer metadata for one generated class constant. */
public record FoundryConstantDetails(String enumName, long value, boolean bitfield)
        implements FoundryMemberDetails {
    public FoundryConstantDetails {
        enumName = optionalText(enumName, "enumName");
        if (bitfield && enumName.isEmpty()) {
            throw new IllegalArgumentException("A bitfield constant must declare an enumName.");
        }
    }

    private static String optionalText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (!checked.isEmpty() && checked.isBlank()) {
            throw new IllegalArgumentException(name + " must be empty or non-blank.");
        }
        return checked;
    }
}

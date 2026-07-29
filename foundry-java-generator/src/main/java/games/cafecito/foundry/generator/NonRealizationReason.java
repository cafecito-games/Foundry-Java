package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import java.util.List;

/**
 * Closed vocabulary of approved reasons an accepted source entity realizes no Java member of its
 * own.
 *
 * <p>The vocabulary is deliberately small and only widened by an explicit, reviewable change to
 * this enumeration. The parity oracle never accepts a reason outside this set, so generation can
 * never widen its own vocabulary to make the oracle pass.
 */
public enum NonRealizationReason {
    /** The argument appears inside the erased signature of the member its callable realizes. */
    ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE,
    /** The return value appears as the erased return type of the member its callable realizes. */
    RETURN_VALUE_REALIZED_IN_MEMBER_SIGNATURE,
    /** The signal argument appears as a type argument of the realized typed-signal accessor. */
    SIGNAL_ARGUMENT_REALIZED_IN_SIGNAL_TYPE,
    /** The property is served by the engine methods it names instead of a synthesized accessor. */
    PROPERTY_REALIZED_BY_ENGINE_METHOD,
    /** The built-in member is served by the engine method of the same accessor name. */
    BUILTIN_MEMBER_REALIZED_BY_ENGINE_METHOD,
    /** The layout table row is served by the generated size or offset query API. */
    LAYOUT_TABLE_ENTRY_REALIZED_BY_QUERY_API;

    /** Returns the approved vocabulary in declaration order. */
    public static List<NonRealizationReason> approved() {
        return List.of(values());
    }

    /** Returns whether {@code token} names an approved non-realization reason. */
    public static boolean isApproved(String token) {
        if (token == null) {
            return false;
        }
        for (NonRealizationReason reason : values()) {
            if (reason.name().equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** Resolves an approved reason, rejecting every token outside the closed vocabulary. */
    public static NonRealizationReason require(String token) {
        if (!isApproved(token)) {
            throw new ApiInputException(
                    "Non-realization reason is outside the approved vocabulary: "
                            + Diagnostics.escape(token)
                            + ".");
        }
        return valueOf(token);
    }
}

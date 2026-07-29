package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import java.util.List;

/**
 * Closed, binding-neutral vocabulary of reasons an accepted source entity realizes no member of its
 * own in a binding.
 *
 * <p>The vocabulary deliberately carries no Java idiom: no erasure, no package, no accessor naming.
 * Every reason describes a relationship between source entities that any binding of the same engine
 * API can express, so two bindings can be compared without either one understanding the other's
 * language.
 *
 * <p>{@link #of(NonRealizationReason)} maps the Java vocabulary onto this one with a total switch
 * over the closed Java enumeration, so approving a new Java reason without deciding its neutral
 * meaning fails to compile. Neither vocabulary may be widened to make a gate pass.
 */
public enum NeutralNonRealizationReason {
    /**
     * The entity appears inside the signature of the member realized for its parent, so it has no
     * separate declaration of its own.
     */
    SUBSUMED_BY_ENCLOSING_SIGNATURE,
    /**
     * The entity appears as a type parameter of the member realized for its parent, so it has no
     * separate declaration of its own.
     */
    SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT,
    /** The entity is served by an engine accessor the binding already exposes. */
    SERVED_BY_ENGINE_ACCESSOR,
    /** The entity is a layout table row served by the binding's size or offset query API. */
    SERVED_BY_LAYOUT_QUERY_API;

    /** Returns the neutral vocabulary in declaration order. */
    public static List<NeutralNonRealizationReason> approved() {
        return List.of(values());
    }

    /** Maps one Java non-realization reason onto its binding-neutral meaning. */
    public static NeutralNonRealizationReason of(NonRealizationReason reason) {
        return switch (reason) {
            case ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE, RETURN_VALUE_REALIZED_IN_MEMBER_SIGNATURE ->
                    SUBSUMED_BY_ENCLOSING_SIGNATURE;
            case SIGNAL_ARGUMENT_REALIZED_IN_SIGNAL_TYPE -> SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT;
            case PROPERTY_REALIZED_BY_ENGINE_METHOD, BUILTIN_MEMBER_REALIZED_BY_ENGINE_METHOD ->
                    SERVED_BY_ENGINE_ACCESSOR;
            case LAYOUT_TABLE_ENTRY_REALIZED_BY_QUERY_API -> SERVED_BY_LAYOUT_QUERY_API;
        };
    }

    /** Resolves a neutral reason, rejecting every token outside the closed vocabulary. */
    public static NeutralNonRealizationReason require(String token) {
        for (NeutralNonRealizationReason reason : values()) {
            if (reason.name().equals(token)) {
                return reason;
            }
        }
        throw new ApiInputException(
                "Non-realization reason is outside the binding-neutral vocabulary: "
                        + Diagnostics.escape(token)
                        + ".");
    }
}

package games.cafecito.foundry.runtime;

/** Typed metadata carried by an exported Java member descriptor. */
public sealed interface FoundryMemberDetails
        permits FoundryConstantDetails, FoundryPropertyDetails, NoFoundryMemberDetails {
    /**
     * Returns the shared marker used by members and legacy descriptors without enriched metadata.
     *
     * @return the immutable empty-details marker
     */
    static FoundryMemberDetails none() {
        return NoFoundryMemberDetails.INSTANCE;
    }
}

enum NoFoundryMemberDetails implements FoundryMemberDetails {
    INSTANCE
}

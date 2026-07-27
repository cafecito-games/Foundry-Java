package games.cafecito.foundry.runtime;

/** Ownership mode for an opaque object handle exposed to Java. */
public enum ObjectOwnership {
    /** The engine owns the object; Java must never release it. */
    BORROWED,
    /** Java adopts an existing native ownership token and releases it at most once. */
    OWNED,
    /** Java retains the object once and releases that retain at most once. */
    REFERENCE_COUNTED
}

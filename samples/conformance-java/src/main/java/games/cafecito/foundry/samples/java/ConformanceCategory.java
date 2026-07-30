package games.cafecito.foundry.samples.java;

/**
 * The complete behavioural conformance matrix a Foundry-Java consumer must be able to demonstrate.
 *
 * <p>Every constant must be claimed by at least one named conformance test through {@link Covers}.
 * Adding a constant without adding a test fails the coverage assertion, so the matrix cannot grow
 * silently.
 */
public enum ConformanceCategory {
    INITIALIZATION,
    DEINITIALIZATION,
    CALLS,
    DEFAULT_ARGUMENTS,
    PROPERTIES,
    SIGNALS,
    VIRTUAL_OVERRIDES,
    UTILITY_FUNCTIONS,
    SINGLETONS,
    BUILT_IN_TYPES,
    OPERATORS,
    TYPED_COLLECTIONS,
    UNTYPED_COLLECTIONS,
    PACKED_ARRAYS,
    CALLABLES,
    OBJECT_IDENTITY,
    OWNERSHIP,
    EXCEPTIONS,
    ENGINE_CALL_ERRORS,
    OBJECT_DESTRUCTION,
    CLOSE_AND_CLEANER_FALLBACK,
    THREAD_ATTACH_AND_DETACH,
    REENTRANT_CALLBACKS,
    EXCEPTIONS_FROM_CALLBACKS,
    INITIALIZATION_LEVEL_MISMATCH,
    DEINITIALIZATION_RACES
}

package games.cafecito.foundry.runtime;

/** Stable Foundry call result codes transported by the native bridge. */
public enum FoundryCallError {
    OK,
    INVALID_METHOD,
    INVALID_ARGUMENT,
    TOO_FEW_ARGUMENTS,
    TOO_MANY_ARGUMENTS,
    INSTANCE_IS_NULL,
    METHOD_NOT_CONST,
    UNKNOWN
}

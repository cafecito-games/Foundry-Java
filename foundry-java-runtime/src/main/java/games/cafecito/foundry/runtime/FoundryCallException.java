package games.cafecito.foundry.runtime;

import java.util.Objects;

/** Lossless Java representation of a failed engine method invocation. */
public final class FoundryCallException extends RuntimeException {
    private final String methodIdentity;
    private final FoundryCallError callError;
    private final int argumentIndex;
    private final String expectedType;

    FoundryCallException(
            String methodIdentity,
            FoundryCallError callError,
            int argumentIndex,
            String expectedType) {
        super(
                "Foundry call "
                        + methodIdentity
                        + " failed with "
                        + callError
                        + (argumentIndex >= 0 ? " at argument " + argumentIndex : "")
                        + (!expectedType.isEmpty() ? " (expected " + expectedType + ")" : ""));
        this.methodIdentity = Objects.requireNonNull(methodIdentity, "methodIdentity");
        this.callError = Objects.requireNonNull(callError, "callError");
        this.argumentIndex = argumentIndex;
        this.expectedType = Objects.requireNonNull(expectedType, "expectedType");
    }

    public String methodIdentity() {
        return methodIdentity;
    }

    public FoundryCallError callError() {
        return callError;
    }

    public int argumentIndex() {
        return argumentIndex;
    }

    public String expectedType() {
        return expectedType;
    }
}

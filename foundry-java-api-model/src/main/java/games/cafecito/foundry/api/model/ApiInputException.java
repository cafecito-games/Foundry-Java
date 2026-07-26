package games.cafecito.foundry.api.model;

/** Reports invalid, incomplete, or incompatible Foundry API inputs. */
public final class ApiInputException extends RuntimeException {
    public ApiInputException(String message) {
        super(message);
    }

    public ApiInputException(String message, Throwable cause) {
        super(message, cause);
    }
}

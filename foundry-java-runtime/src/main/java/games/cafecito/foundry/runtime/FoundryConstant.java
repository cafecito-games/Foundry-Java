package games.cafecito.foundry.runtime;

import games.cafecito.foundry.types.Variant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Lazily resolves one typed generated Foundry constant through a live binding context. */
public final class FoundryConstant<T> {
    private final String sourceIdentity;
    private final Function<Variant, T> decoder;

    public FoundryConstant(String sourceIdentity, Function<Variant, T> decoder) {
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new IllegalArgumentException("Constant source identity must not be blank.");
        }
        this.sourceIdentity = sourceIdentity;
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public String sourceIdentity() {
        return sourceIdentity;
    }

    public T get(FoundryBindingContext context) {
        Variant value =
                Objects.requireNonNull(context, "context").call(0, sourceIdentity, List.of());
        return decoder.apply(value);
    }
}

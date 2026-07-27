package games.cafecito.foundry.runtime;

/**
 * Narrow internal SPI for an engine that materializes decoded native object values.
 *
 * <p>Applications do not implement this interface. It exists so the host-neutral binding context
 * can attach itself without widening the frozen {@link FoundryEngine} transport contract.
 */
public interface FoundryBindingContextAware {
    void attachBindingContext(FoundryBindingContext context);
}

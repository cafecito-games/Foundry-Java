package games.cafecito.foundry.runtime;

/**
 * Direct, generated access to one Java extension class.
 *
 * <p>Implementations are emitted by the annotation processor and invoke generated trampolines
 * without reflection.
 */
public interface FoundryExtensionAccess {
    Object construct(FoundryBindingContext context, ObjectLease lease);

    Object invoke(Object target, String name, Object[] arguments);

    Object getProperty(Object target, String name);

    void setProperty(Object target, String name, Object value);
}

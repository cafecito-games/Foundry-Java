package games.cafecito.foundry.runtime;

/**
 * Direct, generated access to one Java extension class.
 *
 * <p>Implementations are emitted by the annotation processor and invoke generated trampolines
 * without reflection.
 */
public interface FoundryExtensionAccess {
    Object construct(FoundryBindingContext context, ObjectLease lease);

    /**
     * Dispatches a member the engine resolved by its exported name.
     *
     * <p>The {@code name} is the <em>Java</em> member name, never the exported Foundry name: the
     * native bridge resolves the exported name to a member descriptor and then hands this method
     * that descriptor's {@link FoundryMemberDescriptor#javaName()}. Implementations must key their
     * dispatch on the Java name.
     */
    Object invoke(Object target, String name, Object[] arguments);

    /**
     * Reads a property through its accessor.
     *
     * <p>The {@code name} is the Java getter name declared by {@link
     * FoundryPropertyDetails#getter()}, not the exported property name.
     */
    Object getProperty(Object target, String name);

    /**
     * Writes a property through its accessor.
     *
     * <p>The {@code name} is the Java setter name declared by {@link
     * FoundryPropertyDetails#setter()}, not the exported property name.
     */
    void setProperty(Object target, String name, Object value);
}

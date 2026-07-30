package games.cafecito.foundry.acceptance;

@javax.annotation.processing.Generated("games.cafecito.foundry.processor.FoundryExtensionProcessor")
public final class EngineProbe_FoundryTrampoline {
    private EngineProbe_FoundryTrampoline() {}

    public static EngineProbe construct(
            games.cafecito.foundry.runtime.FoundryBindingContext context,
            games.cafecito.foundry.runtime.ObjectLease lease) {
        return new EngineProbe(context, lease);
    }

    public static Object invoke(Object target, String name, Object[] arguments) {
        EngineProbe receiver = (EngineProbe) target;
        return switch (name) {
            case "engine_probe" -> {
                yield receiver.engineProbe((long) arguments[0]);
            }
            default -> throw new IllegalArgumentException("Unknown method: " + name);
        };
    }

    public static Object getProperty(Object target, String name) {
        throw new IllegalArgumentException("Unknown property: " + name);
    }

    public static void setProperty(Object target, String name, Object value) {
        EngineProbe receiver = (EngineProbe) target;
        switch (name) {
            default -> throw new IllegalArgumentException("Unknown property: " + name);
        }
    }
}

package games.cafecito.foundry.generated.acceptance;

@javax.annotation.processing.Generated("games.cafecito.foundry.processor.FoundryExtensionProcessor")
public final class AcceptanceRegistry
        implements games.cafecito.foundry.runtime.FoundryModuleProvider {
    public static final games.cafecito.foundry.runtime.FoundryModuleProvider PROVIDER =
            new AcceptanceRegistry();

    private AcceptanceRegistry() {}

    private static final games.cafecito.foundry.runtime.FoundryModuleDescriptor DESCRIPTOR =
            new games.cafecito.foundry.runtime.FoundryModuleDescriptor(
                    2,
                    "acceptance",
                    "games.cafecito.foundry.generated.acceptance.AcceptanceRegistry",
                    "48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b",
                    "1",
                    "1",
                    "1",
                    java.util.List.of(
                            new games.cafecito.foundry.runtime.FoundryClassDescriptor(
                                    "games.cafecito.foundry.acceptance.EngineProbe",
                                    "FoundryJavaEngineProbeDisabled",
                                    "games.cafecito.foundry.generated.classes.Node",
                                    "SCENE",
                                    java.util.List.of(),
                                    new games.cafecito.foundry.runtime.FoundryExtensionAccess() {
                                        @Override
                                        public Object construct(
                                                games.cafecito.foundry.runtime.FoundryBindingContext context,
                                                games.cafecito.foundry.runtime.ObjectLease lease) {
                                            return games.cafecito.foundry.acceptance.EngineProbe_FoundryTrampoline.construct(context, lease);
                                        }

                                        @Override
                                        public Object invoke(
                                                Object target, String name, Object[] arguments) {
                                            return games.cafecito.foundry.acceptance.EngineProbe_FoundryTrampoline.invoke(
                                                    target, name, arguments);
                                        }

                                        @Override
                                        public Object getProperty(Object target, String name) {
                                            return games.cafecito.foundry.acceptance.EngineProbe_FoundryTrampoline.getProperty(target, name);
                                        }

                                        @Override
                                        public void setProperty(
                                                Object target, String name, Object value) {
                                            games.cafecito.foundry.acceptance.EngineProbe_FoundryTrampoline.setProperty(target, name, value);
                                        }
                                    },
                                    java.util.List.of(
                                            new games.cafecito.foundry.runtime.FoundryMemberDescriptor(
                                                    "method",
                                                    "engine_probe",
                                                    "engineProbe",
                                                    "long(long)")))));

    @Override
    public games.cafecito.foundry.runtime.FoundryModuleDescriptor descriptor() {
        return DESCRIPTOR;
    }
}

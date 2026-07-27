package games.cafecito.foundry.java;

import games.cafecito.foundry.runtime.FoundryBindingContext;
import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryConstantDetails;
import games.cafecito.foundry.runtime.FoundryExtensionAccess;
import games.cafecito.foundry.runtime.FoundryMemberDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleProvider;
import games.cafecito.foundry.runtime.FoundryObject;
import games.cafecito.foundry.runtime.FoundryPropertyDetails;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import games.cafecito.foundry.runtime.FoundryRuntime;
import games.cafecito.foundry.runtime.ObjectLease;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Direct, debug-only registry used by the Android production-startup acceptance lane. */
public final class FoundryJavaTestRegistry {
    private static final String REGISTRY_NAME =
            "games.cafecito.foundry.java.FoundryJavaTestRegistry";
    private static final String CORE_JAVA_NAME = REGISTRY_NAME + "$CoreExtension";
    private static final String SCENE_JAVA_NAME = REGISTRY_NAME + "$SceneExtension";
    private static final AtomicInteger DESCRIPTOR_EVALUATIONS = new AtomicInteger();
    private static final FoundryModuleDescriptor DESCRIPTOR =
            new FoundryModuleDescriptor(
                    FoundryModuleDescriptor.CURRENT_FORMAT,
                    "foundry-java-android-test",
                    REGISTRY_NAME,
                    FoundryRuntime.API_SHA256,
                    FoundryRuntime.GENERATOR_VERSION,
                    FoundryRuntime.RUNTIME_CONTRACT_VERSION,
                    FoundryRuntime.BRIDGE_CONTRACT_VERSION,
                    List.of(coreDescriptor(), sceneDescriptor()));
    private static final FoundryModuleProvider PROVIDER =
            () -> {
                DESCRIPTOR_EVALUATIONS.incrementAndGet();
                return DESCRIPTOR;
            };
    private static final FoundryRegistryBootstrap BOOTSTRAP =
            new FoundryRegistryBootstrap(List.of(PROVIDER));

    private FoundryJavaTestRegistry() {}

    /** Returns the single immutable bootstrap without scanning or reflection. */
    public static FoundryRegistryBootstrap bootstrap() {
        return BOOTSTRAP;
    }

    /** Exposes descriptor evaluation evidence to the acceptance runner. */
    public static int descriptorEvaluations() {
        return DESCRIPTOR_EVALUATIONS.get();
    }

    private static FoundryClassDescriptor coreDescriptor() {
        return new FoundryClassDescriptor(
                CORE_JAVA_NAME,
                "FoundryJavaTestCore",
                "Node",
                "CORE",
                List.of(),
                new CoreAccess(),
                List.of(
                        new FoundryMemberDescriptor(
                                "constant",
                                "ANSWER",
                                "ANSWER",
                                "long",
                                new FoundryConstantDetails("", 42L, false)),
                        new FoundryMemberDescriptor(
                                "method", "round_trip", "roundTrip", "long(long)"),
                        new FoundryMemberDescriptor(
                                "method", "throwing_probe", "throwingProbe", "long()"),
                        new FoundryMemberDescriptor(
                                "override", "_process", "process", "long(long)"),
                        new FoundryMemberDescriptor(
                                "property",
                                "value",
                                "value",
                                "long",
                                new FoundryPropertyDetails(
                                        "getValue", "setValue", -1, "", "", "", "")),
                        new FoundryMemberDescriptor("signal", "ping", "ping", "void(long)")));
    }

    private static FoundryClassDescriptor sceneDescriptor() {
        return new FoundryClassDescriptor(
                SCENE_JAVA_NAME,
                "FoundryJavaTestScene",
                "Node",
                "SCENE",
                List.of(CORE_JAVA_NAME),
                new SceneAccess(),
                List.of());
    }

    /** Test extension exercised through the same direct access contract as generated registries. */
    public static final class CoreExtension extends FoundryObject {
        public static final long ANSWER = 42L;

        private long value = 7L;

        private CoreExtension(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }

        public long roundTrip(long input) {
            long result = input + 1L;
            FoundryJavaStartupEvidence.recordCallbackDispatch(result);
            return result;
        }

        public long throwingProbe() {
            FoundryJavaStartupEvidence.recordExceptionDispatch();
            throw new IllegalStateException("foundry_java_task6_fixture_exception");
        }

        public long process(long delta) {
            return delta * 2L;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Task6FakeExtension";
        }
    }

    /** Dependency-only fixture registered at the SCENE initialization level. */
    public static final class SceneExtension extends FoundryObject {
        private SceneExtension(FoundryBindingContext context, ObjectLease lease) {
            super(context, lease);
        }
    }

    private static final class CoreAccess implements FoundryExtensionAccess {
        @Override
        public Object construct(FoundryBindingContext context, ObjectLease lease) {
            CoreExtension extension = new CoreExtension(context, lease);
            extension.onInvalidated(FoundryJavaStartupEvidence::recordInvalidation);
            return extension;
        }

        @Override
        public Object invoke(Object target, String name, Object[] arguments) {
            CoreExtension receiver = (CoreExtension) target;
            return switch (name) {
                case "roundTrip" -> receiver.roundTrip(numberArgument(arguments, 0));
                case "throwingProbe" -> receiver.throwingProbe();
                case "process" -> receiver.process(numberArgument(arguments, 0));
                default -> throw new IllegalArgumentException("Unknown method: " + name);
            };
        }

        @Override
        public Object getProperty(Object target, String name) {
            CoreExtension receiver = (CoreExtension) target;
            if ("getValue".equals(name)) {
                return receiver.getValue();
            }
            throw new IllegalArgumentException("Unknown property: " + name);
        }

        @Override
        public void setProperty(Object target, String name, Object value) {
            CoreExtension receiver = (CoreExtension) target;
            if ("setValue".equals(name)) {
                receiver.setValue(((Number) value).longValue());
                return;
            }
            throw new IllegalArgumentException("Unknown property: " + name);
        }
    }

    private static final class SceneAccess implements FoundryExtensionAccess {
        @Override
        public Object construct(FoundryBindingContext context, ObjectLease lease) {
            return new SceneExtension(context, lease);
        }

        @Override
        public Object invoke(Object target, String name, Object[] arguments) {
            throw new IllegalArgumentException("Unknown method: " + name);
        }

        @Override
        public Object getProperty(Object target, String name) {
            throw new IllegalArgumentException("Unknown property: " + name);
        }

        @Override
        public void setProperty(Object target, String name, Object value) {
            throw new IllegalArgumentException("Unknown property: " + name);
        }
    }

    private static long numberArgument(Object[] arguments, int index) {
        if (arguments == null
                || arguments.length <= index
                || !(arguments[index] instanceof Number number)) {
            throw new IllegalArgumentException("Expected numeric argument " + index + ".");
        }
        return number.longValue();
    }
}

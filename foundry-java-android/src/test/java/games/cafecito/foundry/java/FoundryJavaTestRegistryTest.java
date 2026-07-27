package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import games.cafecito.foundry.runtime.FoundryClassDescriptor;
import games.cafecito.foundry.runtime.FoundryConstantDetails;
import games.cafecito.foundry.runtime.FoundryMemberDescriptor;
import games.cafecito.foundry.runtime.FoundryModuleDescriptor;
import games.cafecito.foundry.runtime.FoundryPropertyDetails;
import games.cafecito.foundry.runtime.FoundryRegistryBootstrap;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class FoundryJavaTestRegistryTest {
    private static final String FIXTURE_PACKAGE = "games.cafecito.foundry.java.";
    private static final String CORE_JAVA_NAME =
            FIXTURE_PACKAGE + "FoundryJavaTestRegistry$CoreExtension";
    private static final String SCENE_JAVA_NAME =
            FIXTURE_PACKAGE + "FoundryJavaTestRegistry$SceneExtension";

    @Test
    void registryPublishesTwoDeterministicDirectDescriptors() throws Exception {
        Class<?> registry = requireFixture("FoundryJavaTestRegistry");
        FoundryRegistryBootstrap bootstrap =
                (FoundryRegistryBootstrap) registry.getMethod("bootstrap").invoke(null);

        assertEquals(1, bootstrap.providers().size());
        assertEquals(1, bootstrap.descriptors().size());
        assertEquals(List.of("foundry-java-android-test"), bootstrap.moduleNames());
        assertThrows(UnsupportedOperationException.class, () -> bootstrap.providers().clear());

        FoundryModuleDescriptor module = bootstrap.descriptors().get(0);
        assertEquals(FIXTURE_PACKAGE + "FoundryJavaTestRegistry", module.registry());
        assertEquals(2, module.classes().size());

        FoundryClassDescriptor core = module.classes().get(0);
        assertEquals(CORE_JAVA_NAME, core.javaName());
        assertEquals("FoundryJavaTestCore", core.foundryName());
        assertEquals("Node", core.baseName());
        assertEquals("CORE", core.initializationLevel());
        assertEquals(List.of(), core.after());
        assertEquals(
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
                        new FoundryMemberDescriptor("signal", "ping", "ping", "void(long)")),
                core.members());

        FoundryClassDescriptor scene = module.classes().get(1);
        assertEquals(SCENE_JAVA_NAME, scene.javaName());
        assertEquals("FoundryJavaTestScene", scene.foundryName());
        assertEquals("Node", scene.baseName());
        assertEquals("SCENE", scene.initializationLevel());
        assertEquals(List.of(CORE_JAVA_NAME), scene.after());
        assertEquals(List.of(), scene.members());
    }

    @Test
    void providerReturnsTheImmutableBootstrapAndEvaluatesItsDescriptorOnce() throws Exception {
        Class<?> registry = requireFixture("FoundryJavaTestRegistry");
        FoundryRegistryBootstrap bootstrap =
                (FoundryRegistryBootstrap) registry.getMethod("bootstrap").invoke(null);
        Class<?> providerType = requireFixture("FoundryJavaTestStartupProvider");
        Object provider = providerType.getConstructor().newInstance();
        Method providerBootstrap = providerType.getDeclaredMethod("bootstrap");
        providerBootstrap.setAccessible(true);

        assertSame(bootstrap, providerBootstrap.invoke(provider));
        assertSame(bootstrap, registry.getMethod("bootstrap").invoke(null));
        assertEquals(1, registry.getMethod("descriptorEvaluations").invoke(null));
        assertThrows(UnsupportedOperationException.class, () -> bootstrap.descriptors().clear());
    }

    @Test
    void startupEvidenceRecordsProviderBeforeApplicationAndActivity() throws Exception {
        Class<?> evidence = requireFixture("FoundryJavaStartupEvidence");
        invokeStatic(evidence, "resetForTesting");

        invokeStatic(evidence, "recordProviderPrimed");
        invokeStatic(evidence, "recordApplicationCreated");
        invokeStatic(evidence, "recordActivityCreated");

        Object events = evidence.getMethod("eventsForTesting").invoke(null);
        assertEquals(List.of("provider_primed", "application_created", "activity_created"), events);
        assertTrue((Boolean) evidence.getMethod("providerBeforeApplication").invoke(null));
        assertTrue((Boolean) evidence.getMethod("providerBeforeActivity").invoke(null));
    }

    private static void invokeStatic(Class<?> type, String name) throws Exception {
        type.getMethod(name).invoke(null);
    }

    private static Class<?> requireFixture(String simpleName) {
        try {
            Class<?> type = Class.forName(FIXTURE_PACKAGE + simpleName);
            assertNotNull(type);
            return type;
        } catch (ClassNotFoundException failure) {
            fail("Missing debug fixture " + simpleName + " (expected RED)", failure);
            throw new AssertionError("unreachable");
        }
    }
}

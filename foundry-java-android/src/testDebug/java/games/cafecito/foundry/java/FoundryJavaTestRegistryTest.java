package games.cafecito.foundry.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Debug-variant contract tests for the Android production-startup fixture. */
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
        assertEquals(
                List.of("provider_on_create", "application_on_create", "activity_on_create"),
                events);
        assertTrue((Boolean) evidence.getMethod("providerBeforeApplication").invoke(null));
        assertTrue((Boolean) evidence.getMethod("providerBeforeActivity").invoke(null));
    }

    @Test
    void reportEventsMergeStartupAndNativeLifecycleInContractOrder() throws Exception {
        Class<?> evidence = requireFixture("FoundryJavaStartupEvidence");
        invokeStatic(evidence, "resetForTesting");
        invokeStatic(evidence, "recordProviderPrimed");
        invokeStatic(evidence, "recordApplicationCreated");
        invokeStatic(evidence, "recordActivityCreated");
        evidence.getMethod("recordCallbackDispatch", long.class).invoke(null, 42L);
        invokeStatic(evidence, "recordExceptionDispatch");
        invokeStatic(evidence, "recordInvalidation");
        Method merge = requireFixtureMethod(evidence, "mergeLifecycleEvents", List.class);

        Object events =
                merge.invoke(
                        null,
                        List.of(
                                "foundry_extension_entry",
                                "core_initialize",
                                "scene_initialize",
                                "callback_dispatch",
                                "scene_deinitialize",
                                "core_deinitialize",
                                "context_invalidate"));

        assertEquals(
                List.of(
                        "provider_on_create",
                        "application_on_create",
                        "activity_on_create",
                        "foundry_extension_entry",
                        "core_initialize",
                        "scene_initialize",
                        "callback_dispatch",
                        "scene_deinitialize",
                        "core_deinitialize",
                        "context_invalidate"),
                events);
    }

    @Test
    void runIndexMustBePresentNonblankAndPositive() throws Exception {
        Class<?> host = requireFixture("FoundryJavaTestHost");
        Method parser = requireFixtureMethod(host, "requireRunIndex", String.class);

        assertEquals(7, parser.invoke(null, "7"));
        assertIllegalRunIndex(parser, null);
        assertIllegalRunIndex(parser, "");
        assertIllegalRunIndex(parser, " ");
        assertIllegalRunIndex(parser, "0");
        assertIllegalRunIndex(parser, "-1");
        assertIllegalRunIndex(parser, "not-a-number");
    }

    @Test
    void preEntryJsonContractAcceptsIntegerZeroAndRejectsArrayOnDevice() throws Exception {
        String host =
                fixtureSource(
                        "src/debug/java/games/cafecito/foundry/java/FoundryJavaTestHost.java");
        String instrumentation =
                fixtureSource(
                        "src/androidTest/java/games/cafecito/foundry/java/"
                                + "FoundryJavaInstrumentation.java");

        assertTrue(host.contains("evidence.getInt(\"registered_classes\") == 0"));
        assertFalse(host.contains("evidence.getJSONArray(\"registered_classes\")"));
        assertTrue(instrumentation.contains("validatePreEntryJsonContract();"));
        assertTrue(instrumentation.contains("authoritative.put(\"registered_classes\", 0);"));
        assertTrue(
                instrumentation.contains(
                        "wrongType.put(\"registered_classes\", new JSONArray());"));
    }

    @Test
    void partialNativeLifecycleUsesTolerantFailureReportAndPreservesRawJson() throws Exception {
        String evidence =
                fixtureSource(
                        "src/debug/java/games/cafecito/foundry/java/"
                                + "FoundryJavaStartupEvidence.java");
        String instrumentation =
                fixtureSource(
                        "src/androidTest/java/games/cafecito/foundry/java/"
                                + "FoundryJavaInstrumentation.java");
        int failureBranch = evidence.indexOf("if (failure != null)");
        int strictLifecycleRead =
                evidence.indexOf("lifecycle.getJSONArray(\"registration_order\")");

        assertTrue(failureBranch >= 0, "buildReport has no tolerant failure branch");
        assertTrue(
                failureBranch < strictLifecycleRead,
                "strict lifecycle fields are read before the failure branch");
        assertTrue(evidence.contains("buildFailureReport("));
        assertTrue(evidence.contains("report.put(\"native_lifecycle\", copy(nativeLifecycle));"));
        assertTrue(instrumentation.contains("validateFailureReportContract(runIndex);"));
        assertTrue(
                instrumentation.contains(
                        "new JSONObject().put(\"schema_version\", 1).put(\"run_index\", runIndex)"));
    }

    @Test
    void runIndexParsingOccursInsideTheFailureReportingBoundary() throws Exception {
        String source =
                fixtureSource(
                        "src/androidTest/java/games/cafecito/foundry/java/"
                                + "FoundryJavaInstrumentation.java");
        int acceptance = source.indexOf("private void runAcceptance");
        int reportingTry = source.indexOf("try {", acceptance);
        int parsing = source.indexOf("parseRunIndex(arguments)", acceptance);

        assertTrue(acceptance >= 0, "missing acceptance runner");
        assertTrue(reportingTry > acceptance, "missing failure reporting boundary");
        assertTrue(parsing > reportingTry, "run index parsing occurs before failure reporting");
        assertTrue(
                source.substring(acceptance, parsing).contains("int runIndex = 0;"),
                "invalid input has no deterministic fallback run index");
    }

    private static String fixtureSource(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Path.of(relativePath)), StandardCharsets.UTF_8);
    }

    private static void assertIllegalRunIndex(Method parser, String encoded) {
        InvocationTargetException failure =
                assertThrows(
                        InvocationTargetException.class,
                        () -> parser.invoke(null, new Object[] {encoded}));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    private static void invokeStatic(Class<?> type, String name) throws Exception {
        type.getMethod(name).invoke(null);
    }

    private static Method requireFixtureMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException failure) {
            fail("Missing debug fixture method " + type.getSimpleName() + "." + name, failure);
            throw new AssertionError("unreachable");
        }
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

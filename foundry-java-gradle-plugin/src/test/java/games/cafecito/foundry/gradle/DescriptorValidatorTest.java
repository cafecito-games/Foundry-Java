package games.cafecito.foundry.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DescriptorValidatorTest {
    private static final String API_SHA =
            "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51";
    private static final String OTHER_API_SHA =
            "15e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51";
    private static final Set<String> ALL_ABIS =
            Set.of("armeabi-v7a", "arm64-v8a", "x86", "x86_64");

    @Test
    void parsesStrictFormatTwoAndPreservesRepeatableEntries() {
        FoundryDescriptor descriptor =
                DescriptorValidator.parse(
                        "modules/demo.jar",
                        descriptorText(
                                "demo-module",
                                "games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                                API_SHA,
                                "1",
                                "1",
                                "1"));

        assertEquals("modules/demo.jar", descriptor.artifact());
        assertEquals(2, descriptor.format());
        assertEquals("demo-module", descriptor.module());
        assertEquals(
                "games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                descriptor.registry());
        assertEquals(API_SHA, descriptor.apiSha256());
        assertEquals("1", descriptor.generatorVersion());
        assertEquals("1", descriptor.runtimeContractVersion());
        assertEquals("1", descriptor.bridgeContractVersion());
        assertEquals(
                List.of("class", "method", "method", "signal", "property"),
                descriptor.entries().stream().map(FoundryDescriptor.Entry::kind).toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> descriptor.entries().add(new FoundryDescriptor.Entry("method", "bad")));
    }

    @Test
    void rejectsFormatOneUnknownDuplicateMissingAndMisorderedHeaders() {
        String valid =
                descriptorText(
                        "demo-module",
                        "games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                        API_SHA,
                        "1",
                        "1",
                        "1");
        List<InvalidDescriptor> cases =
                List.of(
                        new InvalidDescriptor(
                                "format one", valid.replaceFirst("format=2", "format=1"), "format=1"),
                        new InvalidDescriptor(
                                "unknown header",
                                valid.replaceFirst(
                                        "registry=", "legacy_manifest=enabled\\nregistry="),
                                "legacy_manifest=enabled"),
                        new InvalidDescriptor(
                                "duplicate header",
                                valid.replaceFirst("registry=", "module=other\\nregistry="),
                                "module=other"),
                        new InvalidDescriptor(
                                "missing header",
                                valid.replaceFirst("generator_version=1\\n", ""),
                                "generator_version"),
                        new InvalidDescriptor(
                                "misordered header",
                                valid.replaceFirst(
                                        "module=demo-module\\nregistry=games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                                        "registry=games.cafecito.foundry.generated.demomodule.DemoModuleRegistry\\nmodule=demo-module"),
                                "expected field module"),
                        new InvalidDescriptor(
                                "entry before headers",
                                "class=demo.Before|Before|demo.EngineNode|SCENE|\\n" + valid,
                                "expected field format"));

        for (InvalidDescriptor invalid : cases) {
            assertInvalid(invalid.name(), invalid.contents(), invalid.expected());
        }
    }

    @Test
    void rejectsMalformedHeaderNamesHashesVersionsAndLines() {
        String valid =
                descriptorText(
                        "demo-module",
                        "games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                        API_SHA,
                        "1",
                        "1",
                        "1");
        List<InvalidDescriptor> cases =
                List.of(
                        new InvalidDescriptor(
                                "module",
                                valid.replace("module=demo-module", "module=Demo_Module"),
                                "module=Demo_Module"),
                        new InvalidDescriptor(
                                "registry",
                                valid.replace(
                                        "registry=games.cafecito.foundry.generated.demomodule.DemoModuleRegistry",
                                        "registry=not a java name"),
                                "registry=not a java name"),
                        new InvalidDescriptor(
                                "hash",
                                valid.replace("api_sha256=" + API_SHA, "api_sha256=" + API_SHA.toUpperCase()),
                                "api_sha256="),
                        new InvalidDescriptor(
                                "generator",
                                valid.replace("generator_version=1", "generator_version=0"),
                                "generator_version=0"),
                        new InvalidDescriptor(
                                "runtime",
                                valid.replace(
                                        "runtime_contract_version=1",
                                        "runtime_contract_version=one"),
                                "runtime_contract_version=one"),
                        new InvalidDescriptor(
                                "bridge",
                                valid.replace(
                                        "bridge_contract_version=1",
                                        "bridge_contract_version=1.0"),
                                "bridge_contract_version=1.0"),
                        new InvalidDescriptor(
                                "malformed line",
                                valid.replace("method=demo.SpinningCube|reset|reset|void()",
                                        "method-without-equals"),
                                "method-without-equals"),
                        new InvalidDescriptor(
                                "unknown entry",
                                valid + "constructor=demo.SpinningCube|new|new|void()\\n",
                                "constructor="),
                        new InvalidDescriptor(
                                "malformed class",
                                valid.replace(
                                        "class=demo.SpinningCube|SpinningCube|demo.EngineNode|SCENE|",
                                        "class=demo.SpinningCube|SpinningCube"),
                                "class="),
                        new InvalidDescriptor(
                                "malformed member",
                                valid.replace(
                                        "method=demo.SpinningCube|reset|reset|void()",
                                        "method=demo.SpinningCube||reset|void()"),
                                "method="));

        for (InvalidDescriptor invalid : cases) {
            assertInvalid(invalid.name(), invalid.contents(), invalid.expected());
        }
    }

    @Test
    void validatesAndSortsACompatibleWholeGraph() {
        ArrayList<FoundryDescriptor> descriptors =
                new ArrayList<>(
                        List.of(
                                parse("zeta.jar", "zeta", "example.Zeta", API_SHA, "1", "1", "1"),
                                parse("alpha.jar", "alpha", "example.Alpha", API_SHA, "1", "1", "1")));
        DescriptorValidator.AndroidPayload payload =
                new DescriptorValidator.AndroidPayload(
                        "foundry-java-android.aar", true, true, ALL_ABIS);

        List<FoundryDescriptor> validated =
                DescriptorValidator.validateGraph(
                        descriptors, List.of(payload), Set.of("x86_64", "arm64-v8a"));
        descriptors.clear();

        assertEquals(List.of("alpha", "zeta"), validated.stream().map(FoundryDescriptor::module).toList());
        assertThrows(UnsupportedOperationException.class, validated::clear);
    }

    @Test
    void rejectsDuplicateModuleAndRegistryIdentitiesWithArtifactEvidence() {
        IllegalArgumentException duplicateModule =
                assertGraphInvalid(
                        List.of(
                                parse("zeta.jar", "shared", "example.Zeta", API_SHA, "1", "1", "1"),
                                parse("alpha.jar", "shared", "example.Alpha", API_SHA, "1", "1", "1")),
                        payloads(),
                        Set.of("arm64-v8a"));
        assertContainsAll(
                duplicateModule.getMessage(),
                "alpha.jar",
                "zeta.jar",
                "module=shared");

        IllegalArgumentException duplicateRegistry =
                assertGraphInvalid(
                        List.of(
                                parse("zeta.jar", "zeta", "example.Shared", API_SHA, "1", "1", "1"),
                                parse("alpha.jar", "alpha", "example.Shared", API_SHA, "1", "1", "1")),
                        payloads(),
                        Set.of("arm64-v8a"));
        assertContainsAll(
                duplicateRegistry.getMessage(),
                "alpha.jar",
                "zeta.jar",
                "registry=example.Shared");
    }

    @Test
    void rejectsMixedApiGeneratorRuntimeAndBridgeContracts() {
        List<MixedContract> cases =
                List.of(
                        new MixedContract("api_sha256", OTHER_API_SHA, "1", "1", "1"),
                        new MixedContract("generator_version", API_SHA, "2", "1", "1"),
                        new MixedContract("runtime_contract_version", API_SHA, "1", "2", "1"),
                        new MixedContract("bridge_contract_version", API_SHA, "1", "1", "2"));

        for (MixedContract mixed : cases) {
            IllegalArgumentException failure =
                    assertGraphInvalid(
                            List.of(
                                    parse("alpha.jar", "alpha", "example.Alpha", API_SHA, "1", "1", "1"),
                                    parse(
                                            "zeta.jar",
                                            "zeta",
                                            "example.Zeta",
                                            mixed.apiSha(),
                                            mixed.generator(),
                                            mixed.runtime(),
                                            mixed.bridge())),
                            payloads(),
                            Set.of("arm64-v8a"));
            assertContainsAll(failure.getMessage(), "alpha.jar", "zeta.jar", mixed.field() + "=");
        }
    }

    @Test
    void rejectsDuplicateOrMissingBridgeAndConfigurationPayloads() {
        FoundryDescriptor descriptor =
                parse("demo.jar", "demo", "example.Demo", API_SHA, "1", "1", "1");
        DescriptorValidator.AndroidPayload first =
                new DescriptorValidator.AndroidPayload("a.aar", true, true, ALL_ABIS);
        DescriptorValidator.AndroidPayload second =
                new DescriptorValidator.AndroidPayload("b.aar", true, true, ALL_ABIS);

        IllegalArgumentException duplicates =
                assertGraphInvalid(
                        List.of(descriptor), List.of(second, first), Set.of("arm64-v8a"));
        assertContainsAll(
                duplicates.getMessage(),
                "a.aar",
                "b.aar",
                "bridge_payload=true",
                "configuration_payload=true");

        IllegalArgumentException missing =
                assertGraphInvalid(
                        List.of(descriptor),
                        List.of(
                                new DescriptorValidator.AndroidPayload(
                                        "empty.aar", false, false, Set.of())),
                        Set.of("arm64-v8a"));
        assertContainsAll(
                missing.getMessage(),
                "empty.aar",
                "bridge_payload=false",
                "configuration_payload=false");
    }

    @Test
    void rejectsMissingRequestedAbiWithPayloadIdentity() {
        IllegalArgumentException failure =
                assertGraphInvalid(
                        List.of(parse("demo.jar", "demo", "example.Demo", API_SHA, "1", "1", "1")),
                        List.of(
                                new DescriptorValidator.AndroidPayload(
                                        "bridge-arm64.aar",
                                        true,
                                        true,
                                        Set.of("arm64-v8a"))),
                        Set.of("x86_64", "arm64-v8a"));

        assertContainsAll(failure.getMessage(), "bridge-arm64.aar", "abi=x86_64");
    }

    @Test
    void wholeGraphDiagnosticsAreStableSortedAndNameConflictingValues() {
        IllegalArgumentException failure =
                assertGraphInvalid(
                        List.of(
                                parse("zeta.jar", "shared", "example.Shared", OTHER_API_SHA, "2", "2", "2"),
                                parse("alpha.jar", "shared", "example.Shared", API_SHA, "1", "1", "1")),
                        List.of(
                                new DescriptorValidator.AndroidPayload(
                                        "zeta.aar", true, true, Set.of("arm64-v8a")),
                                new DescriptorValidator.AndroidPayload(
                                        "alpha.aar", true, true, Set.of("arm64-v8a"))),
                        Set.of("x86_64"));

        List<String> diagnostics = failure.getMessage().lines().skip(1).toList();
        List<String> sorted = diagnostics.stream().sorted().toList();
        assertEquals(sorted, diagnostics);
        assertContainsAll(
                failure.getMessage(),
                "api_sha256=" + API_SHA,
                "api_sha256=" + OTHER_API_SHA,
                "generator_version=1",
                "generator_version=2",
                "runtime_contract_version=1",
                "runtime_contract_version=2",
                "bridge_contract_version=1",
                "bridge_contract_version=2");
    }

    private static FoundryDescriptor parse(
            String artifact,
            String module,
            String registry,
            String apiSha,
            String generator,
            String runtime,
            String bridge) {
        return DescriptorValidator.parse(
                artifact,
                descriptorText(module, registry, apiSha, generator, runtime, bridge));
    }

    private static String descriptorText(
            String module,
            String registry,
            String apiSha,
            String generator,
            String runtime,
            String bridge) {
        return """
                format=2
                module=%s
                registry=%s
                api_sha256=%s
                generator_version=%s
                runtime_contract_version=%s
                bridge_contract_version=%s
                class=demo.SpinningCube|SpinningCube|demo.EngineNode|SCENE|
                method=demo.SpinningCube|reset|reset|void()
                method=demo.SpinningCube|spin|spin|void(double)
                signal=demo.SpinningCube|reset_done|Reset|void(double)
                property=demo.SpinningCube|speed|speed|double
                """
                .formatted(module, registry, apiSha, generator, runtime, bridge);
    }

    private static List<DescriptorValidator.AndroidPayload> payloads() {
        return List.of(
                new DescriptorValidator.AndroidPayload(
                        "foundry-java-android.aar", true, true, ALL_ABIS));
    }

    private static void assertInvalid(String name, String contents, String expected) {
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DescriptorValidator.parse("bad/" + name + ".jar", contents));
        assertContainsAll(failure.getMessage(), "bad/" + name + ".jar", expected);
    }

    private static IllegalArgumentException assertGraphInvalid(
            List<FoundryDescriptor> descriptors,
            List<DescriptorValidator.AndroidPayload> payloads,
            Set<String> requestedAbis) {
        return assertThrows(
                IllegalArgumentException.class,
                () -> DescriptorValidator.validateGraph(descriptors, payloads, requestedAbis));
    }

    private static void assertContainsAll(String actual, String... expected) {
        for (String fragment : expected) {
            assertTrue(
                    actual.contains(fragment),
                    () -> "Expected <" + actual + "> to contain <" + fragment + ">.");
        }
    }

    private record InvalidDescriptor(String name, String contents, String expected) {}

    private record MixedContract(
            String field, String apiSha, String generator, String runtime, String bridge) {}
}

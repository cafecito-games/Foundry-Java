package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.ApiInputs;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundrySourceGeneratorTest {
    private static final String API_HASH =
            "85e91174c1a8a48629223d6459bb2ef595ad1da405b2ce88435c24fe221aec51";
    private static final String HEADER_HASH =
            "ecf9a1f1e6b2642385a521725313efb2baea8b81fcac9dc837f55a4b90498991";
    private static final FoundrySourceGenerator.Metadata METADATA =
            new FoundrySourceGenerator.Metadata(
                    API_HASH,
                    HEADER_HASH,
                    "3923e920b2fb6db68f82dfdab2bf7b1df125492d",
                    "0.1.0-alpha.8",
                    "1",
                    "1");
    private static final String BINDING_VERSION = "0.1.0-SNAPSHOT";
    private static final Pattern METHOD_DECLARATION =
            Pattern.compile(
                    "^ {4}public (?:static )?[\\w.<>\\[\\], ?]+ (\\w+)\\(", Pattern.MULTILINE);
    private static final Pattern MANIFEST_HASH =
            Pattern.compile(
                    "[0-9a-f]{64}(?=\";\\n    public static final String GENERATOR_VERSION)");

    @TempDir Path temporaryDirectory;

    @Test
    void goldenGenerationCoversEveryFixtureCategoryAndSourceEntity() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());

        CompatibilityManifest manifest = supportedManifest(api);
        GeneratedTree generated = new FoundrySourceGenerator().generate(api, METADATA, manifest);

        Set<String> parsedIdentities =
                api.entities().stream()
                        .map(FoundryApi.Entity::identity)
                        .collect(Collectors.toSet());
        assertEquals(parsedIdentities, generated.coveredIdentities());
        assertEquals(parsedIdentities, publicIdentityMarkers(generated));
        assertEquals(
                parsedIdentities,
                manifest.entries().stream()
                        .map(CompatibilityManifest.Entry::sourceIdentity)
                        .collect(Collectors.toSet()));
        assertEquals(
                parsedIdentities.size(),
                manifest.statusCounts().get(CompatibilityManifest.Status.SUPPORTED));
        assertTrue(
                manifest.entries().stream()
                        .allMatch(
                                entry ->
                                        entry.reasonCode()
                                                .equals("WS5_MODEL_AND_GENERATOR_REPRESENTABLE")));

        String allSources = String.join("\n", generated.sources().values());
        for (String requiredIdentity :
                List.of(
                        "classes/Node/methods/_process#100",
                        "classes/Node/methods/get_children#101/arguments/include_internal",
                        "classes/Node/properties/owner_path",
                        "classes/Node/signals/renamed",
                        "builtin_classes/Array/constructors/#1",
                        "builtin_classes/Array/operators/==#Array",
                        "global_enums/Variant.Type",
                        "utility_functions/type_convert#128",
                        "utility_functions/type_convert#128/arguments/packed",
                        "singletons/Engine",
                        "native_structures/ObjectID")) {
            assertTrue(
                    allSources.contains("public-identity-base64 " + base64(requiredIdentity)),
                    requiredIdentity);
        }
        assertTrue(allSources.contains("GeneratedRegistration"));
        assertFalse(allSources.contains("generated_at"));
        assertFalse(allSources.contains(System.getProperty("user.dir")));
        assertTrue(
                generated.sources().values().stream()
                        .allMatch(
                                source ->
                                        source.startsWith(
                                                "// Foundry-Java generated metadata; dynamic values"
                                                        + " use RFC 4648 base64.\n")));

        String registration =
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/GeneratedRegistration.java");
        assertNotNull(registration);
        assertEquals(10, generated.descriptorCatalog().size());
        assertTrue(registration.contains("public static final int ROOT_COUNT = 10"));
        assertTrue(
                registration.contains(
                        "public static final int ENTITY_COUNT = " + parsedIdentities.size()));
        assertTrue(registration.contains("Object.registerObjectType(context)"));
        assertTrue(registration.contains("Node.registerObjectType(context)"));
        assertFalse(registration.contains("Descriptor"));

        String objectWrapper =
                generated.sources().get("games/cafecito/foundry/generated/classes/Object.java");
        String nodeWrapper =
                generated.sources().get("games/cafecito/foundry/generated/classes/Node.java");
        String builtinWrapper =
                generated.sources().get("games/cafecito/foundry/generated/builtins/ArrayApi.java");
        String utilityWrapper =
                generated.sources().get("games/cafecito/foundry/generated/Utilities.java");
        String singletonWrapper =
                generated.sources().get("games/cafecito/foundry/generated/singletons/Engine.java");
        assertNotNull(objectWrapper);
        assertNotNull(nodeWrapper);
        assertNotNull(builtinWrapper);
        assertNotNull(utilityWrapper);
        assertNotNull(singletonWrapper);
        assertTrue(nodeWrapper.contains("@games.cafecito.foundry.annotations.GeneratedByFoundry"));
        assertTrue(
                nodeWrapper.contains(
                        "@games.cafecito.foundry.annotations.FoundryVirtual(\"_process\")"));
        assertTrue(nodeWrapper.contains("public class Node extends Object"));
        assertTrue(
                objectWrapper.contains(
                        "public class Object extends "
                                + "games.cafecito.foundry.runtime.FoundryObject"));
        assertTrue(nodeWrapper.contains("games.cafecito.foundry.runtime.FoundryBindingContext"));
        assertTrue(nodeWrapper.contains("games.cafecito.foundry.runtime.ObjectLease lease"));
        assertFalse(
                nodeWrapper.contains("games.cafecito.foundry.runtime.ObjectOwnership ownership"));
        assertTrue(nodeWrapper.contains("ObjectOwnership.BORROWED"));
        assertTrue(nodeWrapper.contains("context.bind("));
        assertTrue(
                singletonWrapper.contains(
                        "games.cafecito.foundry.generated.classes.Object     bind("));
        assertTrue(singletonWrapper.contains("classes.Object.bind(context, objectHandle)"));
        assertTrue(
                nodeWrapper.contains(
                        "public-identity-base64 "
                                + base64("classes/Node/methods/get_children#101")));
        assertTrue(builtinWrapper.contains("public final class ArrayApi"));
        assertTrue(
                builtinWrapper.contains(
                        "FoundryArray<games.cafecito.foundry.types.Variant> receiver"));
        assertFalse(builtinWrapper.contains("Variant value"));
        assertTrue(utilityWrapper.contains("public static games.cafecito.foundry.types.Variant"));
        assertTrue(utilityWrapper.contains("context.call(0,"));
        assertNotNull(
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/" + "GeneratedPublicApi.java"));

        String typeConvertDescriptor =
                generated.sources().get("games/cafecito/foundry/generated/Utilities.java");
        String valueIdentity = "utility_functions/type_convert#128/arguments/value";
        String packedIdentity = "utility_functions/type_convert#128/arguments/packed";
        assertTrue(
                typeConvertDescriptor.indexOf("public-identity-base64 " + base64(valueIdentity))
                        < typeConvertDescriptor.indexOf(
                                "public-identity-base64 " + base64(packedIdentity)));

        String provenance =
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/" + "GeneratedApiProvenance.java");
        assertNotNull(provenance);
        String normalized = MANIFEST_HASH.matcher(provenance).replaceFirst("<manifest-sha256>");
        assertEquals(expectedProvenance(), normalized);
    }

    @Test
    void generatedSurfaceUsesStableRecognizableStronglyTypedJavaApi() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());
        GeneratedTree generated =
                new FoundrySourceGenerator().generate(api, METADATA, supportedManifest(api));

        String object =
                generated.sources().get("games/cafecito/foundry/generated/classes/Object.java");
        String node = generated.sources().get("games/cafecito/foundry/generated/classes/Node.java");
        String array =
                generated.sources().get("games/cafecito/foundry/generated/builtins/ArrayApi.java");
        String utilities =
                generated.sources().get("games/cafecito/foundry/generated/Utilities.java");
        String engine =
                generated.sources().get("games/cafecito/foundry/generated/singletons/Engine.java");
        String globalEnum =
                generated.sources().get("games/cafecito/foundry/generated/enums/VariantType.java");

        assertNotNull(object);
        assertNotNull(node);
        assertNotNull(array);
        assertNotNull(utilities);
        assertNotNull(engine);
        assertNotNull(globalEnum);
        assertTrue(globalEnum.contains("@games.cafecito.foundry.annotations.GeneratedByFoundry"));
        assertTrue(node.contains("public class Node extends Object"));
        assertTrue(node.contains("getChildren(boolean includeInternal)"));
        assertTrue(node.contains("getChildren()"));
        assertTrue(node.contains("setOwnerPath(games.cafecito.foundry.types.NodePath ownerPath)"));
        assertTrue(node.contains("getOwnerPath()"));
        assertTrue(node.contains("renamedSignal()"));
        assertTrue(
                node.contains("@games.cafecito.foundry.annotations.FoundryVirtual(\"_process\")"));
        assertTrue(node.contains("protected void onProcess(double delta)"));
        assertTrue(node.contains("public enum ProcessMode"));
        assertTrue(node.contains("NOTIFICATION_READY"));
        assertTrue(array.contains("public final class ArrayApi"));
        assertTrue(
                array.contains(
                        "void assign(games.cafecito.foundry.runtime.FoundryBindingContext context,"));
        assertTrue(array.contains("FoundryArray<games.cafecito.foundry.types.Variant> receiver"));
        assertTrue(
                array.contains(
                        "boolean equalTo(games.cafecito.foundry.runtime.FoundryBindingContext"
                                + " context,"));
        assertTrue(array.contains("public enum StorageMode"));
        assertTrue(array.contains("MAX_SIZE"));
        assertTrue(
                utilities.contains(
                        "public static games.cafecito.foundry.types.Variant typeConvert("));
        assertTrue(engine.contains("public final class Engine"));
        assertTrue(globalEnum.contains("public enum VariantType"));

        String allPublicSources =
                generated.sources().entrySet().stream()
                        .filter(entry -> entry.getKey().contains("/generated/"))
                        .map(Map.Entry::getValue)
                        .collect(Collectors.joining("\n"));
        assertFalse(
                Pattern.compile("\\bApi[A-Za-z0-9_]*_[0-9a-f]{12}\\b")
                        .matcher(allPublicSources)
                        .find());
        assertFalse(
                Pattern.compile("\\binvoke_[A-Za-z0-9_]+_[0-9a-f]{12}\\b")
                        .matcher(allPublicSources)
                        .find());
    }

    @Test
    void typeMapperHandlesConstraintsPointersAndEncodedCollectionsWithoutInventedClasses() {
        assertEquals(
                "games.cafecito.foundry.runtime.FoundryObject",
                FoundrySourceGenerator.javaTypeForTesting("BaseMaterial3D,ShaderMaterial"));
        assertEquals(
                "games.cafecito.foundry.generated.classes.Mesh",
                FoundrySourceGenerator.javaTypeForTesting("Mesh,-PlaneMesh,-PointMesh,-QuadMesh"));
        assertEquals(
                "games.cafecito.foundry.runtime.FoundryNativeHandle<"
                        + "games.cafecito.foundry.generated.pointers."
                        + "NativePointers.ConstUint8PointerPointer>",
                FoundrySourceGenerator.javaTypeForTesting("const uint8_t **"));
        assertEquals(
                "games.cafecito.foundry.generated.structures."
                        + "PhysicsServer3DExtensionMotionResult",
                FoundrySourceGenerator.javaTypeForTesting("PhysicsServer3DExtensionMotionResult*"));
        assertEquals(
                "games.cafecito.foundry.types.FoundryArray<"
                        + "games.cafecito.foundry.generated.classes.CompositorEffect>",
                FoundrySourceGenerator.javaTypeForTesting("typedarray::24/17:CompositorEffect"));
        assertEquals(
                "games.cafecito.foundry.types.FoundryArray<"
                        + "games.cafecito.foundry.types.Variant>",
                FoundrySourceGenerator.javaTypeForTesting("typedarray::27/0:"));
        assertEquals(
                "games.cafecito.foundry.types.FoundryDictionary<"
                        + "games.cafecito.foundry.types.Color, "
                        + "games.cafecito.foundry.types.Color>",
                FoundrySourceGenerator.javaTypeForTesting("typeddictionary::Color;Color"));
        assertThrows(
                ApiInputException.class,
                () -> FoundrySourceGenerator.javaTypeForTesting("typeddictionary::Color;"));
    }

    @Test
    void generationFailsClosedWhenMetadataReferencesAnUnknownType() throws IOException {
        FoundryApi api =
                FoundryApiParser.parse(
                        fixture()
                                .replace(
                                        "\"name\": \"include_internal\",\n"
                                                + "              \"type\": \"bool\",",
                                        "\"name\": \"include_internal\",\n"
                                                + "              \"type\": \"InventedApiType\","));

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                new FoundrySourceGenerator()
                                        .generate(api, METADATA, supportedManifest(api)));

        assertTrue(failure.getMessage().contains("Unknown Foundry API type InventedApiType"));
        assertTrue(failure.getMessage().contains("include_internal"));
    }

    @Test
    void generatedPointerCallsEncodeAndDecodeOpaqueBridgeHandlesIncludingNull() throws Exception {
        String pointerFixture =
                fixture()
                        .replace("\"type\": \"typedarray::Node\"", "\"type\": \"const void*\"")
                        .replace(
                                "\"name\": \"include_internal\",\n"
                                        + "              \"type\": \"bool\",",
                                "\"name\": \"include_internal\",\n"
                                        + "              \"type\": \"const void*\",");
        FoundryApi api = FoundryApiParser.parse(pointerFixture);
        GeneratedTree generated =
                new FoundrySourceGenerator().generate(api, METADATA, supportedManifest(api));
        String node = generated.sources().get("games/cafecito/foundry/generated/classes/Node.java");

        assertNotNull(node);
        assertTrue(
                node.contains(
                        "FoundryNativeHandle<games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid> getChildren("
                                + "games.cafecito.foundry.runtime.FoundryNativeHandle<"
                                + "games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid> includeInternal)"));
        assertTrue(
                node.contains(
                        "includeInternal.requireContext(context().contextHandle())"
                                + ".requireType(games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid.class).bridgeHandle()"));
        assertTrue(
                node.contains(
                        "FoundryNativeHandle.owned(context().contextHandle(), "
                                + "games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid.class, "
                                + "__foundryGeneratedResult.asLong(), context().engine())"));

        Path output = temporaryDirectory.resolve("pointer-calls");
        generated.writeTo(output);
        assertEquals(0, compile(output, temporaryDirectory.resolve("pointer-call-classes")));
    }

    @Test
    void globalBitfieldsGenerateNamedLongFlagsInsteadOfInvalidEnums() throws IOException {
        String bitfieldFixture =
                fixture()
                        .replace("\"name\": \"Variant.Type\"", "\"name\": \"Permissions\"")
                        .replace("\"is_bitfield\": false", "\"is_bitfield\": true");
        FoundryApi api = FoundryApiParser.parse(bitfieldFixture);
        GeneratedTree generated =
                new FoundrySourceGenerator().generate(api, METADATA, supportedManifest(api));

        String flags =
                generated.sources().get("games/cafecito/foundry/generated/enums/Permissions.java");
        assertNotNull(flags);
        assertTrue(flags.contains("public final class Permissions"));
        assertTrue(flags.contains("public static final long TYPE_NIL = 0L"));
        assertFalse(flags.contains("public enum Permissions"));
    }

    @Test
    void generatedOperatorJavadocsEscapeHtmlMetacharacters() throws IOException {
        FoundryApi api =
                FoundryApiParser.parse(fixture().replace("\"name\": \"==\"", "\"name\": \"<\""));
        GeneratedTree generated =
                new FoundrySourceGenerator().generate(api, METADATA, supportedManifest(api));
        String array =
                generated.sources().get("games/cafecito/foundry/generated/builtins/ArrayApi.java");

        assertNotNull(array);
        assertTrue(array.contains("/** Applies the &lt; operator. */"));
        assertFalse(array.contains("/** Applies the < operator. */"));
    }

    @Test
    void cleanRepeatGenerationIsByteIdenticalAndGeneratedJavaCompiles() throws Exception {
        FoundryApi api = FoundryApiParser.parse(fixture());
        FoundrySourceGenerator generator = new FoundrySourceGenerator();

        CompatibilityManifest manifest = supportedManifest(api);
        GeneratedTree first = generator.generate(api, METADATA, manifest);
        GeneratedTree second =
                generator.generate(FoundryApiParser.parse(api.canonicalJson()), METADATA, manifest);
        Path firstOutput = temporaryDirectory.resolve("first");
        Path secondOutput = temporaryDirectory.resolve("second");
        first.writeTo(firstOutput);
        second.writeTo(secondOutput);

        assertEquals(first.sha256ByPath(), second.sha256ByPath());
        assertEquals(treeHashes(firstOutput), treeHashes(secondOutput));
        Path firstClasses = temporaryDirectory.resolve("first-classes");
        assertEquals(0, compile(firstOutput, firstClasses));
        assertEquals(0, compile(secondOutput, temporaryDirectory.resolve("second-classes")));
        assertEquals(first.descriptorCatalog(), runtimeRegistrationCatalog(firstClasses));
    }

    @Test
    void fullAcceptedApiGenerationIsExhaustiveDeterministicAndCompiles() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        FoundrySourceGenerator.Metadata metadata =
                new FoundrySourceGenerator.Metadata(
                        inputs.extensionApiSha256(),
                        inputs.interfaceHeaderSha256(),
                        inputs.provenance().foundryCommit(),
                        inputs.provenance().foundryVersion(),
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());

        CompatibilityManifest acceptedManifest = CompatibilityManifest.parse(api, inputs);
        GeneratedTree first =
                new FoundrySourceGenerator().generate(api, metadata, acceptedManifest);
        GeneratedTree second =
                new FoundrySourceGenerator()
                        .generate(
                                FoundryApiParser.parse(api.canonicalJson()),
                                metadata,
                                acceptedManifest);
        Path output = temporaryDirectory.resolve("accepted");
        Path secondOutput = temporaryDirectory.resolve("accepted-second");
        first.writeTo(output);
        second.writeTo(secondOutput);
        assertEquals(57_899, api.entities().size());
        assertEquals(57_899, acceptedManifest.entries().size());
        assertEquals(1_279, first.sources().size());
        assertEquals(1_297, first.descriptorCatalog().size());
        assertEquals(
                57_899,
                acceptedManifest.statusCounts().get(CompatibilityManifest.Status.SUPPORTED));
        assertTrue(
                acceptedManifest.entries().stream()
                        .allMatch(
                                entry ->
                                        entry.reasonCode()
                                                .equals("WS5_MODEL_AND_GENERATOR_REPRESENTABLE")));
        assertEquals(
                api.entities().stream()
                        .map(FoundryApi.Entity::identity)
                        .collect(Collectors.toSet()),
                first.coveredIdentities());
        assertEquals(first.coveredIdentities(), publicIdentityMarkers(first));
        assertEquals(first.sha256ByPath(), second.sha256ByPath());
        assertEquals(treeHashes(output), treeHashes(secondOutput));
        assertEquals(acceptedManifest.canonicalJson(), first.manifest().canonicalJson());
        String node3d = first.sources().get("games/cafecito/foundry/generated/classes/Node3D.java");
        assertNotNull(node3d);
        assertTrue(node3d.contains("rotateY(double angle)"));
        assertTrue(node3d.contains("setPosition(games.cafecito.foundry.types.Vector3 position)"));
        assertTrue(node3d.contains("games.cafecito.foundry.types.Vector3 getPosition()"));
        assertEquals(
                inputs.compatibilityManifestSha256(),
                sha256(first.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, compile(output, temporaryDirectory.resolve("accepted-classes")));
    }

    /**
     * An extension declares its parent as the generated binding type, and the processor registers
     * that parent under the binding's own name because the engine resolves parents by engine class
     * name. Every generated engine class must therefore keep the engine's name in Java, or the
     * engine would reject any extension that inherits from the renamed binding.
     */
    @Test
    void everyGeneratedEngineClassKeepsItsEngineClassNameInJava() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);

        GeneratedTree generated = generateAcceptedApi();

        List<String> engineClassNames =
                api.categories().getOrDefault("classes", List.of()).stream()
                        .map(FoundryApi.Entity::identity)
                        .map(identity -> identity.substring(identity.lastIndexOf('/') + 1))
                        .toList();
        assertFalse(engineClassNames.isEmpty());
        assertEquals(
                List.of(),
                engineClassNames.stream()
                        .filter(
                                name ->
                                        !generated.sources()
                                                .containsKey(
                                                        "games/cafecito/foundry/generated/classes/"
                                                                + name
                                                                + ".java"))
                        .toList());
    }

    @Test
    void generatedNativeDispatchCoversEveryKindAndAcceptedInventoryDeterministically()
            throws IOException {
        GeneratedTree first = generateAcceptedApi();
        GeneratedTree second = generateAcceptedApi();

        String facade =
                first.sources()
                        .get("games/cafecito/foundry/generated/GeneratedNativeDispatch.java");
        List<Map.Entry<String, String>> shards =
                first.sources().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getKey()
                                                .matches(
                                                        "games/cafecito/foundry/generated/"
                                                                + "GeneratedNativeDispatchShard"
                                                                + "[0-9]{3}\\.java"))
                        .toList();
        String rows = shards.stream().map(Map.Entry::getValue).collect(Collectors.joining("\n"));

        assertNotNull(facade);
        assertEquals(91, shards.size());
        assertEquals(23_226, countOccurrences(rows, "target.put("));
        Map.of(
                        "CLASS_METHOD", 16_318,
                        "CLASS_PROPERTY", 4_108,
                        "CLASS_SIGNAL", 508,
                        "BUILTIN_METHOD", 1_000,
                        "BUILTIN_CONSTRUCTOR", 156,
                        "BUILTIN_OPERATOR", 749,
                        "BUILTIN_MEMBER", 62,
                        "BUILTIN_CONSTANT", 210,
                        "UTILITY_FUNCTION", 115)
                .forEach(
                        (kind, expected) ->
                                assertEquals(
                                        expected,
                                        countOccurrences(
                                                rows, "FoundryNativeDispatch.Kind." + kind),
                                        kind));
        assertTrue(
                shards.stream()
                        .allMatch(
                                shard ->
                                        shard.getValue()
                                                        .lines()
                                                        .filter(
                                                                line ->
                                                                        line.contains(
                                                                                "target.put("))
                                                        .count()
                                                <= 256));
        assertTrue(shards.stream().allMatch(shard -> shard.getValue().contains("final class ")));
        assertTrue(facade.contains("public static FoundryNativeDispatch require(String identity)"));
        assertFalse(facade.contains("public static java.util.Map"));
        assertEquals(first.sha256ByPath(), second.sha256ByPath());
    }

    @Test
    void generatedNativeDispatchPreservesArityReceiversConstantsTypesAndAccessors()
            throws IOException {
        GeneratedTree generated = generateAcceptedApi();

        String substr = dispatchRow(generated, "builtin_classes/String/methods/substr#787537301");
        assertTrue(substr.contains("Kind.BUILTIN_METHOD"), substr);
        assertTrue(substr.contains("\"String\", \"substr\", 787537301L, -1"), substr);
        assertTrue(substr.contains("java.util.List.of(\"int\", \"int\"), 1, \"String\""), substr);
        assertTrue(substr.endsWith("false, false));"), substr);

        String staticBuiltin =
                dispatchRow(generated, "builtin_classes/String/methods/num#1555901022");
        assertTrue(
                staticBuiltin.contains("java.util.List.of(\"float\", \"int\"), 1, \"String\""),
                staticBuiltin);
        assertTrue(staticBuiltin.endsWith("false, true));"), staticBuiltin);

        String vararg = dispatchRow(generated, "utility_functions/max#3896050336");
        assertTrue(vararg.contains("Kind.UTILITY_FUNCTION"), vararg);
        assertTrue(vararg.contains("\"\", \"max\", 3896050336L, -1"), vararg);
        assertTrue(
                vararg.contains("java.util.List.of(\"Variant\", \"Variant\"), 2, \"Variant\""),
                vararg);
        assertTrue(vararg.endsWith("true, false));"), vararg);

        String scriptNew = dispatchRow(generated, "classes/FoundryScript/methods/new#1545262638");
        assertTrue(scriptNew.contains("java.util.List.of(), 0, \"Variant\""), scriptNew);
        assertTrue(scriptNew.endsWith("true, false));"), scriptNew);

        String constant = dispatchRow(generated, "builtin_classes/Vector2/constants/ZERO");
        assertTrue(constant.contains("Kind.BUILTIN_CONSTANT"), constant);
        assertTrue(constant.contains("\"Vector2\", \"ZERO\", -1L, -1"), constant);
        assertTrue(constant.contains("java.util.List.of(), 0, \"Vector2\""), constant);

        String resolvedProperty =
                dispatchRow(generated, "classes/AStar2D/properties/neighbor_filter_enabled");
        assertTrue(
                resolvedProperty.contains(
                        "\"classes/AStar2D/methods/is_neighbor_filter_enabled#36873697\", "
                                + "\"is_neighbor_filter_enabled\", 36873697L"),
                resolvedProperty);
        assertTrue(
                resolvedProperty.contains(
                        "\"classes/AStar2D/methods/set_neighbor_filter_enabled#2586408642\", "
                                + "\"set_neighbor_filter_enabled\", 2586408642L"),
                resolvedProperty);

        String unresolvedProperty =
                dispatchRow(generated, "classes/AimModifier3D/properties/setting_count");
        assertTrue(
                unresolvedProperty.contains(
                        "\"\", \"get_setting_count\", -1L, \"\", \"set_setting_count\", -1L"),
                unresolvedProperty);
    }

    @Test
    void generatedNativeDispatchRejectsAmbiguousPropertyAccessorNames() throws IOException {
        String duplicateAccessors =
                """
                {
                  "name": "get_owner_path",
                  "is_const": true,
                  "is_vararg": false,
                  "is_static": false,
                  "is_virtual": false,
                  "hash": 102,
                  "return_value": {"type": "NodePath"}
                },
                {
                  "name": "get_owner_path",
                  "is_const": true,
                  "is_vararg": false,
                  "is_static": false,
                  "is_virtual": false,
                  "hash": 103,
                  "return_value": {"type": "NodePath"}
                },
                """;
        FoundryApi api =
                FoundryApiParser.parse(
                        fixture()
                                .replace(
                                        "      \"methods\": [\n        {\n"
                                                + "          \"name\": \"_process\",",
                                        "      \"methods\": [\n"
                                                + duplicateAccessors
                                                + "        {\n"
                                                + "          \"name\": \"_process\","));

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                new FoundrySourceGenerator()
                                        .generate(api, METADATA, supportedManifest(api)));

        assertTrue(failure.getMessage().contains("Ambiguous property accessor"));
        assertTrue(failure.getMessage().contains("classes/Node/properties/owner_path"));
        assertTrue(failure.getMessage().contains("get_owner_path"));
    }

    @Test
    void generatedNativeDispatchIsImmutableAndRejectsUnknownIdentities() throws Exception {
        FoundryApi api = FoundryApiParser.parse(fixture());
        GeneratedTree generated =
                new FoundrySourceGenerator().generate(api, METADATA, supportedManifest(api));
        Path output = temporaryDirectory.resolve("dispatch");
        Path classes = temporaryDirectory.resolve("dispatch-classes");
        generated.writeTo(output);
        assertEquals(0, compile(output, classes));

        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> dispatch =
                    loader.loadClass("games.cafecito.foundry.generated.GeneratedNativeDispatch");
            Object known =
                    dispatch.getMethod("require", String.class)
                            .invoke(null, "classes/Node/methods/get_children#101");
            assertEquals(
                    "classes/Node/methods/get_children#101",
                    known.getClass().getMethod("identity").invoke(known));

            java.lang.reflect.Field entriesField = dispatch.getDeclaredField("ENTRIES");
            entriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> entries = (Map<String, Object>) entriesField.get(null);
            assertThrows(UnsupportedOperationException.class, () -> entries.put("invented", known));

            java.lang.reflect.InvocationTargetException failure =
                    assertThrows(
                            java.lang.reflect.InvocationTargetException.class,
                            () ->
                                    dispatch.getMethod("require", String.class)
                                            .invoke(null, "missing"));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals(
                    "Unknown Foundry native dispatch identity: missing",
                    failure.getCause().getMessage());

            java.lang.reflect.InvocationTargetException nullFailure =
                    assertThrows(
                            java.lang.reflect.InvocationTargetException.class,
                            () ->
                                    dispatch.getMethod("require", String.class)
                                            .invoke(null, new Object[] {null}));
            assertTrue(nullFailure.getCause() instanceof NullPointerException);
            assertEquals("identity", nullFailure.getCause().getMessage());
        }
    }

    @Test
    void singletonsReturnCanonicalGeneratedEngineWrappers() throws IOException {
        GeneratedTree generated = generateAcceptedApi();
        String singleton =
                generated.sources().get("games/cafecito/foundry/generated/singletons/Engine.java");
        String canonical =
                generated.sources().get("games/cafecito/foundry/generated/classes/Engine.java");

        assertNotNull(singleton);
        assertNotNull(canonical);
        assertTrue(
                Pattern.compile(
                                "public static games\\.cafecito\\.foundry\\.generated\\.classes\\."
                                        + "Engine\\s+bind\\(")
                        .matcher(singleton)
                        .find());
        assertTrue(singleton.contains("games.cafecito.foundry.generated.classes.Engine.bind("));
        assertFalse(singleton.contains("extends games.cafecito.foundry.runtime.FoundryObject"));
        assertTrue(canonical.contains("getFramesPerSecond()"));
    }

    @Test
    void generatedClassOwnershipAndConstructionComeOnlyFromSchemaFlags() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        GeneratedTree generated = generateAcceptedApi();
        long refcounted = 0;
        long instantiable = 0;

        for (FoundryApi.Entity entity : api.categories().get("classes")) {
            String className =
                    entity.source().optional("name").requireString(entity.identity() + ".name");
            String source =
                    generated
                            .sources()
                            .get("games/cafecito/foundry/generated/classes/" + className + ".java");
            assertNotNull(source, className);
            boolean isRefcounted =
                    entity.source().optional("is_refcounted").requireBoolean(entity.identity());
            boolean isInstantiable =
                    entity.source().optional("is_instantiable").requireBoolean(entity.identity());
            assertFalse(source.contains("ObjectOwnership ownership"), className);
            assertEquals(
                    isRefcounted, source.contains("ObjectOwnership.REFERENCE_COUNTED"), className);
            assertEquals(
                    isInstantiable,
                    Pattern.compile("static\\s+" + Pattern.quote(className) + "\\s+create\\(")
                            .matcher(source)
                            .find(),
                    className);
            refcounted += isRefcounted ? 1 : 0;
            instantiable += isInstantiable ? 1 : 0;
        }
        assertEquals(676, refcounted);
        assertEquals(919, instantiable);
    }

    @Test
    void generatedNativeStructuresAndPointerCallsAreContextBoundAndStronglyTyped()
            throws IOException {
        GeneratedTree generated = generateAcceptedApi();
        String allSources = String.join("\n", generated.sources().values());
        long usableStructures =
                generated.sources().entrySet().stream()
                        .filter(entry -> entry.getKey().contains("/generated/structures/"))
                        .filter(entry -> entry.getValue().contains("FoundryNativeHandle<"))
                        .filter(entry -> entry.getValue().contains("public static "))
                        .filter(entry -> entry.getValue().contains(" fromBridge("))
                        .count();

        assertEquals(14, usableStructures);
        assertTrue(
                allSources.contains(
                        "games.cafecito.foundry.generated.structures."
                                + "PhysicsServer3DExtensionMotionResult result"));
        assertTrue(
                allSources.contains(".requireContext(context.contextHandle())")
                        || allSources.contains(".requireContext(context().contextHandle())"));
        assertTrue(
                allSources.contains(
                        "FoundryNativeHandle<games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid> space"));
        assertTrue(
                allSources.contains(
                        "FoundryNativeHandle<games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstFoundryExtensionInitializationFunction>"
                                + " initFunc"));
        assertTrue(
                allSources.contains(
                        ".requireType(games.cafecito.foundry.generated.pointers."
                                + "NativePointers.ConstVoid.class)"));
        assertFalse(allSources.contains("FoundryNativeHandle.Opaque"));
        assertFalse(
                Pattern.compile(
                                "(?m)public .*\\bgames\\.cafecito\\.foundry\\.runtime\\."
                                        + "FoundryNativeHandle\\s+[A-Za-z_$]")
                        .matcher(allSources)
                        .find());
    }

    @Test
    void generatedPublicArtifactsAreUsableTypedAndFreeOfGenericHashDescriptors()
            throws IOException {
        GeneratedTree generated = generateAcceptedApi();
        String node = generated.sources().get("games/cafecito/foundry/generated/classes/Node.java");

        assertNotNull(node);
        assertTrue(
                node.contains(
                        "FoundryTypedSignal.Of1<games.cafecito.foundry.generated.classes.Node>"
                                + " replacingBySignal()"));
        assertTrue(
                generated.sources().entrySet().stream()
                        .noneMatch(
                                entry ->
                                        entry.getKey()
                                                .matches(
                                                        ".*/Generated[A-Za-z0-9_]+_[0-9a-f]{12}"
                                                                + "\\.java")));
        assertTrue(
                generated.sources().keySet().stream()
                        .noneMatch(path -> path.contains("/generated/builtins/Vector3.java")));
        assertTrue(
                generated.sources().keySet().stream()
                        .anyMatch(path -> path.contains("/generated/builtins/Vector3Api.java")));
        assertTrue(
                generated.sources().entrySet().stream()
                        .filter(entry -> entry.getKey().contains("/generated/layout/"))
                        .allMatch(
                                entry ->
                                        entry.getValue().contains("public static int byteSize()")
                                                || entry.getValue()
                                                        .contains("public static int memberOffset(")
                                                || entry.getValue()
                                                        .contains("public static int byteSize(")));
    }

    @Test
    void productionCliConsumesCheckedManifestAndFailsClosedOnMetadataMismatch() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        Path output = temporaryDirectory.resolve("cli-output");
        Path manifestOutput = temporaryDirectory.resolve("manifest-output.json");
        Path realizationOutput = temporaryDirectory.resolve("realization-map.tsv");
        Path surfaceManifestOutput = temporaryDirectory.resolve("surface-manifest.json");
        String[] cliArguments = {
            acceptedDirectory.toString(),
            output.toString(),
            manifestOutput.toString(),
            realizationOutput.toString(),
            surfaceManifestOutput.toString(),
            BINDING_VERSION
        };

        FoundrySourceGenerator.main(cliArguments);
        Map<String, String> firstHashes = treeHashes(output);
        String firstRealizationMap = Files.readString(realizationOutput);
        String firstSurfaceManifest = Files.readString(surfaceManifestOutput);
        FoundrySourceGenerator.main(cliArguments);

        assertEquals(
                Files.readString(acceptedDirectory.resolve("compatibility-manifest.json")),
                Files.readString(manifestOutput));
        assertEquals(firstHashes, treeHashes(output));
        assertEquals(firstRealizationMap, Files.readString(realizationOutput));
        assertEquals(firstSurfaceManifest, Files.readString(surfaceManifestOutput));

        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        CompatibilityManifest manifest = CompatibilityManifest.parse(api, inputs);
        CompatibilityManifest mismatch =
                CompatibilityManifest.create(
                        api,
                        "0".repeat(64),
                        manifest.generatorVersion(),
                        manifest.bridgeContractVersion(),
                        manifest.entries().stream()
                                .collect(
                                        Collectors.toMap(
                                                CompatibilityManifest.Entry::sourceIdentity,
                                                entry ->
                                                        new CompatibilityManifest.Classification(
                                                                entry.status(), entry.reasonCode()),
                                                (left, right) -> left,
                                                LinkedHashMap::new)));
        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () -> new FoundrySourceGenerator().generate(api, METADATA, mismatch));
        assertTrue(failure.getMessage().contains("api_sha256"));
    }

    @Test
    void generatedPropertyAccessorsNeverOverrideAnInheritedMethod() throws IOException {
        GeneratedTree generated = generateAcceptedApi();
        Map<String, String> classSources = generatedClassSources(generated);
        Map<String, String> parentByClassName = new LinkedHashMap<>();
        Map<String, Set<String>> declaredMethodNames = new LinkedHashMap<>();
        Map<String, Set<String>> propertyAccessorNames = new LinkedHashMap<>();
        classSources.forEach(
                (className, source) -> {
                    parentByClassName.put(className, declaredParentClassName(source));
                    declaredMethodNames.put(className, declaredMethodNames(source));
                    propertyAccessorNames.put(className, propertyAccessorNames(source));
                });

        List<String> accidentalOverrides = new java.util.ArrayList<>();
        int propertyAccessorSlots = 0;
        for (var entry : propertyAccessorNames.entrySet()) {
            propertyAccessorSlots += entry.getValue().size();
            for (String accessor : entry.getValue()) {
                for (String ancestor = parentByClassName.get(entry.getKey());
                        ancestor != null && !ancestor.isEmpty();
                        ancestor = parentByClassName.get(ancestor)) {
                    if (declaredMethodNames.getOrDefault(ancestor, Set.of()).contains(accessor)) {
                        accidentalOverrides.add(
                                entry.getKey() + "." + accessor + "() overrides " + ancestor);
                    }
                }
            }
        }

        assertEquals(List.of(), accidentalOverrides.stream().sorted().toList());
        // Indexed properties whose engine accessor is a shared parameterized method must keep
        // their property-bound accessor: get_param(10) is the only other way to reach them.
        assertTrue(
                propertyAccessorNames
                        .get("DirectionalLight3D")
                        .contains("getDirectionalShadowSplit1"),
                "indexed property accessors must keep being generated");
        assertTrue(
                propertyAccessorNames
                        .get("DirectionalLight3D")
                        .contains("setDirectionalShadowSplit1"),
                "indexed property accessors must keep being generated");
        // Engine-private accessors with no exported method must keep being generated.
        assertTrue(
                propertyAccessorNames.get("AnimationNode").contains("getFilters"),
                "engine-private property accessors must keep being generated");
        assertTrue(
                propertyAccessorNames.get("AnimationNode").contains("setFilters"),
                "engine-private property accessors must keep being generated");
        assertEquals(80, propertyAccessorSlots, "37 indexed plus 43 engine-private accessor slots");
        assertFalse(
                declaredMethodNames.get("FontFile").contains("getFontStyle"),
                "FontFile must not redeclare the inherited Font.getFontStyle()");
    }

    private static Map<String, String> generatedClassSources(GeneratedTree generated) {
        Pattern classPath =
                Pattern.compile("games/cafecito/foundry/generated/classes/(\\w+)\\.java");
        Map<String, String> sources = new LinkedHashMap<>();
        generated
                .sources()
                .forEach(
                        (path, source) -> {
                            var matcher = classPath.matcher(path);
                            if (matcher.matches()) {
                                sources.put(matcher.group(1), source);
                            }
                        });
        assertFalse(sources.isEmpty(), "accepted generation must produce class sources");
        return sources;
    }

    /** Returns the generated simple superclass name, or empty when the root extends the runtime. */
    private static String declaredParentClassName(String source) {
        var matcher =
                Pattern.compile("(?m)^public class (\\w+) extends (\\S+) \\{$").matcher(source);
        if (!matcher.find()) {
            return "";
        }
        String parent = matcher.group(2);
        return parent.contains(".") ? "" : parent;
    }

    private static Set<String> declaredMethodNames(String source) {
        Set<String> names = new java.util.LinkedHashSet<>();
        var matcher = METHOD_DECLARATION.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Returns the names of generated methods whose body dispatches a property identity. */
    private static Set<String> propertyAccessorNames(String source) {
        Set<String> names = new java.util.LinkedHashSet<>();
        String current = null;
        for (String line : source.split("\n", -1)) {
            var matcher = METHOD_DECLARATION.matcher(line);
            if (matcher.lookingAt()) {
                current = matcher.group(1);
            } else if (current != null
                    && line.contains("call(\"")
                    && line.contains("/properties/")) {
                names.add(current);
            }
        }
        return names;
    }

    @Test
    void realizationMapCoversEveryClassifiedIdentityExactlyOnceInOneOfTwoStates()
            throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        CompatibilityManifest manifest = CompatibilityManifest.parse(api, inputs);
        FoundrySourceGenerator.Metadata metadata =
                new FoundrySourceGenerator.Metadata(
                        inputs.extensionApiSha256(),
                        inputs.interfaceHeaderSha256(),
                        inputs.provenance().foundryCommit(),
                        inputs.provenance().foundryVersion(),
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());

        RealizationMap map =
                new FoundrySourceGenerator().generate(api, metadata, manifest).realizationMap();

        assertEquals(
                manifest.entries().stream()
                        .map(CompatibilityManifest.Entry::sourceIdentity)
                        .sorted()
                        .toList(),
                map.entries().stream().map(RealizationMap.Entry::sourceIdentity).toList());
        for (RealizationMap.Entry entry : map.entries()) {
            if (entry.isRealized()) {
                assertTrue(entry.nonRealizationReason().isEmpty(), entry.sourceIdentity());
            } else {
                assertTrue(
                        NonRealizationReason.isApproved(entry.nonRealizationReason()),
                        entry.sourceIdentity());
            }
        }
        assertEquals(
                map.render(), RealizationMap.parse(map.render()).render(), "map must round-trip");
        assertEquals(
                map.render(),
                new FoundrySourceGenerator()
                        .generate(api, metadata, manifest)
                        .realizationMap()
                        .render(),
                "map must be stable across repeated generation");
    }

    @Test
    void generatedTreeRejectsNormalizedTraversalOutsideOutputRoot() {
        GeneratedTree malicious =
                new GeneratedTree(
                        Map.of("safe/../../escaped.java", "escaped"),
                        Set.of(),
                        CompatibilityManifest.create(
                                FoundryApiParser.parse(minimalApi()), API_HASH, "1", "1", Map.of()),
                        Map.of(),
                        RealizationMap.of(List.of()));

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () -> malicious.writeTo(temporaryDirectory.resolve("traversal")));
        assertTrue(failure.getMessage().contains("escapes the output root"));
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped.java")));
    }

    @Test
    void javaUnicodePreprocessingCannotEscapeGeneratedCommentsOrInjectDeclarations()
            throws IOException {
        String unicodeLineBreak = "\\" + "u000a";
        String payload =
                "Node" + unicodeLineBreak + "class InjectedDeclaration {} // */ \\\\ trailing";
        String actualControlPayload = "description\nclass InjectedControl {} // */ \\\\ trailing";
        FoundryApi api =
                FoundryApiParser.parse(
                        fixture()
                                .replace(
                                        "\"name\": \"Node\"",
                                        "\"name\": \"" + jsonStringBody(payload) + "\"")
                                .replace(
                                        "\"type\": \"typedarray::Node\"",
                                        "\"type\": \"typedarray::" + jsonStringBody(payload) + "\"")
                                .replace(
                                        "\"inherits\": \"Object\",",
                                        "\"inherits\": \"Object\",\n"
                                                + "      \"description\": \""
                                                + jsonStringBody(actualControlPayload)
                                                + "\","));
        String metadataPayload =
                "version" + unicodeLineBreak + "class InjectedHeader {} // */ \\\\ trailing";
        FoundrySourceGenerator.Metadata metadata =
                new FoundrySourceGenerator.Metadata(
                        API_HASH,
                        HEADER_HASH,
                        "3923e920b2fb6db68f82dfdab2bf7b1df125492d",
                        metadataPayload,
                        metadataPayload,
                        metadataPayload);
        CompatibilityManifest manifest = supportedManifest(api, metadataPayload, metadataPayload);

        GeneratedTree generated = new FoundrySourceGenerator().generate(api, metadata, manifest);
        Path output = temporaryDirectory.resolve("unicode-adversary");
        Path classes = temporaryDirectory.resolve("unicode-adversary-classes");
        generated.writeTo(output);

        for (String source : generated.sources().values()) {
            source.lines()
                    .filter(line -> line.startsWith("//"))
                    .forEach(
                            line -> {
                                assertFalse(line.contains(payload), line);
                                assertFalse(line.contains(actualControlPayload), line);
                                assertFalse(line.contains(metadataPayload), line);
                                assertFalse(line.contains("\\"), line);
                                assertFalse(line.contains("*/"), line);
                                assertFalse(line.contains("class Injected"), line);
                            });
        }
        assertEquals(0, compile(output, classes));
        assertFalse(Files.exists(classes.resolve("InjectedDeclaration.class")));
        assertFalse(Files.exists(classes.resolve("InjectedHeader.class")));
        assertFalse(Files.exists(classes.resolve("InjectedControl.class")));

        FoundrySourceGenerator.Metadata actualControl =
                new FoundrySourceGenerator.Metadata(
                        API_HASH,
                        HEADER_HASH,
                        "3923e920b2fb6db68f82dfdab2bf7b1df125492d",
                        "bad\nversion",
                        "1",
                        "1");
        assertThrows(
                ApiInputException.class,
                () ->
                        new FoundrySourceGenerator()
                                .generate(api, actualControl, supportedManifest(api)));
    }

    private static int compile(Path sourceRoot, Path output) throws IOException {
        Files.createDirectories(output);
        Path runtimeStubs =
                writeRuntimeStubs(output.resolveSibling(output.getFileName() + "-stubs"));
        List<String> generatedSources;
        try (var files = Files.walk(sourceRoot)) {
            generatedSources =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }
        List<String> stubSources;
        try (var files = Files.walk(runtimeStubs)) {
            stubSources =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }
        List<String> sources =
                java.util.stream.Stream.concat(stubSources.stream(), generatedSources.stream())
                        .toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests must run under the supported JDK, not a JRE.");
        List<String> arguments =
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        "--release", "17", "-d", output.toString()),
                                sources.stream())
                        .toList();
        return compiler.run(null, null, null, arguments.toArray(String[]::new));
    }

    private static Path writeRuntimeStubs(Path root) throws IOException {
        Path annotations = root.resolve("games/cafecito/foundry/annotations");
        Path runtime = root.resolve("games/cafecito/foundry/runtime");
        Path types = root.resolve("games/cafecito/foundry/types");
        Files.createDirectories(annotations);
        Files.createDirectories(runtime);
        Files.createDirectories(types);
        Files.writeString(
                annotations.resolve("GeneratedByFoundry.java"),
                """
                package games.cafecito.foundry.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Target({
                    java.lang.annotation.ElementType.TYPE,
                    java.lang.annotation.ElementType.METHOD
                })
                public @interface GeneratedByFoundry {}
                """);
        Files.writeString(
                annotations.resolve("FoundryVirtual.java"),
                """
                package games.cafecito.foundry.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                public @interface FoundryVirtual {
                    String value();
                }
                """);
        Files.writeString(
                types.resolve("Variant.java"),
                """
                package games.cafecito.foundry.types;
                public final class Variant {
                    public static Variant of(Object value) { return null; }
                    public static Variant ofObject(
                            games.cafecito.foundry.runtime.FoundryObject value) { return null; }
                    public Object as(VariantType type) { return null; }
                    public Variant asNil() { return this; }
                    public boolean asBoolean() { return false; }
                    public long asLong() { return 0; }
                    public double asDouble() { return 0; }
                    public String asString() { return null; }
                    public Vector2 asVector2() { return null; }
                    public Vector2i asVector2i() { return null; }
                    public Rect2 asRect2() { return null; }
                    public Rect2i asRect2i() { return null; }
                    public Vector3 asVector3() { return null; }
                    public Vector3i asVector3i() { return null; }
                    public Transform2D asTransform2D() { return null; }
                    public Vector4 asVector4() { return null; }
                    public Vector4i asVector4i() { return null; }
                    public Plane asPlane() { return null; }
                    public Quaternion asQuaternion() { return null; }
                    public Aabb asAabb() { return null; }
                    public Basis asBasis() { return null; }
                    public Transform3D asTransform3D() { return null; }
                    public Projection asProjection() { return null; }
                    public Color asColor() { return null; }
                    public StringName asStringName() { return null; }
                    public NodePath asNodePath() { return null; }
                    public Rid asRid() { return null; }
                    public games.cafecito.foundry.runtime.FoundryObject asObject() { return null; }
                    public games.cafecito.foundry.runtime.FoundryCallable asCallable() { return null; }
                    public games.cafecito.foundry.runtime.FoundrySignal asSignal() { return null; }
                }
                """);
        Files.writeString(
                types.resolve("VariantCodec.java"),
                """
                package games.cafecito.foundry.types;
                public interface VariantCodec<T> {
                    VariantCodec<Variant> VARIANT = null;
                    VariantCodec<Variant> NIL = null;
                    static VariantCodec<Object> forType(VariantType type) { return null; }
                }
                """);
        Files.writeString(
                types.resolve("VariantType.java"),
                """
                package games.cafecito.foundry.types;
                public enum VariantType {
                    NIL, BOOLEAN, INTEGER, FLOAT, STRING, VECTOR2, VECTOR2I, RECT2, RECT2I,
                    VECTOR3, VECTOR3I, TRANSFORM2D, VECTOR4, VECTOR4I, PLANE, QUATERNION,
                    AABB, BASIS, TRANSFORM3D, PROJECTION, COLOR, STRING_NAME, NODE_PATH, RID,
                    OBJECT, CALLABLE, SIGNAL, DICTIONARY, ARRAY, PACKED_BYTE_ARRAY,
                    PACKED_INT32_ARRAY, PACKED_INT64_ARRAY, PACKED_FLOAT32_ARRAY,
                    PACKED_FLOAT64_ARRAY, PACKED_STRING_ARRAY, PACKED_VECTOR2_ARRAY,
                    PACKED_VECTOR3_ARRAY, PACKED_COLOR_ARRAY, PACKED_VECTOR4_ARRAY
                }
                """);
        for (String type :
                List.of(
                        "Vector2",
                        "Vector2i",
                        "Rect2",
                        "Rect2i",
                        "Vector3",
                        "Vector3i",
                        "Transform2D",
                        "Vector4",
                        "Vector4i",
                        "Plane",
                        "Quaternion",
                        "Aabb",
                        "Basis",
                        "Transform3D",
                        "Projection",
                        "Color",
                        "StringName",
                        "NodePath",
                        "Rid",
                        "PackedByteArray",
                        "PackedInt32Array",
                        "PackedInt64Array",
                        "PackedFloat32Array",
                        "PackedFloat64Array",
                        "PackedStringArray",
                        "PackedVector2Array",
                        "PackedVector3Array",
                        "PackedColorArray",
                        "PackedVector4Array")) {
            Files.writeString(
                    types.resolve(type + ".java"),
                    "package games.cafecito.foundry.types; public final class " + type + " {}\n");
        }
        Files.writeString(
                types.resolve("FoundryArray.java"),
                """
                package games.cafecito.foundry.types;
                public final class FoundryArray<T> {}
                """);
        Files.writeString(
                types.resolve("FoundryDictionary.java"),
                """
                package games.cafecito.foundry.types;
                public final class FoundryDictionary<K, V> {}
                """);
        Files.writeString(
                runtime.resolve("FoundryCallError.java"),
                """
                package games.cafecito.foundry.runtime;
                public enum FoundryCallError { OK, UNKNOWN }
                """);
        Files.writeString(
                runtime.resolve("ObjectOwnership.java"),
                """
                package games.cafecito.foundry.runtime;
                public enum ObjectOwnership { BORROWED, OWNED, REFERENCE_COUNTED }
                """);
        Files.writeString(
                runtime.resolve("ObjectLease.java"),
                """
                package games.cafecito.foundry.runtime;
                public final class ObjectLease {}
                """);
        Files.writeString(
                runtime.resolve("FoundryObject.java"),
                """
                package games.cafecito.foundry.runtime;
                public class FoundryObject {
                    protected FoundryObject(FoundryBindingContext context, ObjectLease lease) {}
                    protected final games.cafecito.foundry.types.Variant call(
                            String identity,
                            games.cafecito.foundry.types.Variant... arguments) {
                        return null;
                    }
                    protected final FoundryBindingContext context() { return null; }
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryCallable.java"),
                "package games.cafecito.foundry.runtime; public final class FoundryCallable {}\n");
        Files.writeString(
                runtime.resolve("FoundrySignal.java"),
                "package games.cafecito.foundry.runtime; public final class FoundrySignal {}\n");
        Files.writeString(
                runtime.resolve("FoundryTypedSignal.java"),
                """
                package games.cafecito.foundry.runtime;
                import games.cafecito.foundry.types.VariantCodec;
                public final class FoundryTypedSignal {
                    public static final class Of0 {
                        public Of0(FoundrySignal signal) {}
                    }
                    public static final class Of1<A> {
                        public Of1(FoundrySignal signal, VariantCodec<A> a) {}
                    }
                    public static final class Of2<A, B> {
                        public Of2(FoundrySignal signal, VariantCodec<A> a, VariantCodec<B> b) {}
                    }
                    public static final class Of3<A, B, C> {
                        public Of3(
                                FoundrySignal signal,
                                VariantCodec<A> a,
                                VariantCodec<B> b,
                                VariantCodec<C> c) {}
                    }
                    public static final class Of4<A, B, C, D> {
                        public Of4(
                                FoundrySignal signal,
                                VariantCodec<A> a,
                                VariantCodec<B> b,
                                VariantCodec<C> c,
                                VariantCodec<D> d) {}
                    }
                    public static final class Of5<A, B, C, D, E> {
                        public Of5(
                                FoundrySignal signal,
                                VariantCodec<A> a,
                                VariantCodec<B> b,
                                VariantCodec<C> c,
                                VariantCodec<D> d,
                                VariantCodec<E> e) {}
                    }
                    private FoundryTypedSignal() {}
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryConstant.java"),
                """
                package games.cafecito.foundry.runtime;
                public final class FoundryConstant<T> {
                    public FoundryConstant(
                            String identity,
                            java.util.function.Function<
                                    games.cafecito.foundry.types.Variant, T> decoder) {}
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryNativeDispatch.java"),
                """
                package games.cafecito.foundry.runtime;
                public record FoundryNativeDispatch(
                        String identity,
                        Kind kind,
                        String ownerNativeType,
                        String nativeName,
                        long compatibilityHash,
                        int constructorIndex,
                        java.util.List<String> argumentNativeTypes,
                        int minimumArgumentCount,
                        String returnNativeType,
                        String getterIdentity,
                        String getterNativeName,
                        long getterCompatibilityHash,
                        String setterIdentity,
                        String setterNativeName,
                        long setterCompatibilityHash,
                        boolean vararg,
                        boolean staticCall) {
                    public enum Kind {
                        CLASS_METHOD(1),
                        CLASS_PROPERTY(2),
                        CLASS_SIGNAL(3),
                        BUILTIN_METHOD(4),
                        BUILTIN_CONSTRUCTOR(5),
                        BUILTIN_OPERATOR(6),
                        BUILTIN_MEMBER(7),
                        BUILTIN_CONSTANT(8),
                        UTILITY_FUNCTION(9);
                        Kind(int wireCode) {}
                    }
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryNativeHandle.java"),
                """
                package games.cafecito.foundry.runtime;
                public record FoundryNativeHandle<T>(
                        long contextHandle, Class<T> nativeType, long bridgeHandle) {
                    public static <T> FoundryNativeHandle<T> of(
                            long contextHandle, Class<T> nativeType, long bridgeHandle) {
                        return new FoundryNativeHandle<>(contextHandle, nativeType, bridgeHandle);
                    }
                    public static <T> FoundryNativeHandle<T> owned(
                            long contextHandle,
                            Class<T> nativeType,
                            long bridgeHandle,
                            FoundryEngine engine) {
                        return new FoundryNativeHandle<>(contextHandle, nativeType, bridgeHandle);
                    }
                    public FoundryNativeHandle<T> requireContext(long contextHandle) { return this; }
                    public <U> FoundryNativeHandle<T> requireType(Class<U> nativeType) {
                        return this;
                    }
                    public boolean isNull() { return bridgeHandle == 0; }
                    public void close() {}
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryEngine.java"),
                """
                package games.cafecito.foundry.runtime;
                public interface FoundryEngine {
                    CallResult call(
                            long contextHandle,
                            long objectHandle,
                            String methodIdentity,
                            java.util.List<games.cafecito.foundry.types.Variant> arguments);
                    long singleton(long contextHandle, String name);
                    long instantiate(long contextHandle, String className);
                    void release(long contextHandle, long objectHandle);
                    record CallResult(
                            games.cafecito.foundry.types.Variant value,
                            FoundryCallError error) {}
                }
                """);
        Files.writeString(
                runtime.resolve("FoundryBindingContext.java"),
                """
                package games.cafecito.foundry.runtime;
                public final class FoundryBindingContext {
                    private final long contextHandle;
                    private final FoundryEngine engine;
                    public FoundryBindingContext(long contextHandle, FoundryEngine engine) {
                        this.contextHandle = contextHandle;
                        this.engine = engine;
                    }
                    public long contextHandle() { return contextHandle; }
                    public FoundryEngine engine() { return engine; }
                    public boolean isAlive() { return true; }
                    public games.cafecito.foundry.types.Variant call(
                            long objectHandle,
                            String methodIdentity,
                            java.util.List<games.cafecito.foundry.types.Variant> arguments) {
                        return null;
                    }
                    public <T extends FoundryObject> T bind(
                            long objectHandle,
                            ObjectOwnership ownership,
                            Class<T> wrapperClass,
                            ObjectFactory<T> factory) {
                        return factory.create(this, new ObjectLease());
                    }
                    public <T extends FoundryObject> void registerObjectType(
                            String type, Class<T> wrapperClass, ObjectFactory<T> factory) {}
                    public <T extends FoundryObject> void registerObjectType(
                            String type,
                            ObjectOwnership ownership,
                            Class<T> wrapperClass,
                            ObjectFactory<T> factory) {}
                    public interface ObjectFactory<T extends FoundryObject> {
                        T create(FoundryBindingContext context, ObjectLease lease);
                    }
                }
                """);
        return root;
    }

    private static String dispatchRow(GeneratedTree generated, String identity) {
        String quotedIdentity = "\"" + identity + "\"";
        return generated.sources().entrySet().stream()
                .filter(entry -> entry.getKey().contains("GeneratedNativeDispatchShard"))
                .flatMap(entry -> entry.getValue().lines())
                .filter(line -> line.contains(quotedIdentity))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing dispatch row " + identity));
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String scalarSource(String generatedSources, String identity) {
        Pattern line =
                Pattern.compile(
                        "(?m)^// entity-base64 "
                                + Pattern.quote(base64(identity))
                                + " edge-base64 [A-Za-z0-9+/=]+ ordinal [0-9]+"
                                + " source-base64 ([A-Za-z0-9+/=]+)$");
        var match = line.matcher(generatedSources);
        assertTrue(match.find(), identity);
        return new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8);
    }

    private static Set<String> publicIdentityMarkers(GeneratedTree generated) {
        Pattern marker = Pattern.compile("(?m)^// public-identity-base64 ([A-Za-z0-9+/=]+)$");
        return generated.sources().values().stream()
                .flatMap(source -> marker.matcher(source).results())
                .map(
                        result ->
                                new String(
                                        Base64.getDecoder().decode(result.group(1)),
                                        StandardCharsets.UTF_8))
                .collect(Collectors.toSet());
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String registrationEntry(String sourceLine) {
        var matcher =
                Pattern.compile("new Descriptor\\(\\\"([^\\\"]+)\\\", ([A-Za-z0-9_]+)\\.class\\)")
                        .matcher(sourceLine.trim());
        assertTrue(matcher.find(), sourceLine);
        return matcher.group(1) + "=" + matcher.group(2);
    }

    private static Map<String, String> runtimeRegistrationCatalog(Path classes) throws Exception {
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()},
                        ClassLoader.getPlatformClassLoader())) {
            Class<?> publicApi =
                    loader.loadClass("games.cafecito.foundry.generated.GeneratedPublicApi");
            List<?> roots = (List<?>) publicApi.getMethod("roots").invoke(null);
            Map<String, String> catalog = new LinkedHashMap<>();
            for (Object root : roots) {
                String identity = (String) root.getClass().getMethod("sourceIdentity").invoke(root);
                Class<?> publicClass =
                        (Class<?>) root.getClass().getMethod("publicClass").invoke(root);
                catalog.put(identity, publicClass.getName());
            }
            return catalog;
        }
    }

    private static CompatibilityManifest supportedManifest(FoundryApi api) {
        return supportedManifest(api, "1", "1");
    }

    @Test
    void theAcceptedSurfaceManifestIsDerivedFromTheMapAndReadableByANeutralConsumer()
            throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        RealizationMap map = generateAcceptedApi().realizationMap();
        SurfaceManifest.Provenance provenance =
                new SurfaceManifest.Provenance(
                        inputs.provenance().apiVersion(),
                        inputs.extensionApiSha256(),
                        BINDING_VERSION,
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());

        SurfaceManifest manifest = SurfaceManifest.from(map, provenance);
        String rendering = manifest.canonicalJson();
        SurfaceManifest reparsed = SurfaceManifest.parse(rendering);

        assertEquals(List.of(), reparsed.disagreementsWith(map));
        assertEquals(List.of(), RealizationVerifier.provenanceDrift(reparsed, provenance));
        assertEquals(rendering, reparsed.canonicalJson());
        assertEquals(map.sha256(), manifest.realizationMapSha256());

        NeutralSurfaceManifestConsumer.Coverage coverage =
                NeutralSurfaceManifestConsumer.read(rendering).coverage();
        Map<String, String> accounting = pinnedRealizationAccounting();

        assertEquals(SurfaceManifest.BINDING_ID, coverage.bindingId());
        assertEquals(
                Integer.parseInt(accounting.get("source-entities")), coverage.coveredEntities());
        assertEquals(
                Integer.parseInt(accounting.get("realized-entities")), coverage.realizedEntities());
        assertEquals(
                Integer.parseInt(accounting.get("non-realized-entities")),
                coverage.nonRealizationReasonCounts().values().stream()
                        .mapToInt(Integer::intValue)
                        .sum());
        assertEquals(
                NeutralNonRealizationReason.approved().stream()
                        .map(Enum::name)
                        .collect(Collectors.toSet()),
                coverage.nonRealizationReasonCounts().keySet());
    }

    @Test
    void aSurfaceManifestThatDisagreesWithTheAcceptedMapIsRejected() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        RealizationMap map = generateAcceptedApi().realizationMap();
        SurfaceManifest.Provenance provenance =
                new SurfaceManifest.Provenance(
                        inputs.provenance().apiVersion(),
                        inputs.extensionApiSha256(),
                        BINDING_VERSION,
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());
        RealizationMap.Entry first = map.entries().get(0);

        SurfaceManifest fromADifferentMap =
                SurfaceManifest.from(
                        RealizationMap.of(
                                map.entries().stream()
                                        .filter(entry -> !entry.equals(first))
                                        .toList()),
                        provenance);

        List<String> disagreements = fromADifferentMap.disagreementsWith(map);
        assertFalse(disagreements.isEmpty());
        assertTrue(
                disagreements.stream()
                        .allMatch(message -> message.startsWith(SurfaceManifest.DISAGREEMENT)));
        assertTrue(
                disagreements.stream()
                        .anyMatch(message -> message.contains(first.sourceIdentity())));
        assertFalse(
                RealizationVerifier.provenanceDrift(
                                SurfaceManifest.from(map, provenance),
                                new SurfaceManifest.Provenance(
                                        inputs.provenance().apiVersion(),
                                        inputs.extensionApiSha256(),
                                        "9.9.9",
                                        inputs.provenance().generatorVersion(),
                                        inputs.provenance().bridgeContractVersion()))
                        .isEmpty());
    }

    private static Map<String, String> pinnedRealizationAccounting() throws IOException {
        Path baseline =
                Path.of(System.getProperty("user.dir"))
                        .resolve(
                                "../foundry-java-runtime/api/foundry-java-realization-accounting.txt")
                        .normalize();
        Map<String, String> pinned = new LinkedHashMap<>();
        for (String line : Files.readString(baseline, StandardCharsets.UTF_8).split("\n", -1)) {
            String[] fields = line.split(" ", 2);
            if (fields.length == 2) {
                pinned.put(fields[0], fields[1]);
            }
        }
        return pinned;
    }

    private static GeneratedTree generateAcceptedApi() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);
        FoundrySourceGenerator.Metadata metadata =
                new FoundrySourceGenerator.Metadata(
                        inputs.extensionApiSha256(),
                        inputs.interfaceHeaderSha256(),
                        inputs.provenance().foundryCommit(),
                        inputs.provenance().foundryVersion(),
                        inputs.provenance().generatorVersion(),
                        inputs.provenance().bridgeContractVersion());
        return new FoundrySourceGenerator()
                .generate(api, metadata, CompatibilityManifest.parse(api, inputs));
    }

    private static CompatibilityManifest supportedManifest(
            FoundryApi api, String generatorVersion, String bridgeContractVersion) {
        Map<String, CompatibilityManifest.Classification> classifications = new LinkedHashMap<>();
        api.entities().stream()
                .sorted(Comparator.comparing(FoundryApi.Entity::identity))
                .forEach(
                        entity ->
                                classifications.put(
                                        entity.identity(),
                                        new CompatibilityManifest.Classification(
                                                CompatibilityManifest.Status.SUPPORTED,
                                                "WS5_MODEL_AND_GENERATOR_REPRESENTABLE")));
        return CompatibilityManifest.create(
                api, API_HASH, generatorVersion, bridgeContractVersion, classifications);
    }

    private static String jsonStringBody(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String minimalApi() {
        return """
                {
                  "header": {
                    "version_major": 0,
                    "version_minor": 1,
                    "version_patch": 0,
                    "version_status": "alpha8",
                    "version_build": "custom",
                    "version_full_name": "Foundry",
                    "precision": "single"
                  },
                  "builtin_class_sizes": [],
                  "builtin_class_member_offsets": [],
                  "global_constants": [],
                  "global_enums": [],
                  "utility_functions": [],
                  "builtin_classes": [],
                  "classes": [],
                  "singletons": [],
                  "native_structures": []
                }
                """;
    }

    private static java.util.Map<String, String> treeHashes(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .collect(
                            Collectors.toMap(
                                    path -> root.relativize(path).toString(),
                                    path -> {
                                        try {
                                            return sha256(Files.readAllBytes(path));
                                        } catch (IOException exception) {
                                            throw new java.io.UncheckedIOException(exception);
                                        }
                                    }));
        }
    }

    private static String fixture() throws IOException {
        try (var stream =
                FoundrySourceGeneratorTest.class.getResourceAsStream(
                        "/fixtures/complete-api.json")) {
            if (stream == null) {
                throw new IOException("Missing complete API fixture.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String expectedProvenance() throws IOException {
        try (var stream =
                FoundrySourceGeneratorTest.class.getResourceAsStream(
                        "/fixtures/expected/GeneratedApiProvenance.java.golden")) {
            if (stream == null) {
                throw new IOException("Missing provenance golden.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

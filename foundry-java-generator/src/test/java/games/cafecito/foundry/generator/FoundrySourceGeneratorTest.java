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
                    allSources.contains("entity-base64 " + base64(requiredIdentity)),
                    requiredIdentity);
        }
        assertTrue(allSources.contains("GeneratedRegistration"));
        assertTrue(scalarSource(allSources, "classes/Node").contains("\"inherits\":\"Object\""));
        assertTrue(
                scalarSource(allSources, "classes/Node/methods/_process#100")
                        .contains("\"is_virtual\":true"));
        assertTrue(
                scalarSource(
                                allSources,
                                "classes/Node/methods/get_children#101/arguments/include_internal")
                        .contains("\"default_value\":\"false\""));
        assertTrue(
                scalarSource(allSources, "utility_functions/type_convert#128/arguments/packed")
                        .contains("\"type\":\"PackedByteArray\""));
        assertTrue(
                scalarSource(allSources, "global_enums/Variant.Type")
                        .contains("\"is_bitfield\":false"));
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
        List<String> expectedCatalog =
                generated.descriptorCatalog().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .toList();
        assertEquals(
                Map.ofEntries(
                        Map.entry(
                                "builtin_class_member_offsets/float_32",
                                "GeneratedBuiltin_class_member_offsetsFloat_32_4f3a6f8a556e"),
                        Map.entry(
                                "builtin_class_sizes/float_32",
                                "GeneratedBuiltin_class_sizesFloat_32_8eff911e47ce"),
                        Map.entry(
                                "builtin_classes/Array",
                                "GeneratedBuiltin_classesArray_2652de84a90c"),
                        Map.entry("classes/Node", "GeneratedClassesNode_55aa10d9c1aa"),
                        Map.entry("classes/Object", "GeneratedClassesObject_9a9c800c766e"),
                        Map.entry(
                                "global_constants/PROPERTY_USAGE_DEFAULT",
                                "GeneratedGlobal_constantsPROPERTY_USAGE_DEFAULT_781b45ab8fcd"),
                        Map.entry(
                                "global_enums/Variant.Type",
                                "GeneratedGlobal_enumsVariantType_50143ad9cd87"),
                        Map.entry(
                                "native_structures/ObjectID",
                                "GeneratedNative_structuresObjectID_2e744d4a66c3"),
                        Map.entry("singletons/Engine", "GeneratedSingletonsEngine_d0c5170cce56"),
                        Map.entry(
                                "utility_functions/type_convert#128",
                                "GeneratedUtility_functionsType_convert128_b1055cd0b571")),
                generated.descriptorCatalog());
        assertEquals(
                expectedCatalog,
                registration
                        .lines()
                        .filter(line -> line.trim().startsWith("new Descriptor("))
                        .map(FoundrySourceGeneratorTest::registrationEntry)
                        .toList());
        assertTrue(registration.contains("public static java.util.List<Descriptor> descriptors()"));

        String objectWrapper =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/classes/"
                                        + "ApiObject_9a9c800c766e.java");
        String nodeWrapper =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/classes/"
                                        + "ApiNode_55aa10d9c1aa.java");
        String builtinWrapper =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/builtins/"
                                        + "ApiArray_2652de84a90c.java");
        String utilityWrapper =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/utilities/"
                                        + "ApiType_convert128_b1055cd0b571.java");
        String singletonWrapper =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/singletons/"
                                        + "ApiEngine_d0c5170cce56.java");
        assertNotNull(objectWrapper);
        assertNotNull(nodeWrapper);
        assertNotNull(builtinWrapper);
        assertNotNull(utilityWrapper);
        assertNotNull(singletonWrapper);
        assertTrue(
                nodeWrapper.contains(
                        "public class ApiNode_55aa10d9c1aa extends ApiObject_9a9c800c766e"));
        assertTrue(
                objectWrapper.contains(
                        "public class ApiObject_9a9c800c766e extends "
                                + "games.cafecito.foundry.runtime.FoundryObject"));
        assertTrue(nodeWrapper.contains("games.cafecito.foundry.runtime.FoundryBindingContext"));
        assertTrue(nodeWrapper.contains("games.cafecito.foundry.runtime.ObjectLease lease"));
        assertTrue(
                nodeWrapper.contains("games.cafecito.foundry.runtime.ObjectOwnership ownership"));
        assertTrue(nodeWrapper.contains("context.bind("));
        assertTrue(objectWrapper.contains("return call(identity, arguments);"));
        assertTrue(
                singletonWrapper.contains("extends games.cafecito.foundry.runtime.FoundryObject"));
        assertTrue(singletonWrapper.contains("ObjectOwnership.BORROWED"));
        assertTrue(
                nodeWrapper.contains(
                        "public-identity-base64 "
                                + base64("classes/Node/methods/get_children#101")));
        assertTrue(builtinWrapper.contains("games.cafecito.foundry.types.Variant value"));
        assertTrue(builtinWrapper.contains("argumentsWithReceiver.add(value);"));
        assertTrue(builtinWrapper.contains("context.call(0, identity, argumentsWithReceiver)"));
        assertTrue(builtinWrapper.contains("if (!context.isAlive())"));
        assertTrue(utilityWrapper.contains("public static games.cafecito.foundry.types.Variant"));
        assertTrue(
                utilityWrapper.contains("context.call(0, identity, java.util.List.of(arguments))"));
        assertTrue(utilityWrapper.contains("if (!context.isAlive())"));
        assertNotNull(
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/" + "GeneratedPublicApi.java"));

        String typeConvertDescriptor =
                generated
                        .sources()
                        .get(
                                "games/cafecito/foundry/generated/"
                                        + generated
                                                .descriptorCatalog()
                                                .get("utility_functions/type_convert#128")
                                        + ".java");
        assertNotNull(typeConvertDescriptor);
        String valueIdentity = "utility_functions/type_convert#128/arguments/value";
        String packedIdentity = "utility_functions/type_convert#128/arguments/packed";
        assertTrue(
                typeConvertDescriptor.indexOf("entity-base64 " + base64(valueIdentity))
                        < typeConvertDescriptor.indexOf("entity-base64 " + base64(packedIdentity)));
        assertTrue(
                typeConvertDescriptor.contains(
                        "entity-base64 "
                                + base64(valueIdentity)
                                + " edge-base64 YXJndW1lbnRz ordinal 0"));
        assertTrue(
                typeConvertDescriptor.contains(
                        "entity-base64 "
                                + base64(packedIdentity)
                                + " edge-base64 YXJndW1lbnRz ordinal 1"));

        String provenance =
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/" + "GeneratedApiProvenance.java");
        assertNotNull(provenance);
        String normalized = MANIFEST_HASH.matcher(provenance).replaceFirst("<manifest-sha256>");
        assertEquals(expectedProvenance(), normalized);
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
        assertEquals(57_904, api.entities().size());
        assertEquals(57_904, acceptedManifest.entries().size());
        assertEquals(2_599, first.sources().size());
        assertEquals(1_298, first.descriptorCatalog().size());
        assertEquals(
                57_904,
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
        assertEquals(
                inputs.compatibilityManifestSha256(),
                sha256(first.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, compile(output, temporaryDirectory.resolve("accepted-classes")));
    }

    @Test
    void productionCliConsumesCheckedManifestAndFailsClosedOnMetadataMismatch() throws IOException {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        Path output = temporaryDirectory.resolve("cli-output");
        Path manifestOutput = temporaryDirectory.resolve("manifest-output.json");

        FoundrySourceGenerator.main(
                new String[] {
                    acceptedDirectory.toString(), output.toString(), manifestOutput.toString()
                });
        Map<String, String> firstHashes = treeHashes(output);
        FoundrySourceGenerator.main(
                new String[] {
                    acceptedDirectory.toString(), output.toString(), manifestOutput.toString()
                });

        assertEquals(
                Files.readString(acceptedDirectory.resolve("compatibility-manifest.json")),
                Files.readString(manifestOutput));
        assertEquals(firstHashes, treeHashes(output));

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
    void generatedTreeRejectsNormalizedTraversalOutsideOutputRoot() {
        GeneratedTree malicious =
                new GeneratedTree(
                        Map.of("safe/../../escaped.java", "escaped"),
                        Set.of(),
                        CompatibilityManifest.create(
                                FoundryApiParser.parse(minimalApi()), API_HASH, "1", "1", Map.of()),
                        Map.of());

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
        Path runtime = root.resolve("games/cafecito/foundry/runtime");
        Path types = root.resolve("games/cafecito/foundry/types");
        Files.createDirectories(runtime);
        Files.createDirectories(types);
        Files.writeString(
                types.resolve("Variant.java"),
                "package games.cafecito.foundry.types; public final class Variant {}\n");
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
                public enum ObjectOwnership { BORROWED, REFERENCE_COUNTED }
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
                    public interface ObjectFactory<T extends FoundryObject> {
                        T create(FoundryBindingContext context, ObjectLease lease);
                    }
                }
                """);
        return root;
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
            Class<?> registration =
                    loader.loadClass("games.cafecito.foundry.generated.GeneratedRegistration");
            List<?> descriptors = (List<?>) registration.getMethod("descriptors").invoke(null);
            Map<String, String> catalog = new LinkedHashMap<>();
            for (Object descriptor : descriptors) {
                String identity =
                        (String) descriptor.getClass().getMethod("rootIdentity").invoke(descriptor);
                Class<?> descriptorClass =
                        (Class<?>)
                                descriptor
                                        .getClass()
                                        .getMethod("descriptorClass")
                                        .invoke(descriptor);
                catalog.put(identity, descriptorClass.getSimpleName());
            }
            return catalog;
        }
    }

    private static CompatibilityManifest supportedManifest(FoundryApi api) {
        return supportedManifest(api, "1", "1");
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

package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.ApiInputs;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.FoundryApi;
import games.cafecito.foundry.api.model.FoundryApiParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
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

        GeneratedTree generated = new FoundrySourceGenerator().generate(api, METADATA);

        Set<String> parsedIdentities =
                api.entities().stream()
                        .map(FoundryApi.Entity::identity)
                        .collect(Collectors.toSet());
        assertEquals(parsedIdentities, generated.coveredIdentities());
        assertEquals(
                parsedIdentities,
                generated.manifest().entries().stream()
                        .map(CompatibilityManifest.Entry::sourceIdentity)
                        .collect(Collectors.toSet()));
        assertEquals(
                parsedIdentities.size(),
                generated.manifest().statusCounts().get(CompatibilityManifest.Status.SUPPORTED));
        assertTrue(
                generated.manifest().entries().stream()
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
            assertTrue(allSources.contains("entity " + requiredIdentity), requiredIdentity);
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

        String provenance =
                generated
                        .sources()
                        .get("games/cafecito/foundry/generated/" + "GeneratedApiProvenance.java");
        assertNotNull(provenance);
        String normalized = MANIFEST_HASH.matcher(provenance).replaceFirst("<manifest-sha256>");
        assertEquals(expectedProvenance(), normalized);
    }

    @Test
    void cleanRepeatGenerationIsByteIdenticalAndGeneratedJavaCompiles() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());
        FoundrySourceGenerator generator = new FoundrySourceGenerator();

        GeneratedTree first = generator.generate(api, METADATA);
        GeneratedTree second =
                generator.generate(FoundryApiParser.parse(api.canonicalJson()), METADATA);
        Path firstOutput = temporaryDirectory.resolve("first");
        Path secondOutput = temporaryDirectory.resolve("second");
        first.writeTo(firstOutput);
        second.writeTo(secondOutput);

        assertEquals(first.sha256ByPath(), second.sha256ByPath());
        assertEquals(treeHashes(firstOutput), treeHashes(secondOutput));
        assertEquals(0, compile(firstOutput, temporaryDirectory.resolve("first-classes")));
        assertEquals(0, compile(secondOutput, temporaryDirectory.resolve("second-classes")));
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

        GeneratedTree first = new FoundrySourceGenerator().generate(api, metadata);
        GeneratedTree second =
                new FoundrySourceGenerator()
                        .generate(FoundryApiParser.parse(api.canonicalJson()), metadata);
        Path output = temporaryDirectory.resolve("accepted");
        Path secondOutput = temporaryDirectory.resolve("accepted-second");
        first.writeTo(output);
        second.writeTo(secondOutput);
        CompatibilityManifest acceptedManifest =
                CompatibilityManifest.parse(api, inputs.compatibilityManifestJson());

        assertEquals(
                api.entities().stream()
                        .map(FoundryApi.Entity::identity)
                        .collect(Collectors.toSet()),
                first.coveredIdentities());
        assertEquals(first.sha256ByPath(), second.sha256ByPath());
        assertEquals(treeHashes(output), treeHashes(secondOutput));
        assertEquals(acceptedManifest.canonicalJson(), first.manifest().canonicalJson());
        assertEquals(
                inputs.compatibilityManifestSha256(),
                sha256(first.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, compile(output, temporaryDirectory.resolve("accepted-classes")));
    }

    private static int compile(Path sourceRoot, Path output) throws IOException {
        Files.createDirectories(output);
        List<String> sources;
        try (var files = Files.walk(sourceRoot)) {
            sources =
                    files.filter(path -> path.toString().endsWith(".java"))
                            .map(Path::toString)
                            .sorted()
                            .toList();
        }
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

    private static String scalarSource(String generatedSources, String identity) {
        Pattern line =
                Pattern.compile(
                        "(?m)^// entity "
                                + Pattern.quote(identity)
                                + " source-base64 ([A-Za-z0-9+/=]+)$");
        var match = line.matcher(generatedSources);
        assertTrue(match.find(), identity);
        return new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8);
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

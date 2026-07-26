package games.cafecito.foundry.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FoundryApiParserTest {
    @Test
    void parsesAndDeterministicallySerializesTheFullAcceptedApi() {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);

        FoundryApi api = FoundryApiParser.parse(inputs);
        FoundryApi reparsed = FoundryApiParser.parse(api.canonicalJson());

        assertEquals("0.1.0-alpha.8", api.header().apiVersion());
        assertEquals(1051, api.categories().get("classes").size());
        assertEquals(38, api.categories().get("builtin_classes").size());
        assertEquals(39, api.categories().get("singletons").size());
        assertTrue(api.entities().size() > 10_000);
        assertEquals(api.categoryCounts(), reparsed.categoryCounts());
        assertEquals(api.canonicalJson(), reparsed.canonicalJson());
    }

    @Test
    void rejectsAProvenanceApiVersionThatDiffersFromTheParsedHeader() {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        ApiProvenance provenance = inputs.provenance();
        ApiProvenance mismatchedProvenance =
                new ApiProvenance(
                        provenance.schemaVersion(),
                        provenance.sourceRepository(),
                        provenance.sourceRelease(),
                        provenance.releaseUrl(),
                        provenance.archiveUrl(),
                        provenance.archiveSha256(),
                        provenance.foundryCommit(),
                        provenance.foundryVersion(),
                        "0.2.0",
                        provenance.abiMinimum(),
                        provenance.license(),
                        provenance.licenseUrl(),
                        provenance.generatorVersion(),
                        provenance.bridgeContractVersion(),
                        provenance.files());
        ApiInputs mismatched =
                new ApiInputs(
                        mismatchedProvenance,
                        inputs.extensionApiJson(),
                        inputs.interfaceHeader(),
                        inputs.compatibilityManifestJson());

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(mismatched));

        assertTrue(failure.getMessage().contains("$.api_version"));
        assertTrue(failure.getMessage().contains("0.1.0-alpha.8"));
        assertTrue(failure.getMessage().contains("0.2.0"));
    }

    @Test
    void parsesEveryFoundrySwiftCategoryIntoImmutableStableEntities() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());

        assertEquals("0.1.0-alpha.8", api.header().apiVersion());
        assertEquals(
                Map.of(
                        "builtin_class_sizes", 2,
                        "builtin_class_member_offsets", 3,
                        "global_constants", 1,
                        "global_enums", 2,
                        "utility_functions", 3,
                        "builtin_classes", 11,
                        "classes", 13,
                        "singletons", 1,
                        "native_structures", 1),
                api.categoryCounts());
        assertTrue(api.entity("classes/Node/methods/_process#100").isPresent());
        assertTrue(api.entity("classes/Node/properties/owner_path").isPresent());
        assertTrue(api.entity("classes/Node/signals/renamed").isPresent());
        assertTrue(api.entity("builtin_classes/Array/operators/==#Array").isPresent());
        assertTrue(api.entity("utility_functions/type_convert#128/arguments/packed").isPresent());
        assertThrows(UnsupportedOperationException.class, () -> api.entities().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> api.categories().put("other", List.of()));
    }

    @Test
    void rejectsUnknownNestedConstructWithJsonPathAndEntityIdentity() throws IOException {
        String unknown =
                fixture()
                        .replace(
                                "\"name\": \"Node\",\n      \"is_refcounted\"",
                                "\"name\": \"Node\",\n      \"surprise\": true,\n      \"is_refcounted\"");

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(unknown));

        assertTrue(failure.getMessage().contains("$.classes[1].surprise"));
        assertTrue(failure.getMessage().contains("classes/Node"));
    }

    @Test
    void rejectsMalformedAndDuplicateSourceIdentities() throws IOException {
        String malformed = fixture().replace("\"name\": \"Object\",", "\"name\": \"\",");
        ApiInputException blankFailure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(malformed));
        assertTrue(blankFailure.getMessage().contains("$.classes[0].name"));

        String duplicate =
                fixture()
                        .replace(
                                "    {\n      \"name\": \"Node\",\n      \"is_refcounted\"",
                                "    {\n      \"name\": \"Object\",\n      \"is_refcounted\"");
        ApiInputException duplicateFailure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(duplicate));
        assertTrue(
                duplicateFailure.getMessage().contains("Duplicate source identity classes/Object"));
    }

    @Test
    void normalizesNamedCollectionOrderButPreservesArgumentOrder() throws IOException {
        String reorderedClasses =
                fixture()
                        .replace(
                                classesBlock(),
                                classesBlock()
                                        .replace(
                                                objectClass() + ",\n    " + nodeClass(),
                                                nodeClass() + ",\n    " + objectClass()));

        FoundryApi original = FoundryApiParser.parse(fixture());
        FoundryApi reordered = FoundryApiParser.parse(reorderedClasses);

        assertEquals(original.canonicalJson(), reordered.canonicalJson());
        assertTrue(
                original.canonicalJson().indexOf("\"name\":\"value\"")
                        < original.canonicalJson().indexOf("\"name\":\"packed\""));
    }

    @Test
    void rejectsDuplicateJsonKeysAndUnknownSchemaEnumerations() throws IOException {
        ApiInputException duplicateKey =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                FoundryApiParser.parse(
                                        fixture()
                                                .replace(
                                                        "\"precision\": \"single\"",
                                                        "\"precision\": \"single\", \"precision\": \"double\"")));
        assertTrue(duplicateKey.getMessage().contains("duplicate object key"));

        ApiInputException unknownApiType =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                FoundryApiParser.parse(
                                        fixture()
                                                .replace(
                                                        "\"api_type\": \"core\"",
                                                        "\"api_type\": \"mobile\"")));
        assertTrue(unknownApiType.getMessage().contains("$.classes[0].api_type"));
    }

    private static String fixture() throws IOException {
        try (var stream =
                FoundryApiParserTest.class.getResourceAsStream("/fixtures/complete-api.json")) {
            if (stream == null) {
                throw new IOException("Missing complete API fixture.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String classesBlock() throws IOException {
        String source = fixture();
        int start = source.indexOf("  \"classes\": [\n");
        int end = source.indexOf("\n  ],\n  \"singletons\":", start) + "\n  ]".length();
        return source.substring(start, end);
    }

    private static String objectClass() {
        return """
                {
                      "name": "Object",
                      "is_refcounted": false,
                      "is_instantiable": true,
                      "api_type": "core"
                    }""";
    }

    private static String nodeClass() throws IOException {
        String block = classesBlock();
        int start = block.indexOf("    {\n      \"name\": \"Node\"");
        return block.substring(start + 4, block.length() - "\n  ]".length());
    }
}

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
        assertEquals(57_904, api.entities().size());
        assertEquals(
                Map.of(
                        "builtin_class_member_offsets", 252,
                        "builtin_class_sizes", 164,
                        "builtin_classes", 3_333,
                        "classes", 53_240,
                        "global_constants", 11,
                        "global_enums", 542,
                        "native_structures", 14,
                        "singletons", 39,
                        "utility_functions", 309),
                api.categoryCounts());
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
        FoundryApi.Entity valueArgument =
                api.entity("utility_functions/type_convert#128/arguments/value").orElseThrow();
        FoundryApi.Entity packedArgument =
                api.entity("utility_functions/type_convert#128/arguments/packed").orElseThrow();
        assertEquals("arguments", valueArgument.edge());
        assertEquals(0, valueArgument.ordinal());
        assertEquals("arguments", packedArgument.edge());
        assertEquals(1, packedArgument.ordinal());
        assertEquals(
                List.of(valueArgument.identity(), packedArgument.identity()),
                api.entity("utility_functions/type_convert#128").orElseThrow().children().stream()
                        .map(FoundryApi.Entity::identity)
                        .toList());
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
        assertTrue(unknownApiType.getMessage().contains("classes/Object"));
    }

    @Test
    void validatesIntegerFieldsWithoutNarrowingUnsignedHashes() throws IOException {
        for (String invalidHash : List.of("42.5", "4e2", "-0", "-1", "18446744073709551616")) {
            String invalid = fixture().replace("\"hash\": 42", "\"hash\": " + invalidHash);
            ApiInputException failure =
                    assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(invalid));
            assertTrue(failure.getMessage().contains("$.builtin_classes[0].methods[0].hash"));
            assertTrue(failure.getMessage().contains("builtin_classes/Array/methods/assign"));
        }

        String maximumUnsigned =
                fixture().replace("\"hash\": 42", "\"hash\": 18446744073709551615");
        FoundryApi parsed = FoundryApiParser.parse(maximumUnsigned);
        assertTrue(
                parsed.entity("builtin_classes/Array/methods/" + "assign#18446744073709551615")
                        .isPresent());

        for (String mutation : List.of("\"index\": 0", "\"size\": 8", "\"offset\": 0")) {
            for (String invalidValue : List.of("-0", "-1")) {
                String invalid =
                        fixture()
                                .replace(
                                        mutation,
                                        mutation.substring(0, mutation.indexOf(':') + 1)
                                                + " "
                                                + invalidValue);
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(invalid));
            }
        }
        String fractionalEnum = fixture().replace("\"value\": 13", "\"value\": 1.3");
        assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(fractionalEnum));
    }

    @Test
    void zeroAndNegativeZeroCannotCreateDistinctUnsignedSourceIdentities() {
        String first =
                """
                {
                  "name": "alias",
                  "category": "general",
                  "is_vararg": false,
                  "hash": 0
                }
                """;
        String second = first.replace("\"hash\": 0", "\"hash\": -0");
        String aliases =
                minimalApi()
                        .replace(
                                "\"utility_functions\": []",
                                "\"utility_functions\": [" + first + "," + second + "]");

        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(aliases));

        assertTrue(failure.getMessage().contains("$.utility_functions[1].hash"));
        assertTrue(failure.getMessage().contains("canonical unsigned integer"));
        assertTrue(failure.getMessage().contains("utility_functions/alias"));
    }

    @Test
    void rejectsBlankIdentitySegmentsAndEscapedControlCharacters() throws IOException {
        String blankOperator =
                fixture().replace("\"right_type\": \"Array\"", "\"right_type\": \" \"");
        ApiInputException operatorFailure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(blankOperator));
        assertTrue(operatorFailure.getMessage().contains(".right_type"));
        assertTrue(operatorFailure.getMessage().contains("builtin_classes/Array/operators"));

        String injectedName =
                fixture().replace("\"name\": \"Node\"", "\"name\": \"Node\\nInjected\"");
        ApiInputException injectionFailure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(injectedName));
        assertTrue(injectionFailure.getMessage().contains("$.classes[1].name"));
        assertTrue(injectionFailure.getMessage().contains("control"));
    }

    @Test
    void everyNestedSchemaDiagnosticIsSingleLineAndNamesTheNearestValidatedEntity()
            throws IOException {
        String source = fixture();
        assertDiagnostic(
                replaceFirstArrayValue(source, "\"arguments\"", "{}"),
                "$.utility_functions[0].arguments",
                "utility_functions/type_convert#128");
        assertDiagnostic(
                replaceFirstArrayElement(source, "\"arguments\"", "\"not-an-object\""),
                "$.utility_functions[0].arguments[0]",
                "utility_functions/type_convert#128/arguments");
        assertDiagnostic(
                source.replace(
                        "\"type\": \"Variant\",\n          \"default_value\"",
                        "\"type\": 7,\n          \"default_value\""),
                "$.utility_functions[0].arguments[0].type",
                "utility_functions/type_convert#128/arguments/value");
        assertDiagnostic(
                source.replace("          \"name\": \"value\",\n", ""),
                "$.utility_functions[0].arguments[0].name",
                "utility_functions/type_convert#128/arguments");
        assertDiagnostic(
                source.replace("\"name\": \"value\"", "\"name\": \"bad\\nname\""),
                "$.utility_functions[0].arguments[0].name",
                "utility_functions/type_convert#128/arguments");
        String invalidEnum =
                source.replace(
                        "\"api_type\": \"core\"", "\"api_type\": \"mobile\\\\branch\\nInjected\"");
        ApiInputException enumFailure =
                assertDiagnostic(invalidEnum, "$.classes[0].api_type", "classes/Object");
        assertTrue(
                enumFailure.getMessage().contains("mobile\\\\branch\\nInjected"),
                enumFailure.getMessage());
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

    private static ApiInputException assertDiagnostic(String json, String path, String identity) {
        ApiInputException failure =
                assertThrows(ApiInputException.class, () -> FoundryApiParser.parse(json));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("(entity " + identity + ")"), failure.getMessage());
        assertTrue(!failure.getMessage().contains("\n"), failure.getMessage());
        assertTrue(!failure.getMessage().contains("\r"), failure.getMessage());
        assertTrue(
                failure.getMessage().codePoints().noneMatch(Character::isISOControl),
                failure.getMessage());
        return failure;
    }

    private static String replaceFirstArrayValue(
            String json, String fieldName, String replacement) {
        int field = json.indexOf(fieldName);
        int start = json.indexOf('[', field + fieldName.length());
        int end = matchingDelimiter(json, start, '[', ']');
        return json.substring(0, start) + replacement + json.substring(end + 1);
    }

    private static String replaceFirstArrayElement(
            String json, String fieldName, String replacement) {
        int field = json.indexOf(fieldName);
        int arrayStart = json.indexOf('[', field + fieldName.length());
        int elementStart = json.indexOf('{', arrayStart + 1);
        int elementEnd = matchingDelimiter(json, elementStart, '{', '}');
        return json.substring(0, elementStart) + replacement + json.substring(elementEnd + 1);
    }

    private static int matchingDelimiter(String source, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < source.length(); index++) {
            char value = source.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == open) {
                depth++;
            } else if (value == close && --depth == 0) {
                return index;
            }
        }
        throw new AssertionError("Unbalanced JSON delimiter at " + start + ".");
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
}

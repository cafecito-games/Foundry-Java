package games.cafecito.foundry.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatibilityManifestTest {
    private static final String API_HASH =
            "48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b";

    @Test
    void requiresOneExplicitStableClassificationForEveryParsedIdentity() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());
        Map<String, CompatibilityManifest.Classification> classifications =
                explicitSupportedClassifications(api);

        CompatibilityManifest manifest =
                CompatibilityManifest.create(api, API_HASH, "1", "1", classifications);

        assertEquals(api.entities().size(), manifest.entries().size());
        assertEquals(
                Map.of(CompatibilityManifest.Status.SUPPORTED, api.entities().size()),
                manifest.statusCounts());
        assertEquals(
                api.entities().stream().map(FoundryApi.Entity::identity).sorted().toList(),
                manifest.entries().stream()
                        .map(CompatibilityManifest.Entry::sourceIdentity)
                        .toList());
        assertTrue(
                manifest.entries().stream()
                        .allMatch(
                                entry ->
                                        entry.reasonCode().equals("PUBLIC_FOUNDRY_EXTENSION_API")));
        assertEquals(
                manifest.canonicalJson(),
                CompatibilityManifest.parse(api, manifest.canonicalJson()).canonicalJson());
    }

    @Test
    void rejectsMissingExtraAndDuplicateClassifications() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());
        Map<String, CompatibilityManifest.Classification> missing =
                explicitSupportedClassifications(api);
        missing.remove(api.entities().get(0).identity());

        ApiInputException missingFailure =
                assertThrows(
                        ApiInputException.class,
                        () -> CompatibilityManifest.create(api, API_HASH, "1", "1", missing));
        assertTrue(missingFailure.getMessage().contains("Unclassified source identities"));

        Map<String, CompatibilityManifest.Classification> extra =
                explicitSupportedClassifications(api);
        extra.put(
                "classes/NotInSource",
                new CompatibilityManifest.Classification(
                        CompatibilityManifest.Status.SUPPORTED, "PUBLIC_FOUNDRY_EXTENSION_API"));
        ApiInputException extraFailure =
                assertThrows(
                        ApiInputException.class,
                        () -> CompatibilityManifest.create(api, API_HASH, "1", "1", extra));
        assertTrue(extraFailure.getMessage().contains("Unknown classified identities"));

        String duplicateJson =
                CompatibilityManifest.create(
                                api, API_HASH, "1", "1", explicitSupportedClassifications(api))
                        .canonicalJson();
        int entryStart = duplicateJson.indexOf("{\"reason_code\"");
        int entryEnd = duplicateJson.indexOf('}', entryStart) + 1;
        String firstEntry = duplicateJson.substring(entryStart, entryEnd);
        String duplicatedManifest =
                duplicateJson.replace("\"entries\":[", "\"entries\":[" + firstEntry + ",");
        ApiInputException duplicateFailure =
                assertThrows(
                        ApiInputException.class,
                        () -> CompatibilityManifest.parse(api, duplicatedManifest));
        assertTrue(duplicateFailure.getMessage().contains("Duplicate compatibility identity"));
    }

    @Test
    void acceptsOnlyApprovedStatusesAndStableNonBlankReasonCodes() throws IOException {
        FoundryApi api = FoundryApiParser.parse(fixture());
        List<CompatibilityManifest.Status> statuses =
                List.of(
                        CompatibilityManifest.Status.SUPPORTED,
                        CompatibilityManifest.Status.EXCLUDED_LANGUAGE,
                        CompatibilityManifest.Status.EXCLUDED_PLATFORM,
                        CompatibilityManifest.Status.EXCLUDED_UPSTREAM);
        Map<String, CompatibilityManifest.Classification> classifications = new LinkedHashMap<>();
        for (int index = 0; index < api.entities().size(); index++) {
            classifications.put(
                    api.entities().get(index).identity(),
                    new CompatibilityManifest.Classification(
                            statuses.get(index % statuses.size()), "REVIEWED_REASON_" + index));
        }
        CompatibilityManifest manifest =
                CompatibilityManifest.create(api, API_HASH, "1", "1", classifications);
        assertEquals(
                api.entities().size(),
                manifest.statusCounts().values().stream().mapToInt(Integer::intValue).sum());

        Map<String, CompatibilityManifest.Classification> blank =
                explicitSupportedClassifications(api);
        blank.put(
                api.entities().get(0).identity(),
                new CompatibilityManifest.Classification(
                        CompatibilityManifest.Status.SUPPORTED, " "));
        ApiInputException blankFailure =
                assertThrows(
                        ApiInputException.class,
                        () -> CompatibilityManifest.create(api, API_HASH, "1", "1", blank));
        assertTrue(blankFailure.getMessage().contains("reason code"));

        String unknownStatus =
                manifest.canonicalJson().replaceFirst("\"supported\"", "\"partially-supported\"");
        ApiInputException statusFailure =
                assertThrows(
                        ApiInputException.class,
                        () -> CompatibilityManifest.parse(api, unknownStatus));
        assertTrue(statusFailure.getMessage().contains("unknown compatibility status"));
    }

    @Test
    void checkedManifestMetadataMustMatchVerifiedApiInputs() {
        Path acceptedDirectory =
                Path.of(System.getProperty("user.dir")).resolve("../api/current").normalize();
        ApiInputs inputs = ApiInputs.load(acceptedDirectory);
        FoundryApi api = FoundryApiParser.parse(inputs);

        CompatibilityManifest accepted = CompatibilityManifest.parse(api, inputs);
        assertEquals(inputs.extensionApiSha256(), accepted.apiSha256());
        assertEquals(inputs.provenance().generatorVersion(), accepted.generatorVersion());
        assertEquals(inputs.provenance().bridgeContractVersion(), accepted.bridgeContractVersion());

        for (String mutation :
                List.of(
                        "\"api_sha256\":\"" + API_HASH + "\"",
                        "\"generator_version\":\"1\"",
                        "\"bridge_contract_version\":\"1\"")) {
            String replacement =
                    mutation.startsWith("\"api_sha256\"")
                            ? "\"api_sha256\":\"" + "0".repeat(64) + "\""
                            : mutation.replace(":\"1\"", ":\"2\"");
            ApiInputs mismatched =
                    new ApiInputs(
                            inputs.provenance(),
                            inputs.extensionApiJson(),
                            inputs.interfaceHeader(),
                            inputs.compatibilityManifestJson().replace(mutation, replacement));

            ApiInputException failure =
                    assertThrows(
                            ApiInputException.class,
                            () -> CompatibilityManifest.parse(api, mismatched));
            assertTrue(
                    failure.getMessage().contains(mutation.substring(1, mutation.indexOf('"', 1))));
            assertTrue(failure.getMessage().contains("verified provenance"));
        }
    }

    private static Map<String, CompatibilityManifest.Classification>
            explicitSupportedClassifications(FoundryApi api) {
        Map<String, CompatibilityManifest.Classification> classifications = new LinkedHashMap<>();
        api.entities()
                .forEach(
                        entity ->
                                classifications.put(
                                        entity.identity(),
                                        new CompatibilityManifest.Classification(
                                                CompatibilityManifest.Status.SUPPORTED,
                                                "PUBLIC_FOUNDRY_EXTENSION_API")));
        return classifications;
    }

    private static String fixture() throws IOException {
        try (var stream =
                CompatibilityManifestTest.class.getResourceAsStream(
                        "/fixtures/complete-api.json")) {
            if (stream == null) {
                throw new IOException("Missing complete API fixture.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

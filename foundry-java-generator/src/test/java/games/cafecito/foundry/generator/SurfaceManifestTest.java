package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurfaceManifestTest {
    private static final String REASON_CODE = "WS5_MODEL_AND_GENERATOR_REPRESENTABLE";
    private static final String NODE = "games.cafecito.foundry.generated.classes.Node";
    private static final SurfaceManifest.Provenance PROVENANCE =
            new SurfaceManifest.Provenance(
                    "0.1.0-alpha.14",
                    "48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b",
                    "0.1.0-SNAPSHOT",
                    "1",
                    "1");

    private static RealizationMap map() {
        return RealizationMap.of(
                List.of(
                        RealizationMap.Entry.realized(
                                "classes/Node",
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON_CODE,
                                List.of(
                                        JavaMember.ofType(NODE),
                                        JavaMember.ofMethod(NODE, "free", List.of(), "void"))),
                        RealizationMap.Entry.notRealized(
                                "classes/Node/properties/name",
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON_CODE,
                                NonRealizationReason.PROPERTY_REALIZED_BY_ENGINE_METHOD.name())));
    }

    private static String frozenJson(RealizationMap map) {
        return "{\"binding_id\":\"foundry-java\","
                + "\"binding_specific\":{\"namespace\":\"foundry-java\","
                + "\"realization_map_format\":\"foundry-java-realization-map/1\","
                + "\"realization_map_sha256\":\""
                + map.sha256()
                + "\"},"
                + "\"binding_version\":\"0.1.0-SNAPSHOT\","
                + "\"bridge_contract_version\":\"1\","
                + "\"engine_api_sha256\":"
                + "\"48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b\","
                + "\"engine_api_version\":\"0.1.0-alpha.14\","
                + "\"entries\":["
                + "{\"availability\":\"supported\","
                + "\"binding_specific\":{\"compatibility_reason_code\":\""
                + REASON_CODE
                + "\",\"namespace\":\"foundry-java\","
                + "\"realized_members\":[\""
                + NODE
                + "#<type>:"
                + NODE
                + "\",\""
                + NODE
                + "#free:()void\"]},"
                + "\"realization\":\"realized\","
                + "\"realized_member_count\":2,"
                + "\"source_identity\":\"classes/Node\"},"
                + "{\"availability\":\"supported\","
                + "\"binding_specific\":{\"compatibility_reason_code\":\""
                + REASON_CODE
                + "\",\"namespace\":\"foundry-java\","
                + "\"non_realization_reason\":\"PROPERTY_REALIZED_BY_ENGINE_METHOD\"},"
                + "\"non_realization_reason\":\"SERVED_BY_ENGINE_ACCESSOR\","
                + "\"realization\":\"not-realized\","
                + "\"realized_member_count\":0,"
                + "\"source_identity\":\"classes/Node/properties/name\"}],"
                + "\"generator_version\":\"1\","
                + "\"schema_version\":1}\n";
    }

    @Test
    void canonicalJsonFreezesTheNeutralSchemaAndNamespacesEveryBindingSpecificField() {
        RealizationMap map = map();

        SurfaceManifest manifest = SurfaceManifest.from(map, PROVENANCE);

        assertEquals(frozenJson(map), manifest.canonicalJson());
    }

    @Test
    void neutralVocabularyMapsEveryApprovedBindingReasonExplicitly() {
        Map<NonRealizationReason, NeutralNonRealizationReason> expected =
                new EnumMap<>(NonRealizationReason.class);
        expected.put(
                NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE,
                NeutralNonRealizationReason.SUBSUMED_BY_ENCLOSING_SIGNATURE);
        expected.put(
                NonRealizationReason.RETURN_VALUE_REALIZED_IN_MEMBER_SIGNATURE,
                NeutralNonRealizationReason.SUBSUMED_BY_ENCLOSING_SIGNATURE);
        expected.put(
                NonRealizationReason.SIGNAL_ARGUMENT_REALIZED_IN_SIGNAL_TYPE,
                NeutralNonRealizationReason.SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT);
        expected.put(
                NonRealizationReason.PROPERTY_REALIZED_BY_ENGINE_METHOD,
                NeutralNonRealizationReason.SERVED_BY_ENGINE_ACCESSOR);
        expected.put(
                NonRealizationReason.BUILTIN_MEMBER_REALIZED_BY_ENGINE_METHOD,
                NeutralNonRealizationReason.SERVED_BY_ENGINE_ACCESSOR);
        expected.put(
                NonRealizationReason.LAYOUT_TABLE_ENTRY_REALIZED_BY_QUERY_API,
                NeutralNonRealizationReason.SERVED_BY_LAYOUT_QUERY_API);

        Map<NonRealizationReason, NeutralNonRealizationReason> observed =
                new EnumMap<>(NonRealizationReason.class);
        for (NonRealizationReason reason : NonRealizationReason.approved()) {
            observed.put(reason, NeutralNonRealizationReason.of(reason));
        }

        assertEquals(expected, observed);
        assertEquals(
                List.of(
                        NeutralNonRealizationReason.SUBSUMED_BY_ENCLOSING_SIGNATURE,
                        NeutralNonRealizationReason.SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT,
                        NeutralNonRealizationReason.SERVED_BY_ENGINE_ACCESSOR,
                        NeutralNonRealizationReason.SERVED_BY_LAYOUT_QUERY_API),
                NeutralNonRealizationReason.approved());
    }

    @Test
    void neutralVocabularyRejectsTokensOutsideTheClosedSet() {
        assertThrows(
                ApiInputException.class,
                () -> NeutralNonRealizationReason.require("SERVED_BY_MAGIC"));
        assertEquals(
                NeutralNonRealizationReason.SERVED_BY_ENGINE_ACCESSOR,
                NeutralNonRealizationReason.require("SERVED_BY_ENGINE_ACCESSOR"));
    }

    @Test
    void renderingIsDeterministicAndIndependentOfEntryOrder() {
        List<RealizationMap.Entry> reversed = new ArrayList<>(map().entries());
        java.util.Collections.reverse(reversed);

        String first = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();
        String second = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();
        String reorderedRendering =
                SurfaceManifest.from(RealizationMap.of(reversed), PROVENANCE).canonicalJson();

        assertEquals(first, second);
        assertEquals(first, reorderedRendering);
        assertFalse(first.contains(System.getProperty("user.dir")));
        assertEquals(
                SurfaceManifest.from(map(), PROVENANCE).sha256(),
                SurfaceManifest.parse(first).sha256());
    }

    @Test
    void parseRoundTripsTheCanonicalRendering() {
        SurfaceManifest manifest = SurfaceManifest.from(map(), PROVENANCE);

        SurfaceManifest parsed = SurfaceManifest.parse(manifest.canonicalJson());

        assertEquals(manifest.canonicalJson(), parsed.canonicalJson());
        assertEquals(SurfaceManifest.SCHEMA_VERSION, parsed.schemaVersion());
        assertEquals(PROVENANCE, parsed.provenance());
        assertEquals(2, parsed.entries().size());
        assertEquals(1, parsed.realizedEntities());
        assertTrue(parsed.disagreementsWith(map()).isEmpty());
    }

    @Test
    void parseRejectsUnknownNeutralFields() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        assertThrows(
                ApiInputException.class,
                () ->
                        SurfaceManifest.parse(
                                manifest.replace(
                                        "\"schema_version\":1",
                                        "\"surprise\":1,\"schema_version\":1")));
        assertThrows(
                ApiInputException.class,
                () ->
                        SurfaceManifest.parse(
                                manifest.replace(
                                        "\"realization\":\"realized\"",
                                        "\"realization\":\"realized\",\"surprise\":1")));
    }

    @Test
    void parseRejectsAnUnsupportedSchemaVersion() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                SurfaceManifest.parse(
                                        manifest.replace(
                                                "\"schema_version\":1", "\"schema_version\":2")));

        assertTrue(failure.getMessage().contains("schema_version"));
    }

    @Test
    void parseRejectsARealizationStateThatContradictsItsOwnMemberCount() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        assertThrows(
                ApiInputException.class,
                () ->
                        SurfaceManifest.parse(
                                manifest.replace(
                                        "\"realization\":\"realized\","
                                                + "\"realized_member_count\":2",
                                        "\"realization\":\"not-realized\","
                                                + "\"realized_member_count\":2")));
    }

    @Test
    void parseRejectsANotRealizedEntryThatStillClaimsRealizedMembers() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                SurfaceManifest.parse(
                                        manifest.replace(
                                                "\"non_realization_reason\":"
                                                        + "\"PROPERTY_REALIZED_BY_ENGINE_METHOD\"}",
                                                "\"non_realization_reason\":"
                                                        + "\"PROPERTY_REALIZED_BY_ENGINE_METHOD\","
                                                        + "\"realized_members\":[\""
                                                        + NODE
                                                        + "#name:java.lang.String\"]}")));

        assertTrue(failure.getMessage().contains("realized_members"));
    }

    @Test
    void parseRejectsARealizedEntryThatStillClaimsANonRealizationReason() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                SurfaceManifest.parse(
                                        manifest.replace(
                                                "\"namespace\":\"foundry-java\","
                                                        + "\"realized_members\"",
                                                "\"namespace\":\"foundry-java\","
                                                        + "\"non_realization_reason\":"
                                                        + "\"PROPERTY_REALIZED_BY_ENGINE_METHOD\","
                                                        + "\"realized_members\"")));

        assertTrue(failure.getMessage().contains("non_realization_reason"));
    }

    @Test
    void parseRejectsANeutralReasonThatIsNotTheMeaningOfItsBindingReason() {
        String manifest = SurfaceManifest.from(map(), PROVENANCE).canonicalJson();

        ApiInputException failure =
                assertThrows(
                        ApiInputException.class,
                        () ->
                                SurfaceManifest.parse(
                                        manifest.replace(
                                                "\"non_realization_reason\":"
                                                        + "\"SERVED_BY_ENGINE_ACCESSOR\"",
                                                "\"non_realization_reason\":"
                                                        + "\"SERVED_BY_LAYOUT_QUERY_API\"")));

        assertTrue(failure.getMessage().contains("is not the neutral meaning of"));
    }

    @Test
    void disagreementDetectsATamperedReason() {
        String tampered =
                SurfaceManifest.from(map(), PROVENANCE)
                        .canonicalJson()
                        .replace(
                                "\"non_realization_reason\":\"SERVED_BY_ENGINE_ACCESSOR\"",
                                "\"non_realization_reason\":\"SERVED_BY_LAYOUT_QUERY_API\"")
                        .replace(
                                "\"non_realization_reason\":\"PROPERTY_REALIZED_BY_ENGINE_METHOD\"",
                                "\"non_realization_reason\":"
                                        + "\"LAYOUT_TABLE_ENTRY_REALIZED_BY_QUERY_API\"");

        List<String> disagreements = SurfaceManifest.parse(tampered).disagreementsWith(map());

        assertEquals(1, disagreements.size());
        assertTrue(disagreements.get(0).contains("classes/Node/properties/name"));
        assertTrue(disagreements.get(0).startsWith(SurfaceManifest.DISAGREEMENT));
    }

    @Test
    void disagreementDetectsATamperedAvailability() {
        String tampered =
                SurfaceManifest.from(map(), PROVENANCE)
                        .canonicalJson()
                        .replace(
                                "\"availability\":\"supported\","
                                        + "\"binding_specific\":{\"compatibility_reason_code\":\""
                                        + REASON_CODE
                                        + "\",\"namespace\":\"foundry-java\","
                                        + "\"realized_members\"",
                                "\"availability\":\"excluded-platform\","
                                        + "\"binding_specific\":{\"compatibility_reason_code\":\""
                                        + REASON_CODE
                                        + "\",\"namespace\":\"foundry-java\","
                                        + "\"realized_members\"");

        List<String> disagreements = SurfaceManifest.parse(tampered).disagreementsWith(map());

        assertEquals(1, disagreements.size());
        assertTrue(disagreements.get(0).contains("excluded-platform"));
    }

    @Test
    void disagreementDetectsATamperedBindingSpecificMember() {
        String tampered =
                SurfaceManifest.from(map(), PROVENANCE)
                        .canonicalJson()
                        .replace(NODE + "#free:()void", NODE + "#freed:()void");

        List<String> disagreements = SurfaceManifest.parse(tampered).disagreementsWith(map());

        assertEquals(1, disagreements.size());
        assertTrue(disagreements.get(0).contains("classes/Node"));
    }

    @Test
    void disagreementDetectsAnEntryTheMapDoesNotCover() {
        SurfaceManifest manifest = SurfaceManifest.from(map(), PROVENANCE);
        RealizationMap fewer =
                RealizationMap.of(List.of(map().entry("classes/Node/properties/name")));

        List<String> disagreements = manifest.disagreementsWith(fewer);

        assertEquals(2, disagreements.size());
        assertTrue(
                disagreements.stream()
                        .anyMatch(message -> message.contains("realization_map_sha256")));
        assertTrue(disagreements.stream().anyMatch(message -> message.contains("classes/Node")));
    }

    @Test
    void disagreementDetectsAMissingEntry() {
        RealizationMap map = map();
        SurfaceManifest manifest =
                SurfaceManifest.from(
                        RealizationMap.of(List.of(map.entry("classes/Node"))), PROVENANCE);

        List<String> disagreements = manifest.disagreementsWith(map);

        assertTrue(
                disagreements.stream()
                        .anyMatch(
                                message ->
                                        message.contains("classes/Node/properties/name")
                                                && message.contains("absent")));
    }

    @Test
    void disagreementDetectsATamperedRealizationMapDigest() {
        String tampered =
                SurfaceManifest.from(map(), PROVENANCE)
                        .canonicalJson()
                        .replace(map().sha256(), "0".repeat(64));

        List<String> disagreements = SurfaceManifest.parse(tampered).disagreementsWith(map());

        assertEquals(1, disagreements.size());
        assertTrue(disagreements.get(0).contains("realization_map_sha256"));
    }
}

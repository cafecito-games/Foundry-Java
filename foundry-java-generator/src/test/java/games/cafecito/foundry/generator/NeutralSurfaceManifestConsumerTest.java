package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NeutralSurfaceManifestConsumerTest {
    private static final String REASON_CODE = "WS5_MODEL_AND_GENERATOR_REPRESENTABLE";
    private static final String NODE = "games.cafecito.foundry.generated.classes.Node";
    private static final String ENGINE_API_SHA256 =
            "48af7d0e8fbbbc615d985db39c135402e5120649865cc21e43676da5ee65332b";
    private static final SurfaceManifest.Provenance PROVENANCE =
            new SurfaceManifest.Provenance(
                    "0.1.0-alpha.14", ENGINE_API_SHA256, "0.1.0-SNAPSHOT", "1", "1");

    private static String javaManifest() {
        RealizationMap map =
                RealizationMap.of(
                        List.of(
                                RealizationMap.Entry.realized(
                                        "classes/Node",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON_CODE,
                                        List.of(JavaMember.ofType(NODE))),
                                RealizationMap.Entry.notRealized(
                                        "classes/Node/properties/name",
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON_CODE,
                                        NonRealizationReason.PROPERTY_REALIZED_BY_ENGINE_METHOD
                                                .name())));
        return SurfaceManifest.from(map, PROVENANCE).canonicalJson();
    }

    /**
     * A manifest a sibling binding could publish. It carries no Java vocabulary at all and puts its
     * own detail behind its own namespace, so it exercises the neutral portion in isolation.
     */
    private static String siblingManifest() {
        return "{\"schema_version\":1,"
                + "\"engine_api_version\":\"0.1.0-alpha.14\","
                + "\"engine_api_sha256\":\""
                + ENGINE_API_SHA256
                + "\","
                + "\"binding_id\":\"foundry-sibling\","
                + "\"binding_version\":\"2.4.0\","
                + "\"generator_version\":\"7\","
                + "\"bridge_contract_version\":\"3\","
                + "\"binding_specific\":{\"namespace\":\"foundry-sibling\","
                + "\"module_layout\":{\"kind\":\"single\",\"weights\":[1,2,3]}},"
                + "\"entries\":["
                + "{\"source_identity\":\"classes/Node\",\"availability\":\"supported\","
                + "\"realization\":\"not-realized\","
                + "\"non_realization_reason\":\"SERVED_BY_ENGINE_ACCESSOR\","
                + "\"realized_member_count\":0},"
                + "{\"source_identity\":\"classes/Node/properties/name\","
                + "\"availability\":\"supported\",\"realization\":\"realized\","
                + "\"realized_member_count\":4,"
                + "\"binding_specific\":{\"namespace\":\"foundry-sibling\","
                + "\"protocol_witnesses\":[\"NodeName\"]}},"
                + "{\"source_identity\":\"classes/Node/methods/queue_free\","
                + "\"availability\":\"excluded-platform\",\"realization\":\"not-realized\","
                + "\"non_realization_reason\":\"SERVED_BY_LAYOUT_QUERY_API\","
                + "\"realized_member_count\":0}]}";
    }

    @Test
    void computesCoverageWithoutInterpretingAlienBindingSpecificContent() {
        String withAlienDetail =
                javaManifest()
                        .replace(
                                "\"namespace\":\"foundry-java\"",
                                "\"namespace\":\"foundry-java\","
                                        + "\"an_unknown_future_field\":{\"nested\":[1,2,"
                                        + "{\"deep\":null}],\"flag\":true}");

        NeutralSurfaceManifestConsumer.Coverage coverage =
                NeutralSurfaceManifestConsumer.read(withAlienDetail).coverage();

        assertEquals("foundry-java", coverage.bindingId());
        assertEquals(ENGINE_API_SHA256, coverage.engineApiSha256());
        assertEquals(2, coverage.coveredEntities());
        assertEquals(1, coverage.realizedEntities());
        assertEquals(Map.of("SERVED_BY_ENGINE_ACCESSOR", 1), coverage.nonRealizationReasonCounts());
    }

    @Test
    void diffsTwoBindingsOverOneEngineApiFromNeutralFieldsAlone() {
        NeutralSurfaceManifestConsumer java = NeutralSurfaceManifestConsumer.read(javaManifest());
        NeutralSurfaceManifestConsumer sibling =
                NeutralSurfaceManifestConsumer.read(siblingManifest());

        NeutralSurfaceManifestConsumer.Diff diff = java.diff(sibling);

        assertEquals(List.of("classes/Node"), diff.realizedOnlyInLeft());
        assertEquals(List.of("classes/Node/properties/name"), diff.realizedOnlyInRight());
        assertEquals(List.of(), diff.coveredOnlyInLeft());
        assertEquals(List.of("classes/Node/methods/queue_free"), diff.coveredOnlyInRight());
        assertEquals(3, sibling.coverage().coveredEntities());
        assertEquals(1, sibling.coverage().realizedEntities());
    }

    @Test
    void refusesToDiffBindingsDescribingDifferentEngineApiHashes() {
        NeutralSurfaceManifestConsumer java = NeutralSurfaceManifestConsumer.read(javaManifest());
        NeutralSurfaceManifestConsumer other =
                NeutralSurfaceManifestConsumer.read(
                        siblingManifest().replace(ENGINE_API_SHA256, "0".repeat(64)));

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> java.diff(other));

        assertTrue(failure.getMessage().contains("different engine API hashes"));
    }

    @Test
    void rejectsAnUnknownNeutralField() {
        String manifest =
                javaManifest()
                        .replace("\"schema_version\":1", "\"schema_version\":1,\"surprise\":true");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NeutralSurfaceManifestConsumer.read(manifest));

        assertTrue(failure.getMessage().contains("surprise"));
    }

    @Test
    void rejectsBindingSpecificContentThatDoesNotNameItsNamespace() {
        String manifest =
                javaManifest()
                        .replace("\"namespace\":\"foundry-java\",", "")
                        .replace(",\"namespace\":\"foundry-java\"", "");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NeutralSurfaceManifestConsumer.read(manifest));

        assertTrue(failure.getMessage().contains("namespace"));
    }

    @Test
    void rejectsAnUnderstoodSchemaVersionItDoesNotImplement() {
        String manifest = javaManifest().replace("\"schema_version\":1", "\"schema_version\":2");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NeutralSurfaceManifestConsumer.read(manifest));

        assertTrue(failure.getMessage().contains("schema_version"));
    }

    @Test
    void rejectsARealizationVocabularyItDoesNotUnderstand() {
        String manifest =
                javaManifest()
                        .replace(
                                "\"non_realization_reason\":\"SERVED_BY_ENGINE_ACCESSOR\","
                                        + "\"realization\":\"not-realized\"",
                                "\"non_realization_reason\":\"SERVED_BY_MAGIC\","
                                        + "\"realization\":\"not-realized\"");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NeutralSurfaceManifestConsumer.read(manifest));

        assertTrue(failure.getMessage().contains("SERVED_BY_MAGIC"));
    }
}

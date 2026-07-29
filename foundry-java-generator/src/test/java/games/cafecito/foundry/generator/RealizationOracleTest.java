package games.cafecito.foundry.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Freezes the engine-API parity oracle contract over a generated realization map. */
class RealizationOracleTest {
    private static final String REASON = "WS5_MODEL_AND_GENERATOR_REPRESENTABLE";
    private static final String OWNER = "games.cafecito.foundry.generated.classes.ExampleNode";
    private static final String CLASS_IDENTITY = "classes/ExampleNode";
    private static final String METHOD_IDENTITY = "classes/ExampleNode/methods/get_value";
    private static final String ARGUMENT_IDENTITY =
            "classes/ExampleNode/methods/get_value/arguments/index";
    private static final String SIGNAL_IDENTITY = "classes/ExampleNode/signals/custom_action";
    private static final JavaMember CLASS_TYPE = JavaMember.ofType(OWNER);
    private static final JavaMember METHOD =
            JavaMember.ofMethod(OWNER, "getValue", List.of("long"), "java.lang.String");
    private static final JavaMember STRUCTURAL_BIND =
            JavaMember.ofMethod(
                    OWNER,
                    "bind",
                    List.of("games.cafecito.foundry.runtime.FoundryBindingContext", "long"),
                    OWNER);

    @Test
    void aTotalConsistentMapReportsNoViolation() {
        assertEquals(List.of(), RealizationOracle.verify(map(), manifest(), surface()));
    }

    @Test
    void aMutatedRealizedMemberIsReportedAgainstItsSourceIdentity() {
        RealizationMap mutated =
                RealizationMap.of(
                        replace(
                                map().entries(),
                                RealizationMap.Entry.realized(
                                        METHOD_IDENTITY,
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        List.of(
                                                JavaMember.ofMethod(
                                                        OWNER,
                                                        "getValue",
                                                        List.of("int"),
                                                        "java.lang.String")))));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(mutated, manifest(), surface());

        assertEquals(
                List.of(
                        RealizationOracle.Kind.GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY,
                        RealizationOracle.Kind.SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER),
                violations.stream().map(RealizationOracle.Violation::kind).toList());
        RealizationOracle.Violation unrealized = violations.get(1);
        assertEquals(METHOD_IDENTITY, unrealized.sourceIdentity());
        assertEquals(
                "SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER"
                        + " source-identity=classes/ExampleNode/methods/get_value"
                        + " expected=generated member "
                        + OWNER
                        + "#getValue:(int)java.lang.String"
                        + " observed=absent from the compiled generated surface"
                        + " manifest-entry=classes/ExampleNode/methods/get_value=supported/"
                        + REASON,
                unrealized.message());
    }

    @Test
    void aMutatedGenericTypeArgumentIsReportedThroughTheDeclaredView() {
        JavaMember declaredSignal =
                JavaMember.ofMethod(
                        OWNER,
                        "customActionSignal" + JavaMember.DECLARED_VIEW_SUFFIX,
                        List.of(),
                        "games.cafecito.foundry.runtime.FoundryTypedSignal.Of1"
                                + "<games.cafecito.foundry.types.StringName>");
        JavaMember erasedSignal =
                JavaMember.ofMethod(
                        OWNER,
                        "customActionSignal",
                        List.of(),
                        "games.cafecito.foundry.runtime.FoundryTypedSignal.Of1");
        JavaMember driftedSignal =
                JavaMember.ofMethod(
                        OWNER,
                        "customActionSignal" + JavaMember.DECLARED_VIEW_SUFFIX,
                        List.of(),
                        "games.cafecito.foundry.runtime.FoundryTypedSignal.Of1<java.lang.Long>");
        List<CompatibilityManifest.Entry> manifest = new ArrayList<>(manifest());
        manifest.add(
                new CompatibilityManifest.Entry(
                        SIGNAL_IDENTITY, CompatibilityManifest.Status.SUPPORTED, REASON));
        List<RealizationMap.Entry> entries = new ArrayList<>(map().entries());
        entries.add(
                RealizationMap.Entry.realized(
                        SIGNAL_IDENTITY,
                        CompatibilityManifest.Status.SUPPORTED,
                        REASON,
                        List.of(erasedSignal, driftedSignal)));
        List<JavaMember> members = new ArrayList<>(surface().members());
        members.add(erasedSignal);
        members.add(declaredSignal);

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(
                        RealizationMap.of(entries), manifest, GeneratedSurface.of(members));

        assertEquals(
                List.of(
                        RealizationOracle.Kind.GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY,
                        RealizationOracle.Kind.SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER),
                violations.stream().map(RealizationOracle.Violation::kind).toList());
        assertEquals(SIGNAL_IDENTITY, violations.get(1).sourceIdentity());
        assertTrue(violations.get(1).expected().contains("<java.lang.Long>"));
    }

    @Test
    void aRemovedEntityIsReportedAgainstTheVendoredManifest() {
        List<RealizationMap.Entry> remaining = new ArrayList<>(map().entries());
        remaining.removeIf(entry -> entry.sourceIdentity().equals(METHOD_IDENTITY));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(RealizationMap.of(remaining), manifest(), surface());

        assertEquals(
                List.of(
                        RealizationOracle.Kind.GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY,
                        RealizationOracle.Kind.SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER),
                violations.stream().map(RealizationOracle.Violation::kind).toList());
        RealizationOracle.Violation absent = violations.get(1);
        assertEquals(METHOD_IDENTITY, absent.sourceIdentity());
        assertEquals("exactly one realization entry", absent.expected());
        assertEquals("absent from the realization map", absent.observed());
        assertEquals(METHOD_IDENTITY + "=supported/" + REASON, absent.manifestEntry());
    }

    @Test
    void aDriftedStatusIsReportedWithBothStates() {
        RealizationMap drifted =
                RealizationMap.of(
                        replace(
                                map().entries(),
                                RealizationMap.Entry.notRealized(
                                        ARGUMENT_IDENTITY,
                                        CompatibilityManifest.Status.EXCLUDED_PLATFORM,
                                        REASON,
                                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                                .name())));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(drifted, manifest(), surface());

        assertEquals(1, violations.size());
        RealizationOracle.Violation drift = violations.get(0);
        assertEquals(RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT, drift.kind());
        assertEquals(ARGUMENT_IDENTITY, drift.sourceIdentity());
        assertEquals("supported/" + REASON, drift.expected());
        assertEquals("excluded-platform/" + REASON, drift.observed());
        assertEquals(ARGUMENT_IDENTITY + "=supported/" + REASON, drift.manifestEntry());
    }

    @Test
    void aDriftedReasonCodeIsReportedWithBothStates() {
        RealizationMap drifted =
                RealizationMap.of(
                        replace(
                                map().entries(),
                                RealizationMap.Entry.notRealized(
                                        ARGUMENT_IDENTITY,
                                        CompatibilityManifest.Status.SUPPORTED,
                                        "WS5_SOMETHING_ELSE",
                                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                                .name())));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(drifted, manifest(), surface());

        assertEquals(1, violations.size());
        assertEquals(
                RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT, violations.get(0).kind());
        assertEquals("supported/WS5_SOMETHING_ELSE", violations.get(0).observed());
    }

    @Test
    void anUnapprovedNonRealizationReasonIsRejectedInsteadOfWideningTheVocabulary() {
        RealizationMap widened =
                RealizationMap.of(
                        replace(
                                map().entries(),
                                RealizationMap.Entry.notRealized(
                                        ARGUMENT_IDENTITY,
                                        CompatibilityManifest.Status.SUPPORTED,
                                        REASON,
                                        "NOT_WORTH_GENERATING")));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(widened, manifest(), surface());

        assertEquals(1, violations.size());
        RealizationOracle.Violation unapproved = violations.get(0);
        assertEquals(RealizationOracle.Kind.UNAPPROVED_NON_REALIZATION_REASON, unapproved.kind());
        assertEquals(ARGUMENT_IDENTITY, unapproved.sourceIdentity());
        assertEquals("NOT_WORTH_GENERATING", unapproved.observed());
        assertTrue(unapproved.expected().contains("ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE"));
        assertFalse(NonRealizationReason.isApproved("NOT_WORTH_GENERATING"));
        assertThrows(
                ApiInputException.class,
                () -> NonRealizationReason.require("NOT_WORTH_GENERATING"));
    }

    @Test
    void aGeneratedMemberWithNoSourceEntityIsReported() {
        List<JavaMember> extended = new ArrayList<>(surface().members());
        JavaMember orphan = JavaMember.ofMethod(OWNER, "getOrphan", List.of(), "java.lang.String");
        extended.add(orphan);

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(map(), manifest(), GeneratedSurface.of(extended));

        assertEquals(1, violations.size());
        RealizationOracle.Violation unclaimed = violations.get(0);
        assertEquals(
                RealizationOracle.Kind.GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY, unclaimed.kind());
        assertEquals("none", unclaimed.sourceIdentity());
        assertEquals("unclaimed generated member " + orphan.render(), unclaimed.observed());
    }

    @Test
    void aRealizationEntryOutsideTheVendoredManifestIsReported() {
        List<RealizationMap.Entry> extended = new ArrayList<>(map().entries());
        extended.add(
                RealizationMap.Entry.notRealized(
                        "classes/ExampleNode/methods/removed_upstream",
                        CompatibilityManifest.Status.SUPPORTED,
                        REASON,
                        NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE.name()));

        List<RealizationOracle.Violation> violations =
                RealizationOracle.verify(RealizationMap.of(extended), manifest(), surface());

        assertEquals(1, violations.size());
        assertEquals(
                RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT, violations.get(0).kind());
        assertEquals("absent", violations.get(0).manifestEntry());
    }

    @Test
    void anExcludedEntityMayNotRealizeAMember() {
        RealizationMap excluded =
                RealizationMap.of(
                        replace(
                                map().entries(),
                                RealizationMap.Entry.realized(
                                        METHOD_IDENTITY,
                                        CompatibilityManifest.Status.EXCLUDED_LANGUAGE,
                                        REASON,
                                        List.of(METHOD))));

        List<RealizationOracle.Violation> kinds =
                RealizationOracle.verify(excluded, manifest(), surface());

        assertEquals(
                List.of(
                        RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT,
                        RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT),
                kinds.stream().map(RealizationOracle.Violation::kind).toList());
    }

    @Test
    void everyFailureRendersOnOneEscapedLine() {
        RealizationOracle.Violation violation =
                new RealizationOracle.Violation(
                        RealizationOracle.Kind.MANIFEST_CLASSIFICATION_DRIFT,
                        "classes/Example\nNode\\members",
                        "supported",
                        "excluded-language",
                        "classes/Example\tNode=supported/" + REASON);

        String message = violation.message();

        assertFalse(message.contains("\n"));
        assertFalse(message.contains("\t"));
        assertTrue(message.contains("classes/Example\\nNode\\\\members"));
        assertTrue(message.contains("classes/Example\\tNode=supported/" + REASON));
    }

    @Test
    void theApprovedStructuralSurfaceExplainsBindingContractMembersOnly() {
        assertTrue(RealizationOracle.isApprovedStructuralSurface(STRUCTURAL_BIND));
        assertFalse(
                RealizationOracle.isApprovedStructuralSurface(
                        JavaMember.ofMethod(OWNER, "getOrphan", List.of(), "long")));
        assertTrue(
                RealizationOracle.isApprovedStructuralSurface(
                        JavaMember.ofField(
                                "games.cafecito.foundry.generated.GeneratedApiProvenance",
                                "API_SHA256",
                                "java.lang.String")));
        assertTrue(
                RealizationOracle.isApprovedStructuralSurface(
                        JavaMember.ofType(
                                "games.cafecito.foundry.generated.GeneratedNativeDispatch0007")));
        assertFalse(RealizationOracle.isApprovedStructuralSurface(JavaMember.ofType(OWNER)));
    }

    private static List<CompatibilityManifest.Entry> manifest() {
        return List.of(
                new CompatibilityManifest.Entry(
                        CLASS_IDENTITY, CompatibilityManifest.Status.SUPPORTED, REASON),
                new CompatibilityManifest.Entry(
                        METHOD_IDENTITY, CompatibilityManifest.Status.SUPPORTED, REASON),
                new CompatibilityManifest.Entry(
                        ARGUMENT_IDENTITY, CompatibilityManifest.Status.SUPPORTED, REASON));
    }

    private static RealizationMap map() {
        return RealizationMap.of(
                List.of(
                        RealizationMap.Entry.realized(
                                CLASS_IDENTITY,
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON,
                                List.of(CLASS_TYPE)),
                        RealizationMap.Entry.realized(
                                METHOD_IDENTITY,
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON,
                                List.of(METHOD)),
                        RealizationMap.Entry.notRealized(
                                ARGUMENT_IDENTITY,
                                CompatibilityManifest.Status.SUPPORTED,
                                REASON,
                                NonRealizationReason.ARGUMENT_REALIZED_IN_MEMBER_SIGNATURE
                                        .name())));
    }

    private static GeneratedSurface surface() {
        return GeneratedSurface.of(List.of(CLASS_TYPE, METHOD, STRUCTURAL_BIND));
    }

    private static List<RealizationMap.Entry> replace(
            List<RealizationMap.Entry> entries, RealizationMap.Entry replacement) {
        List<RealizationMap.Entry> result = new ArrayList<>();
        for (RealizationMap.Entry entry : entries) {
            result.add(
                    entry.sourceIdentity().equals(replacement.sourceIdentity())
                            ? replacement
                            : entry);
        }
        return result;
    }
}

package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.CompatibilityManifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Verifies that the generated Java surface is at parity with the vendored engine API.
 *
 * <p>The authority is the vendored compatibility manifest, never a sibling binding: a sibling
 * consumer of the same {@code extension_api.json} has its own idioms, exclusions, and release
 * cadence, so diffing against one would import phantom differences. The oracle therefore compares
 * three artifacts that all derive from the pinned engine API: the vendored manifest, the generated
 * realization map, and the compiled generated surface.
 *
 * <p>It reports four disjoint conditions, each naming the offending source identity:
 *
 * <ol>
 *   <li>{@link Kind#SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER} — an entity classified {@code
 *       supported} realizes no Java member that exists in the compiled surface;
 *   <li>{@link Kind#GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY} — a generated member no source entity
 *       claims and no approved structural surface explains;
 *   <li>{@link Kind#MANIFEST_CLASSIFICATION_DRIFT} — a status, reason code, or covered identity
 *       that disagrees with the vendored manifest;
 *   <li>{@link Kind#UNAPPROVED_NON_REALIZATION_REASON} — a non-realization reason outside the
 *       closed approved vocabulary.
 * </ol>
 */
public final class RealizationOracle {
    /**
     * Generated types that exist to carry binding infrastructure rather than to realize one source
     * entity. Every member they declare is approved; the set is only widened by an explicit,
     * reviewable change.
     */
    private static final Set<String> APPROVED_INFRASTRUCTURE_TYPES =
            Set.of(
                    "games.cafecito.foundry.generated.GeneratedApiProvenance",
                    "games.cafecito.foundry.generated.GeneratedNativeDispatch",
                    "games.cafecito.foundry.generated.GeneratedPublicApi",
                    "games.cafecito.foundry.generated.GeneratedPublicApi.Root",
                    "games.cafecito.foundry.generated.GeneratedRegistration",
                    "games.cafecito.foundry.generated.Utilities",
                    "games.cafecito.foundry.generated.pointers.NativePointers");

    /**
     * Prefix of the generated native dispatch shards. Shard count follows the engine API size, so
     * the shards are approved by prefix rather than by name.
     */
    private static final String APPROVED_NATIVE_DISPATCH_SHARD_PREFIX =
            "games.cafecito.foundry.generated.GeneratedNativeDispatch";

    /**
     * Structural members every generated root declares to be bindable, constructible, or
     * enumerable. They realize the binding contract itself rather than one engine entity, so no
     * source identity claims them. The set is closed and only widened by an explicit, reviewable
     * change.
     */
    private static final Set<String> APPROVED_STRUCTURAL_MEMBERS =
            Set.of(
                    JavaMember.CONSTRUCTOR_MEMBER_NAME,
                    "bind",
                    "byteSize",
                    "close",
                    "create",
                    "fromBridge",
                    "fromValue",
                    "handle",
                    "isNull",
                    "layoutFormat",
                    "memberOffset",
                    "nullValue",
                    "registerObjectType",
                    "value",
                    "valueOf",
                    "values");

    private RealizationOracle() {}

    /** Returns the approved infrastructure types in sorted order. */
    public static List<String> approvedInfrastructureTypes() {
        return List.copyOf(new TreeSet<>(APPROVED_INFRASTRUCTURE_TYPES));
    }

    /** Returns the approved structural member names in sorted order. */
    public static List<String> approvedStructuralMembers() {
        return List.copyOf(new TreeSet<>(APPROVED_STRUCTURAL_MEMBERS));
    }

    /** Verifies the realization map against the vendored manifest and the compiled surface. */
    public static List<Violation> verify(
            RealizationMap map, CompatibilityManifest manifest, GeneratedSurface surface) {
        return verify(map, manifest.entries(), surface);
    }

    /** Verifies the realization map against vendored classifications and the compiled surface. */
    public static List<Violation> verify(
            RealizationMap map,
            List<CompatibilityManifest.Entry> classifications,
            GeneratedSurface surface) {
        List<Violation> violations = new ArrayList<>();
        Map<String, CompatibilityManifest.Entry> manifestEntries = new TreeMap<>();
        for (CompatibilityManifest.Entry entry : classifications) {
            manifestEntries.put(entry.sourceIdentity(), entry);
        }
        Map<JavaMember, String> claims = new LinkedHashMap<>();

        for (RealizationMap.Entry entry : map.entries()) {
            CompatibilityManifest.Entry classified = manifestEntries.get(entry.sourceIdentity());
            if (classified == null) {
                violations.add(
                        new Violation(
                                Kind.MANIFEST_CLASSIFICATION_DRIFT,
                                entry.sourceIdentity(),
                                "a vendored manifest classification",
                                "a realization entry classified "
                                        + entry.status().jsonValue()
                                        + "/"
                                        + entry.reasonCode(),
                                "absent"));
            } else if (classified.status() != entry.status()
                    || !classified.reasonCode().equals(entry.reasonCode())) {
                violations.add(
                        new Violation(
                                Kind.MANIFEST_CLASSIFICATION_DRIFT,
                                entry.sourceIdentity(),
                                classified.status().jsonValue() + "/" + classified.reasonCode(),
                                entry.status().jsonValue() + "/" + entry.reasonCode(),
                                manifestEntry(classified)));
            }
            if (!entry.isRealized()
                    && !NonRealizationReason.isApproved(entry.nonRealizationReason())) {
                violations.add(
                        new Violation(
                                Kind.UNAPPROVED_NON_REALIZATION_REASON,
                                entry.sourceIdentity(),
                                "a reason from the approved vocabulary "
                                        + NonRealizationReason.approved(),
                                entry.nonRealizationReason(),
                                manifestEntry(classified)));
            }
            for (JavaMember member : entry.realizedMembers()) {
                claims.putIfAbsent(member, entry.sourceIdentity());
                if (!surface.contains(member)) {
                    violations.add(
                            new Violation(
                                    Kind.SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER,
                                    entry.sourceIdentity(),
                                    "generated member " + member.render(),
                                    "absent from the compiled generated surface",
                                    manifestEntry(classified)));
                }
            }
            if (entry.isRealized() && entry.status() != CompatibilityManifest.Status.SUPPORTED) {
                violations.add(
                        new Violation(
                                Kind.MANIFEST_CLASSIFICATION_DRIFT,
                                entry.sourceIdentity(),
                                "no realized member for a non-supported classification",
                                "realized " + entry.realizedMembers().get(0).render(),
                                manifestEntry(classified)));
            }
        }

        for (CompatibilityManifest.Entry classified : manifestEntries.values()) {
            RealizationMap.Entry entry = map.entry(classified.sourceIdentity());
            if (entry != null) {
                continue;
            }
            violations.add(
                    new Violation(
                            classified.status() == CompatibilityManifest.Status.SUPPORTED
                                    ? Kind.SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER
                                    : Kind.MANIFEST_CLASSIFICATION_DRIFT,
                            classified.sourceIdentity(),
                            "exactly one realization entry",
                            "absent from the realization map",
                            manifestEntry(classified)));
        }

        for (JavaMember member : surface.members()) {
            if (claims.containsKey(member) || isApprovedStructuralSurface(member)) {
                continue;
            }
            violations.add(
                    new Violation(
                            Kind.GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY,
                            "none",
                            "a claiming source entity or an approved structural surface",
                            "unclaimed generated member " + member.render(),
                            "absent"));
        }

        violations.sort(
                Comparator.comparing((Violation violation) -> violation.kind().name())
                        .thenComparing(Violation::sourceIdentity)
                        .thenComparing(Violation::observed));
        return List.copyOf(violations);
    }

    /**
     * Returns whether a generated member belongs to the approved structural surface that realizes
     * the binding contract instead of one engine entity.
     */
    static boolean isApprovedStructuralSurface(JavaMember member) {
        String topLevelOwner = member.topLevelOwner();
        if (APPROVED_INFRASTRUCTURE_TYPES.contains(topLevelOwner)
                || topLevelOwner.startsWith(APPROVED_NATIVE_DISPATCH_SHARD_PREFIX)) {
            return true;
        }
        return !member.isType() && APPROVED_STRUCTURAL_MEMBERS.contains(member.erasedViewName());
    }

    private static String manifestEntry(CompatibilityManifest.Entry classified) {
        return classified == null
                ? "absent"
                : classified.sourceIdentity()
                        + "="
                        + classified.status().jsonValue()
                        + "/"
                        + classified.reasonCode();
    }

    /** The four disjoint parity failures the oracle reports. */
    public enum Kind {
        SUPPORTED_ENTITY_WITHOUT_REALIZED_MEMBER,
        GENERATED_MEMBER_WITHOUT_SOURCE_ENTITY,
        MANIFEST_CLASSIFICATION_DRIFT,
        UNAPPROVED_NON_REALIZATION_REASON
    }

    /** One parity failure, rendered on a single escaped line. */
    public record Violation(
            Kind kind,
            String sourceIdentity,
            String expected,
            String observed,
            String manifestEntry) {
        /** Renders this failure as one line with controls and backslashes escaped. */
        public String message() {
            return kind.name()
                    + " source-identity="
                    + Diagnostics.escape(sourceIdentity)
                    + " expected="
                    + Diagnostics.escape(expected)
                    + " observed="
                    + Diagnostics.escape(observed)
                    + " manifest-entry="
                    + Diagnostics.escape(manifestEntry);
        }
    }
}

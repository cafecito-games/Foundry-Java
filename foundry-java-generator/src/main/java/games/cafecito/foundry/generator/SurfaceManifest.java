package games.cafecito.foundry.generator;

import games.cafecito.foundry.api.model.ApiInputException;
import games.cafecito.foundry.api.model.CompatibilityManifest;
import games.cafecito.foundry.api.model.JsonParser;
import games.cafecito.foundry.api.model.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The versioned, binding-neutral surface manifest: what this binding realizes from the engine API,
 * expressed in vocabulary every binding of the same engine API can read.
 *
 * <p>The manifest is derived from the {@link RealizationMap} and never synthesized independently.
 * {@link #disagreementsWith(RealizationMap)} re-derives the manifest from the map and reports every
 * difference, so a manifest that drifts from the map fails the build instead of being published.
 *
 * <p>Its neutral portion carries the schema version, the engine API identity and hash, the binding
 * identity and version, the generator and bridge-contract versions, and one entry per {@code
 * source_identity} recording availability, realization state, realized member count, and — when the
 * entity realizes nothing — one {@link NeutralNonRealizationReason}. A consumer that reads only
 * those fields can compute realization coverage and diff two bindings.
 *
 * <p>Java-specific detail — erased signatures, package names, Java type names, and the Java
 * non-realization vocabulary — lives only inside {@code binding_specific} objects that name their
 * own {@code namespace}. Consumers may ignore those objects entirely.
 *
 * <p>{@link #SCHEMA_VERSION} changes only by explicit, reviewable change. A consumer that
 * encounters a {@code schema_version} it does not implement must refuse the manifest rather than
 * interpret it; content inside a {@code binding_specific} object may change without a version bump,
 * because no neutral consumer reads it.
 */
public final class SurfaceManifest {
    /** Version of the binding-neutral schema this producer emits. */
    public static final int SCHEMA_VERSION = 1;

    /** Stable identity of this binding across engine API releases. */
    public static final String BINDING_ID = "foundry-java";

    /** Key of every optional, binding-defined section. */
    public static final String BINDING_SPECIFIC_KEY = "binding_specific";

    /** Key naming the binding that defines a {@link #BINDING_SPECIFIC_KEY} section's content. */
    public static final String NAMESPACE_KEY = "namespace";

    /** Neutral token of an entity a binding realizes. */
    public static final String REALIZED = "realized";

    /** Neutral token of an entity a binding realizes nothing for. */
    public static final String NOT_REALIZED = "not-realized";

    /** Diagnostic prefix of every manifest-versus-map disagreement. */
    public static final String DISAGREEMENT = "SURFACE_MANIFEST_DISAGREES_WITH_REALIZATION_MAP";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Set<String> MANIFEST_KEYS =
            Set.of(
                    "schema_version",
                    "engine_api_version",
                    "engine_api_sha256",
                    "binding_id",
                    "binding_version",
                    "generator_version",
                    "bridge_contract_version",
                    "entries",
                    BINDING_SPECIFIC_KEY);

    private static final Set<String> ENTRY_KEYS =
            Set.of(
                    "source_identity",
                    "availability",
                    "realization",
                    "realized_member_count",
                    "non_realization_reason",
                    BINDING_SPECIFIC_KEY);

    private static final Set<String> MANIFEST_DETAIL_KEYS =
            Set.of(NAMESPACE_KEY, "realization_map_format", "realization_map_sha256");

    private static final Set<String> ENTRY_DETAIL_KEYS =
            Set.of(
                    NAMESPACE_KEY,
                    "compatibility_reason_code",
                    "non_realization_reason",
                    "realized_members");

    private final int schemaVersion;
    private final Provenance provenance;
    private final String realizationMapFormat;
    private final String realizationMapSha256;
    private final Map<String, Entry> entries;

    private SurfaceManifest(
            int schemaVersion,
            Provenance provenance,
            String realizationMapFormat,
            String realizationMapSha256,
            Map<String, Entry> entries) {
        this.schemaVersion = schemaVersion;
        this.provenance = provenance;
        this.realizationMapFormat = realizationMapFormat;
        this.realizationMapSha256 = realizationMapSha256;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(entries)));
    }

    /** Derives the manifest from a realization map. This is the only way to produce one. */
    public static SurfaceManifest from(RealizationMap map, Provenance provenance) {
        if (provenance == null) {
            throw new ApiInputException("Surface manifest requires accepted provenance.");
        }
        Map<String, Entry> derived = new TreeMap<>();
        for (RealizationMap.Entry entry : map.entries()) {
            derived.put(entry.sourceIdentity(), derive(entry));
        }
        return new SurfaceManifest(
                SCHEMA_VERSION, provenance, RealizationMap.FORMAT, map.sha256(), derived);
    }

    /** Restates one map entry in neutral vocabulary, with its Java detail namespaced. */
    private static Entry derive(RealizationMap.Entry entry) {
        NonRealizationReason bindingReason =
                entry.isRealized()
                        ? null
                        : NonRealizationReason.require(entry.nonRealizationReason());
        return new Entry(
                entry.sourceIdentity(),
                entry.status(),
                entry.isRealized(),
                entry.realizedMembers(),
                bindingReason,
                entry.reasonCode());
    }

    /** Returns the schema version of the neutral portion. */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** Returns the engine, binding, and producer identities the manifest carries. */
    public Provenance provenance() {
        return provenance;
    }

    /** Returns the digest of the realization map this manifest was derived from. */
    public String realizationMapSha256() {
        return realizationMapSha256;
    }

    /** Returns every entry ordered by source identity. */
    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Returns the number of covered source identities. */
    public int size() {
        return entries.size();
    }

    /** Returns how many covered source identities this binding realizes at least one member for. */
    public int realizedEntities() {
        int realized = 0;
        for (Entry entry : entries.values()) {
            if (entry.realized()) {
                realized++;
            }
        }
        return realized;
    }

    /** Renders the manifest as canonical JSON with sorted keys and a trailing newline. */
    public String canonicalJson() {
        List<JsonValue> entryValues = new ArrayList<>();
        entries.values().forEach(entry -> entryValues.add(entry.json()));
        Map<String, JsonValue> detail = new LinkedHashMap<>();
        detail.put(NAMESPACE_KEY, new JsonValue.JsonString(BINDING_ID));
        detail.put("realization_map_format", new JsonValue.JsonString(realizationMapFormat));
        detail.put("realization_map_sha256", new JsonValue.JsonString(realizationMapSha256));
        Map<String, JsonValue> values = new LinkedHashMap<>();
        values.put("schema_version", new JsonValue.JsonNumber(Integer.toString(schemaVersion)));
        values.put("engine_api_version", new JsonValue.JsonString(provenance.engineApiVersion()));
        values.put("engine_api_sha256", new JsonValue.JsonString(provenance.engineApiSha256()));
        values.put("binding_id", new JsonValue.JsonString(BINDING_ID));
        values.put("binding_version", new JsonValue.JsonString(provenance.bindingVersion()));
        values.put("generator_version", new JsonValue.JsonString(provenance.generatorVersion()));
        values.put(
                "bridge_contract_version",
                new JsonValue.JsonString(provenance.bridgeContractVersion()));
        values.put(BINDING_SPECIFIC_KEY, new JsonValue.JsonObject(detail));
        values.put("entries", new JsonValue.JsonArray(entryValues));
        return new JsonValue.JsonObject(values).canonicalJson() + "\n";
    }

    /** Returns the SHA-256 of {@link #canonicalJson()}. */
    public String sha256() {
        return sha256(canonicalJson());
    }

    /** Parses a manifest this binding produced, rejecting anything outside the frozen schema. */
    public static SurfaceManifest parse(String json) {
        JsonValue.JsonObject root = JsonParser.parse(json).requireObject("$");
        requireKnownKeys(root, MANIFEST_KEYS, "$");
        int schemaVersion = root.require("schema_version", "$").requireInt("$.schema_version");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new ApiInputException(
                    "$.schema_version "
                            + schemaVersion
                            + " is not the emitted schema_version "
                            + SCHEMA_VERSION
                            + ".");
        }
        String bindingId = root.require("binding_id", "$").requireString("$.binding_id");
        if (!bindingId.equals(BINDING_ID)) {
            throw new ApiInputException(
                    "$.binding_id must be "
                            + BINDING_ID
                            + "; a sibling binding's manifest is read through its neutral portion, "
                            + "not through this producer.");
        }
        Provenance provenance =
                new Provenance(
                        root.require("engine_api_version", "$")
                                .requireString("$.engine_api_version"),
                        root.require("engine_api_sha256", "$").requireString("$.engine_api_sha256"),
                        root.require("binding_version", "$").requireString("$.binding_version"),
                        root.require("generator_version", "$").requireString("$.generator_version"),
                        root.require("bridge_contract_version", "$")
                                .requireString("$.bridge_contract_version"));
        JsonValue.JsonObject detail =
                root.require(BINDING_SPECIFIC_KEY, "$").requireObject("$." + BINDING_SPECIFIC_KEY);
        requireKnownKeys(detail, MANIFEST_DETAIL_KEYS, "$." + BINDING_SPECIFIC_KEY);
        requireNamespace(detail, "$." + BINDING_SPECIFIC_KEY);
        String realizationMapFormat =
                detail.require("realization_map_format", "$." + BINDING_SPECIFIC_KEY)
                        .requireString("$." + BINDING_SPECIFIC_KEY + ".realization_map_format");
        String realizationMapSha256 =
                requireSha256(
                        detail.require("realization_map_sha256", "$." + BINDING_SPECIFIC_KEY)
                                .requireString(
                                        "$." + BINDING_SPECIFIC_KEY + ".realization_map_sha256"),
                        "$." + BINDING_SPECIFIC_KEY + ".realization_map_sha256");
        List<JsonValue> entryValues =
                root.require("entries", "$").requireArray("$.entries").values();
        Map<String, Entry> entries = new TreeMap<>();
        for (int index = 0; index < entryValues.size(); index++) {
            String path = "$.entries[" + index + "]";
            Entry entry = Entry.parse(entryValues.get(index).requireObject(path), path);
            if (entries.put(entry.sourceIdentity(), entry) != null) {
                throw new ApiInputException(
                        "$.entries covers source identity twice: "
                                + Diagnostics.escape(entry.sourceIdentity())
                                + ".");
            }
        }
        return new SurfaceManifest(
                schemaVersion, provenance, realizationMapFormat, realizationMapSha256, entries);
    }

    /**
     * Re-derives the manifest from {@code map} and reports every difference, ordered by source
     * identity. An empty result proves the manifest is the map, restated in neutral vocabulary.
     *
     * <p>Derivation happens one entry at a time. Deriving a whole second manifest to compare against
     * would double the resident cost of a document that covers every engine-API entity.
     */
    public List<String> disagreementsWith(RealizationMap map) {
        List<String> disagreements = new ArrayList<>();
        String expectedMapSha256 = map.sha256();
        if (!expectedMapSha256.equals(realizationMapSha256)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_sha256 expected="
                            + Diagnostics.escape(expectedMapSha256)
                            + " observed="
                            + Diagnostics.escape(realizationMapSha256));
        }
        if (!RealizationMap.FORMAT.equals(realizationMapFormat)) {
            disagreements.add(
                    DISAGREEMENT
                            + " field=realization_map_format expected="
                            + Diagnostics.escape(RealizationMap.FORMAT)
                            + " observed="
                            + Diagnostics.escape(realizationMapFormat));
        }
        int covered = 0;
        for (RealizationMap.Entry mapEntry : map.entries()) {
            Entry expectedEntry = derive(mapEntry);
            Entry observed = entries.get(expectedEntry.sourceIdentity());
            if (observed == null) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(), expectedEntry.render(), "absent"));
                continue;
            }
            covered++;
            if (!observed.equals(expectedEntry)) {
                disagreements.add(
                        disagreement(
                                expectedEntry.sourceIdentity(),
                                expectedEntry.render(),
                                observed.render()));
            }
        }
        if (covered != entries.size()) {
            for (Entry observed : entries.values()) {
                if (map.entry(observed.sourceIdentity()) == null) {
                    disagreements.add(
                            disagreement(observed.sourceIdentity(), "absent", observed.render()));
                }
            }
        }
        return List.copyOf(disagreements);
    }

    private static String disagreement(String sourceIdentity, String expected, String observed) {
        return DISAGREEMENT
                + " source-identity="
                + Diagnostics.escape(sourceIdentity)
                + " expected="
                + Diagnostics.escape(expected)
                + " observed="
                + Diagnostics.escape(observed);
    }

    private static void requireKnownKeys(
            JsonValue.JsonObject object, Set<String> known, String path) {
        for (String key : object.values().keySet()) {
            if (!known.contains(key)) {
                throw new ApiInputException(
                        path + " carries an unknown field " + Diagnostics.escape(key) + ".");
            }
        }
    }

    private static void requireNamespace(JsonValue.JsonObject detail, String path) {
        String namespace =
                detail.require(NAMESPACE_KEY, path).requireString(path + "." + NAMESPACE_KEY);
        if (!namespace.equals(BINDING_ID)) {
            throw new ApiInputException(
                    path
                            + "."
                            + NAMESPACE_KEY
                            + " must be "
                            + BINDING_ID
                            + ", got "
                            + Diagnostics.escape(namespace)
                            + ".");
        }
    }

    private static String requireSha256(String value, String path) {
        if (!SHA256.matcher(value).matches()) {
            throw new ApiInputException(path + " must contain 64 lowercase hexadecimal digits.");
        }
        return value;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime provides SHA-256.", exception);
        }
    }

    /** The engine, binding, and producer identities every manifest carries. */
    public record Provenance(
            String engineApiVersion,
            String engineApiSha256,
            String bindingVersion,
            String generatorVersion,
            String bridgeContractVersion) {
        public Provenance {
            requireText(engineApiVersion, "engine API version");
            requireText(bindingVersion, "binding version");
            requireText(generatorVersion, "generator version");
            requireText(bridgeContractVersion, "bridge contract version");
            requireSha256(engineApiSha256 == null ? "" : engineApiSha256, "$.engine_api_sha256");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new ApiInputException("Surface manifest requires a " + field + ".");
            }
        }
    }

    /** One source identity, restated in neutral vocabulary with its Java detail namespaced. */
    public record Entry(
            String sourceIdentity,
            CompatibilityManifest.Status availability,
            boolean realized,
            List<JavaMember> realizedMembers,
            NonRealizationReason bindingReason,
            String compatibilityReasonCode) {
        public Entry {
            if (sourceIdentity == null || sourceIdentity.isBlank()) {
                throw new ApiInputException("Surface manifest entry requires a source identity.");
            }
            if (availability == null) {
                throw new ApiInputException(
                        "Surface manifest entry requires availability: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
            if (compatibilityReasonCode == null || compatibilityReasonCode.isBlank()) {
                throw new ApiInputException(
                        "Surface manifest entry requires a compatibility reason code: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
            realizedMembers = List.copyOf(realizedMembers);
            if (realized == realizedMembers.isEmpty() || realized == (bindingReason != null)) {
                throw new ApiInputException(
                        "Surface manifest entry must realize members or carry exactly one "
                                + "non-realization reason: "
                                + Diagnostics.escape(sourceIdentity)
                                + ".");
            }
        }

        /** Returns the binding-neutral reason this entity realizes nothing, or {@code null}. */
        public NeutralNonRealizationReason neutralReason() {
            return bindingReason == null ? null : NeutralNonRealizationReason.of(bindingReason);
        }

        /** Returns the neutral realization token. */
        public String realization() {
            return realized ? REALIZED : NOT_REALIZED;
        }

        /** Renders this entry as one stable diagnostic fragment. */
        public String render() {
            StringBuilder rendered =
                    new StringBuilder(availability.jsonValue())
                            .append('|')
                            .append(realization())
                            .append('|')
                            .append(realized ? "-" : neutralReason().name())
                            .append('|')
                            .append(compatibilityReasonCode)
                            .append('|');
            if (realized) {
                for (int index = 0; index < realizedMembers.size(); index++) {
                    if (index > 0) {
                        rendered.append(';');
                    }
                    rendered.append(realizedMembers.get(index).render());
                }
            } else {
                rendered.append(bindingReason.name());
            }
            return rendered.toString();
        }

        JsonValue.JsonObject json() {
            Map<String, JsonValue> detail = new LinkedHashMap<>();
            detail.put(NAMESPACE_KEY, new JsonValue.JsonString(BINDING_ID));
            detail.put(
                    "compatibility_reason_code", new JsonValue.JsonString(compatibilityReasonCode));
            Map<String, JsonValue> values = new LinkedHashMap<>();
            values.put("source_identity", new JsonValue.JsonString(sourceIdentity));
            values.put("availability", new JsonValue.JsonString(availability.jsonValue()));
            values.put("realization", new JsonValue.JsonString(realization()));
            values.put(
                    "realized_member_count",
                    new JsonValue.JsonNumber(Integer.toString(realizedMembers.size())));
            if (realized) {
                List<JsonValue> members = new ArrayList<>();
                realizedMembers.forEach(
                        member -> members.add(new JsonValue.JsonString(member.render())));
                detail.put("realized_members", new JsonValue.JsonArray(members));
            } else {
                values.put(
                        "non_realization_reason", new JsonValue.JsonString(neutralReason().name()));
                detail.put(
                        "non_realization_reason", new JsonValue.JsonString(bindingReason.name()));
            }
            values.put(BINDING_SPECIFIC_KEY, new JsonValue.JsonObject(detail));
            return new JsonValue.JsonObject(values);
        }

        static Entry parse(JsonValue.JsonObject object, String path) {
            requireKnownKeys(object, ENTRY_KEYS, path);
            String sourceIdentity =
                    object.require("source_identity", path)
                            .requireString(path + ".source_identity");
            CompatibilityManifest.Status availability =
                    availability(
                            object.require("availability", path)
                                    .requireString(path + ".availability"),
                            path);
            String realization =
                    object.require("realization", path).requireString(path + ".realization");
            boolean realized =
                    switch (realization) {
                        case REALIZED -> true;
                        case NOT_REALIZED -> false;
                        default ->
                                throw new ApiInputException(
                                        path
                                                + ".realization is outside the neutral vocabulary: "
                                                + Diagnostics.escape(realization)
                                                + ".");
                    };
            int memberCount =
                    object.require("realized_member_count", path)
                            .requireInt(path + ".realized_member_count");
            JsonValue.JsonObject detail =
                    object.require(BINDING_SPECIFIC_KEY, path)
                            .requireObject(path + "." + BINDING_SPECIFIC_KEY);
            requireKnownKeys(detail, ENTRY_DETAIL_KEYS, path + "." + BINDING_SPECIFIC_KEY);
            requireNamespace(detail, path + "." + BINDING_SPECIFIC_KEY);
            String compatibilityReasonCode =
                    detail.require("compatibility_reason_code", path + "." + BINDING_SPECIFIC_KEY)
                            .requireString(
                                    path
                                            + "."
                                            + BINDING_SPECIFIC_KEY
                                            + ".compatibility_reason_code");
            List<JavaMember> members = new ArrayList<>();
            NonRealizationReason bindingReason = null;
            if (realized) {
                if (object.optional("non_realization_reason") != null) {
                    throw new ApiInputException(
                            path + ".non_realization_reason must be absent for a realized entity.");
                }
                if (detail.optional("non_realization_reason") != null) {
                    throw new ApiInputException(
                            path
                                    + "."
                                    + BINDING_SPECIFIC_KEY
                                    + ".non_realization_reason must be absent for a realized"
                                    + " entity.");
                }
                for (JsonValue member :
                        detail.require("realized_members", path + "." + BINDING_SPECIFIC_KEY)
                                .requireArray(
                                        path + "." + BINDING_SPECIFIC_KEY + ".realized_members")
                                .values()) {
                    members.add(
                            JavaMember.parse(
                                    member.requireString(
                                            path
                                                    + "."
                                                    + BINDING_SPECIFIC_KEY
                                                    + ".realized_members[]")));
                }
            } else {
                if (detail.optional("realized_members") != null) {
                    throw new ApiInputException(
                            path
                                    + "."
                                    + BINDING_SPECIFIC_KEY
                                    + ".realized_members must be absent for an entity that realizes"
                                    + " nothing.");
                }
                NeutralNonRealizationReason neutral =
                        NeutralNonRealizationReason.require(
                                object.require("non_realization_reason", path)
                                        .requireString(path + ".non_realization_reason"));
                bindingReason =
                        NonRealizationReason.require(
                                detail.require(
                                                "non_realization_reason",
                                                path + "." + BINDING_SPECIFIC_KEY)
                                        .requireString(
                                                path
                                                        + "."
                                                        + BINDING_SPECIFIC_KEY
                                                        + ".non_realization_reason"));
                if (NeutralNonRealizationReason.of(bindingReason) != neutral) {
                    throw new ApiInputException(
                            path
                                    + ".non_realization_reason "
                                    + neutral
                                    + " is not the neutral meaning of "
                                    + bindingReason
                                    + ".");
                }
            }
            if (memberCount != members.size()) {
                throw new ApiInputException(
                        path
                                + ".realized_member_count "
                                + memberCount
                                + " disagrees with the realized surface it declares.");
            }
            return new Entry(
                    sourceIdentity,
                    availability,
                    realized,
                    members,
                    bindingReason,
                    compatibilityReasonCode);
        }

        private static CompatibilityManifest.Status availability(String jsonValue, String path) {
            for (CompatibilityManifest.Status candidate : CompatibilityManifest.Status.values()) {
                if (candidate.jsonValue().equals(jsonValue)) {
                    return candidate;
                }
            }
            throw new ApiInputException(
                    path
                            + ".availability is outside the neutral vocabulary: "
                            + Diagnostics.escape(jsonValue)
                            + ".");
        }
    }
}

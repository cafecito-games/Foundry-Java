package games.cafecito.foundry.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Exhaustive stable classification of every parsed Foundry API source identity. */
public final class CompatibilityManifest {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<String> ROOT_KEYS =
            Set.of(
                    "schema_version",
                    "api_sha256",
                    "generator_version",
                    "bridge_contract_version",
                    "entries");
    private static final Set<String> ENTRY_KEYS =
            Set.of("source_identity", "status", "reason_code");

    private final String apiSha256;
    private final String generatorVersion;
    private final String bridgeContractVersion;
    private final List<Entry> entries;

    private CompatibilityManifest(
            String apiSha256,
            String generatorVersion,
            String bridgeContractVersion,
            List<Entry> entries) {
        this.apiSha256 = apiSha256;
        this.generatorVersion = generatorVersion;
        this.bridgeContractVersion = bridgeContractVersion;
        this.entries = List.copyOf(entries);
    }

    public static CompatibilityManifest create(
            FoundryApi api,
            String apiSha256,
            String generatorVersion,
            String bridgeContractVersion,
            Map<String, Classification> classifications) {
        requireMetadata(apiSha256, generatorVersion, bridgeContractVersion);
        Map<String, FoundryApi.Entity> sourceByIdentity =
                api.entities().stream()
                        .collect(
                                Collectors.toMap(
                                        FoundryApi.Entity::identity,
                                        Function.identity(),
                                        (left, right) -> {
                                            throw new ApiInputException(
                                                    "Duplicate parsed identity "
                                                            + left.identity()
                                                            + ".");
                                        },
                                        LinkedHashMap::new));
        Set<String> missing = new HashSet<>(sourceByIdentity.keySet());
        missing.removeAll(classifications.keySet());
        if (!missing.isEmpty()) {
            throw new ApiInputException(
                    "Unclassified source identities: " + sortedSample(missing) + ".");
        }
        Set<String> extra = new HashSet<>(classifications.keySet());
        extra.removeAll(sourceByIdentity.keySet());
        if (!extra.isEmpty()) {
            throw new ApiInputException(
                    "Unknown classified identities: " + sortedSample(extra) + ".");
        }

        List<Entry> entries = new ArrayList<>();
        for (String identity : sourceByIdentity.keySet().stream().sorted().toList()) {
            Classification classification = classifications.get(identity);
            requireClassification(identity, classification);
            entries.add(new Entry(identity, classification.status(), classification.reasonCode()));
        }
        return new CompatibilityManifest(
                apiSha256, generatorVersion, bridgeContractVersion, entries);
    }

    public static CompatibilityManifest parse(FoundryApi api, String json) {
        JsonValue.JsonObject root = JsonParser.parse(json).requireObject("$");
        requireExactKeys(root, ROOT_KEYS, "$");
        int schema = root.require("schema_version", "$").requireInt("$.schema_version");
        if (schema != 1) {
            throw new ApiInputException("$.schema_version must be 1.");
        }
        String apiSha256 = requiredString(root, "api_sha256", "$");
        String generatorVersion = requiredString(root, "generator_version", "$");
        String bridgeContractVersion = requiredString(root, "bridge_contract_version", "$");
        JsonValue.JsonArray entryValues = root.require("entries", "$").requireArray("$.entries");
        Map<String, Classification> classifications = new LinkedHashMap<>();
        for (int index = 0; index < entryValues.values().size(); index++) {
            String path = "$.entries[" + index + "]";
            JsonValue.JsonObject object = entryValues.values().get(index).requireObject(path);
            requireExactKeys(object, ENTRY_KEYS, path);
            String identity = requiredString(object, "source_identity", path);
            String statusValue = requiredString(object, "status", path);
            Status status = Status.fromJson(statusValue, path + ".status");
            String reason = requiredString(object, "reason_code", path);
            if (classifications.putIfAbsent(identity, new Classification(status, reason)) != null) {
                throw new ApiInputException(
                        "Duplicate compatibility identity " + identity + " at " + path + ".");
            }
        }
        return create(api, apiSha256, generatorVersion, bridgeContractVersion, classifications);
    }

    public static CompatibilityManifest parse(FoundryApi api, ApiInputs inputs) {
        CompatibilityManifest manifest = parse(api, inputs.compatibilityManifestJson());
        requireVerifiedMetadata("api_sha256", manifest.apiSha256(), inputs.extensionApiSha256());
        requireVerifiedMetadata(
                "generator_version",
                manifest.generatorVersion(),
                inputs.provenance().generatorVersion());
        requireVerifiedMetadata(
                "bridge_contract_version",
                manifest.bridgeContractVersion(),
                inputs.provenance().bridgeContractVersion());
        return manifest;
    }

    public String apiSha256() {
        return apiSha256;
    }

    public String generatorVersion() {
        return generatorVersion;
    }

    public String bridgeContractVersion() {
        return bridgeContractVersion;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Map<Status, Integer> statusCounts() {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Entry entry : entries) {
            counts.merge(entry.status(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    public String canonicalJson() {
        List<JsonValue> entryValues = new ArrayList<>();
        for (Entry entry : entries) {
            entryValues.add(
                    new JsonValue.JsonObject(
                            Map.of(
                                    "source_identity",
                                    new JsonValue.JsonString(entry.sourceIdentity()),
                                    "status",
                                    new JsonValue.JsonString(entry.status().jsonValue()),
                                    "reason_code",
                                    new JsonValue.JsonString(entry.reasonCode()))));
        }
        return new JsonValue.JsonObject(
                                Map.of(
                                        "schema_version",
                                        new JsonValue.JsonNumber("1"),
                                        "api_sha256",
                                        new JsonValue.JsonString(apiSha256),
                                        "generator_version",
                                        new JsonValue.JsonString(generatorVersion),
                                        "bridge_contract_version",
                                        new JsonValue.JsonString(bridgeContractVersion),
                                        "entries",
                                        new JsonValue.JsonArray(entryValues)))
                        .canonicalJson()
                + "\n";
    }

    private static void requireMetadata(
            String apiSha256, String generatorVersion, String bridgeContractVersion) {
        if (!SHA256.matcher(apiSha256).matches()) {
            throw new ApiInputException(
                    "Compatibility API hash must contain 64 lowercase hexadecimal digits.");
        }
        if (generatorVersion == null || generatorVersion.isBlank()) {
            throw new ApiInputException("Generator version must not be blank.");
        }
        if (bridgeContractVersion == null || bridgeContractVersion.isBlank()) {
            throw new ApiInputException("Bridge contract version must not be blank.");
        }
    }

    private static void requireVerifiedMetadata(
            String field, String manifestValue, String verifiedValue) {
        if (!manifestValue.equals(verifiedValue)) {
            throw new ApiInputException(
                    "$."
                            + field
                            + " does not match verified provenance: expected "
                            + verifiedValue
                            + ", got "
                            + manifestValue
                            + ".");
        }
    }

    private static void requireClassification(String identity, Classification classification) {
        if (classification == null || classification.status() == null) {
            throw new ApiInputException(identity + " must have an approved compatibility status.");
        }
        if (classification.reasonCode() == null
                || !REASON_CODE.matcher(classification.reasonCode()).matches()) {
            throw new ApiInputException(identity + " must have a stable uppercase reason code.");
        }
    }

    private static String sortedSample(Set<String> values) {
        List<String> sorted = values.stream().sorted().toList();
        int end = Math.min(sorted.size(), 10);
        String suffix = sorted.size() > end ? " (and " + (sorted.size() - end) + " more)" : "";
        return sorted.subList(0, end) + suffix;
    }

    private static void requireExactKeys(
            JsonValue.JsonObject object, Set<String> accepted, String path) {
        for (String key : object.values().keySet()) {
            if (!accepted.contains(key)) {
                throw new ApiInputException("Unknown construct at " + path + "." + key + ".");
            }
        }
        for (String key : accepted) {
            if (!object.values().containsKey(key)) {
                throw new ApiInputException(path + "." + key + " is required.");
            }
        }
    }

    private static String requiredString(
            JsonValue.JsonObject object, String key, String parentPath) {
        String path = parentPath + "." + key;
        String value = object.require(key, parentPath).requireString(path);
        if (value.isBlank()) {
            throw new ApiInputException(path + " must not be blank.");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiInputException(path + " must not contain control characters.");
        }
        return value;
    }

    public enum Status {
        SUPPORTED("supported"),
        EXCLUDED_LANGUAGE("excluded-language"),
        EXCLUDED_PLATFORM("excluded-platform"),
        EXCLUDED_UPSTREAM("excluded-upstream");

        private final String jsonValue;

        Status(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        public String jsonValue() {
            return jsonValue;
        }

        static Status fromJson(String value, String path) {
            for (Status status : values()) {
                if (status.jsonValue.equals(value)) {
                    return status;
                }
            }
            throw new ApiInputException(
                    path + " contains unknown compatibility status '" + value + "'.");
        }
    }

    public record Classification(Status status, String reasonCode) {}

    public record Entry(String sourceIdentity, Status status, String reasonCode) {}
}

package games.cafecito.foundry.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fixture consumer that understands only the binding-neutral portion of a surface manifest.
 *
 * <p>It deliberately shares no code with the producer: it carries its own JSON reader, knows only
 * the neutral key sets and the neutral realization vocabulary, and never interprets the content of
 * any {@code binding_specific} section. Binding-specific content is accepted only when it is an
 * object that names its own namespace; anything else, and any key outside the neutral sets, is
 * rejected. It exists to prove that a foreign binding can compute realization coverage and diff two
 * bindings from the neutral portion alone.
 */
final class NeutralSurfaceManifestConsumer {
    /** The single schema version this consumer implements. */
    static final int UNDERSTOOD_SCHEMA_VERSION = 1;

    private static final String BINDING_SPECIFIC = "binding_specific";
    private static final String NAMESPACE = "namespace";
    private static final String REALIZED = "realized";
    private static final String NOT_REALIZED = "not-realized";

    private static final Set<String> NEUTRAL_MANIFEST_KEYS =
            Set.of(
                    "schema_version",
                    "engine_api_version",
                    "engine_api_sha256",
                    "binding_id",
                    "binding_version",
                    "generator_version",
                    "bridge_contract_version",
                    "entries");

    private static final Set<String> NEUTRAL_ENTRY_KEYS =
            Set.of(
                    "source_identity",
                    "availability",
                    "realization",
                    "realized_member_count",
                    "non_realization_reason");

    private static final Set<String> NEUTRAL_AVAILABILITY =
            Set.of("supported", "excluded-language", "excluded-platform", "excluded-upstream");

    private static final Set<String> NEUTRAL_REASONS =
            Set.of(
                    "SUBSUMED_BY_ENCLOSING_SIGNATURE",
                    "SUBSUMED_BY_ENCLOSING_TYPE_ARGUMENT",
                    "SERVED_BY_ENGINE_ACCESSOR",
                    "SERVED_BY_LAYOUT_QUERY_API");

    private final String bindingId;
    private final String engineApiSha256;
    private final Map<String, Boolean> realizationBySourceIdentity;
    private final Map<String, Integer> nonRealizationReasonCounts;

    private NeutralSurfaceManifestConsumer(
            String bindingId,
            String engineApiSha256,
            Map<String, Boolean> realizationBySourceIdentity,
            Map<String, Integer> nonRealizationReasonCounts) {
        this.bindingId = bindingId;
        this.engineApiSha256 = engineApiSha256;
        this.realizationBySourceIdentity = Map.copyOf(realizationBySourceIdentity);
        this.nonRealizationReasonCounts = Map.copyOf(nonRealizationReasonCounts);
    }

    /** Reads a manifest, rejecting anything this consumer does not understand. */
    static NeutralSurfaceManifestConsumer read(String json) {
        Map<String, Object> manifest = object(Json.parse(json), "$");
        for (String key : manifest.keySet()) {
            if (key.equals(BINDING_SPECIFIC)) {
                requireNamespacedBindingSection(manifest.get(key), "$." + BINDING_SPECIFIC);
                continue;
            }
            if (!NEUTRAL_MANIFEST_KEYS.contains(key)) {
                throw new IllegalArgumentException("$ carries an unknown neutral field " + key);
            }
        }
        int schemaVersion = integer(manifest.get("schema_version"), "$.schema_version");
        if (schemaVersion != UNDERSTOOD_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "$.schema_version "
                            + schemaVersion
                            + " is not the understood schema version "
                            + UNDERSTOOD_SCHEMA_VERSION);
        }
        Map<String, Boolean> realization = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        List<Object> entries = array(manifest.get("entries"), "$.entries");
        for (int index = 0; index < entries.size(); index++) {
            String path = "$.entries[" + index + "]";
            Map<String, Object> entry = object(entries.get(index), path);
            for (String key : entry.keySet()) {
                if (key.equals(BINDING_SPECIFIC)) {
                    requireNamespacedBindingSection(entry.get(key), path + "." + BINDING_SPECIFIC);
                    continue;
                }
                if (!NEUTRAL_ENTRY_KEYS.contains(key)) {
                    throw new IllegalArgumentException(
                            path + " carries an unknown neutral field " + key);
                }
            }
            String sourceIdentity = string(entry.get("source_identity"), path + ".source_identity");
            String availability = string(entry.get("availability"), path + ".availability");
            if (!NEUTRAL_AVAILABILITY.contains(availability)) {
                throw new IllegalArgumentException(
                        path + ".availability is outside the neutral vocabulary: " + availability);
            }
            String state = string(entry.get("realization"), path + ".realization");
            boolean realized =
                    switch (state) {
                        case REALIZED -> true;
                        case NOT_REALIZED -> false;
                        default ->
                                throw new IllegalArgumentException(
                                        path
                                                + ".realization is outside the neutral vocabulary: "
                                                + state);
                    };
            if (realization.put(sourceIdentity, realized) != null) {
                throw new IllegalArgumentException(
                        "$ covers source identity twice: " + sourceIdentity);
            }
            if (!realized) {
                String reason =
                        string(
                                entry.get("non_realization_reason"),
                                path + ".non_realization_reason");
                if (!NEUTRAL_REASONS.contains(reason)) {
                    throw new IllegalArgumentException(
                            path
                                    + ".non_realization_reason is outside the neutral vocabulary: "
                                    + reason);
                }
                reasons.merge(reason, 1, Integer::sum);
            }
        }
        return new NeutralSurfaceManifestConsumer(
                string(manifest.get("binding_id"), "$.binding_id"),
                string(manifest.get("engine_api_sha256"), "$.engine_api_sha256"),
                realization,
                reasons);
    }

    /** Returns the coverage this binding reports, computed from neutral fields alone. */
    Coverage coverage() {
        int realized = 0;
        for (boolean state : realizationBySourceIdentity.values()) {
            if (state) {
                realized++;
            }
        }
        return new Coverage(
                bindingId,
                engineApiSha256,
                realizationBySourceIdentity.size(),
                realized,
                nonRealizationReasonCounts);
    }

    /** Diffs two bindings over the same engine API from neutral fields alone. */
    Diff diff(NeutralSurfaceManifestConsumer other) {
        if (!engineApiSha256.equals(other.engineApiSha256)) {
            throw new IllegalArgumentException(
                    "Bindings describe different engine API hashes: "
                            + engineApiSha256
                            + " and "
                            + other.engineApiSha256);
        }
        List<String> realizedOnlyHere = new ArrayList<>();
        List<String> realizedOnlyThere = new ArrayList<>();
        List<String> coveredOnlyHere = new ArrayList<>();
        List<String> coveredOnlyThere = new ArrayList<>();
        for (var entry : realizationBySourceIdentity.entrySet()) {
            Boolean theirs = other.realizationBySourceIdentity.get(entry.getKey());
            if (theirs == null) {
                coveredOnlyHere.add(entry.getKey());
            } else if (entry.getValue() && !theirs) {
                realizedOnlyHere.add(entry.getKey());
            } else if (!entry.getValue() && theirs) {
                realizedOnlyThere.add(entry.getKey());
            }
        }
        for (String identity : other.realizationBySourceIdentity.keySet()) {
            if (!realizationBySourceIdentity.containsKey(identity)) {
                coveredOnlyThere.add(identity);
            }
        }
        return new Diff(
                List.copyOf(realizedOnlyHere),
                List.copyOf(realizedOnlyThere),
                List.copyOf(coveredOnlyHere),
                List.copyOf(coveredOnlyThere));
    }

    private static void requireNamespacedBindingSection(Object value, String path) {
        Map<String, Object> section = object(value, path);
        if (!(section.get(NAMESPACE) instanceof String namespace) || namespace.isBlank()) {
            throw new IllegalArgumentException(
                    path + " must name the namespace that defines its content");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(path + " must be a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String path) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException(path + " must be a JSON array");
    }

    private static String string(Object value, String path) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(path + " must be a JSON string");
    }

    private static int integer(Object value, String path) {
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        throw new IllegalArgumentException(path + " must be a JSON integer");
    }

    /** Realization coverage a neutral consumer can compute without binding-specific fields. */
    record Coverage(
            String bindingId,
            String engineApiSha256,
            int coveredEntities,
            int realizedEntities,
            Map<String, Integer> nonRealizationReasonCounts) {}

    /** The neutral difference between two bindings over one engine API. */
    record Diff(
            List<String> realizedOnlyInLeft,
            List<String> realizedOnlyInRight,
            List<String> coveredOnlyInLeft,
            List<String> coveredOnlyInRight) {}

    /** A minimal JSON reader so the consumer shares no parsing code with the producer. */
    private static final class Json {
        private final String source;
        private int cursor;

        private Json(String source) {
            this.source = source;
        }

        static Object parse(String source) {
            Json json = new Json(source);
            json.skipWhitespace();
            Object value = json.readValue();
            json.skipWhitespace();
            if (json.cursor != source.length()) {
                throw new IllegalArgumentException("Trailing JSON content at " + json.cursor);
            }
            return value;
        }

        private Object readValue() {
            char character = peek();
            return switch (character) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                cursor++;
                return values;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (values.put(key, readValue()) != null) {
                    throw new IllegalArgumentException("Duplicate JSON key " + key);
                }
                skipWhitespace();
                char character = peek();
                cursor++;
                if (character == '}') {
                    return values;
                }
                if (character != ',') {
                    throw new IllegalArgumentException("Malformed JSON object at " + cursor);
                }
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                cursor++;
                return values;
            }
            while (true) {
                skipWhitespace();
                values.add(readValue());
                skipWhitespace();
                char character = peek();
                cursor++;
                if (character == ']') {
                    return values;
                }
                if (character != ',') {
                    throw new IllegalArgumentException("Malformed JSON array at " + cursor);
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder text = new StringBuilder();
            while (true) {
                char character = source.charAt(cursor++);
                if (character == '"') {
                    return text.toString();
                }
                if (character != '\\') {
                    text.append(character);
                    continue;
                }
                char escape = source.charAt(cursor++);
                switch (escape) {
                    case '"', '\\', '/' -> text.append(escape);
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'u' -> {
                        text.append(
                                (char) Integer.parseInt(source.substring(cursor, cursor + 4), 16));
                        cursor += 4;
                    }
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown JSON escape \\" + escape + " at " + cursor);
                }
            }
        }

        private Object readNumber() {
            int start = cursor;
            while (cursor < source.length()
                    && "+-.eE0123456789".indexOf(source.charAt(cursor)) >= 0) {
                cursor++;
            }
            String lexeme = source.substring(start, cursor);
            try {
                return Long.parseLong(lexeme);
            } catch (NumberFormatException exception) {
                return Double.parseDouble(lexeme);
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!source.startsWith(literal, cursor)) {
                throw new IllegalArgumentException("Malformed JSON literal at " + cursor);
            }
            cursor += literal.length();
            return value;
        }

        private char peek() {
            if (cursor >= source.length()) {
                throw new IllegalArgumentException("Truncated JSON at " + cursor);
            }
            return source.charAt(cursor);
        }

        private void expect(char character) {
            if (peek() != character) {
                throw new IllegalArgumentException(
                        "Expected " + character + " at " + cursor + " in JSON");
            }
            cursor++;
        }

        private void skipWhitespace() {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
        }
    }
}

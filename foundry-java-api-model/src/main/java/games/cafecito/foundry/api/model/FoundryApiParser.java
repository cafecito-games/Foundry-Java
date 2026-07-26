package games.cafecito.foundry.api.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict schema parser for the complete Foundry-Swift-compatible extension API categories. */
public final class FoundryApiParser {
    private static final List<String> CATEGORY_ORDER =
            List.of(
                    "builtin_class_sizes",
                    "builtin_class_member_offsets",
                    "global_constants",
                    "global_enums",
                    "utility_functions",
                    "builtin_classes",
                    "classes",
                    "singletons",
                    "native_structures");
    private static final Set<String> ROOT_KEYS =
            Set.of(
                    "header",
                    "builtin_class_sizes",
                    "builtin_class_member_offsets",
                    "global_constants",
                    "global_enums",
                    "utility_functions",
                    "builtin_classes",
                    "classes",
                    "singletons",
                    "native_structures");
    private static final Set<String> META_VALUES =
            Set.of(
                    "Basis",
                    "Vector2",
                    "Vector2i",
                    "Vector3",
                    "Vector4",
                    "char32",
                    "double",
                    "float",
                    "int8",
                    "int16",
                    "int32",
                    "int64",
                    "required",
                    "uint8",
                    "uint16",
                    "uint32",
                    "uint64");
    private static final Pattern SIGNED_INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Pattern UNSIGNED_INTEGER = Pattern.compile("(?:0|[1-9][0-9]*)");
    private static final BigInteger MAX_UNSIGNED_64 = new BigInteger("18446744073709551615");
    private static final BigInteger MAX_SIGNED_64 = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MAX_SIGNED_32 = BigInteger.valueOf(Integer.MAX_VALUE);

    private FoundryApiParser() {}

    public static FoundryApi parse(ApiInputs inputs) {
        FoundryApi api = parse(inputs.extensionApiJson());
        String declaredVersion = inputs.provenance().apiVersion();
        if (!api.header().apiVersion().equals(declaredVersion)) {
            throw new ApiInputException(
                    "$.api_version "
                            + declaredVersion
                            + " does not match parsed API header "
                            + api.header().apiVersion()
                            + ".");
        }
        return api;
    }

    public static FoundryApi parse(String json) {
        JsonValue.JsonObject root = requireObject(JsonParser.parse(json), "$", "<api>");
        requireExactKeys(root, ROOT_KEYS, ROOT_KEYS, "$", "<api>");
        JsonValue.JsonObject headerObject =
                requireObject(require(root, "header", "$", "<api>"), "$.header", "<api>");
        validateHeader(headerObject);
        FoundryApi.Header header =
                new FoundryApi.Header(
                        integer(headerObject, "version_major", "$.header"),
                        integer(headerObject, "version_minor", "$.header"),
                        integer(headerObject, "version_patch", "$.header"),
                        string(headerObject, "version_status", "$.header"),
                        string(headerObject, "version_build", "$.header"),
                        string(headerObject, "version_full_name", "$.header"),
                        string(headerObject, "precision", "$.header"));

        Map<String, List<FoundryApi.Entity>> categories = new LinkedHashMap<>();
        Map<String, JsonValue> normalizedRoot = new LinkedHashMap<>();
        normalizedRoot.put("header", headerObject);
        for (String category : CATEGORY_ORDER) {
            JsonValue.JsonArray array =
                    requireArray(require(root, category, "$", "<api>"), "$." + category, "<api>");
            Kind kind = topLevelKind(category);
            ParsedCollection parsed =
                    parseCollection(
                            category, "$." + category, category, category, kind, array, false);
            categories.put(category, parsed.entities());
            normalizedRoot.put(category, new JsonValue.JsonArray(parsed.values()));
        }
        return new FoundryApi(header, categories, new JsonValue.JsonObject(normalizedRoot));
    }

    private static ParsedCollection parseCollection(
            String category,
            String path,
            String parentIdentity,
            String edge,
            Kind kind,
            JsonValue.JsonArray array,
            boolean preserveOrder) {
        List<ParsedEntity> parsed = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < array.values().size(); index++) {
            String entityPath = path + "[" + index + "]";
            JsonValue.JsonObject object =
                    requireObject(array.values().get(index), entityPath, parentIdentity);
            ParsedEntity entity = parseEntity(category, entityPath, parentIdentity, kind, object);
            if (!identities.add(entity.entity().identity())) {
                throw schemaError(
                        entityPath,
                        entity.entity().identity(),
                        "Duplicate source identity " + entity.entity().identity());
            }
            parsed.add(entity);
        }
        if (!preserveOrder) {
            parsed.sort(Comparator.comparing(value -> value.entity().identity()));
        }
        for (int index = 0; index < parsed.size(); index++) {
            ParsedEntity current = parsed.get(index);
            parsed.set(
                    index,
                    new ParsedEntity(
                            current.entity().withPosition(edge, index), current.normalized()));
        }
        return new ParsedCollection(
                parsed.stream().map(ParsedEntity::entity).toList(),
                parsed.stream().map(value -> (JsonValue) value.normalized()).toList());
    }

    private static ParsedEntity parseEntity(
            String category,
            String path,
            String parentIdentity,
            Kind kind,
            JsonValue.JsonObject object) {
        String segment = identitySegment(kind, object, path, parentIdentity);
        String identity = parentIdentity + "/" + segment;
        requireExactKeys(object, allowed(kind), required(kind), path, identity);
        validateScalarFields(kind, object, path, identity);

        List<FoundryApi.Entity> children = new ArrayList<>();
        Map<String, JsonValue> normalized = new LinkedHashMap<>();
        for (var field : object.values().entrySet()) {
            Child child = child(kind, field.getKey());
            if (child == null) {
                normalized.put(field.getKey(), field.getValue());
                continue;
            }
            if (child.kind() == Kind.RETURN_VALUE) {
                JsonValue.JsonObject returnObject =
                        requireObject(field.getValue(), path + "." + field.getKey(), identity);
                ParsedEntity parsedReturn =
                        parseEntity(
                                category,
                                path + "." + field.getKey(),
                                identity + "/" + field.getKey(),
                                Kind.RETURN_VALUE,
                                returnObject);
                children.add(parsedReturn.entity().withPosition(field.getKey(), 0));
                normalized.put(field.getKey(), parsedReturn.normalized());
            } else {
                JsonValue.JsonArray childArray =
                        requireArray(field.getValue(), path + "." + field.getKey(), identity);
                ParsedCollection parsed =
                        parseCollection(
                                category,
                                path + "." + field.getKey(),
                                identity + "/" + field.getKey(),
                                field.getKey(),
                                child.kind(),
                                childArray,
                                child.preserveOrder());
                children.addAll(parsed.entities());
                normalized.put(field.getKey(), new JsonValue.JsonArray(parsed.values()));
            }
        }
        JsonValue.JsonObject normalizedObject = new JsonValue.JsonObject(normalized);
        return new ParsedEntity(
                new FoundryApi.Entity(
                        category, identity, path, "<unassigned>", -1, normalizedObject, children),
                normalizedObject);
    }

    private static void validateHeader(JsonValue.JsonObject header) {
        Set<String> keys =
                Set.of(
                        "version_major",
                        "version_minor",
                        "version_patch",
                        "version_status",
                        "version_build",
                        "version_full_name",
                        "precision");
        requireExactKeys(header, keys, keys, "$.header", "header");
        nonnegativeInt(header, "version_major", "$.header");
        nonnegativeInt(header, "version_minor", "$.header");
        nonnegativeInt(header, "version_patch", "$.header");
        string(header, "version_status", "$.header");
        string(header, "version_build", "$.header");
        string(header, "version_full_name", "$.header");
        requireOneOf(
                string(header, "precision", "$.header"),
                Set.of("single", "double"),
                "$.header.precision",
                "header");
    }

    private static void validateScalarFields(
            Kind kind, JsonValue.JsonObject object, String path, String identity) {
        for (var field : object.values().entrySet()) {
            String key = field.getKey();
            if (child(kind, key) != null) {
                continue;
            }
            String fieldPath = path + "." + key;
            switch (fieldType(kind, key)) {
                case STRING -> {
                    String value = requireString(field.getValue(), fieldPath, identity);
                    if ((key.equals("name")
                                    || key.equals("member")
                                    || key.equals("type")
                                    || key.equals("format")
                                    || key.equals("build_configuration")
                                    || key.equals("right_type"))
                            && (value.isBlank() || containsControl(value))) {
                        throw schemaError(
                                fieldPath,
                                identity,
                                "must not be blank or contain control characters");
                    }
                    if (key.equals("api_type")) {
                        requireOneOf(value, Set.of("core", "editor"), fieldPath, identity);
                    } else if (key.equals("category")) {
                        requireOneOf(
                                value, Set.of("general", "math", "random"), fieldPath, identity);
                    } else if (key.equals("meta")) {
                        requireOneOf(value, META_VALUES, fieldPath, identity);
                    } else if (key.equals("build_configuration")) {
                        requireOneOf(
                                value,
                                Set.of("float_32", "float_64", "double_32", "double_64"),
                                fieldPath,
                                identity);
                    }
                }
                case NUMBER -> requireInteger(field.getValue(), key, fieldPath, identity);
                case BOOLEAN -> requireBoolean(field.getValue(), fieldPath, identity);
                case ARRAY, OBJECT -> throw new AssertionError("Child fields are handled first.");
            }
        }
    }

    private static String identitySegment(
            Kind kind, JsonValue.JsonObject object, String path, String parentIdentity) {
        if (kind == Kind.RETURN_VALUE) {
            return "value";
        }
        String base =
                switch (kind) {
                    case SIZE_BLOCK, OFFSET_BLOCK ->
                            identityString(object, "build_configuration", path, parentIdentity);
                    case OFFSET_MEMBER -> identityString(object, "member", path, parentIdentity);
                    case CONSTRUCTOR ->
                            "#"
                                    + integerLexeme(
                                            object,
                                            "index",
                                            path,
                                            parentIdentity,
                                            BigInteger.ZERO,
                                            MAX_SIGNED_32,
                                            true);
                    default -> identityString(object, "name", path, parentIdentity);
                };
        return switch (kind) {
            case BUILTIN_METHOD, CLASS_METHOD, UTILITY ->
                    base
                            + "#"
                            + integerLexeme(
                                    object,
                                    "hash",
                                    path,
                                    parentIdentity + "/" + base,
                                    BigInteger.ZERO,
                                    MAX_UNSIGNED_64,
                                    true);
            case OPERATOR ->
                    base
                            + "#"
                            + (object.optional("right_type") == null
                                    ? "unary"
                                    : nonblankIdentityString(
                                            requireString(
                                                    object.optional("right_type"),
                                                    path + ".right_type",
                                                    parentIdentity + "/" + base),
                                            path + ".right_type",
                                            parentIdentity + "/" + base));
            default -> base;
        };
    }

    private static Kind topLevelKind(String category) {
        return switch (category) {
            case "builtin_class_sizes" -> Kind.SIZE_BLOCK;
            case "builtin_class_member_offsets" -> Kind.OFFSET_BLOCK;
            case "global_constants" -> Kind.GLOBAL_CONSTANT;
            case "global_enums" -> Kind.GLOBAL_ENUM;
            case "utility_functions" -> Kind.UTILITY;
            case "builtin_classes" -> Kind.BUILTIN;
            case "classes" -> Kind.CLASS;
            case "singletons" -> Kind.SINGLETON;
            case "native_structures" -> Kind.NATIVE_STRUCTURE;
            default -> throw new AssertionError(category);
        };
    }

    private static Child child(Kind kind, String key) {
        return switch (kind) {
            case SIZE_BLOCK -> key.equals("sizes") ? child(Kind.SIZE) : null;
            case OFFSET_BLOCK -> key.equals("classes") ? child(Kind.OFFSET_CLASS) : null;
            case OFFSET_CLASS -> key.equals("members") ? child(Kind.OFFSET_MEMBER) : null;
            case GLOBAL_ENUM, BUILTIN_ENUM, CLASS_ENUM ->
                    key.equals("values") ? child(Kind.ENUM_VALUE) : null;
            case UTILITY -> key.equals("arguments") ? orderedChild(Kind.ARGUMENT) : null;
            case BUILTIN ->
                    switch (key) {
                        case "operators" -> child(Kind.OPERATOR);
                        case "constructors" -> child(Kind.CONSTRUCTOR);
                        case "methods" -> child(Kind.BUILTIN_METHOD);
                        case "members" -> child(Kind.ARGUMENT);
                        case "constants" -> child(Kind.BUILTIN_CONSTANT);
                        case "enums" -> child(Kind.BUILTIN_ENUM);
                        default -> null;
                    };
            case CONSTRUCTOR, BUILTIN_METHOD, SIGNAL ->
                    key.equals("arguments") ? orderedChild(Kind.ARGUMENT) : null;
            case CLASS_METHOD ->
                    switch (key) {
                        case "arguments" -> orderedChild(Kind.ARGUMENT);
                        case "return_value" -> new Child(Kind.RETURN_VALUE, true);
                        default -> null;
                    };
            case CLASS ->
                    switch (key) {
                        case "methods" -> child(Kind.CLASS_METHOD);
                        case "properties" -> child(Kind.PROPERTY);
                        case "signals" -> child(Kind.SIGNAL);
                        case "constants" -> child(Kind.CLASS_CONSTANT);
                        case "enums" -> child(Kind.CLASS_ENUM);
                        default -> null;
                    };
            default -> null;
        };
    }

    private static Child child(Kind kind) {
        return new Child(kind, false);
    }

    private static Child orderedChild(Kind kind) {
        return new Child(kind, true);
    }

    private static Set<String> allowed(Kind kind) {
        return switch (kind) {
            case SIZE_BLOCK -> Set.of("build_configuration", "sizes");
            case SIZE -> Set.of("name", "size");
            case OFFSET_BLOCK -> Set.of("build_configuration", "classes");
            case OFFSET_CLASS -> Set.of("name", "members");
            case OFFSET_MEMBER -> Set.of("member", "offset", "meta");
            case GLOBAL_CONSTANT -> Set.of("name", "value", "is_bitfield", "description");
            case GLOBAL_ENUM, CLASS_ENUM -> Set.of("name", "is_bitfield", "values", "description");
            case BUILTIN_ENUM -> Set.of("name", "values", "description");
            case ENUM_VALUE -> Set.of("name", "value", "description");
            case UTILITY ->
                    Set.of(
                            "name",
                            "return_type",
                            "category",
                            "is_vararg",
                            "hash",
                            "arguments",
                            "description");
            case ARGUMENT -> Set.of("name", "type", "default_value", "meta", "description");
            case BUILTIN ->
                    Set.of(
                            "name",
                            "is_keyed",
                            "operators",
                            "constructors",
                            "has_destructor",
                            "indexing_return_type",
                            "methods",
                            "members",
                            "constants",
                            "enums",
                            "brief_description",
                            "description");
            case OPERATOR -> Set.of("name", "right_type", "return_type", "description");
            case CONSTRUCTOR -> Set.of("index", "arguments", "description");
            case BUILTIN_METHOD ->
                    Set.of(
                            "name",
                            "return_type",
                            "is_vararg",
                            "is_const",
                            "is_static",
                            "hash",
                            "arguments",
                            "description");
            case BUILTIN_CONSTANT -> Set.of("name", "type", "value", "description");
            case CLASS ->
                    Set.of(
                            "name",
                            "is_refcounted",
                            "is_instantiable",
                            "inherits",
                            "api_type",
                            "enums",
                            "methods",
                            "properties",
                            "signals",
                            "constants",
                            "brief_description",
                            "description");
            case CLASS_METHOD ->
                    Set.of(
                            "name",
                            "is_const",
                            "is_vararg",
                            "is_static",
                            "is_virtual",
                            "is_required",
                            "hash",
                            "return_value",
                            "arguments",
                            "description");
            case RETURN_VALUE -> Set.of("type", "meta", "description");
            case PROPERTY -> Set.of("type", "name", "setter", "getter", "index", "description");
            case SIGNAL -> Set.of("name", "arguments", "description");
            case CLASS_CONSTANT -> Set.of("name", "value", "description");
            case SINGLETON -> Set.of("name", "type");
            case NATIVE_STRUCTURE -> Set.of("name", "format");
        };
    }

    private static Set<String> required(Kind kind) {
        return switch (kind) {
            case SIZE_BLOCK -> Set.of("build_configuration", "sizes");
            case SIZE -> Set.of("name", "size");
            case OFFSET_BLOCK -> Set.of("build_configuration", "classes");
            case OFFSET_CLASS -> Set.of("name", "members");
            case OFFSET_MEMBER -> Set.of("member", "offset", "meta");
            case GLOBAL_CONSTANT -> Set.of("name", "value", "is_bitfield");
            case GLOBAL_ENUM, CLASS_ENUM -> Set.of("name", "is_bitfield", "values");
            case BUILTIN_ENUM -> Set.of("name", "values");
            case ENUM_VALUE -> Set.of("name", "value");
            case UTILITY -> Set.of("name", "category", "is_vararg", "hash");
            case ARGUMENT -> Set.of("name", "type");
            case BUILTIN ->
                    Set.of("name", "is_keyed", "operators", "constructors", "has_destructor");
            case OPERATOR -> Set.of("name", "return_type");
            case CONSTRUCTOR -> Set.of("index");
            case BUILTIN_METHOD -> Set.of("name", "is_vararg", "is_const", "is_static", "hash");
            case BUILTIN_CONSTANT -> Set.of("name", "type", "value");
            case CLASS -> Set.of("name", "is_refcounted", "is_instantiable", "api_type");
            case CLASS_METHOD ->
                    Set.of("name", "is_const", "is_vararg", "is_static", "is_virtual", "hash");
            case RETURN_VALUE -> Set.of("type");
            case PROPERTY -> Set.of("type", "name", "getter");
            case SIGNAL -> Set.of("name");
            case CLASS_CONSTANT -> Set.of("name", "value");
            case SINGLETON -> Set.of("name", "type");
            case NATIVE_STRUCTURE -> Set.of("name", "format");
        };
    }

    private static FieldType fieldType(Kind kind, String key) {
        if (Set.of(
                        "is_bitfield",
                        "is_vararg",
                        "is_const",
                        "is_static",
                        "is_virtual",
                        "is_required",
                        "is_keyed",
                        "has_destructor",
                        "is_refcounted",
                        "is_instantiable")
                .contains(key)) {
            return FieldType.BOOLEAN;
        }
        if (Set.of("size", "offset", "hash", "index").contains(key)) {
            return FieldType.NUMBER;
        }
        if (key.equals("value")) {
            return kind == Kind.BUILTIN_CONSTANT ? FieldType.STRING : FieldType.NUMBER;
        }
        return FieldType.STRING;
    }

    private static void requireExactKeys(
            JsonValue.JsonObject object,
            Set<String> accepted,
            Set<String> required,
            String path,
            String identity) {
        for (String key : object.values().keySet()) {
            if (!accepted.contains(key)) {
                throw schemaError(
                        path + "." + key, identity, "contains an unknown schema construct");
            }
        }
        for (String key : required) {
            if (!object.values().containsKey(key)) {
                throw schemaError(path + "." + key, identity, "is required");
            }
        }
    }

    private static String string(JsonValue.JsonObject object, String key, String path) {
        return requireString(require(object, key, path, "header"), path + "." + key, "header");
    }

    private static int integer(JsonValue.JsonObject object, String key, String path) {
        return Integer.parseInt(
                integerLexeme(object, key, path, "header", BigInteger.ZERO, MAX_SIGNED_32, true));
    }

    private static int nonnegativeInt(JsonValue.JsonObject object, String key, String path) {
        String lexeme =
                integerLexeme(object, key, path, "header", BigInteger.ZERO, MAX_SIGNED_32, true);
        return Integer.parseInt(lexeme);
    }

    private static void requireInteger(JsonValue value, String key, String path, String identity) {
        BigInteger minimum = null;
        BigInteger maximum = null;
        boolean canonicalUnsigned = false;
        if (key.equals("hash")) {
            minimum = BigInteger.ZERO;
            maximum = MAX_UNSIGNED_64;
            canonicalUnsigned = true;
        } else if (key.equals("index")) {
            minimum = BigInteger.ZERO;
            maximum = MAX_SIGNED_32;
            canonicalUnsigned = true;
        } else if (key.equals("size") || key.equals("offset")) {
            minimum = BigInteger.ZERO;
            maximum = MAX_SIGNED_64;
            canonicalUnsigned = true;
        }
        requireIntegerLexeme(value, path, identity, minimum, maximum, canonicalUnsigned);
    }

    private static String integerLexeme(
            JsonValue.JsonObject object,
            String key,
            String parentPath,
            String identity,
            BigInteger minimum,
            BigInteger maximum,
            boolean canonicalUnsigned) {
        String path = parentPath + "." + key;
        return requireIntegerLexeme(
                require(object, key, parentPath, identity),
                path,
                identity,
                minimum,
                maximum,
                canonicalUnsigned);
    }

    private static String requireIntegerLexeme(
            JsonValue value,
            String path,
            String identity,
            BigInteger minimum,
            BigInteger maximum,
            boolean canonicalUnsigned) {
        Pattern integerPattern = canonicalUnsigned ? UNSIGNED_INTEGER : SIGNED_INTEGER;
        if (!(value instanceof JsonValue.JsonNumber number)
                || !integerPattern.matcher(number.lexeme()).matches()) {
            throw schemaError(
                    path,
                    identity,
                    canonicalUnsigned
                            ? "must be a canonical unsigned integer"
                            : "must be a JSON integer");
        }
        BigInteger parsed = new BigInteger(number.lexeme());
        if ((minimum != null && parsed.compareTo(minimum) < 0)
                || (maximum != null && parsed.compareTo(maximum) > 0)) {
            throw schemaError(path, identity, "is outside the supported integer range");
        }
        return canonicalUnsigned ? parsed.toString() : number.lexeme();
    }

    private static String nonblankIdentityString(String value, String path, String identity) {
        if (value.isBlank() || containsControl(value)) {
            throw schemaError(path, identity, "must not be blank or contain control characters");
        }
        return value;
    }

    private static String identityString(
            JsonValue.JsonObject object, String key, String path, String parentIdentity) {
        String fieldPath = path + "." + key;
        return nonblankIdentityString(
                requireString(
                        require(object, key, path, parentIdentity), fieldPath, parentIdentity),
                fieldPath,
                parentIdentity);
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static void requireOneOf(
            String value, Set<String> accepted, String path, String identity) {
        if (!accepted.contains(value)) {
            throw schemaError(
                    path,
                    identity,
                    "contains unknown construct '" + value + "'; expected " + accepted);
        }
    }

    private static JsonValue require(
            JsonValue.JsonObject object, String key, String parentPath, String identity) {
        JsonValue value = object.optional(key);
        if (value == null) {
            throw schemaError(parentPath + "." + key, identity, "is required");
        }
        return value;
    }

    private static JsonValue.JsonObject requireObject(
            JsonValue value, String path, String identity) {
        if (value instanceof JsonValue.JsonObject object) {
            return object;
        }
        throw schemaError(path, identity, "must be a JSON object");
    }

    private static JsonValue.JsonArray requireArray(JsonValue value, String path, String identity) {
        if (value instanceof JsonValue.JsonArray array) {
            return array;
        }
        throw schemaError(path, identity, "must be a JSON array");
    }

    private static String requireString(JsonValue value, String path, String identity) {
        if (value instanceof JsonValue.JsonString string) {
            return string.value();
        }
        throw schemaError(path, identity, "must be a JSON string");
    }

    private static boolean requireBoolean(JsonValue value, String path, String identity) {
        if (value instanceof JsonValue.JsonBoolean bool) {
            return bool.value();
        }
        throw schemaError(path, identity, "must be a JSON boolean");
    }

    private static ApiInputException schemaError(String path, String identity, String detail) {
        return new ApiInputException(
                diagnostic(path)
                        + " "
                        + diagnostic(detail)
                        + " (entity "
                        + diagnostic(identity)
                        + ").");
    }

    private static String diagnostic(String value) {
        StringBuilder escaped = new StringBuilder();
        value.codePoints()
                .forEach(
                        codePoint -> {
                            if (codePoint == '\\') {
                                escaped.append("\\\\");
                            } else if (codePoint == '\n') {
                                escaped.append("\\n");
                            } else if (codePoint == '\r') {
                                escaped.append("\\r");
                            } else if (codePoint == '\t') {
                                escaped.append("\\t");
                            } else if (Character.isISOControl(codePoint)
                                    || Character.getType(codePoint) == Character.LINE_SEPARATOR
                                    || Character.getType(codePoint)
                                            == Character.PARAGRAPH_SEPARATOR) {
                                escaped.append(String.format("\\u%04x", codePoint));
                            } else {
                                escaped.appendCodePoint(codePoint);
                            }
                        });
        return escaped.toString();
    }

    private enum Kind {
        SIZE_BLOCK,
        SIZE,
        OFFSET_BLOCK,
        OFFSET_CLASS,
        OFFSET_MEMBER,
        GLOBAL_CONSTANT,
        GLOBAL_ENUM,
        BUILTIN_ENUM,
        CLASS_ENUM,
        ENUM_VALUE,
        UTILITY,
        ARGUMENT,
        BUILTIN,
        OPERATOR,
        CONSTRUCTOR,
        BUILTIN_METHOD,
        BUILTIN_CONSTANT,
        CLASS,
        CLASS_METHOD,
        RETURN_VALUE,
        PROPERTY,
        SIGNAL,
        CLASS_CONSTANT,
        SINGLETON,
        NATIVE_STRUCTURE
    }

    private enum FieldType {
        STRING,
        NUMBER,
        BOOLEAN,
        ARRAY,
        OBJECT
    }

    private record Child(Kind kind, boolean preserveOrder) {}

    private record ParsedEntity(FoundryApi.Entity entity, JsonValue.JsonObject normalized) {}

    private record ParsedCollection(List<FoundryApi.Entity> entities, List<JsonValue> values) {}
}

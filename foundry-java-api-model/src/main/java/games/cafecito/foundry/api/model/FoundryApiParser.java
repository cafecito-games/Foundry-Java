package games.cafecito.foundry.api.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        JsonValue.JsonObject root = JsonParser.parse(json).requireObject("$");
        requireExactKeys(root, ROOT_KEYS, ROOT_KEYS, "$", "<api>");
        JsonValue.JsonObject headerObject = root.require("header", "$").requireObject("$.header");
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
            JsonValue.JsonArray array = root.require(category, "$").requireArray("$." + category);
            Kind kind = topLevelKind(category);
            ParsedCollection parsed =
                    parseCollection(category, "$." + category, category, kind, array, false);
            categories.put(category, parsed.entities());
            normalizedRoot.put(category, new JsonValue.JsonArray(parsed.values()));
        }
        return new FoundryApi(header, categories, new JsonValue.JsonObject(normalizedRoot));
    }

    private static ParsedCollection parseCollection(
            String category,
            String path,
            String parentIdentity,
            Kind kind,
            JsonValue.JsonArray array,
            boolean preserveOrder) {
        List<ParsedEntity> parsed = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < array.values().size(); index++) {
            String entityPath = path + "[" + index + "]";
            JsonValue.JsonObject object = array.values().get(index).requireObject(entityPath);
            ParsedEntity entity = parseEntity(category, entityPath, parentIdentity, kind, object);
            if (!identities.add(entity.entity().identity())) {
                throw new ApiInputException(
                        "Duplicate source identity " + entity.entity().identity() + ".");
            }
            parsed.add(entity);
        }
        if (!preserveOrder) {
            parsed.sort(Comparator.comparing(value -> value.entity().identity()));
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
        String segment = identitySegment(kind, object, path);
        String identity = parentIdentity + "/" + segment;
        requireExactKeys(object, allowed(kind), required(kind), path, identity);
        validateScalarFields(kind, object, path);

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
                        field.getValue().requireObject(path + "." + field.getKey());
                ParsedEntity parsedReturn =
                        parseEntity(
                                category,
                                path + "." + field.getKey(),
                                identity + "/" + field.getKey(),
                                Kind.RETURN_VALUE,
                                returnObject);
                children.add(parsedReturn.entity());
                normalized.put(field.getKey(), parsedReturn.normalized());
            } else {
                JsonValue.JsonArray childArray =
                        field.getValue().requireArray(path + "." + field.getKey());
                ParsedCollection parsed =
                        parseCollection(
                                category,
                                path + "." + field.getKey(),
                                identity + "/" + field.getKey(),
                                child.kind(),
                                childArray,
                                child.preserveOrder());
                children.addAll(parsed.entities());
                normalized.put(field.getKey(), new JsonValue.JsonArray(parsed.values()));
            }
        }
        children.sort(Comparator.comparing(FoundryApi.Entity::identity));
        JsonValue.JsonObject normalizedObject = new JsonValue.JsonObject(normalized);
        return new ParsedEntity(
                new FoundryApi.Entity(category, identity, path, normalizedObject, children),
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
        integer(header, "version_major", "$.header");
        integer(header, "version_minor", "$.header");
        integer(header, "version_patch", "$.header");
        string(header, "version_status", "$.header");
        string(header, "version_build", "$.header");
        string(header, "version_full_name", "$.header");
        requireOneOf(
                string(header, "precision", "$.header"),
                Set.of("single", "double"),
                "$.header.precision");
    }

    private static void validateScalarFields(Kind kind, JsonValue.JsonObject object, String path) {
        for (var field : object.values().entrySet()) {
            String key = field.getKey();
            if (child(kind, key) != null) {
                continue;
            }
            String fieldPath = path + "." + key;
            switch (fieldType(kind, key)) {
                case STRING -> {
                    String value = field.getValue().requireString(fieldPath);
                    if ((key.equals("name")
                                    || key.equals("member")
                                    || key.equals("type")
                                    || key.equals("format")
                                    || key.equals("build_configuration"))
                            && value.isBlank()) {
                        throw new ApiInputException(fieldPath + " must not be blank.");
                    }
                    if (key.equals("api_type")) {
                        requireOneOf(value, Set.of("core", "editor"), fieldPath);
                    } else if (key.equals("category")) {
                        requireOneOf(value, Set.of("general", "math", "random"), fieldPath);
                    } else if (key.equals("meta")) {
                        requireOneOf(value, META_VALUES, fieldPath);
                    } else if (key.equals("build_configuration")) {
                        requireOneOf(
                                value,
                                Set.of("float_32", "float_64", "double_32", "double_64"),
                                fieldPath);
                    }
                }
                case NUMBER -> requireNumber(field.getValue(), fieldPath);
                case BOOLEAN -> field.getValue().requireBoolean(fieldPath);
                case ARRAY, OBJECT -> throw new AssertionError("Child fields are handled first.");
            }
        }
    }

    private static String identitySegment(Kind kind, JsonValue.JsonObject object, String path) {
        if (kind == Kind.RETURN_VALUE) {
            return "value";
        }
        String base =
                switch (kind) {
                    case SIZE_BLOCK, OFFSET_BLOCK -> string(object, "build_configuration", path);
                    case OFFSET_MEMBER -> string(object, "member", path);
                    case CONSTRUCTOR -> "#" + number(object, "index", path);
                    default -> string(object, "name", path);
                };
        if (base.isBlank()) {
            String identityKey =
                    switch (kind) {
                        case SIZE_BLOCK, OFFSET_BLOCK -> "build_configuration";
                        case OFFSET_MEMBER -> "member";
                        case CONSTRUCTOR -> "index";
                        default -> "name";
                    };
            throw new ApiInputException(path + "." + identityKey + " must not be blank.");
        }
        return switch (kind) {
            case BUILTIN_METHOD, CLASS_METHOD, UTILITY -> base + "#" + number(object, "hash", path);
            case OPERATOR ->
                    base
                            + "#"
                            + (object.optional("right_type") == null
                                    ? "unary"
                                    : object.optional("right_type")
                                            .requireString(path + ".right_type"));
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
                throw new ApiInputException(
                        "Unknown construct at " + path + "." + key + " (entity " + identity + ").");
            }
        }
        for (String key : required) {
            if (!object.values().containsKey(key)) {
                throw new ApiInputException(
                        path + "." + key + " is required (entity " + identity + ").");
            }
        }
    }

    private static String string(JsonValue.JsonObject object, String key, String path) {
        return object.require(key, path).requireString(path + "." + key);
    }

    private static int integer(JsonValue.JsonObject object, String key, String path) {
        return object.require(key, path).requireInt(path + "." + key);
    }

    private static String number(JsonValue.JsonObject object, String key, String path) {
        JsonValue value = object.require(key, path);
        requireNumber(value, path + "." + key);
        return ((JsonValue.JsonNumber) value).lexeme();
    }

    private static void requireNumber(JsonValue value, String path) {
        if (!(value instanceof JsonValue.JsonNumber)) {
            throw new ApiInputException(path + " must be a JSON number.");
        }
    }

    private static void requireOneOf(String value, Set<String> accepted, String path) {
        if (!accepted.contains(value)) {
            throw new ApiInputException(
                    path
                            + " contains unknown construct '"
                            + value
                            + "'; expected "
                            + accepted
                            + ".");
        }
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

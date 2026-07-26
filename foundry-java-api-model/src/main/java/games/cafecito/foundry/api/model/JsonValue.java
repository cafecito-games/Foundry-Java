package games.cafecito.foundry.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Dependency-free immutable JSON values used by accepted API metadata. */
public sealed interface JsonValue
        permits JsonValue.JsonArray,
                JsonValue.JsonBoolean,
                JsonValue.JsonNull,
                JsonValue.JsonNumber,
                JsonValue.JsonObject,
                JsonValue.JsonString {
    default JsonObject requireObject(String path) {
        if (this instanceof JsonObject object) {
            return object;
        }
        throw new ApiInputException(path + " must be a JSON object.");
    }

    default JsonArray requireArray(String path) {
        if (this instanceof JsonArray array) {
            return array;
        }
        throw new ApiInputException(path + " must be a JSON array.");
    }

    default String requireString(String path) {
        if (this instanceof JsonString string) {
            return string.value();
        }
        throw new ApiInputException(path + " must be a JSON string.");
    }

    default int requireInt(String path) {
        if (this instanceof JsonNumber number) {
            try {
                return Integer.parseInt(number.lexeme());
            } catch (NumberFormatException exception) {
                throw new ApiInputException(path + " must be a 32-bit JSON integer.", exception);
            }
        }
        throw new ApiInputException(path + " must be a JSON integer.");
    }

    default boolean requireBoolean(String path) {
        if (this instanceof JsonBoolean bool) {
            return bool.value();
        }
        throw new ApiInputException(path + " must be a JSON boolean.");
    }

    String canonicalJson();

    record JsonObject(Map<String, JsonValue> values) implements JsonValue {
        public JsonObject {
            values =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(new TreeMap<>(Map.copyOf(values))));
        }

        public JsonValue require(String key, String path) {
            JsonValue value = values.get(key);
            if (value == null) {
                throw new ApiInputException(path + "." + key + " is required.");
            }
            return value;
        }

        public JsonValue optional(String key) {
            return values.get(key);
        }

        @Override
        public String canonicalJson() {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (var entry : values.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                appendQuoted(json, entry.getKey());
                json.append(':').append(entry.getValue().canonicalJson());
            }
            return json.append('}').toString();
        }
    }

    record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray {
            values = Collections.unmodifiableList(new ArrayList<>(List.copyOf(values)));
        }

        @Override
        public String canonicalJson() {
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(values.get(index).canonicalJson());
            }
            return json.append(']').toString();
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            if (value == null) {
                throw new NullPointerException("value");
            }
        }

        @Override
        public String canonicalJson() {
            StringBuilder json = new StringBuilder();
            appendQuoted(json, value);
            return json.toString();
        }
    }

    record JsonNumber(String lexeme) implements JsonValue {
        public JsonNumber {
            if (lexeme == null) {
                throw new NullPointerException("lexeme");
            }
        }

        @Override
        public String canonicalJson() {
            return lexeme;
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {
        @Override
        public String canonicalJson() {
            return Boolean.toString(value);
        }
    }

    enum JsonNull implements JsonValue {
        INSTANCE;

        @Override
        public String canonicalJson() {
            return "null";
        }
    }

    private static void appendQuoted(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (character < 0x20) {
                        target.append("\\u%04x".formatted((int) character));
                    } else {
                        target.append(character);
                    }
                }
            }
        }
        target.append('"');
    }
}

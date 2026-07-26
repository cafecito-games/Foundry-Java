package games.cafecito.foundry.api.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict RFC 8259 parser that preserves number lexemes and rejects duplicate object keys. */
public final class JsonParser {
    private final String source;
    private int offset;

    private JsonParser(String source) {
        this.source = source;
    }

    public static JsonValue parse(String source) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        JsonParser parser = new JsonParser(source);
        JsonValue value = parser.parseValue("$");
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("$", "unexpected trailing content");
        }
        return value;
    }

    private JsonValue parseValue(String path) {
        skipWhitespace();
        if (atEnd()) {
            throw error(path, "expected a JSON value");
        }
        return switch (source.charAt(offset)) {
            case '{' -> parseObject(path);
            case '[' -> parseArray(path);
            case '"' -> new JsonValue.JsonString(parseString(path));
            case 't' -> {
                consumeKeyword("true", path);
                yield new JsonValue.JsonBoolean(true);
            }
            case 'f' -> {
                consumeKeyword("false", path);
                yield new JsonValue.JsonBoolean(false);
            }
            case 'n' -> {
                consumeKeyword("null", path);
                yield JsonValue.JsonNull.INSTANCE;
            }
            default -> parseNumber(path);
        };
    }

    private JsonValue.JsonObject parseObject(String path) {
        offset++;
        skipWhitespace();
        Map<String, JsonValue> values = new LinkedHashMap<>();
        if (consumeIf('}')) {
            return new JsonValue.JsonObject(values);
        }
        while (true) {
            skipWhitespace();
            if (atEnd() || source.charAt(offset) != '"') {
                throw error(path, "expected an object key");
            }
            String key = parseString(path);
            skipWhitespace();
            consume(':', path);
            String childPath = path + "." + key;
            JsonValue value = parseValue(childPath);
            if (values.putIfAbsent(key, value) != null) {
                throw error(childPath, "duplicate object key");
            }
            skipWhitespace();
            if (consumeIf('}')) {
                return new JsonValue.JsonObject(values);
            }
            consume(',', path);
        }
    }

    private JsonValue.JsonArray parseArray(String path) {
        offset++;
        skipWhitespace();
        List<JsonValue> values = new ArrayList<>();
        if (consumeIf(']')) {
            return new JsonValue.JsonArray(values);
        }
        while (true) {
            values.add(parseValue(path + "[" + values.size() + "]"));
            skipWhitespace();
            if (consumeIf(']')) {
                return new JsonValue.JsonArray(values);
            }
            consume(',', path);
        }
    }

    private String parseString(String path) {
        consume('"', path);
        StringBuilder result = new StringBuilder();
        while (!atEnd()) {
            char character = source.charAt(offset++);
            if (character == '"') {
                return result.toString();
            }
            if (character == '\\') {
                if (atEnd()) {
                    throw error(path, "unterminated escape sequence");
                }
                char escaped = source.charAt(offset++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(parseUnicodeEscape(path));
                    default -> throw error(path, "unknown escape sequence \\" + escaped);
                }
            } else {
                if (character < 0x20) {
                    throw error(path, "unescaped control character");
                }
                result.append(character);
            }
        }
        throw error(path, "unterminated string");
    }

    private char parseUnicodeEscape(String path) {
        if (offset + 4 > source.length()) {
            throw error(path, "incomplete Unicode escape");
        }
        int value = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(source.charAt(offset++), 16);
            if (digit < 0) {
                throw error(path, "invalid Unicode escape");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private JsonValue.JsonNumber parseNumber(String path) {
        int start = offset;
        consumeIf('-');
        if (consumeIf('0')) {
            if (!atEnd() && Character.isDigit(source.charAt(offset))) {
                throw error(path, "leading zero in number");
            }
        } else {
            consumeDigits(path);
        }
        if (consumeIf('.')) {
            consumeDigits(path);
        }
        if (!atEnd() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
            offset++;
            if (!atEnd() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
                offset++;
            }
            consumeDigits(path);
        }
        if (start == offset) {
            throw error(path, "expected a JSON value");
        }
        return new JsonValue.JsonNumber(source.substring(start, offset));
    }

    private void consumeDigits(String path) {
        int start = offset;
        while (!atEnd() && Character.isDigit(source.charAt(offset))) {
            offset++;
        }
        if (start == offset) {
            throw error(path, "expected a digit");
        }
    }

    private void consumeKeyword(String keyword, String path) {
        if (!source.startsWith(keyword, offset)) {
            throw error(path, "expected " + keyword);
        }
        offset += keyword.length();
    }

    private void consume(char expected, String path) {
        if (atEnd() || source.charAt(offset) != expected) {
            throw error(path, "expected '" + expected + "'");
        }
        offset++;
    }

    private boolean consumeIf(char expected) {
        if (!atEnd() && source.charAt(offset) == expected) {
            offset++;
            return true;
        }
        return false;
    }

    private void skipWhitespace() {
        while (!atEnd()) {
            char character = source.charAt(offset);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            offset++;
        }
    }

    private boolean atEnd() {
        return offset == source.length();
    }

    private ApiInputException error(String path, String message) {
        return new ApiInputException(message + " at " + path + " (character " + offset + ").");
    }
}

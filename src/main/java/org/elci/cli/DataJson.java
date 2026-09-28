package org.elci.cli;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plutus Data in cardano-cli's detailed JSON schema: {@code {"int": n}}, {@code {"bytes": "hex"}},
 * {@code {"list": [..]}}, {@code {"map": [{"k": .., "v": ..}]}} and
 * {@code {"constructor": n, "fields": [..]}}. A small parser without reflection, so the native
 * CLI reads it too.
 */
final class DataJson {
    private final String text;
    private int at;

    private DataJson(String text) {
        this.text = text;
    }

    static PlutusData parse(String json) {
        var parser = new DataJson(json);
        var value = parser.value();
        parser.space();
        if (parser.at != json.length()) throw parser.error("unexpected trailing content");
        return data(value);
    }

    private static PlutusData data(Object value) {
        if (!(value instanceof Map<?, ?> object) || object.size() != 1 && !(object.size() == 2
                && object.containsKey("constructor") && object.containsKey("fields")))
            throw new IllegalArgumentException("expected an object with one of int, bytes, list, map or"
                    + " constructor/fields, not " + value);
        if (object.containsKey("constructor")) {
            if (!(object.get("constructor") instanceof BigInteger tag) || tag.signum() < 0)
                throw new IllegalArgumentException("constructor must be a non-negative integer");
            return new PlutusData.ConstrData(tag, list(object.get("fields")).stream().map(DataJson::data).toList());
        }
        var entry = object.entrySet().iterator().next();
        return switch ((String) entry.getKey()) {
            case "int" -> {
                if (!(entry.getValue() instanceof BigInteger integer)) throw new IllegalArgumentException("int must be an integer");
                yield PlutusData.integer(integer);
            }
            case "bytes" -> {
                if (!(entry.getValue() instanceof String hex)) throw new IllegalArgumentException("bytes must be a hex string");
                yield PlutusData.bytes(HexFormat.of().parseHex(hex));
            }
            case "list" -> PlutusData.list(list(entry.getValue()).stream().map(DataJson::data).toArray(PlutusData[]::new));
            case "map" -> {
                var pairs = new ArrayList<PlutusData.Pair>();
                for (var item : list(entry.getValue())) {
                    if (!(item instanceof Map<?, ?> pair) || pair.size() != 2 || !pair.containsKey("k") || !pair.containsKey("v"))
                        throw new IllegalArgumentException("map entries must be {\"k\": .., \"v\": ..}");
                    pairs.add(new PlutusData.Pair(data(pair.get("k")), data(pair.get("v"))));
                }
                yield PlutusData.map(pairs.toArray(PlutusData.Pair[]::new));
            }
            default -> throw new IllegalArgumentException("unknown Data field '" + entry.getKey() + "'");
        };
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> items)) throw new IllegalArgumentException("expected an array, not " + value);
        return items;
    }

    private Object value() {
        space();
        if (at == text.length()) throw error("unexpected end of input");
        char c = text.charAt(at);
        if (c == '{') {
            at++;
            var object = new LinkedHashMap<String, Object>();
            space();
            if (peek('}')) return object;
            do {
                space();
                String key = string();
                space();
                expect(':');
                if (object.put(key, value()) != null) throw error("duplicate key " + key);
                space();
            } while (take(','));
            expect('}');
            return object;
        }
        if (c == '[') {
            at++;
            var items = new ArrayList<Object>();
            space();
            if (peek(']')) return items;
            do items.add(value()); while (take(','));
            space();
            expect(']');
            return items;
        }
        if (c == '"') return string();
        if (c == '-' || Character.isDigit(c)) {
            int start = at++;
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
            try {
                return new BigInteger(text.substring(start, at));
            } catch (NumberFormatException e) {
                throw error("invalid integer");
            }
        }
        throw error("unexpected '" + c + "'");
    }

    private String string() {
        expect('"');
        int start = at;
        while (at < text.length() && text.charAt(at) != '"') {
            if (text.charAt(at) == '\\') throw error("escapes are not supported");
            at++;
        }
        if (at == text.length()) throw error("unterminated string");
        return text.substring(start, at++);
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    }

    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private boolean take(char c) {
        space();
        return peek(c);
    }

    private void expect(char c) {
        space();
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + at);
    }
}

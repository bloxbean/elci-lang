package org.elci.test;

import org.julclang.compiler.pir.PirType;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reading Elci values encoded as Plutus Data, for test reports and scenario translation. */
final class Data {
    private Data() {}

    /** A compact rendering: integers, {@code #hex} bytes, lists, maps and {@code Constr tag [..]}. */
    static String show(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData i -> i.value().toString();
            case PlutusData.BytesData b -> "#" + HexFormat.of().formatHex(b.value());
            case PlutusData.ListData l -> "[" + String.join(", ", l.items().stream().map(Data::show).toList()) + "]";
            case PlutusData.MapData m -> "{" + String.join(", ", m.entries().stream()
                    .map(e -> show(e.key()) + ": " + show(e.value())).toList()) + "}";
            case PlutusData.ConstrData c -> "Constr " + c.constructorTag()
                    + (c.fields().isEmpty() ? "" : " [" + String.join(", ", c.fields().stream().map(Data::show).toList()) + "]");
        };
    }

    /**
     * A value in Elci syntax, read through its Data representation: {@code True},
     * {@code Just 5}, {@code { amount = 5, owner = #ab }}, {@code ( 1, "a" )} and
     * {@code Map.fromList [ ( k, v ) ]}. Byte strings are {@code #hex}. {@code nested} wraps an
     * application or a negative number in parentheses, as an argument needs.
     */
    static String show(PlutusData data, PirType type, Map<String, PirType> namedTypes, boolean nested) {
        try {
            return render(data, type, namedTypes, nested);
        } catch (RuntimeException e) {
            return show(data);
        }
    }

    private static String render(PlutusData data, PirType type, Map<String, PirType> named, boolean nested) {
        return switch (type) {
            case PirType.IntegerType _ -> {
                var value = integer(data);
                yield nested && value.signum() < 0 ? "(" + value + ")" : value.toString();
            }
            case PirType.ByteStringType _ -> bytes(data).length == 0 ? "#(empty)" : show(data);
            case PirType.StringType _ -> "\"" + string(data).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n") + "\"";
            case PirType.BoolType _ -> constr(data, -1).tag() == 1 ? "True" : "False";
            case PirType.UnitType _ -> "()";
            case PirType.ListType list -> "[" + String.join(", ", list(data).stream()
                    .map(item -> render(item, list.elemType(), named, false)).toList()) + "]";
            case PirType.MapType map -> parenthesize(nested, "Map.fromList [" + String.join(", ",
                    ((PlutusData.MapData) data).entries().stream().map(entry -> "( " + render(entry.key(), map.keyType(), named, false)
                            + ", " + render(entry.value(), map.valueType(), named, false) + " )").toList()) + "]");
            case PirType.OptionalType optional -> {
                var c = constr(data, -1);
                yield c.tag() == 1 ? "None" : parenthesize(nested, "Some " + render(c.fields().getFirst(), optional.elemType(), named, true));
            }
            case PirType.RecordType record -> {
                var fields = constr(data, 0).fields();
                var shown = new java.util.ArrayList<String>();
                boolean tuple = record.name().startsWith("elci.Tuple");
                for (int i = 0; i < record.fields().size(); i++) {
                    String value = render(fields.get(i), record.fields().get(i).type(), named, false);
                    shown.add(tuple ? value : record.fields().get(i).name() + " = " + value);
                }
                yield shown.isEmpty() ? (tuple ? "()" : "{}")
                        : tuple ? "( " + String.join(", ", shown) + " )" : "{ " + String.join(", ", shown) + " }";
            }
            case PirType.SumType sum -> {
                var c = constr(data, -1);
                var constructor = sum.constructors().stream().filter(k -> k.tag() == c.tag()).findFirst().orElseThrow();
                var text = new StringBuilder(constructor.name());
                for (int i = 0; i < constructor.fields().size(); i++)
                    text.append(' ').append(render(c.fields().get(i), constructor.fields().get(i).type(), named, true));
                yield constructor.fields().isEmpty() ? text.toString() : parenthesize(nested, text.toString());
            }
            case PirType.NamedTypeRef ref -> render(data, named.get(ref.stableId()), named, nested);
            default -> show(data);
        };
    }

    private static String parenthesize(boolean nested, String text) {
        return nested ? "(" + text + ")" : text;
    }

    /** The fields of an Elci record by name; records encode fields in name order. */
    static Map<String, PlutusData> record(PlutusData data, PirType type) {
        if (!(type instanceof PirType.RecordType record))
            throw new IllegalArgumentException("Expected a record type, found " + type);
        var fields = constr(data, 0).fields();
        var result = new LinkedHashMap<String, PlutusData>();
        for (int i = 0; i < record.fields().size(); i++) result.put(record.fields().get(i).name(), fields.get(i));
        return result;
    }

    /** A constructor value, checking its tag when {@code tag} is not negative. */
    static PlutusData.ConstrData constr(PlutusData data, int tag) {
        if (!(data instanceof PlutusData.ConstrData c) || tag >= 0 && c.tag() != tag)
            throw new IllegalArgumentException("Expected Constr " + tag + ", found " + show(data));
        return c;
    }

    static BigInteger integer(PlutusData data) {
        return ((PlutusData.IntData) data).value();
    }

    static byte[] bytes(PlutusData data) {
        return ((PlutusData.BytesData) data).value();
    }

    static String string(PlutusData data) {
        return new String(bytes(data), StandardCharsets.UTF_8);
    }

    static boolean bool(PlutusData data) {
        return constr(data, -1).tag() == 1;
    }

    static java.util.List<PlutusData> list(PlutusData data) {
        return ((PlutusData.ListData) data).items();
    }

    /** Elci's {@code Maybe}: {@code Nothing} is {@code Constr 0 []}, {@code Just x} is {@code Constr 1 [x]}. */
    static PlutusData maybe(PlutusData data) {
        var c = constr(data, -1);
        return c.tag() == 0 ? null : c.fields().getFirst();
    }

    /** The element type of a list, or the payload type of the constructor field at {@code index}. */
    static PirType element(PirType type) {
        return ((PirType.ListType) type).elemType();
    }

    static PirType field(PirType record, String name) {
        return ((PirType.RecordType) record).fields().stream().filter(f -> f.name().equals(name)).findFirst()
                .orElseThrow().type();
    }
}

package org.elci.driver;

import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Elci values in Elci syntax, for people: {@code True}, {@code Just 5},
 * {@code { amount = 5, owner = #ab }}, {@code ( 1, "a" )} and {@code Map.fromList [ ( k, v ) ]}.
 * Byte strings are {@code #hex} ({@code #(empty)} when empty). Values are read through their
 * PIR type; anything the type does not explain falls back to a plain Data rendering.
 */
public final class ElciValues {
    private ElciValues() {}

    /** A Data value of {@code type}; {@code nested} parenthesizes applications and negative numbers. */
    public static String show(PlutusData data, PirType type, Map<String, PirType> namedTypes, boolean nested) {
        try {
            return render(data, type, namedTypes, nested);
        } catch (RuntimeException e) {
            return data(data);
        }
    }

    /** The result of evaluating a program whose value has type {@code type}. */
    public static String show(Term term, PirType type, Map<String, PirType> namedTypes) {
        if (!(term instanceof Term.Const(Constant constant))) return "<function>";
        try {
            return constant(constant, type, namedTypes);
        } catch (RuntimeException e) {
            return constant.toString();
        }
    }

    /** A compact Data rendering: integers, {@code #hex} bytes, lists, maps and {@code Constr tag [..]}. */
    public static String data(PlutusData data) {
        return switch (data) {
            case PlutusData.IntData i -> i.value().toString();
            case PlutusData.BytesData b -> bytes(b.value());
            case PlutusData.ListData l -> "[" + String.join(", ", l.items().stream().map(ElciValues::data).toList()) + "]";
            case PlutusData.MapData m -> "{" + String.join(", ", m.entries().stream()
                    .map(e -> data(e.key()) + ": " + data(e.value())).toList()) + "}";
            case PlutusData.ConstrData c -> "Constr " + c.constructorTag()
                    + (c.fields().isEmpty() ? "" : " [" + String.join(", ", c.fields().stream().map(ElciValues::data).toList()) + "]");
        };
    }

    private static String constant(Constant constant, PirType declared, Map<String, PirType> named) {
        PirType type = resolve(declared, named);
        return switch (constant) {
            case Constant.IntegerConst i -> i.value().toString();
            case Constant.BoolConst b -> b.value() ? "True" : "False";
            case Constant.StringConst s -> string(s.value());
            case Constant.ByteStringConst b -> bytes(b.value());
            case Constant.UnitConst _ -> "()";
            case Constant.DataConst d -> render(d.value(), type, named, false);
            case Constant.ListConst list when type instanceof PirType.MapType map -> "Map.fromList ["
                    + String.join(", ", list.values().stream().map(entry -> {
                        var pair = (Constant.PairConst) entry;
                        return "( " + constant(pair.first(), map.keyType(), named) + ", "
                                + constant(pair.second(), map.valueType(), named) + " )";
                    }).toList()) + "]";
            case Constant.ListConst list -> {
                PirType element = type instanceof PirType.ListType l ? l.elemType() : new PirType.DataType();
                yield "[" + String.join(", ", list.values().stream().map(v -> constant(v, element, named)).toList()) + "]";
            }
            case Constant.ArrayConst array -> {
                PirType element = type instanceof PirType.ArrayType a ? a.elemType() : new PirType.DataType();
                yield "Array.fromList [" + String.join(", ", array.values().stream()
                        .map(v -> constant(v, element, named)).toList()) + "]";
            }
            case Constant.PairConst pair -> {
                var types = type instanceof PirType.PairType p ? List.of(p.first(), p.second())
                        : List.<PirType>of(new PirType.DataType(), new PirType.DataType());
                yield "( " + constant(pair.first(), types.get(0), named) + ", " + constant(pair.second(), types.get(1), named) + " )";
            }
            default -> constant.toString();
        };
    }

    private static PirType resolve(PirType type, Map<String, PirType> named) {
        return type instanceof PirType.NamedTypeRef ref && named.containsKey(ref.stableId()) ? named.get(ref.stableId()) : type;
    }

    private static String render(PlutusData data, PirType type, Map<String, PirType> named, boolean nested) {
        return switch (type) {
            case PirType.IntegerType _ -> {
                var value = ((PlutusData.IntData) data).value();
                yield nested && value.signum() < 0 ? "(" + value + ")" : value.toString();
            }
            case PirType.ByteStringType _ -> bytes(((PlutusData.BytesData) data).value());
            case PirType.StringType _ -> string(new String(((PlutusData.BytesData) data).value(), StandardCharsets.UTF_8));
            case PirType.BoolType _ -> constr(data).tag() == 1 ? "True" : "False";
            case PirType.UnitType _ -> "()";
            case PirType.ListType list -> "[" + String.join(", ", ((PlutusData.ListData) data).items().stream()
                    .map(item -> render(item, list.elemType(), named, false)).toList()) + "]";
            case PirType.MapType map -> parenthesize(nested, "Map.fromList [" + String.join(", ",
                    ((PlutusData.MapData) data).entries().stream().map(entry -> "( " + render(entry.key(), map.keyType(), named, false)
                            + ", " + render(entry.value(), map.valueType(), named, false) + " )").toList()) + "]");
            case PirType.OptionalType optional -> {
                var c = constr(data);
                yield c.tag() == 1 ? "None" : parenthesize(nested, "Some " + render(c.fields().getFirst(), optional.elemType(), named, true));
            }
            case PirType.RecordType record -> {
                var fields = constr(data).fields();
                var shown = new ArrayList<String>();
                boolean tuple = record.name().startsWith("elci.Tuple");
                for (int i = 0; i < record.fields().size(); i++) {
                    String value = render(fields.get(i), record.fields().get(i).type(), named, false);
                    shown.add(tuple ? value : record.fields().get(i).name() + " = " + value);
                }
                yield shown.isEmpty() ? (tuple ? "()" : "{}")
                        : tuple ? "( " + String.join(", ", shown) + " )" : "{ " + String.join(", ", shown) + " }";
            }
            case PirType.SumType sum -> {
                var c = constr(data);
                var constructor = sum.constructors().stream().filter(k -> k.tag() == c.tag()).findFirst().orElseThrow();
                // Elci unions name constructors by their source names; imported ones may be qualified.
                String name = constructor.name().substring(constructor.name().lastIndexOf('.') + 1);
                var text = new StringBuilder(name);
                for (int i = 0; i < constructor.fields().size(); i++)
                    text.append(' ').append(render(c.fields().get(i), constructor.fields().get(i).type(), named, true));
                yield constructor.fields().isEmpty() ? text.toString() : parenthesize(nested, text.toString());
            }
            case PirType.NamedTypeRef ref -> render(data, named.get(ref.stableId()), named, nested);
            default -> data(data);
        };
    }

    private static PlutusData.ConstrData constr(PlutusData data) {
        return (PlutusData.ConstrData) data;
    }

    private static String bytes(byte[] value) {
        return value.length == 0 ? "#(empty)" : "#" + HexFormat.of().formatHex(value);
    }

    private static String string(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String parenthesize(boolean nested, String text) {
        return nested ? "(" + text + ")" : text;
    }
}

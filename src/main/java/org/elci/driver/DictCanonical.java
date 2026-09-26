package org.elci.driver;

import static org.elci.types.Types.*;

import org.elci.types.Inference;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Rewrites generated Data so that every Dict in it has strictly ascending keys (ADR-009): a
 * property's arguments are decoded as a validator would decode them, and a Dict with unsorted
 * keys fails to decode. Keys order as Elci's {@code comparable} order: integers numerically, byte
 * strings and strings by their bytes, lists and tuples lexicographically.
 */
final class DictCanonical {
    private final Inference inference;

    DictCanonical(Inference inference) {
        this.inference = inference;
    }

    /** The rewrite for values of {@code type}; the identity when it contains no Dict. */
    UnaryOperator<PlutusData> of(Ty type) {
        return containsDict(type, new HashSet<>()) ? data -> canonical(data, type) : UnaryOperator.identity();
    }

    private boolean containsDict(Ty type, Set<String> visiting) {
        if (!(prune(type) instanceof Con c)) return false;
        if (c.name().equals("Dict")) return true;
        if (c.name().equals("->") || !visiting.add(c.toString())) return false;
        try {
            for (Ty argument : c.args()) if (containsDict(argument, visiting)) return true;
            for (var fields : variants(c).values()) for (Ty field : fields) if (containsDict(field, visiting)) return true;
            return false;
        } finally {
            visiting.remove(c.toString());
        }
    }

    private Map<Integer, List<Ty>> variants(Con c) {
        if (c.name().equals("Tuple") || c.name().startsWith("Record:")) return Map.of(0, c.args());
        if (c.name().equals("JulcOptional")) return Map.of(0, c.args(), 1, List.of());
        var result = new LinkedHashMap<Integer, List<Ty>>();
        for (var union : inference.unions.values())
            if (inference.nominal(union.name()).equals(c.name())) {
                var variables = new HashMap<String, Ty>();
                for (int i = 0; i < union.parameters().size(); i++) variables.put(union.parameters().get(i), c.args().get(i));
                int tag = 0;
                for (var variant : union.variants())
                    result.put(tag++, variant.fields().stream().map(f -> inference.annotatedPublic(f, variables)).toList());
            }
        return result;
    }

    private PlutusData canonical(PlutusData data, Ty type) {
        var c = (Con) prune(type);
        switch (c.name()) {
            case "Dict", "Map" -> {
                if (!(data instanceof PlutusData.MapData map)) return data;
                Ty k = c.args().get(0), v = c.args().get(1);
                var entries = new ArrayList<PlutusData.Pair>();
                for (var entry : map.entries())
                    entries.add(new PlutusData.Pair(canonical(entry.key(), k), canonical(entry.value(), v)));
                if (c.name().equals("Map")) return new PlutusData.MapData(entries);
                var order = order(k);
                entries.sort((a, b) -> order.compare(a.key(), b.key()));
                var distinct = new ArrayList<PlutusData.Pair>();
                for (var entry : entries)
                    if (distinct.isEmpty() || order.compare(distinct.getLast().key(), entry.key()) != 0) distinct.add(entry);
                return new PlutusData.MapData(distinct);
            }
            case "List" -> {
                if (!(data instanceof PlutusData.ListData list)) return data;
                return new PlutusData.ListData(list.items().stream().map(item -> canonical(item, c.args().getFirst())).toList());
            }
            default -> {
                if (!(data instanceof PlutusData.ConstrData constr)) return data;
                var fields = variants(c).get(constr.tag());
                if (fields == null || fields.size() != constr.fields().size()) return data;
                var result = new ArrayList<PlutusData>();
                for (int i = 0; i < fields.size(); i++) result.add(canonical(constr.fields().get(i), fields.get(i)));
                return new PlutusData.ConstrData(constr.tag(), result);
            }
        }
    }

    /** Elci's comparable order on the Data encodings of a key type. */
    private Comparator<PlutusData> order(Ty type) {
        var c = (Con) prune(type);
        if (c.name().equals("Int")) return Comparator.comparing(d -> ((PlutusData.IntData) d).value(), BigInteger::compareTo);
        if (c.name().equals("List")) {
            var element = order(c.args().getFirst());
            return (a, b) -> lexicographic(((PlutusData.ListData) a).items(), ((PlutusData.ListData) b).items(), i -> element);
        }
        if (c.name().equals("Tuple")) {
            var components = c.args().stream().map(this::order).toList();
            return (a, b) -> lexicographic(((PlutusData.ConstrData) a).fields(), ((PlutusData.ConstrData) b).fields(), components::get);
        }
        // Strings encode as their UTF-8 bytes, which is their order; byte strings compare unsigned.
        return (a, b) -> Arrays.compareUnsigned(((PlutusData.BytesData) a).value(), ((PlutusData.BytesData) b).value());
    }

    private static int lexicographic(List<PlutusData> left, List<PlutusData> right,
                                     java.util.function.IntFunction<Comparator<PlutusData>> order) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int result = order.apply(i).compare(left.get(i), right.get(i));
            if (result != 0) return result;
        }
        return Integer.compare(left.size(), right.size());
    }
}

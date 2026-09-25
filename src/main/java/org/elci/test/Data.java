package org.elci.test;

import org.elci.driver.ElciValues;
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
        return ElciValues.data(data);
    }

    /** A value in Elci syntax, read through its Data representation (see {@link ElciValues}). */
    static String show(PlutusData data, PirType type, Map<String, PirType> namedTypes, boolean nested) {
        return ElciValues.show(data, type, namedTypes, nested);
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

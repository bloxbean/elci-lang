package org.elci.e2e;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.util.HexUtil;
import org.elci.testkit.ElciTestkit;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Elci example validators, compiled exactly as {@code elci build} compiles them, and their data. */
final class Contracts {
    private static final Path EXAMPLES = Path.of(System.getProperty("elci.examples", "examples"));

    private Contracts() {}

    /** An example project's named validator, with its parameters applied, as a deployable script. */
    static PlutusV3Script validator(String example, String name, PlutusData... parameters) throws Exception {
        var applied = Arrays.stream(parameters).map(PlutusDataAdapter::fromClientLib)
                .toArray(org.julclang.core.PlutusData[]::new);
        return JulcScriptAdapter.fromProgram(ElciTestkit.validator(EXAMPLES.resolve(example), name, applied));
    }

    /** A script's address on the devnet, without a staking part. */
    static String address(PlutusV3Script script) {
        return AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
    }

    /**
     * An Elci record: constructor 0 with its fields sorted by name, whatever order the type
     * declares them in, as abi.json lists them.
     */
    static PlutusData record(Map<String, PlutusData> fields) {
        return ConstrPlutusData.of(0, new TreeMap<>(fields).values().toArray(PlutusData[]::new));
    }

    /** A union constructor by tag, with its fields in declaration order. */
    static PlutusData constr(int tag, PlutusData... fields) {
        return ConstrPlutusData.of(tag, fields);
    }

    /** An account's payment key hash, as a {@code PubKeyHash}. */
    static byte[] keyHash(com.bloxbean.cardano.client.account.Account account) {
        return account.hdKeyPair().getPublicKey().getKeyHash();
    }

    static PlutusData bytes(byte[] value) {
        return BytesPlutusData.of(value);
    }

    static PlutusData integer(long value) {
        return BigIntPlutusData.of(BigInteger.valueOf(value));
    }

    /** An output reference as Elci's {@code Data.encode ref} writes it: the V3 {@code TxOutRef}. */
    static PlutusData outRef(Utxo utxo) {
        return constr(0, bytes(HexUtil.decodeHexString(utxo.getTxHash())), integer(utxo.getOutputIndex()));
    }
}

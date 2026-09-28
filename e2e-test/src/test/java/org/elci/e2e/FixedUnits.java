package org.elci.e2e;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * Gives every redeemer the same generous budget without running any script. A transaction whose
 * validator fails then still reaches the node, and the test can assert that the node rejects it,
 * rather than trusting an evaluator's verdict.
 */
final class FixedUnits implements TransactionEvaluator {
    private static final ExUnits UNITS = new ExUnits(BigInteger.valueOf(4_000_000), BigInteger.valueOf(2_000_000_000L));

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
        try {
            var redeemers = Transaction.deserialize(cbor).getWitnessSet().getRedeemers();
            var results = redeemers.stream()
                    .map(r -> new EvaluationResult(r.getTag(), r.getIndex().intValue(), UNITS)).toList();
            return Result.<List<EvaluationResult>>success("fixed units").withValue(results);
        } catch (Exception e) {
            return Result.<List<EvaluationResult>>error(e.getMessage());
        }
    }
}

package org.elci.e2e;

import static org.elci.e2e.Contracts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The budgets Elci's toolchain computes are the node's, exactly. Julc's evaluator, the VM
 * `elci test` runs, prices a release; the node accepts the transaction at that budget and
 * rejects it with one CPU step or one memory unit less.
 */
class BudgetE2ETest extends DevnetTest {
    @Test
    void theNodeChargesExactlyWhatJulcComputes() throws Exception {
        var script = validator("escrow", "escrow");
        String scriptAddress = address(script);
        var buyer = Devnet.funded(1000);
        var seller = new Account(Networks.testnet());
        var deal = record(Map.of("buyer", bytes(keyHash(buyer)), "seller", bytes(keyHash(seller)),
                "price", integer(10_000_000), "deadline", integer(Devnet.posixMs(Devnet.slot() + 100_000))));
        String lock = accepted(QUICK_TX.compose(new Tx()
                        .payToContract(scriptAddress, Amount.ada(10), deal)
                        .from(buyer.baseAddress()))
                .withSigner(SignerProviders.signerFrom(buyer))
                .completeAndWait());
        var escrow = Devnet.outputsTo(lock, scriptAddress).getFirst();

        var julc = julcEvaluator();

        // Julc and Yaci Store's evaluator agree on the same transaction.
        var built = release(script, escrow, buyer, seller).withTxEvaluator(julc).buildAndSign();
        var byJulc = julc.evaluateTx(built.serialize(), Set.of(escrow)).getValue();
        var byStore = Devnet.BACKEND.getTransactionService().evaluateTx(built.serialize()).getValue();
        assertEquals(units(byStore), units(byJulc), "Julc and Yaci Store price the release alike");

        rejectedByScript(release(script, escrow, buyer, seller)
                .withTxEvaluator(adjusted(julc, u -> new ExUnits(u.getMem(), u.getSteps().subtract(BigInteger.ONE))))
                .complete());
        rejectedByScript(release(script, escrow, buyer, seller)
                .withTxEvaluator(adjusted(julc, u -> new ExUnits(u.getMem().subtract(BigInteger.ONE), u.getSteps())))
                .complete());
        accepted(release(script, escrow, buyer, seller).withTxEvaluator(julc).completeAndWait());
    }

    private static QuickTxBuilder.TxContext release(PlutusV3Script script, Utxo escrow, Account buyer, Account seller) {
        return QUICK_TX.compose(new Tx()
                        .collectFrom(escrow, constr(0))
                        .payToContract(seller.baseAddress(), Amount.ada(10), outRef(escrow))
                        .attachSpendingValidator(script))
                .feePayer(buyer.baseAddress())
                .withSigner(SignerProviders.signerFrom(buyer))
                .withRequiredSigners(buyer.getBaseAddress());
    }

    /** Julc's budgets, changed. */
    private static TransactionEvaluator adjusted(TransactionEvaluator evaluator, UnaryOperator<ExUnits> change) {
        return (cbor, utxos) -> {
            Result<List<EvaluationResult>> result = evaluator.evaluateTx(cbor, utxos);
            if (result.isSuccessful())
                result.getValue().forEach(r -> r.setExUnits(change.apply(r.getExUnits())));
            return result;
        };
    }

    private static List<String> units(List<EvaluationResult> results) {
        return results.stream().map(r -> r.getRedeemerTag() + "#" + r.getIndex() + " mem " + r.getExUnits().getMem()
                + " steps " + r.getExUnits().getSteps()).sorted().toList();
    }
}

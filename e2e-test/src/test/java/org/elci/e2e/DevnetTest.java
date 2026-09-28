package org.elci.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.TxResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base of the end-to-end tests: each deploys an Elci example on a running Yaci DevKit devnet and
 * submits real transactions through Cardano Client Lib. Without a devnet the tests are skipped.
 */
abstract class DevnetTest {
    static final QuickTxBuilder QUICK_TX = new QuickTxBuilder(Devnet.BACKEND);

    @BeforeAll
    static void requireDevnet() {
        Assumptions.assumeTrue(Devnet.available(),
                "No Yaci DevKit devnet at protocol version 11 on " + Devnet.STORE + "; start one with `devkit start`");
    }

    /**
     * Julc's evaluator, the VM `elci test` runs, reading the devnet: it prices scripts that use the
     * PV11 value builtins, which Yaci Store's evaluator does not run. The node still decides.
     */
    static org.julclang.clientlib.eval.JulcTransactionEvaluator julcEvaluator() throws Exception {
        return new org.julclang.clientlib.eval.JulcTransactionEvaluator(
                new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(Devnet.BACKEND.getUtxoService()),
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(Devnet.BACKEND.getEpochService()),
                new com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier(Devnet.BACKEND.getScriptService()),
                Devnet.slotConfig());
    }

    /** A transaction the node must accept, and wait for it on chain. */
    static String accepted(TxResult result) {
        assertTrue(result.isSuccessful(), () -> "The node rejected the transaction: " + result.getResponse());
        return result.getTxHash() != null ? result.getTxHash() : result.getValue();
    }

    /**
     * A transaction the node must reject because its Plutus script fails when the node runs it
     * ({@code PlutusFailure} under {@code ValidationTagMismatch}), not for any other reason.
     */
    static void rejectedByScript(TxResult result) {
        assertFalse(result.isSuccessful(), "The node accepted a transaction its validator should reject");
        String response = String.valueOf(result.getResponse());
        assertTrue(response.contains("PlutusFailure"),
                () -> "The node rejected the transaction, but not for a failing script: " + response);
    }
}

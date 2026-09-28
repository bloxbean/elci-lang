package org.elci.e2e;

import static org.elci.e2e.Contracts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * examples/vesting on a devnet: funds locked for a beneficiary until a deadline. The node, not an
 * evaluator, decides: a claim that could run at the deadline is rejected, one after it accepted.
 */
class VestingE2ETest extends DevnetTest {
    @Test
    void beneficiaryClaimsOnlyAfterTheDeadline() throws Exception {
        var owner = Devnet.funded(1000);
        var beneficiary = Devnet.funded(100);
        var script = validator("vesting", "vesting");
        String scriptAddress = address(script);

        long deadlineSlot = Devnet.slot() + 20;
        long deadline = Devnet.posixMs(deadlineSlot);
        var datum = record(Map.of("beneficiary", bytes(keyHash(beneficiary)),
                "deadline", integer(deadline)));
        String lock = accepted(QUICK_TX.compose(new Tx()
                        .payToContract(scriptAddress, Amount.ada(25), datum)
                        .from(owner.baseAddress()))
                .withSigner(SignerProviders.signerFrom(owner))
                .completeAndWait());
        var locked = Devnet.output(lock, Devnet.outputTo(lock, scriptAddress));

        // Before the deadline: a range starting now contains the deadline's slot or earlier times.
        rejectedByScript(QUICK_TX.compose(claim(locked, script, beneficiary.baseAddress()))
                .feePayer(beneficiary.baseAddress())
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .withRequiredSigners(beneficiary.getBaseAddress())
                .validFrom(Devnet.slot())
                .withTxEvaluator(new FixedUnits())
                .complete());

        Devnet.awaitSlot(deadlineSlot + 1);
        String claim = accepted(QUICK_TX.compose(claim(locked, script, beneficiary.baseAddress()))
                .feePayer(beneficiary.baseAddress())
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .withRequiredSigners(beneficiary.getBaseAddress())
                .validFrom(deadlineSlot + 1)
                .completeAndWait());
        var paid = Devnet.output(claim, Devnet.outputTo(claim, beneficiary.baseAddress()));
        assertEquals(beneficiary.baseAddress(), paid.getAddress());
    }

    private static Tx claim(Utxo locked, PlutusV3Script script, String beneficiary) {
        return new Tx()
                .collectFrom(locked, constr(0))
                .payToAddress(beneficiary, Amount.ada(25))
                .attachSpendingValidator(script);
    }
}

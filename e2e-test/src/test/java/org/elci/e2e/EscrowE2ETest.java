package org.elci.e2e;

import static org.elci.e2e.Contracts.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * examples/escrow on a devnet. The buyer releases the payment to the seller in an output tagged
 * with the escrow's reference; the node rejects one payment that tries to release two escrows.
 */
class EscrowE2ETest extends DevnetTest {
    private static final long PRICE = 10;
    private static PlutusV3Script script;
    private static String scriptAddress;
    private static Account buyer;
    private static Account seller;

    @BeforeAll
    static void deploy() throws Exception {
        script = validator("escrow", "escrow");
        scriptAddress = address(script);
        buyer = Devnet.funded(1000);
        seller = new Account(Networks.testnet());
    }

    @Test
    void buyerReleasesThePaymentToTheSeller() throws Exception {
        var escrow = lock(1).getFirst();
        String release = accepted(release(List.of(escrow), List.of(escrow)).completeAndWait());
        var paid = Devnet.outputsTo(release, seller.baseAddress()).getFirst();
        assertEquals(BigInteger.valueOf(PRICE * 1_000_000), paid.getAmount().getFirst().getQuantity());
        assertEquals(outRef(escrow).serializeToHex(), PlutusData.deserialize(HexUtil.decodeHexString(paid.getInlineDatum()))
                .serializeToHex(), "The payment carries the escrow's reference");
    }

    @Test
    void onePaymentCannotReleaseTwoEscrows() throws Exception {
        var escrows = lock(2);
        // Double satisfaction: both escrows spent, one payment tagged for the first.
        rejectedByScript(release(escrows, List.of(escrows.getFirst())).withTxEvaluator(new FixedUnits()).complete());
        // A payment for each releases both.
        accepted(release(escrows, escrows).completeAndWait());
    }

    @Test
    void anUntaggedPaymentReleasesNothing() throws Exception {
        var escrow = lock(1).getFirst();
        rejectedByScript(QUICK_TX.compose(new Tx()
                        .collectFrom(escrow, constr(0))
                        .payToAddress(seller.baseAddress(), Amount.ada(PRICE))
                        .attachSpendingValidator(script))
                .feePayer(buyer.baseAddress())
                .withSigner(SignerProviders.signerFrom(buyer))
                .withRequiredSigners(buyer.getBaseAddress())
                .withTxEvaluator(new FixedUnits())
                .complete());
    }

    /** Lock {@code count} escrows of the price, as separate outputs of one transaction. */
    private static List<Utxo> lock(int count) throws Exception {
        var deal = record(Map.of("buyer", bytes(keyHash(buyer)), "seller", bytes(keyHash(seller)),
                "price", integer(PRICE * 1_000_000), "deadline", integer(Devnet.posixMs(Devnet.slot() + 100_000))));
        var tx = new Tx().from(buyer.baseAddress());
        for (int i = 0; i < count; i++) tx.payToContract(scriptAddress, Amount.ada(PRICE), deal);
        String hash = accepted(QUICK_TX.compose(tx).mergeOutputs(false)
                .withSigner(SignerProviders.signerFrom(buyer)).completeAndWait());
        return Devnet.outputsTo(hash, scriptAddress);
    }

    /** Release the spent escrows, paying the seller once for each escrow in {@code paidFor}. */
    private static QuickTxBuilder.TxContext release(List<Utxo> spent, List<Utxo> paidFor) {
        var tx = new Tx().collectFrom(spent, constr(0)).attachSpendingValidator(script);
        for (var escrow : paidFor) tx.payToContract(seller.baseAddress(), Amount.ada(PRICE), outRef(escrow));
        return QUICK_TX.compose(tx)
                .mergeOutputs(false)
                .feePayer(buyer.baseAddress())
                .withSigner(SignerProviders.signerFrom(buyer))
                .withRequiredSigners(buyer.getBaseAddress());
    }
}

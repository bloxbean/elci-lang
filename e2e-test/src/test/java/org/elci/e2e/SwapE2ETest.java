package org.elci.e2e;

import static org.elci.e2e.Contracts.*;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * examples/order-swap on a devnet with real native tokens: a maker offers 10 GOLD at 3 SILVER
 * each. A taker fills part of it, returning the rest to the script under the same order, then the
 * rest; the node rejects a returned order padded with another token.
 */
class SwapE2ETest extends DevnetTest {
    private static final int PRICE = 3;

    private static PlutusV3Script script;
    private static String scriptAddress;
    private static Account maker;
    private static Account taker;
    private static ScriptPubkey gold;
    private static ScriptPubkey silver;
    private static PlutusData order;

    @BeforeAll
    static void deploy() throws Exception {
        script = validator("order-swap", "swap");
        scriptAddress = address(script);
        maker = Devnet.funded(500, 2);
        taker = Devnet.funded(500, 2);
        gold = ScriptPubkey.create(VerificationKey.create(maker.publicKeyBytes()));
        silver = ScriptPubkey.create(VerificationKey.create(taker.publicKeyBytes()));
        order = record(Map.of(
                "maker", bytes(keyHash(maker)),
                "offerPolicy", bytes(HexUtil.decodeHexString(gold.getPolicyId())),
                "offerName", bytes("GOLD".getBytes()),
                "askPolicy", bytes(HexUtil.decodeHexString(silver.getPolicyId())),
                "askName", bytes("SILVER".getBytes()),
                "price", integer(PRICE)));
    }

    @Test
    void anOrderFilledInTwoParts() throws Exception {
        var locked = open(10);
        // Take 4 GOLD for 12 SILVER; 6 GOLD go back to the script under the same order.
        String partial = accepted(fill(locked, 4, List.of(Amount.ada(2), gold(6))).completeAndWait());
        var rest = Devnet.outputsTo(partial, scriptAddress).getFirst();
        // Take the other 6 for 18 SILVER: the maker is paid the price and the order's lovelace.
        accepted(fill(rest, 6, null).completeAndWait());
    }

    @Test
    void aReturnedOrderPaddedWithAnotherTokenIsRejected() throws Exception {
        var locked = open(10);
        rejectedByScript(fill(locked, 4, List.of(Amount.ada(2), gold(6), silver(1))).withTxEvaluator(new FixedUnits())
                .complete());
    }

    /** The maker mints GOLD and locks {@code quantity} of it in an order. */
    private static Utxo open(int quantity) throws Exception {
        accepted(QUICK_TX.compose(new Tx()
                        .mintAssets(gold, new Asset("GOLD", BigInteger.valueOf(quantity)), maker.baseAddress())
                        .from(maker.baseAddress()))
                .withSigner(SignerProviders.signerFrom(maker)).completeAndWait());
        String hash = accepted(QUICK_TX.compose(new Tx()
                        .payToContract(scriptAddress, List.of(Amount.ada(2), gold(quantity)), order)
                        .from(maker.baseAddress()))
                .withSigner(SignerProviders.signerFrom(maker)).completeAndWait());
        return Devnet.outputsTo(hash, scriptAddress).getFirst();
    }

    /**
     * The taker fills {@code amount}, paying the maker in SILVER in an output tagged with the
     * order. {@code returned} is what goes back to the script for a partial fill; null for a full
     * fill, where the maker is also paid the order's lovelace. One script transaction spends the
     * order and the taker's SILVER, so every output it makes is funded by its own inputs.
     */
    private static QuickTxBuilder.TxContext fill(Utxo locked, int amount, List<Amount> returned) throws Exception {
        String hash = accepted(QUICK_TX.compose(new Tx()
                        // One SILVER more than the price, so an attacker has a token to pad the order with.
                        .mintAssets(silver, new Asset("SILVER", BigInteger.valueOf((long) amount * PRICE + 1)), taker.baseAddress())
                        .from(taker.baseAddress()))
                .withSigner(SignerProviders.signerFrom(taker)).completeAndWait());
        String silverUnit = silver(1).getUnit();
        var funds = Devnet.outputsTo(hash, taker.baseAddress()).stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().equals(silverUnit))).toList();
        var tx = new Tx()
                .collectFrom(locked, constr(0, integer(amount)))
                .collectFrom(funds)
                .payToAddress(taker.baseAddress(), List.of(Amount.ada(2), gold(amount)))
                .payToContract(maker.baseAddress(), List.of(Amount.ada(2), silver((long) amount * PRICE)), outRef(locked));
        if (returned != null) tx.payToContract(scriptAddress, returned, order);
        tx.withChangeAddress(taker.baseAddress());
        tx.attachSpendingValidator(script);
        return QUICK_TX.compose(tx)
                .mergeOutputs(false)
                .feePayer(taker.baseAddress())
                .withSigner(SignerProviders.signerFrom(taker))
                .withTxEvaluator(julcEvaluator());
    }

    private static Amount gold(long quantity) throws Exception {
        return Amount.asset(gold.getPolicyId(), "GOLD", quantity);
    }

    private static Amount silver(long quantity) throws Exception {
        return Amount.asset(silver.getPolicyId(), "SILVER", quantity);
    }
}

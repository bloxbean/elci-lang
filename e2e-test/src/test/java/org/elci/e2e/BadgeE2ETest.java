package org.elci.e2e;

import static org.elci.e2e.Contracts.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

/**
 * The treasury example's badge policy on a devnet, with its admin parameter applied: the admin
 * mints a positive quantity; anyone else, or a quantity of zero, is rejected by the node.
 */
class BadgeE2ETest extends DevnetTest {
    private static PlutusV3Script policy;
    private static Account admin;
    private static Account stranger;

    @BeforeAll
    static void deploy() throws Exception {
        admin = Devnet.funded(100);
        stranger = Devnet.funded(100);
        policy = validator("treasury", "badge", bytes(keyHash(admin)));
    }

    @Test
    void theAdminMintsBadges() throws Exception {
        String minted = accepted(mint(admin, 3).completeAndWait());
        String unit = policy.getPolicyId() + HexUtil.encodeHexString("BADGE".getBytes());
        assertTrue(Devnet.outputsTo(minted, admin.baseAddress()).stream()
                .anyMatch(o -> o.getAmount().stream().anyMatch(a -> a.getUnit().equals(unit)
                        && a.getQuantity().equals(BigInteger.valueOf(3)))), "The admin holds 3 badges");
    }

    @Test
    void noOneElseMintsBadges() throws Exception {
        rejectedByScript(mint(stranger, 1).withTxEvaluator(new FixedUnits()).complete());
    }

    @Test
    void aQuantityOfZeroIsRejected() throws Exception {
        rejectedByScript(mint(admin, 0, 1).withTxEvaluator(new FixedUnits()).complete());
    }

    private static QuickTxBuilder.TxContext mint(Account by, long quantity) {
        return mint(by, quantity, quantity);
    }

    /** Mint {@code minted} badges, claiming {@code claimed} in the redeemer. */
    private static QuickTxBuilder.TxContext mint(Account by, long claimed, long minted) {
        return QUICK_TX.compose(new Tx()
                        .mintAsset(policy, new Asset("BADGE", BigInteger.valueOf(minted)), integer(claimed), by.baseAddress()))
                .feePayer(by.baseAddress())
                .withSigner(SignerProviders.signerFrom(by))
                .withRequiredSigners(by.getBaseAddress());
    }
}

package org.elci.e2e;

import static org.elci.e2e.Contracts.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * examples/marketplace on a devnet, deployed with its Market parameter applied. A seller lists a
 * real NFT; a buyer pays the seller and the operator in outputs tagged with the listing, and takes
 * the NFT.
 */
class MarketplaceE2ETest extends DevnetTest {
    private static final long PRICE = 100_000_000;
    private static final long FEE_BASIS_POINTS = 250;
    private static final long CUT = PRICE * FEE_BASIS_POINTS / 10_000;

    private static PlutusV3Script script;
    private static String scriptAddress;
    private static Account operator;
    private static Account seller;
    private static Account buyer;
    private static ScriptPubkey policy;

    @BeforeAll
    static void deploy() throws Exception {
        operator = new Account(Networks.testnet());
        seller = Devnet.funded(100);
        buyer = Devnet.funded(1000);
        var market = record(Map.of("operator", bytes(keyHash(operator)),
                "feeBasisPoints", integer(FEE_BASIS_POINTS)));
        script = validator("marketplace", "marketplace", market);
        scriptAddress = address(script);
        policy = ScriptPubkey.create(VerificationKey.create(seller.publicKeyBytes()));
    }

    @Test
    void buyerPaysSellerAndOperatorAndTakesTheNft() throws Exception {
        var nft = mint("ElciNft1");
        var listing = list(nft);
        String bought = accepted(buy(listing, nft, PRICE - CUT).completeAndWait());
        assertTrue(Devnet.outputsTo(bought, buyer.baseAddress()).stream()
                .anyMatch(output -> output.getAmount().stream().anyMatch(a -> a.getUnit().equals(nft.getUnit()))),
                "The buyer holds the NFT");
        assertTrue(Devnet.outputsTo(bought, seller.baseAddress()).stream()
                        .anyMatch(output -> output.getAmount().getFirst().getQuantity().equals(BigInteger.valueOf(PRICE - CUT))),
                "The seller is paid the price less the fee");
    }

    @Test
    void aSellerPaidOneLovelaceShortKeepsTheNft() throws Exception {
        var nft = mint("ElciNft2");
        var listing = list(nft);
        rejectedByScript(buy(listing, nft, PRICE - CUT - 1).withTxEvaluator(new FixedUnits()).complete());
    }

    @Test
    void onlyTheSellerCancels() throws Exception {
        var nft = mint("ElciNft3");
        var listing = list(nft);
        rejectedByScript(cancel(listing, nft, buyer).withTxEvaluator(new FixedUnits()).complete());
        accepted(cancel(listing, nft, seller).completeAndWait());
    }

    /** Mint one NFT to the seller under the seller's key policy. */
    private static Amount mint(String name) throws Exception {
        accepted(QUICK_TX.compose(new Tx()
                        .mintAssets(policy, new Asset(name, BigInteger.ONE), seller.baseAddress())
                        .from(seller.baseAddress()))
                .withSigner(SignerProviders.signerFrom(seller))
                .completeAndWait());
        return Amount.asset(policy.getPolicyId(), name, 1);
    }

    /** List the NFT at the price, in an output at the marketplace. */
    private static Utxo list(Amount nft) throws Exception {
        var listing = record(Map.of("seller", bytes(keyHash(seller)), "price", integer(PRICE)));
        String hash = accepted(QUICK_TX.compose(new Tx()
                        .payToContract(scriptAddress, List.of(Amount.ada(2), nft), listing)
                        .from(seller.baseAddress()))
                .withSigner(SignerProviders.signerFrom(seller))
                .completeAndWait());
        return Devnet.outputsTo(hash, scriptAddress).getFirst();
    }

    /** Buy: the listing's NFT to the buyer, the seller and the operator paid in tagged outputs. */
    private static QuickTxBuilder.TxContext buy(Utxo listing, Amount nft, long toSeller) {
        var take = new Tx()
                .collectFrom(listing, constr(0))
                .payToAddress(buyer.baseAddress(), List.of(Amount.ada(2), nft))
                .attachSpendingValidator(script);
        var pay = new Tx()
                .payToContract(seller.baseAddress(), Amount.lovelace(BigInteger.valueOf(toSeller)), outRef(listing))
                .payToContract(operator.baseAddress(), Amount.lovelace(BigInteger.valueOf(CUT)), outRef(listing))
                .from(buyer.baseAddress());
        return QUICK_TX.compose(take, pay)
                .mergeOutputs(false)
                .feePayer(buyer.baseAddress())
                .withSigner(SignerProviders.signerFrom(buyer));
    }

    /** Cancel the listing, signed by {@code by}: the NFT goes back to the seller. */
    private static QuickTxBuilder.TxContext cancel(Utxo listing, Amount nft, Account by) {
        return QUICK_TX.compose(new Tx()
                        .collectFrom(listing, constr(1))
                        .payToAddress(seller.baseAddress(), List.of(Amount.ada(2), nft))
                        .attachSpendingValidator(script))
                .feePayer(by.baseAddress())
                .withSigner(SignerProviders.signerFrom(by))
                .withRequiredSigners(by.getBaseAddress());
    }
}

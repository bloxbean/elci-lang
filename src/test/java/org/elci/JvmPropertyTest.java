package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import net.jqwik.api.*;
import org.elci.testkit.ElciTestkit;
import org.julclang.compiler.backend.ValidatorAbi;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.julclang.testkit.jqwik.SchemaArbitraries;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;

/**
 * JVM property tests over an Elci validator (ADR-006): jqwik drives the script
 * {@code elci build} deploys, with datums and redeemers generated from its ABI by Julc's
 * {@link SchemaArbitraries}.
 */
class JvmPropertyTest {
    static final Path TREASURY = Path.of("examples/treasury");
    static final PubKeyHash ADMIN = new PubKeyHash(new byte[28]);
    static final TxOutRef SPENT = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    static final PlutusData RELEASE = new PlutusData.ConstrData(0, List.of());
    static final ValidatorAbi ABI;
    static final Program TREASURY_SCRIPT;

    static {
        try {
            ABI = ElciTestkit.abi(TREASURY, "treasury");
            TREASURY_SCRIPT = ElciTestkit.validator(TREASURY, "treasury", ADMIN.toPlutusData());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Provide
    Arbitrary<PlutusData> payouts() {
        return SchemaArbitraries.datum(ABI);
    }

    @Provide
    Arbitrary<PlutusData> actions() {
        return SchemaArbitraries.redeemer(ABI, ContractSchema.Purpose.SPEND);
    }

    @Property(tries = 60)
    void unsignedSpendsAreAlwaysRejected(@ForAll("payouts") PlutusData payout, @ForAll("actions") PlutusData action) {
        var context = ScriptContextTestBuilder.spending(SPENT, payout).redeemer(action).buildPlutusData();
        assertFalse(ValidatorTest.evaluate(TREASURY_SCRIPT, context).isSuccess());
    }

    /** A payout record encodes its fields in name order: amount, then beneficiary. */
    @Property(tries = 60)
    void theBeneficiaryReleasesExactlyThePositivePayouts(@ForAll("payouts") PlutusData payout) {
        var fields = ((PlutusData.ConstrData) payout).fields();
        var amount = ((PlutusData.IntData) fields.get(0)).value();
        var beneficiary = new PubKeyHash(((PlutusData.BytesData) fields.get(1)).value());
        var context = ScriptContextTestBuilder.spending(SPENT, payout).redeemer(RELEASE).signer(beneficiary)
                .buildPlutusData();
        assertEquals(amount.signum() > 0, ValidatorTest.evaluate(TREASURY_SCRIPT, context).isSuccess(), payout::toString);
    }

    @Example
    void theAbiDescribesTheBoundaries() {
        assertEquals(List.of("admin"), ABI.parameters().stream().map(p -> p.name()).toList());
        assertNotNull(ABI.handlers().stream().filter(h -> h.purpose() == ContractSchema.Purpose.SPEND).findFirst()
                .orElseThrow().datumType());
    }
}

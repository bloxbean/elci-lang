package org.elci;

import static org.elci.Contexts.*;
import static org.junit.jupiter.api.Assertions.*;

import org.elci.cli.Main;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContextBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/** The named-validator example, loaded from its published Julc artifacts. */
class TreasuryExampleTest {
    static final PubKeyHash ADMIN = key(1);
    static final PubKeyHash BENEFICIARY = key(2);

    @TempDir Path copy;

    static PubKeyHash key(int seed) {
        var bytes = new byte[28];
        bytes[0] = (byte) seed;
        return new PubKeyHash(bytes);
    }

    Program load(String group) throws Exception {
        var output = ValidatorOutput.fromJson(Files.readString(copy.resolve("build/validators/" + group + "/validator.plutus.json")));
        assertTrue(output.isParameterized());
        assertEquals("admin:org.julclang.ledger.PubKeyHash", output.params());
        return JulcScriptAdapter.toProgram(output.cborHex()).applyParams(ADMIN.toPlutusData());
    }

    static PlutusData signed(ScriptContextBuilder builder, PlutusData redeemer, PubKeyHash... signers) {
        for (var signer : signers) builder.signer(signer);
        return builder.redeemer(redeemer).build().toPlutusData();
    }

    @Test
    void treasuryAndBadgeEnforceTheirRoles() throws Exception {
        for (String file : new String[] {"elci.toml", "src/Auth.elm", "src/Badge.elm", "src/Treasury.elm"}) {
            Files.createDirectories(copy.resolve(file).getParent());
            Files.copy(Path.of("examples/treasury").resolve(file), copy.resolve(file));
        }
        assertEquals(0, Main.run("build", copy.toString()));
        var treasury = load("treasury");
        var badge = load("badge");
        var payout = PlutusData.constr(0, PlutusData.integer(50), BENEFICIARY.toPlutusData());
        var release = PlutusData.constr(0);
        var cancel = PlutusData.constr(1);
        assertTrue(accepts(treasury, signed(builder(Purpose.SPEND, payout), release, BENEFICIARY)));
        assertFalse(accepts(treasury, signed(builder(Purpose.SPEND, payout), release, ADMIN)));
        assertTrue(accepts(treasury, signed(builder(Purpose.SPEND, payout), cancel, ADMIN)));
        assertFalse(accepts(treasury, signed(builder(Purpose.SPEND, payout), cancel, BENEFICIARY)));
        var empty = PlutusData.constr(0, PlutusData.integer(0), BENEFICIARY.toPlutusData());
        assertFalse(accepts(treasury, signed(builder(Purpose.SPEND, empty), release, BENEFICIARY)));
        assertTrue(accepts(treasury, signed(builder(Purpose.WITHDRAW, null), PlutusData.constr(0), ADMIN)));
        assertFalse(accepts(treasury, signed(builder(Purpose.WITHDRAW, null), PlutusData.constr(0), BENEFICIARY)));
        assertFalse(accepts(treasury, signed(builder(Purpose.MINT, null), PlutusData.integer(1), ADMIN)));
        assertTrue(accepts(badge, signed(builder(Purpose.MINT, null), PlutusData.integer(1), ADMIN)));
        assertFalse(accepts(badge, signed(builder(Purpose.MINT, null), PlutusData.integer(0), ADMIN)));
        assertFalse(accepts(badge, signed(builder(Purpose.MINT, null), PlutusData.integer(1), BENEFICIARY)));
        assertFalse(accepts(badge, signed(builder(Purpose.SPEND, payout), PlutusData.integer(1), ADMIN)));
    }
}

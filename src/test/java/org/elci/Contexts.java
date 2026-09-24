package org.elci;

import org.julclang.compiler.schema.ContractSchema.Purpose;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.*;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** Full ledger-shaped script contexts for every purpose, shared by validator tests. */
final class Contexts {
    static final TxOutRef REF = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    static final Credential CREDENTIAL = new Credential.ScriptCredential(new ScriptHash(new byte[28]));

    private Contexts() {}

    static ScriptContextBuilder builder(Purpose purpose, PlutusData datum) {
        return switch (purpose) {
            case SPEND -> datum == null ? ScriptContextBuilder.spending(REF) : ScriptContextBuilder.spending(REF, datum);
            case MINT -> ScriptContextBuilder.minting(new PolicyId(new byte[28]));
            case WITHDRAW -> ScriptContextBuilder.rewarding(CREDENTIAL);
            case CERTIFY -> ScriptContextBuilder.certifying(BigInteger.ZERO,
                    new TxCert.RegStaking(CREDENTIAL, Optional.empty()));
            case VOTE -> ScriptContextBuilder.voting(new Voter.DRepVoter(CREDENTIAL));
            case PROPOSE -> ScriptContextBuilder.proposing(BigInteger.ZERO,
                    new ProposalProcedure(BigInteger.TWO, CREDENTIAL, new GovernanceAction.InfoAction()));
        };
    }

    static PlutusData context(Purpose purpose, PlutusData redeemer, PlutusData datum) {
        return builder(purpose, datum).redeemer(redeemer).build().toPlutusData();
    }

    static PlutusData context(Purpose purpose, PlutusData redeemer) {
        return context(purpose, redeemer, null);
    }

    static EvalResult evaluate(Program program, PlutusData... arguments) {
        return JulcVm.create().evaluateWithArgs(program,
                org.julclang.compiler.CompilerTarget.PLUTUS_V3_PV11.ledgerTarget(), List.of(arguments), null,
                EvalOptions.DEFAULT);
    }

    static boolean accepts(Program program, PlutusData... arguments) {
        return evaluate(program, arguments) instanceof EvalResult.Success;
    }
}

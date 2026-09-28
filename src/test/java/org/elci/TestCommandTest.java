package org.elci;

import static org.junit.jupiter.api.Assertions.*;

import org.elci.driver.ElciCompiler;
import org.elci.testkit.ElciTestkit;
import org.julclang.core.PlutusData;
import org.julclang.ledger.*;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** {@code elci test} and the JVM facade (ADR-006). */
class TestCommandTest {
    @TempDir Path project;

    void copy(String example) throws Exception {
        var source = Path.of("examples").resolve(example);
        try (var files = Files.walk(source)) {
            for (var file : files.filter(Files::isRegularFile).filter(f -> !source.relativize(f).startsWith("build")).toList()) {
                var target = project.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
    }

    static NamedValidatorCliTest.Run run(String... args) {
        return NamedValidatorCliTest.run(args);
    }

    @Test
    void runsScenariosExpectationsAndBooleansWithBudgets() throws Exception {
        copy("treasury");
        var result = run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("PASS TreasuryTest.beneficiaryReleases (cpu "), result.out());
        assertTrue(result.out().contains("PASS TreasuryTest.zeroBadgesAreRejected"), result.out());
        assertTrue(result.out().contains("PASS TreasuryTest.payoutsRoundTripThroughData"), result.out());
        assertTrue(result.out().contains("PASS TreasuryTest.onlyTheBeneficiaryReleases (100 runs, max cpu "), result.out());
        assertTrue(result.out().contains("PASS TreasuryTest.payoutsAlwaysRoundTrip (100 runs"), result.out());
        assertTrue(result.out().contains("12 passed, 0 failed (seed "), result.out());
        // Helpers, including functions returning test types, and library declarations are not tests.
        assertFalse(result.out().contains("TreasuryTest.admin "), result.out());
        assertFalse(result.out().contains("TreasuryTest.claim "), result.out());
        assertFalse(result.out().contains("TreasuryTest.badge "), result.out());
        assertFalse(result.out().contains("Expect."), result.out());
        // Tests never touch build artifacts.
        assertFalse(Files.exists(project.resolve("build")));
        var selected = run("test", project.toString(), "--match", "BADGE");
        assertTrue(selected.out().contains("3 passed, 0 failed"), selected.out());
    }

    @Test
    void failuresReportReasonsTracesAndExitStatus() throws Exception {
        copy("treasury");
        var test = project.resolve("tests/TreasuryTest.elm");
        Files.writeString(test, Files.readString(test).replace("import Bytes\n", "import Bytes\nimport Debug\n") + """


                wrongAmount : Expectation
                wrongAmount =
                    Expect.equal 51 payout.amount


                strangerReleases : Scenario
                strangerReleases =
                    claim Release
                        |> Scenario.signedBy (Scenario.key "stranger")


                tooExpensive : Scenario
                tooExpensive =
                    beneficiaryReleases
                        |> Scenario.withinBudget 1000 1000


                tracedFailure : Bool
                tracedFailure =
                    Debug.trace "checking the impossible" (1 + 1 == 3)


                crashes : Bool
                crashes =
                    1 // 0 == 1
                """);
        var result = run("test", project.toString());
        assertEquals(1, result.status());
        String out = result.out();
        assertTrue(out.contains("FAIL TreasuryTest.wrongAmount"), out);
        assertTrue(out.contains("expected: 51") && out.contains("actual:   50"), out);
        assertTrue(out.contains("FAIL TreasuryTest.strangerReleases") && out.contains("rejected the transaction"), out);
        assertTrue(out.contains("FAIL TreasuryTest.tooExpensive") && out.contains("Budget exceeded"), out);
        assertTrue(out.contains("trace: checking the impossible"), out);
        assertTrue(out.contains("FAIL TreasuryTest.crashes") && out.contains("evaluation failed"), out);
        assertTrue(out.contains("12 passed, 5 failed"), out);
    }

    @Test
    void vestingScenariosCheckValidityRangesAndSignatures() throws Exception {
        copy("vesting");
        var result = run("test", project.toString());
        assertEquals(0, result.status(), result.out() + result.err());
        assertTrue(result.out().contains("6 passed, 0 failed"), result.out());
    }

    @Test
    void propertiesShrinkToCounterexamplesAndReproduceFromTheSeed() throws Exception {
        copy("treasury");
        var test = project.resolve("tests/TreasuryTest.elm");
        Files.writeString(test, Files.readString(test) + """


                amountsStaySmall : Int -> Property
                amountsStaySmall amount =
                    Property.holds (amount < 100)


                debtsStaySmall : Int -> Property
                debtsStaySmall debt =
                    Property.holds (debt > -5)


                payoutsStaySmall : Payout -> Maybe Int -> Property
                payoutsStaySmall generated extra =
                    Property.holds (generated.amount < 50 || extra == Nothing)


                totalsStaySmall : List Int -> Property
                totalsStaySmall amounts =
                    Property.expect (Expect.atMost 10 (List.foldl (\\a total -> a + total) 0 amounts))


                strangersRelease : PubKeyHash -> Property
                strangersRelease signer =
                    Property.scenario (claim Release |> Scenario.signedBy signer)
                """);
        var first = run("test", project.toString(), "--seed", "3", "--match", "Small");
        assertEquals(1, first.status());
        String out = first.out();
        // Each failure is shrunk to the smallest value that still fails, shown in Elci syntax.
        assertTrue(out.contains("FAIL TreasuryTest.amountsStaySmall ("), out);
        assertTrue(out.contains("        amountsStaySmall 100\n    the property does not hold"), out);
        assertTrue(out.contains("        debtsStaySmall (-5)\n"), out);
        assertTrue(out.contains("        payoutsStaySmall { amount = 50, beneficiary = #(empty) } (Just 0)\n"), out);
        assertTrue(out.contains("counterexample after ") && out.contains(", shrunk "), out);
        assertTrue(out.contains("Expect.atMost"), out);
        assertTrue(out.contains("0 passed, 4 failed (seed 3)"), out);
        // The seed reproduces the run exactly.
        assertEquals(out, run("test", project.toString(), "--seed", "3", "--match", "Small").out());
        // Scenario properties report the validator's rejection.
        var strangers = run("test", project.toString(), "--seed", "3", "--match", "strangers", "--fuzz", "7");
        assertTrue(strangers.out().contains("FAIL TreasuryTest.strangersRelease (1 run, cpu "), strangers.out());
        assertTrue(strangers.out().contains("rejected the transaction"), strangers.out());
        var runs = run("test", project.toString(), "--match", "roundTrip", "--fuzz", "7");
        assertTrue(runs.out().contains("PASS TreasuryTest.payoutsAlwaysRoundTrip (7 runs"), runs.out());
        assertEquals(1, run("test", project.toString(), "--fuzz", "0").status());
        assertTrue(run("test", project.toString(), "--seed", "x").err().contains("--seed expects an integer"));
    }

    @Test
    void propertiesNeedGeneratableConcreteParameters() throws Exception {
        copy("treasury");
        var test = project.resolve("tests/TreasuryTest.elm");
        var original = Files.readString(test);
        Files.writeString(test, original + """


                applies : (Int -> Int) -> Property
                applies f =
                    Property.holds (f 1 == 1)
                """);
        var functions = run("test", project.toString());
        assertEquals(1, functions.status());
        assertTrue(functions.err().contains("ELCI2009") && functions.err().contains("Data-encodable parameters"), functions.err());
        Files.writeString(test, original + """


                anything : a -> Property
                anything _ =
                    Property.holds True
                """);
        var polymorphic = run("test", project.toString());
        assertTrue(polymorphic.err().contains("needs concrete parameter types"), polymorphic.err());
        Files.writeString(test, original.replace("import Bytes\n", "import Array\nimport Bytes\n") + """


                indexes : Array Int -> Property
                indexes values =
                    Property.holds (Array.length values >= 0)
                """);
        var arrays = run("test", project.toString());
        assertTrue(arrays.err().contains("arrays and ScriptContext cannot be generated"), arrays.err());
    }

    @Test
    void configurationAndOwnershipErrors() throws Exception {
        copy("treasury");
        // A custom test directory.
        Files.move(project.resolve("tests"), project.resolve("spec"));
        assertEquals(1, run("test", project.toString()).status(), "no tests in the default directory");
        Files.writeString(project.resolve("elci.toml"), Files.readString(project.resolve("elci.toml"))
                + "\n[tests]\nsource-directories = [\"spec\"]\n");
        assertEquals(0, run("test", project.toString()).status());
        // Test modules cannot shadow source modules or the test library.
        Files.writeString(project.resolve("spec/Auth.elm"), "module Auth exposing (x)\nx : Int\nx = 1\n");
        assertTrue(run("test", project.toString()).err().contains("both a source and a test module"));
        Files.delete(project.resolve("spec/Auth.elm"));
        Files.writeString(project.resolve("spec/Expect.elm"), "module Expect exposing (x)\nx : Int\nx = 1\n");
        assertTrue(run("test", project.toString()).err().contains("owned by the Elci test library"));
        Files.delete(project.resolve("spec/Expect.elm"));
        // A scenario naming an unknown validator fails without aborting the run.
        Files.writeString(project.resolve("spec/Missing.elm"), """
                module Missing exposing (unknown)

                import Scenario exposing (Scenario)


                unknown : Scenario
                unknown =
                    Scenario.mint "nothing"
                """);
        var missing = run("test", project.toString());
        assertEquals(1, missing.status());
        assertTrue(missing.out().contains("no [validators.nothing]"), missing.out());
        assertTrue(missing.out().contains("12 passed, 1 failed"), missing.out());
    }

    @Test
    void jvmSuitesUseJulcTestkitOnTheBuiltScripts() throws Exception {
        copy("treasury");
        var admin = new PubKeyHash(new byte[28]);
        var treasury = ElciTestkit.validator(project, "treasury", admin.toPlutusData());
        var credential = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var signed = ScriptContextTestBuilder.rewarding(credential).signer(admin).redeemer(PlutusData.UNIT)
                .buildPlutusData();
        ValidatorTest.assertValidates(treasury, signed);
        ValidatorTest.assertRejects(treasury, ScriptContextTestBuilder.rewarding(credential)
                .redeemer(PlutusData.UNIT).buildPlutusData());
        assertThrows(IllegalArgumentException.class, () -> ElciTestkit.validator(project, "treasury"));
        // The facade compiles the same script the CLI publishes.
        assertEquals(0, run("build", project.toString()).status());
        var published = org.julclang.clientlib.ValidatorOutput.fromJson(
                Files.readString(project.resolve("build/validators/treasury/validator.plutus.json")));
        assertEquals(org.julclang.clientlib.JulcScriptAdapter.scriptHash(
                        org.julclang.clientlib.JulcScriptAdapter.toProgram(published.cborHex()).applyParams(admin.toPlutusData())),
                org.julclang.clientlib.JulcScriptAdapter.scriptHash(treasury));
        // Helpers from test modules evaluate too.
        var keys = ElciTestkit.value(project, "TreasuryTest", "keysAreDistinct");
        assertInstanceOf(org.julclang.vm.EvalResult.Success.class, ValidatorTest.evaluate(keys));
    }
}

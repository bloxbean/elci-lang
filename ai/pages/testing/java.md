# Testing from Java

> Load the scripts elci build publishes into JUnit, build ledger contexts with julc-testkit and run jqwik property tests with datums generated from the validator's ABI.

Source: https://elci-lang.dev/testing/java/

`elci test` covers most contracts. When you need full control over the ledger context, or want your contract tests next to the Java code of your off-chain application, you can test the same scripts from JUnit. In this page you set up a Gradle test suite, evaluate a validator with julc-testkit, and write jqwik properties with generated datums and redeemers.

Reach for Java when a test needs something [scenarios](https://elci-lang.dev/testing/scenarios/) cannot describe: reference scripts, other staking credentials or several withdrawals, or more than one script in a transaction.

## Set up a test suite

Elci is a development preview and is not published to a Maven repository. Put the JARs of your Elci build on the test classpath instead: `./gradlew installDist` in the Elci repository writes them to `build/install/elci/lib`, together with the pinned Julc libraries they need. The jqwik integration, `julc-testkit-jqwik`, comes from the BloxBean Maven repository, which publishes the pinned Julc snapshot.

```groovy title="build.gradle"
plugins {
    id 'java'
}

repositories {
    mavenCentral()
    maven {
        url = uri('https://repo.bloxbean.org/maven/snapshots')
        mavenContent { snapshotsOnly() }
    }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// The Elci installation: the elci-lang JAR and its pinned Julc libraries.
def elciLib = '/path/to/elci-lang/build/install/elci/lib'

dependencies {
    testImplementation fileTree(elciLib) { include '*.jar' }
    testImplementation 'org.julclang:julc-testkit-jqwik:0.1.0-pre20-ebaf028-SNAPSHOT'
    testImplementation 'net.jqwik:jqwik:1.9.2'
    testImplementation platform('org.junit:junit-bom:5.11.4')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

test {
    useJUnitPlatform()
    testLogging { events 'passed', 'failed', 'skipped' }
}
```

Keep the Elci project in its own directory next to the Java sources. Tests run from the Gradle project directory, so `Path.of("market")` finds it:

- my-dapp/
  - build.gradle
  - settings.gradle
  - market/ the Elci project from Transaction scenarios
    - elci.toml
    - src/
    - tests/
  - src/
    - test/
      - java/
        - market/
          - MarketValidatorTest.java
          - MarketProperties.java

## ElciTestkit

`org.elci.testkit.ElciTestkit` compiles Elci code exactly as the CLI does, so your JUnit tests evaluate the scripts you deploy.

| Method | Returns |
| --- | --- |
| `validator(project, name, parameters...)` | the `[validators.<name>]` script as a `Program`, with its deployment parameters applied in declared order |
| `abi(project, name)` | the validator's `ValidatorAbi`: its parameter, datum and redeemer types |
| `entrypoint(project)` | the `[entrypoint]` function as a `Program`, as `elci eval` runs it |
| `value(project, module, name)` | any exposed zero-argument declaration of a source or test module, as a `Program` |

`project` is the directory that contains `elci.toml`. All four methods declare `IOException` for a project that cannot be read. `validator` and `abi` throw `IllegalArgumentException` for an unknown validator, and `validator` also when the number of parameters is wrong:

```java
@Test
void theValidatorNeedsItsParameter() {
    assertThrows(IllegalArgumentException.class, () -> ElciTestkit.validator(MARKET, "market"));
}
```

## Encode datums and redeemers

In Java you pass `PlutusData`, so you encode values by hand with the same rules Elci uses:

- A **record** is constructor 0 with its fields in **alphabetical order of their names**. `Listing { seller, price, expires }` is `Constr 0 [expires, price, seller]`.
- A **custom type** constructor is numbered by its position in the declaration, from 0: `Buy` is 0, `Reprice` 1 and `Delist` 2.
- Ledger types such as `PubKeyHash` and `TxOutRef` have `toPlutusData()`. `PlutusData.UNIT` is the Data of `()`.

```java
/** A Listing record encodes its fields in name order: expires, price, seller. */
static PlutusData listing(long price, long expires) {
    return PlutusData.constr(0, PlutusData.integer(expires), PlutusData.integer(price), SELLER.toPlutusData());
}

/** Action constructors are numbered in declaration order: Buy 0, Reprice 1, Delist 2. */
static final PlutusData DELIST = PlutusData.constr(2);
```

## A JUnit test

julc-testkit provides the rest:

- `ScriptContextTestBuilder` builds the ledger context. Start with the purpose, `spending(ref, datum)`, `spending(ref)`, `minting(policyId)`, `rewarding(credential)`, `certifying(index, cert)`, `voting(voter)` or `proposing(index, procedure)`, then add `redeemer`, `signer`, `input`, `referenceInput`, `output`, `mint`, `fee`, `validRange`, `withdrawal`, `datum` (a datum hash and its datum) and more. Finish with `buildPlutusData()`.
- `ValidatorTest.evaluate(program, context)` runs a script and returns an `EvalResult`. `assertValidates` and `assertRejects` evaluate and assert in one step.
- `BudgetAssertions` checks an `EvalResult`: `assertSuccess`, `assertFailure`, `assertBudgetUnder(result, cpu, memory)`, `assertTrace(result, messages...)`, `assertTraceExact` and `assertNoTraces`.

Unlike a scenario, the builder fills in nothing about your script. You choose the spent reference, credentials and policy ids yourself, and `spending(ref, datum)` sets the purpose without adding the spent output to the inputs: add it with `input` when your validator looks for its own input, for example through `Tx.ownInput` or `Tx.continuingOutputs`. The fee defaults to 0 and the validity range to always.

Here is a complete test class for the marketplace's `market` validator:

```java title="src/test/java/market/MarketValidatorTest.java"
package market;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Arrays;
import org.elci.testkit.ElciTestkit;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.*;
import org.julclang.testkit.BudgetAssertions;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.junit.jupiter.api.Test;

class MarketValidatorTest {
    static final Path MARKET = Path.of("market");
    static final PubKeyHash OPERATOR = new PubKeyHash(new byte[28]);
    static final PubKeyHash SELLER = new PubKeyHash(filled(28, 1));
    static final TxOutRef LISTED = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);

    static byte[] filled(int length, int value) {
        var bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    /** A Listing record encodes its fields in name order: expires, price, seller. */
    static PlutusData listing(long price, long expires) {
        return PlutusData.constr(0, PlutusData.integer(expires), PlutusData.integer(price), SELLER.toPlutusData());
    }

    /** Action constructors are numbered in declaration order: Buy 0, Reprice 1, Delist 2. */
    static final PlutusData DELIST = PlutusData.constr(2);

    @Test
    void theSellerDelists() throws IOException {
        Program market = ElciTestkit.validator(MARKET, "market", OPERATOR.toPlutusData());
        var ctx = ScriptContextTestBuilder.spending(LISTED, listing(50_000_000, 2000))
                .redeemer(DELIST)
                .signer(SELLER)
                .buildPlutusData();
        ValidatorTest.assertValidates(market, ctx);
    }

    @Test
    void onlyTheOperatorWithdrawsRewards() throws IOException {
        Program market = ElciTestkit.validator(MARKET, "market", OPERATOR.toPlutusData());
        var credential = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var signed = ScriptContextTestBuilder.rewarding(credential)
                .signer(OPERATOR)
                .redeemer(PlutusData.UNIT)
                .buildPlutusData();
        var result = ValidatorTest.evaluate(market, signed);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 10_000_000, 50_000);

        var unsigned = ScriptContextTestBuilder.rewarding(credential)
                .redeemer(PlutusData.UNIT)
                .buildPlutusData();
        ValidatorTest.assertRejects(market, unsigned);
    }

    @Test
    void theValidatorNeedsItsParameter() {
        assertThrows(IllegalArgumentException.class, () -> ElciTestkit.validator(MARKET, "market"));
    }
}
```

Run it with `./gradlew test`.

:::tip
`BudgetAssertions.assertTrace` pairs well with `Debug.trace` in your validator: it checks that a rejected transaction was rejected for the reason you expect. See [Debugging and budgets](https://elci-lang.dev/testing/debugging/).
:::

## Properties with jqwik

`SchemaArbitraries` from `julc-testkit-jqwik` reads a validator's ABI and generates valid datums, redeemers and parameters for jqwik. jqwik then runs each property many times and shrinks failures with its own strategies.

| Method | Generates |
| --- | --- |
| `SchemaArbitraries.datum(abi)` | datums of the validator's spend handler |
| `SchemaArbitraries.redeemer(abi, purpose)` | redeemers of the handler for a `ContractSchema.Purpose`, such as `SPEND` |
| `SchemaArbitraries.parameters(abi)` | lists of deployment parameters |
| `SchemaArbitraries.forType(type)` | values of any `PirType` |

```java title="src/test/java/market/MarketProperties.java"
package market;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
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

class MarketProperties {
    static final Path MARKET = Path.of("market");
    static final PubKeyHash OPERATOR = new PubKeyHash(new byte[28]);
    static final TxOutRef LISTED = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
    static final ValidatorAbi ABI;
    static final Program SCRIPT;

    static {
        try {
            ABI = ElciTestkit.abi(MARKET, "market");
            SCRIPT = ElciTestkit.validator(MARKET, "market", OPERATOR.toPlutusData());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Provide
    Arbitrary<PlutusData> listings() {
        return SchemaArbitraries.datum(ABI);
    }

    @Provide
    Arbitrary<PlutusData> actions() {
        return SchemaArbitraries.redeemer(ABI, ContractSchema.Purpose.SPEND);
    }

    /** With no outputs and no signature, no action can succeed. */
    @Property(tries = 100)
    void emptyTransactionsAreRejected(@ForAll("listings") PlutusData listing,
                                      @ForAll("actions") PlutusData action) {
        var ctx = ScriptContextTestBuilder.spending(LISTED, listing).redeemer(action).buildPlutusData();
        assertFalse(ValidatorTest.evaluate(SCRIPT, ctx).isSuccess(), () -> listing + " " + action);
    }

    /** A listing encodes its fields in name order: expires, price, seller. */
    @Property(tries = 100)
    void theSellerCanAlwaysDelist(@ForAll("listings") PlutusData listing) {
        var fields = ((PlutusData.ConstrData) listing).fields();
        var seller = new PubKeyHash(((PlutusData.BytesData) fields.get(2)).value());
        var ctx = ScriptContextTestBuilder.spending(LISTED, listing)
                .redeemer(PlutusData.constr(2))
                .signer(seller)
                .buildPlutusData();
        assertTrue(ValidatorTest.evaluate(SCRIPT, ctx).isSuccess(), listing::toString);
    }
}
```

The script and ABI are compiled once, in the static initializer, and shared by every try.

`elci test` properties and jqwik properties complement each other. `elci test` needs no Java and generates Elci-typed arguments, including records and unions you define in tests. With jqwik you build and vary the whole ledger context in Java, and its generators compose with the rest of your Java test code.

## Evaluate other declarations

`ElciTestkit.value` compiles any exposed zero-argument declaration, including tests and helpers in test modules, and `ElciTestkit.entrypoint` compiles a function project's `[entrypoint]`. Evaluate them with `ValidatorTest.evaluate` and read the result term:

```java
static Constant resultOf(EvalResult result) {
    var success = assertInstanceOf(EvalResult.Success.class, result);
    return ((Term.Const) success.resultTerm()).value();
}

@Test
void unitTestsAndEntrypointsEvaluate() throws IOException {
    var free = ElciTestkit.value(Path.of("shop"), "PricingTest", "smallAmountsAreFree");
    assertEquals(new Constant.BoolConst(true), resultOf(ValidatorTest.evaluate(free)));

    var main = ElciTestkit.entrypoint(Path.of("fn"));
    assertEquals(new Constant.IntegerConst(BigInteger.valueOf(42)), resultOf(ValidatorTest.evaluate(main)));
}
```

Here `shop` is the project from [Unit tests](https://elci-lang.dev/testing/unit-tests/), and `fn` is a project made by `elci init fn --purpose function`, whose `main` is `42`. `Constant` and `Term` are in `org.julclang.core`, and `EvalResult` is in `org.julclang.vm`.

# ADR-006: Source-level tests with `elci test`

Status: **Accepted and implemented** (see Implementation evidence).
Date: 25 September 2026
Amended: 25 September 2026, adding [property tests](#property-tests) (Julc `f97f8042`).
Baseline: Elci `a2edc8d`, Julc `97e168c6`. Follows
[ADR-005](005-remaining-frontend-and-backend-integration.md), which deferred a test
command "until an actual test command and test format are defined".

## Context

Elci projects could be checked, built and evaluated, but not tested. There was no
way to assert a validator's behavior from Elci source. `eval` runs only a single
`purpose = "function"` entrypoint. Validator acceptance needs complete ledger
contexts, which only Java code could build.

Julc already has test infrastructure:

- **`julc check`** discovers `@Test` boolean methods in `test/`, compiles each to
  UPLC, evaluates it on the VM and reports budgets.
- **julc-testkit** provides these building blocks:
  - `ScriptContextTestBuilder`: full ledger contexts for every purpose;
  - `ValidatorTest`: evaluation of a `Program` with Data arguments;
  - `BudgetAssertions`: budget and trace assertions.

Elci users write Elm-syntax source and should not need Java to test a contract. JVM
users who embed Elci in JUnit suites should not need a second test framework.

## Decision

### Test modules and discovery

- **Location:** test modules live under `tests/` by default, or under the
  directories in an optional `[tests] source-directories` table.
- **Imports:** test modules may import project modules, configured Java modules and
  the test library. They are never part of `check`, `build` or artifacts.
- **Discovery:** a test is an **exposed, zero-argument, top-level declaration** of a
  test module whose inferred type is one of:
  - `Bool`, which passes when it evaluates to `True`;
  - `Expect.Expectation`, which passes when it evaluates to `Expect.Pass`;
  - `Scenario.Scenario`, a transaction against a validator of this project (below);
  - `Property.Property`, evaluated once.

  An **exposed function whose result is `Property.Property`** is a property test
  (below). Other declarations are helpers. Test names are `Module.declaration`.
- **Isolation:** each test compiles to its own program and runs on Julc's VM through
  `ValidatorTest`. A failure or runtime error in one test never masks another.
- **Reporting:** each test reports pass or fail, CPU and memory, and on failure the
  reason and the script's traces.

### The test library

Two bundled modules are available only to test modules and are written in Elci.

**`Expect`** mirrors elm-test's `Expect`:

| Function | Meaning |
| --- | --- |
| `equal`, `notEqual` | Structural equality |
| `atMost`, `atLeast`, `lessThan`, `greaterThan` | Integer ordering |
| `pass`, `fail`, `onFail` | Explicit outcomes and custom messages |
| `all` | Combine checks on one subject |

A failure carries a message and the Data encodings of the compared values, made with
`Julc.Data.encode`. The runner prints them, because Elci cannot turn arbitrary
values into strings on-chain.

**`Scenario`** describes one transaction as data. The description is an ordinary
Elci value, so datums, redeemers and parameters come from the project's real types
through `Julc.Data.encode`.

- **Purposes:** `spend` (with or without a datum), `mint`, `withdraw`, `certify`,
  `vote` and `propose`.
- **Transaction:** signers, extra inputs and reference inputs, outputs (to the
  validator's own script or to an address), own-policy minting, fee, validity
  bounds and deployment parameters.
- **Expectations:** success or failure, and optionally a budget ceiling.

The runner (JVM) builds the scenario:

1. It compiles the named validator exactly as `elci build` does and applies the
   scenario's parameters.
2. It computes the script's hash and resolves "own" addresses, the own minting
   policy and the own withdrawal credential.
3. It builds the ledger context with julc-testkit's `ScriptContextTestBuilder`,
   adding the spent output as an input at the script address.
4. It evaluates the **deployed script**, with purpose dispatch and strict boundary
   decoding, through `ValidatorTest.evaluate`.
5. It checks the outcome, and the budget with `BudgetAssertions.assertBudgetUnder`.

Scenarios name `[validators.<name>]` groups
([ADR-007](007-named-validators-only.md)).

### Property tests

A property should hold for every argument. It is an exposed function of a test
module whose result is `Property`:

```elm
payoutsAlwaysRoundTrip : Payout -> Property
payoutsAlwaysRoundTrip generated =
    Property.expect (Expect.equal generated (Data.decode (Data.encode generated)))
```

- **Marking.** The bundled `Property` module wraps an outcome: `holds` for a
  `Bool`, `expect` for an `Expectation` and `scenario` for a `Scenario`. The
  `Property` result type is what makes a function a property. Helpers that return
  an `Expectation` or a `Scenario` (`claim : Action -> Scenario`) stay helpers.
- **Compilation.** `ElciCompiler.compileTests` compiles each property to one
  program, `\d1 .. dn -> property (decode d1) .. (decode dn)`. Each argument
  arrives as Data and is decoded by Julc's strict decoder for its parameter type.
  Generated values therefore reach the property exactly as the ledger's would.
- **Generation.** julc-testkit's `DataGenerators.forType` derives a generator for
  each parameter's Data representation. Every value passes the strict boundary
  check. Recursive unions stay finite, and map keys are distinct. Top-level
  parameters typed as ledger hashes get their exact lengths.
- **Runs.** julc-testkit's `PropertyCheck` runs each property `--fuzz` times
  (default 100), with sizes growing from 0.
  - All runs come from one seed. `--seed` sets it, and each property derives its
    own from the seed and its name.
  - The first failure is shrunk greedily, one argument at a time.
  - The report shows the counterexample in Elci syntax, the number of runs and
    shrinks, and the seed.
  - A `scenario` property runs the deployed validator on every run.
- **Parameters.** A property's parameters must be concrete and Data-encodable.
  Functions, the ledger context and unresolved type variables are compile errors
  (ELCI2009), not silent skips.
- **Native image.** The engine uses no reflection and no test engine, so the
  native `elci` binary runs properties too.

For JVM suites, `ElciTestkit.abi` exposes a validator's ABI. julc-testkit-jqwik's
`SchemaArbitraries` then generates its datums, redeemers and parameters for jqwik
`@Property` methods.

### Debugging aid

`Debug.trace : String -> a -> a` emits a Plutus trace and returns its second
argument. It lowers to Julc's `Trace` term, costs budget like any trace, and works
in contracts as well as tests. The runner prints traces of failing tests.

### JVM facade

`org.elci.testkit.ElciTestkit` compiles a project's named validator, its
`[entrypoint]` function or any exposed value to a Julc `Program`. JUnit suites can then use
julc-testkit directly (`ScriptContextTestBuilder`, `ValidatorTest`,
`BudgetAssertions`) against the same scripts `elci build` publishes.

### CLI

```sh
elci test [project-directory] [--match <substring>] [--seed <n>] [--fuzz <runs>]
```

The exit status is 1 when any test fails or none match. Traces and failure details
go to standard output with the summary.

## Invariants

- **Tests never change artifacts.** They cannot affect build outputs, hashes or
  ABIs, and test modules are invisible to `check` and `build`.
- **Scenarios run what `elci build` produces:** the same compile path, parameters
  applied in declared order, and the same Julc wrapper.
- **No second implementation.** Contexts come from Julc's builders, evaluation from
  Julc's VM, and budget checks from julc-testkit. Elci adds only the translation
  from scenario data.
- **Test isolation:** each test is a separate program; results are deterministic,
  and a property run is reproduced exactly by its seed.
- **Generated values are valid:** every generated argument passes the same strict
  decoding a validator applies to its datum and redeemer.

## Alternatives

- **An elm-test `Test` tree** (`describe`/`test`) evaluated as one program. One
  failing test would abort the whole run, and names would need on-chain strings.
  Discovery by declaration keeps tests isolated and named by source.
- **Building ledger contexts in Elci source.** The ledger `Value` has no approved
  constructor, and reimplementing context construction would duplicate Julc. The
  runner translates a small scenario description instead.
- **JUnit only.** Contract authors would have to write Java. The facade still gives
  JUnit users the same scripts.
- **jqwik inside `elci test`.** The jqwik engine is reflection-heavy and runs within
  the JUnit Platform lifecycle; its composite arbitraries fail outside it. The
  native `elci` binary could not use it. jqwik remains the JVM path, through
  `SchemaArbitraries`.
- **elm-test style fuzzers** (`Fuzz.int`, `fuzz fuzzer "name" (\x -> ..)`). A test
  would have to be a value holding both a fuzzer and a function, but Elci values in
  unions are Data and cannot hold functions. Type-directed generation needs no
  fuzzer values. A value can be narrowed in code instead (`modBy 2000 raw`), and
  its shrinking is kept.
- **Treating every exposed function returning a test type as a property.** Helpers
  such as `claim : Action -> Scenario` would run as failing properties. The
  `Property` result type is an explicit, type-checked marker.
- **Coverage-guided fuzzing (Jazzer).** It suits hardening Julc's decoders and VM,
  not contract-level properties. It is a separate later track.

## Consequences

The scenario description covers common validator tests: signatures, validity
ranges, inputs and outputs with lovelace, inline datums and own-policy tokens.
Multi-asset values beyond one policy, reference scripts and certificate or
governance details beyond the purpose itself need the JVM facade for now.

## Implementation evidence

- **New code:**
  - `org.elci.test.TestRunner` (discovery and evaluation) and `Scenarios`
    (translation to julc-testkit's `ScriptContextTestBuilder`);
  - the bundled `Expect.elm` and `Scenario.elm`;
  - the `Bytes` and `Debug` intrinsic modules;
  - `ElciCompiler.compileValues`, the `[tests]` configuration and `elci test`;
  - `org.elci.testkit.ElciTestkit`;
  - `elci init --validator` generating `tests/MainTest.elm`.
- **Tests:** `TestCommandTest`, `IntrinsicsTest` and the extended `InitTest`. See
  [Validation](../docs/VALIDATION.md#source-level-testing-evidence-adr-006).
- **Examples:** `examples/treasury/tests` covers named validators across spending,
  withdrawal and minting. `examples/vesting/tests` covers
  validity ranges.
- **Property tests (amendment):**
  - `Property.elm`;
  - `ElciCompiler.compileTests` and `Lowering.lowerProperty`;
  - the property runner in `TestRunner`, with counterexamples rendered by
    `Data.show`;
  - `--seed` and `--fuzz`;
  - `ElciTestkit.abi`;
  - Elm's `modBy`, for narrowing generated integers.

  They are backed by julc-testkit's `DataGenerators` and `PropertyCheck` and
  julc-testkit-jqwik's `SchemaArbitraries`.
  - `TestCommandTest` covers shrinking to minimal counterexamples (`100`, `(-5)`,
    and a record with a `Just`) and seed reproduction. It also covers `--fuzz`,
    scenario properties, helpers that are not properties, and the ELCI2009
    parameter errors.
  - `JvmPropertyTest` runs jqwik over the treasury ABI.
  - The treasury and vesting examples gained properties.
- **Pre-existing defect fixed:** the pattern-usefulness check expanded a wildcard
  into every constructor, even when the column did not name them all. For a union
  with a `List` field it recursed without end, which `Expect.every` exposed. It
  now falls back to the default matrix (Maranget's incomplete-signature case).
  `CoreCompletionTest` checks termination and that unreachable branches are still
  rejected.

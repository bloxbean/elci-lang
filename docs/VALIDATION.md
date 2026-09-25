# Validation and review

Elci is experimental. Passing these checks is evidence for the implemented
profile; it is not a production-security certification.

## Reproducible checks

Publish the pinned Julc development modules described in the getting-started
guide, then run:

```sh
./gradlew clean test
npm exec --yes --package elm-format@0.8.8 -- \
  elm-format examples src/main/resources/elci/core src/main/resources/elci/test \
  src/test/resources/syntax/valid src/test/resources/syntax/unsupported --validate
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile
build/native/elci check examples/threshold
build/native/elci build examples/threshold
build/native/elci eval examples/functional
build/native/elci eval examples/core-completion
build/native/elci test examples/treasury --seed 42
build/native/elci test examples/vesting --fuzz 300
```

The Java test suite covers parsing and diagnostics, inference, generic
specialization and retained requirements, strictness, general recursion groups,
nested patterns, generic aliases, structural equality, modules, records, tuples,
unions, lists,
ledger fields, serialized boundaries, validator evaluation and execution costs.
`BudgetTest` writes representative measurements to
`build/reports/elci/budgets.csv`.

## Julc compatibility evidence

The shared-backend change is reviewed independently in the Julc repository. Its
focused tests cover direct PIR compilation, lexical closure, target and boundary
rejection, deterministic dependency linking, strict unused bindings, recursion
limits, Java library visibility and transitive materialization.

The extraction was also compared with the pre-extraction Java compiler using five
fixtures, three optimization levels and source maps both enabled and disabled.
All 30 configurations produced identical FLAT hashes, parameter metadata and
source-map entry counts. Run the developer comparison with:

```sh
export JULC_WORKTREE=/path/to/julc-worktree
export JAVA_HOME=/path/to/jdk-25
./gradlew writeRuntimeClasspath
python3 scripts/compare-julc-baseline.py
```

The script is tied to the documented Julc baseline commit and is a maintainer
regression check, not part of the ordinary Elci build.

## Functional core completion evidence

The `3f1b675` functional-core implementation was validated on 19 September 2026:

- 71 Elci JVM tests passed with no failures or skips.
- elm-format 0.8.8 accepted the examples, bundled core, supported syntax corpus,
  and Elm-valid deferred corpus.
- GraalVM 25.3.4.1 produced a 30.51 MiB native `elci` executable. Its
  `core-completion` check produced 827 FLAT bytes and native evaluation returned
  `15` with CPU `11311095` and memory `41004`.
- Two consecutive native builds of the fixture produced identical `abi.json`,
  `validator.flat`, and `validator.plutus` hashes.
- The Julc tree passed 11,061 tests with no failures or errors (536 existing
  skips), including three- and four-function heterogeneous recursive groups.
- All 30 unchanged Java compiler configurations retained identical FLAT hashes,
  parameter metadata, and source-map entry counts.

## Julc revision-2 baseline evidence (ADR-005 E0)

On 25 September 2026 Elci moved from Julc `d6e716e` (`0.1.0-elci-dev`) to Julc
`17da92bb`, the head of the stacked generic-backend revision-2 branch
(`0.1.0-gb-17da92b`). Evidence:

- All 86 Elci tests passed before and after the change.
- The stdlib inventory (`docs/stdlib-compatibility.tsv`) regenerated identically.
- All seven examples built with identical script bytes except `core-completion`.
  Its ABI manifests differ only in the embedded `julcVersion`.
- `core-completion` keeps its 827 FLAT bytes, its result (`15`) and its budget
  (CPU 11,311,095, memory 41,004), but its bytes change. Julc main `c95ee610`
  produces the same new bytes. The change therefore comes from the merged
  recursion-group fix (Julc #159), not from revision 2, which keeps
  revision-1 output byte-identical.

The pin then moved to Julc `97e168c6` (`0.1.0-gb-97e168c`), which adds PR #195
(producer-owned generic type arguments) on top of #192. Every example keeps
identical script bytes; ABI manifests differ only in `julcVersion`.

It then moved to Julc `f8e9cb87` (`0.1.0-gb-f8e9cb8`, PR #196), which adds the
following:

- typed map templates (`TypedMapLib`);
- identical library overloads, which make `ByteStringLib` importable;
- an order-preserving `JulcMap.keys()`/`values()`.

It then moved to `f97f8042` (`0.1.0-gb-f97f804`, PR #197), which adds julc-testkit's
schema-driven generators and property checks. PR #197 changes only the testkit.
Rebuilding every example with the current tree and with the previous commit
(`ba53190` on `f8e9cb8`) gives identical script bytes, results and budgets.
ABI manifests differ only in `julcVersion`.

## Maps and bytes evidence

- **`MapTest`:**
  - dictionary operations;
  - keys, values and `toList` in insertion order without duplicates;
  - record values and `PubKeyHash` keys inside a named validator;
  - the rejected remap of the core `Julc.TypedMap` alias;
  - `Julc.ByteString` imports.
- **`IntrinsicsTest.byteStringCompleteness`:** every added `Bytes` function
  evaluates to its builtin's result.
- **`ImportedTypeTest.libraryValuesAreEvaluatedAtScriptStartLikeTopLevelValues`:**
  records the linking semantics of library values in validators.
- **Stdlib inventory:** 151 compiling, 41 unsupported, 9 generic (`TypedMapLib`)
  and 0 blocked.

## Source-level testing evidence (ADR-006)

`elci test` is exercised by `TestCommandTest`, `IntrinsicsTest` and `InitTest`:

- The treasury example has 9 unit and scenario tests: scenarios for both validators
  across all their roles, and `Bool` and `Expectation` unit tests. The vesting
  example's 4 scenarios check validity ranges and signatures. Property tests, added
  later, are listed below.
- Failures show `Expect.equal` values, a rejected transaction, a budget ceiling
  broken through julc-testkit's `BudgetAssertions`, `Debug.trace` output and
  runtime errors, each without aborting other tests.
- Custom test directories work. Module shadowing, unknown validators and empty
  runs are diagnosed.
- `ElciTestkit` scripts pass julc-testkit's `ValidatorTest` checks, and their
  applied hashes equal those of the published artifacts.
- `elci init --validator` projects pass their generated scenarios for all six
  purposes.

## Property test evidence (ADR-006 amendment)

- **`TestCommandTest.propertiesShrinkToCounterexamplesAndReproduceFromTheSeed`:**
  - false properties shrink to their minimal counterexamples, shown in Elci syntax:
    `amountsStaySmall 100`, `debtsStaySmall (-5)` and
    `payoutsStaySmall { amount = 50, beneficiary = #(empty) } (Just 0)`;
  - an `Expectation` property reports its `Expect.atMost` values;
  - a scenario property reports the validator's rejection;
  - `--seed` reproduces identical output and `--fuzz` sets the run count;
  - invalid option values are rejected.
- **`TestCommandTest.propertiesNeedGeneratableConcreteParameters`:** function and
  polymorphic parameters are ELCI2009 errors.
- **`TestCommandTest.runsScenariosExpectationsAndBooleansWithBudgets`:** the helper
  functions `claim` and `badge`, which return `Scenario`, are not run as properties.
- **`JvmPropertyTest`:** jqwik with Julc's `SchemaArbitraries` over the treasury ABI,
  checking that unsigned spends are always rejected and that the beneficiary
  releases exactly the positive payouts.
- **`IntrinsicsTest.modByTakesTheDivisorsSignAndRemainderByTheDividends`:** covers
  `modBy`.
- **Examples:** the treasury example has 12 tests, including 3 properties. The
  vesting example has 5, including a validity-range property.
- **Native smoke test:** the native binary runs both example suites and a failing
  property with the same counterexample as the JVM. The treasury suite, with 300
  scenario runs, completes in under a second.

## Review gates

Before treating a new construct or adapter as supported, review its source
evaluation order, inferred and PIR types, runtime representation, malformed-data
behavior, deterministic output and execution cost. Serialized boundary changes
also require ABI comparison fixtures. Ledger adapters require both valid context
cases and adversarial malformed inputs.

Known unsupported behavior must produce a diagnostic rather than silently using
raw `Data`, Java runtime execution or an unverified representation. The language
guide is the authoritative feature matrix and must change with the implementation.

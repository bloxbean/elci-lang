# Validation and review

Elci is experimental. Passing these checks is evidence for the implemented
profile; it is not a production-security certification.

## Reproducible checks

Publish the pinned Julc development modules described in the getting-started
guide, then run:

```sh
./gradlew clean test
npm exec --yes --package elm-format@0.8.8 -- \
  elm-format examples src/main/resources/elci/core --validate
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile
build/native/elci check examples/threshold
build/native/elci build examples/threshold
build/native/elci eval examples/functional
```

The Java test suite covers parsing and diagnostics, inference, generic
specialization, strictness, recursion, modules, records, tuples, unions, lists,
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

## Review gates

Before treating a new construct or adapter as supported, review its source
evaluation order, inferred and PIR types, runtime representation, malformed-data
behavior, deterministic output and execution cost. Serialized boundary changes
also require ABI comparison fixtures. Ledger adapters require both valid context
cases and adversarial malformed inputs.

Known unsupported behavior must produce a diagnostic rather than silently using
raw `Data`, Java runtime execution or an unverified representation. The language
guide is the authoritative feature matrix and must change with the implementation.

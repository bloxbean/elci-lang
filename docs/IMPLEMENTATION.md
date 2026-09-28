# Elci implementation and verification log

The language and shared-boundary design ADRs govern this work. Elci is a separate Java 25 Gradle
application; the supplied Julc worktree owns all backend changes.

## Design status

- [ADR-002: functional core completion](../adr/002-functional-core-completion.md)
  defines C1–C5: patterns/destructuring, generic aliases, eligible local
  polymorphism, structural equality and general function recursion groups. These
  stages are implemented and covered by parser, type, evaluator and syntax-corpus
  tests.
- [ADR-003: generic library interoperability](../adr/003-generic-library-interop.md)
  proposes I1–I4: provider schemes, a real generic Java export, a programmatic PIR
  export and verified nominal/container adapters.
- [ADR-004: named validators](../adr/004-named-validators-and-multi-purpose-handlers.md)
  is implemented on Julc's revision-2 `ValidatorProgram`, with deployment
  parameters and datum profiles from ADR-005 E5.
- [ADR-005](../adr/005-remaining-frontend-and-backend-integration.md) tracks the
  remaining integration work and its progress.
- [ADR-007: named validators only](../adr/007-named-validators-only.md) removes
  `[entrypoint]` spending and minting validators; `[entrypoint]` is a function.
- [ADR-006: source-level tests](../adr/006-source-level-tests.md) adds `elci test`,
  the `Expect` and `Scenario` test library, `Debug.trace` and the JVM facade over
  julc-testkit.

ADR-003 remains proposed integration work. ADR-002's conservative value
restriction does not promise unrestricted let-polymorphism. General recursion is
implemented in Julc as a language-neutral PIR backend extension with independent
Java regression checks. Neither ADR completes the transaction-adapter or
marketplace milestone. The [language guide](LANGUAGE.md) describes implemented
behavior only.

## Sequence

1. Establish a neutral PIR backend API in Julc, extracting existing lowering
   without changing Java validator behavior. Materialize actual Java library
   sources behind an adapter. Test target and boundary rejection.
2. Implement source spans, token/layout parsing, modules, type checking,
   functional elaboration and direct PIR lowering. Establish the threshold
   validator and differential VM tests before expanding syntax.
3. Expand the functional core with recursion, generic helpers, data types and
   pattern matching, checking evaluation order and data representations.
4. Build project CLI, deterministic script and ABI artifacts, examples and
   guides. Compile with the supplied GraalVM and exercise the native CLI.
5. Review implementation against ADR acceptance gates; record evidence and
   remaining gaps explicitly.

## Decisions closed during inspection

* Syntax reference: Elm 0.19.1; syntax corpus formatter: elm-format 0.8.8.
  Parser code is original; no upstream compiler code is copied.
* Target: Julc's `plutus-v3-pv11-uplc-1.1.0`.
* JVM baseline: Java 25. Shared API initially experimental and version-pinned.
* Configuration: `elci.toml`, `[project]`, `[validators.<name>]` or an `[entrypoint]` function, `[java-modules]`, `[tests]`.
* Native image must include bundled on-chain Java sources and ledger resources.
* Julc LetRec supports finite function-only mutual recursion through a stable
  recursive Bekić decomposition; eager recursive values remain invalid.

## Invariants

No source-to-Java translation. No frontend copy of UPLC passes. No arbitrary
Java execution. One ledger context argument after deployment parameters.
Strict captures, lazy branches, exact integer arithmetic. Imports never silently
shadow one another. Unsupported syntax and types fail before backend lowering.

# Elci implementation and verification log

The two design ADRs govern this work. Elci is a separate Java 25 Gradle
application; the supplied Julc worktree owns all backend changes.

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
* Configuration: `elci.toml`, `[project]`, `[entrypoint]`, `[java-modules]`.
* Native image must include bundled on-chain Java sources and ledger resources.
* Existing Julc LetRec supports self recursion and two mutually recursive
  bindings; larger recursive groups require an extension or explicit diagnostic.

## Invariants

No source-to-Java translation. No frontend copy of UPLC passes. No arbitrary
Java execution. One ledger context argument after deployment parameters.
Strict captures, lazy branches, exact integer arithmetic. Imports never silently
shadow one another. Unsupported syntax and types fail before backend lowering.

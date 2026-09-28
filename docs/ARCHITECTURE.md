# Compiler architecture

Elci is a separate frontend over Julc's PIR and UPLC backend. It does not
translate source to Java and it does not maintain a second UPLC pipeline.

```text
.elm sources
    -> lexer and layout-aware parser
    -> module and name resolution
    -> type inference and semantic checks
    -> specialization and typed PIR lowering
    -> Julc boundary wrapping and shared PIR backend
    -> target-valid UPLC and FLAT/Plutus artifacts
```

## Frontend stages

`syntax` produces tokens and source AST nodes while retaining line, column and
offset information. [GRAMMAR.md](GRAMMAR.md) specifies what it accepts, and
[`tree-sitter-elci/`](../tree-sitter-elci/) is a tree-sitter grammar that follows it
for editors. `modules` establishes source ownership, resolves imports and
exposing lists, and qualifies source and Java-library names. Import cycles,
ambiguous names and private references fail here. It also loads the core library,
Elci source bundled under `src/main/resources/elci/core/`: `Basics`, `List`,
`Maybe`, `Result`, `String`, `Tuple`, `Map`, `Dict`, `Array`, `Value`, `Interval`
and `Tx`. Their names are reserved for project modules and `[java-modules]` keys.
The core modules are compiled with the program like any module, so a function a
program does not reach is never lowered.

`types` performs rank-1 inference, checks mandatory top-level signatures,
identifies recursive groups, checks supported equality/append constraints and
analyzes case coverage. Entrypoints must have concrete types.

`lowering` specializes reachable generic definitions and produces Julc PIR.
Strict calls, captures and local values preserve source evaluation behavior;
conditionals, Boolean short-circuiting and case branches remain selective. The
representation planner encodes lists, tuples, records and unions explicitly.

## Shared Julc boundary

Elci uses two revisions of Julc's neutral backend contract.

**Revision 1 (functions).** An `[entrypoint]` function, and each `elci test`
program, is a closed, specialized `FrontendProgram` with purpose `FUNCTION`: its
PIR term, PIR type, target and named types. Library exports are inlined at each
use. Julc checks the target and lexical closure and lowers it without a wrapper.

**Revision 2 (validators).** Each `[validators.<name>]` group becomes one
Julc `ValidatorProgram` (ADR-059):

- **imports:** one trusted library import group, materialized once per group, so
  shared private dependencies are linked once;
- **definitions:** the specialized top-level definitions reachable from any
  handler, with declared PIR types;
- **handlers:** purpose-indexed references to those definitions;
- **parameters:** deployment parameters in application order;
- **namedTypes:** Elci's records and unions.

The descriptor declares the capabilities it needs; Elci checks them against
`CompilerBackend.capabilities` before compiling. Julc then runs its structural
PIR verifier over Elci's definitions and handlers. It dispatches on the
ScriptInfo tag through the shared multi-validator wrapper and decodes only the
selected handler's datum and redeemer. Elci never builds its own dispatcher. The
one adapter Elci adds converts the optional-datum profile's Julc `Optional` into
Elci's differently tagged `Maybe`, as ordinary verified PIR.

Java on-chain libraries are exposed through a provider. The provider parses and
compiles their actual bundled Java source and returns closed PIR with transitive
dependencies. Elci never invokes those exports as ordinary JVM functions.

## Correctness boundaries

The frontend owns source typing, symbol hygiene, specialization and runtime
representation choices. Revision-1 function programs are checked for closure
only. Revision-2 programs also pass Julc's structural verifier, which
checks binding, typing and data-constructor agreement. It is not a proof of
semantic correctness.

Serialized data has deterministic representations: records use tag zero and
lexicographically sorted fields; union tags follow constructor declaration order;
tuples use tag zero and positional fields; lists use `ListData`. The ABI manifest
records these choices and can be compared with a pinned expected manifest.

The compiler is invocation-local: parsing, inference, specialization and library
materialization do not rely on mutable global frontend state. Compilation guards
specialization growth and recursive type expansion. These are compiler resource
limits, separate from Cardano's execution budget.

## Current limits

The supported surface is recorded in the language guide. In particular, ledger
records are context views rather than generally serializable user data, complex
ledger sums need dedicated verified adapters (`Value` operations are the core
`Value` module, over the CIP-153 builtins), and the Java provider currently
exposes concrete signatures. These restrictions are intended
to fail closed rather than erase unsupported types to `Data`.

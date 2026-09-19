# JuLC multi-frontend compiler: Elci frontend boundary design

Status: proposed architecture and first implementation sequence. No compiler changes or executable frontend are delivered by this document.

## 1. Decision

Use Elci, a separate Elm-syntax frontend project to establish a practical language-neutral compiler boundary in JuLC. Java remains the existing frontend; both share on-chain library implementations, validator wrapping where semantics agree, PIR lowering, UPLC generation, target validation, evaluation, and artifact services.

Language name: **Elci**, pronounced **“el-see.”** Repository/project name: `elci`.

**Elci — An Elm-syntax functional language for Cardano, powered by JuLC.**

Elci has its own language identity. Elm names the syntax reference and upstream tooling ecosystem; JuLC provides the shared compiler backend and on-chain libraries. This does not imply endorsement by Elm maintainers.

The first deliverable is a vertical slice, not a complete Elci implementation or an implementation of the full Elm ecosystem. It must compile syntactically ordinary Elm source calling an existing Java on-chain function into a valid UPLC validator. Subsequent slices exercise higher-order functions, generic exports, data encoding, and multiple script purposes.

## 2. Product and compatibility contract

- Keep Elm grammar: module/exposing declarations, Elm imports, separate type signatures, space-based application, lambdas, let/in, case/of, immutable records and record updates.
- Do not introduce `spend`, `contract`, Java annotations, or lowercase Java package imports inside `.elm` source. Entrypoints and Java module mappings belong to frontend project configuration.
- All accepted source uses Elm syntax. The prototype implements a documented subset and reports unsupported constructs; exact syntax is not a promise that every Elm program compiles.
- Elci is an Elm-syntax language targeting JuLC, not initially a compatible replacement for Elm's browser runtime, packages, ports, effects, numeric semantics, or compiler.
- General recursion is a planned core capability. The first slice is not advertised as a complete Turing-complete implementation. Ledger budgets still bound actual execution.
- Reuse supported Java on-chain stdlib functions, not arbitrary JVM methods. Language syntax does not expand the target's builtin capabilities.
- Java callers and Elci callers share implementation and representation, with explicit adaptation of currying and type presentation.
- Lean, Truffle source interpreters, a new settlement implementation, and new contract DSLs are outside this workstream.

## 3. Evidence from the current code

Source inspected at `bloxbean/julc` commit `bc7c8199674cdb02821f2bd7074793d7ff051e6a`. Reinspect the actual implementation branch before edits. This document does not claim the snapshot is the latest branch head or that proposed APIs have been compiled.

| Existing component | Observation | Boundary implication |
| --- | --- | --- |
| `JulcCompiler` | Coordinates Java parsing, type registration, entrypoint discovery, libraries, wrappers, PIR passes, UPLC and artifacts | Extract stages without copying this orchestration into the new project |
| `LibraryCompiler` | Package-level compile path accepts JavaParser CompilationUnits and Java TypeResolver | Needs a Java-library provider adapter |
| `StdlibLookup` / `CompositeStdlibLookup` | Provides typed/target-aware PIR call lowering keyed by Java class/method | Adapt implementation mechanism behind neutral exports |
| `PirTerm`, `PirType` | Lambda calculus, recursion, data matching, concrete named types; no general source TypeScheme | Keep source inference separate; add minimal export type schemes only as needed |
| `ValidatorWrapper`, `StrictBoundaryGenerator` | Shared-looking machinery exists but Java orchestration chooses boundary behavior | Accept explicit typed entrypoint and encoding policy |
| `CompilationContext` | Snapshots options and target; also accumulates diagnostics/optimization reporting | Share one resolved target, do not assume the entire object is immutable/thread-safe |
| `CompileResult` | Common program data plus Java-oriented parameter metadata | Preserve legacy facade and introduce neutral result/metadata only where needed |
| `ContractSchema` | Describes validator types/interfaces | Reuse; it is not a library export or executable transaction specification |
| `MathLib.max(BigInteger, BigInteger)` | Existing Java-source on-chain function | First cross-language call target |

## 4. Responsibility boundary

| Concern | Frontend owns | JuLC shared services own |
| --- | --- | --- |
| Grammar and project conventions | Parsing, imports, configuration interpretation | Source identity representation |
| Types | Inference, overload disambiguation, source-level constraints | Concrete PIR types, named identities, export contracts |
| Language semantics | Evaluation order, currying, pattern lowering, failure behavior | Documented PIR execution/representation invariants |
| Libraries | Source module aliases and argument adaptation | Export resolution, source-provider compilation, shared linking |
| Contract interface | Entrypoint selection, concrete datum/redeemer/parameter declarations | Supported wrapping and encoding validation |
| Code generation | Produce typed, specialized definitions | PIR checks/passes, UPLC generation/optimization, target checks |
| Diagnostics | Language-specific errors and locations | Common diagnostic transport and backend origins |
| Tooling | Elci project/editor integration with reuse of Elm tooling where compatible | Evaluation, script artifacts, existing CCL integration |

Do not expose JavaParser ASTs, Java TypeResolver, Elci frontend parse trees, or filesystem discovery assumptions across the public frontend/backend contract.

## 5. Proposed minimal API

Names are provisional. Prefer existing types over parallel copies.

```java
interface CompilerFrontend {
    FrontendResult compile(SourceBundle source,
                           FrontendServices services,
                           CompilationRequest request);
}

interface CompilerBackend {
    BackendResult compile(FrontendProgram program,
                          CompilationRequest request);
}
```

The common driver resolves a target and invokes stages. A project may also explicitly instantiate a frontend; ServiceLoader/plugin discovery is not a first-slice requirement.

### FrontendResult

Contains diagnostics and either a successful FrontendProgram or failure. Do not pass a partially failed program onward. Reuse CompilerDiagnostic where appropriate; namespace new frontend codes rather than hijacking existing meanings.

### FrontendProgram

Contains:

- Concrete typed PIR definitions with stable qualified symbol identities.
- Library symbol dependencies supplied/resolved by the shared library service.
- Named concrete types and representation definitions.
- Entrypoint descriptors and deployment parameters, in explicit ABI order.
- Source-origin information using existing source-location facilities where possible.
- Declared target requirements and the resolved target/profile identity used during lowering.

The stage is **unwrapped, specialized application/library definitions**. Entrypoint bodies are not already ledger wrappers. Backend owns wrapping exactly once, parameter ABI wrapping, shared PIR passes, UPLC lowering, optimization, and final target validation.

FrontendProgram is an in-memory Java API in v0. No stable serialized PIR protocol, remote plugin transport, or promise of compatibility with arbitrary versions of JuLC. Pin a compiler version and an experimental interface revision initially.

### Entrypoint descriptor

Include symbol identity, script purpose, ordered handler arguments, datum optionality, redeemer type, context view, result convention, parameter types/order, origin, and explicit data-decoding policy.

First slice uses the existing supported Plutus V3 spending wrapper with strict typed arguments and Bool-to-success/failure convention. The source has three logical arguments; deployed V3 script receives its ledger ScriptContext argument after any deployment parameters are applied. Do not create an incorrect three-ledger-argument V3 ABI.

Elci explicitly adopts the selected JuLC typed-boundary rules. A future Aiken-compatible frontend might need different rules; the backend must reject unsupported policies instead of silently applying Java defaults. Do not add arbitrary callbacks that bypass target/boundary validation.

## 6. Shared library services

```text
LibraryProvider.describe(module) -> exports and type descriptions
LibraryProvider.materialize(symbol, concreteTypes, target) -> PIR definitions/dependencies
```

These conceptual operations separate type information needed during inference from code generation after specialization.

Export metadata includes stable library/version identity, fully qualified symbol, public type scheme, source method arity, concrete encoding references, required target capabilities, and optional documentation/origin. Generic type schemes belong to the export/source-typing layer; backend PIR remains concrete initially. Never invent generic support by erasing unsupported types to Data.

The Java provider keeps JavaParser, source discovery, type registration, and LibraryCompiler internally. It handles both Java-source functions and programmatic PIR builtins/HOFs behind the same export interface. Existing StdlibLookup alone is not a complete generic export catalogue.

Resolve transitive dependencies, deduplicate by stable symbol and specialization, rename binders hygienically, and preserve recursive groups. Never rely on simple class names or first-match shadowing for cross-language linking. Cache only after correctness, with library content/version, concrete types, compiler revision, and target-sensitive settings in the key.

Expose only methods whose on-chain semantics and signatures are supported. Detect ambiguous Java overloads during binding generation; allow explicit aliases in configuration. No arbitrary Java reflection or execution of library functions at compile time to stand in for on-chain compilation.

## 7. Currying and type interoperability

Java's `max(BigInteger a, BigInteger b)` appears in Elci as `Int -> Int -> Int`. The provider exports argument order explicitly.

For `Math.max x y`, evaluate x then y according to the frontend's strict semantics and apply the shared function. For `let f = Math.max x in ...`, evaluate x when the partial application is created and capture that value once. An eta expansion that delays x until f is invoked would change failure and repeated-work behavior. Full-application optimization must preserve those rules.

Type mapping begins with BigInteger/Int and Bool, then adds byte arrays, supported lists/optional types, records, choices, and ledger types. Function-valued Java exports are supported only where the Java provider already exposes a valid on-chain HOF mechanism; do not promise arbitrary java.util.function interoperability.

Elm structural records and Java nominal records need an explicit bridge. Imported Java records remain opaque/nominal source types with exported constructors/accessors, expressed through valid Elm module/type syntax. Elci type aliases following Elm syntax remain structural. Do not silently treat every structurally similar Elm record as the same Java type.

An explicit boundary schema can assign a structural record a canonical on-chain field order; otherwise arbitrary source reordering must not silently alter an existing ABI. Imported ledger and Java types retain their original constructor tags and field order. Keep raw Bytes distinct from semantic hashes where the shared model requires nominal identity.

## 8. Language semantics baseline

Before expanding the grammar subset, record:

- Strict left-to-right application and binding evaluation.
- Short-circuit && and ||, selected-branch-only if/case behavior.
- Arbitrary-precision Int matching the shared integer representation.
- Explicit integer division/remainder and zero-division behavior; choose the matching existing library/builtin, not an assumed Java or Elm default.
- Rank-1 generic inference/specialization for supported functions; constrain unsupported recursive specialization instead of producing infinitely many instances.
- User-defined functions can be recursive; cyclic eager value initialization is a separate unsupported feature initially.
- Exhaustive matching, type-directed data representations, and no functions serialized as datum/redeemer.
- Unsupported Float, browser effects, ports, and other unsupported Elm constructs report clear errors. This is a target profile with Elm syntax, not full Elm semantics.

## 9. First vertical slice

Use a tiny arithmetic validator as a compiler fixture, **not as a secure application contract**.

```elm
module Example.Threshold exposing (validate)

import Julc.Math as Math
import Julc.Ledger exposing (ScriptContext)

validate : Int -> Int -> ScriptContext -> Bool
validate minimum offered _ =
    offered >= Math.max minimum 0
```

Illustrative Elci project configuration (TOML shape and filename to be finalized; not Elm's standard elm.json):

```toml
[entrypoint]
module = "Example.Threshold"
function = "validate"
purpose = "spend"
boundary = "julc-strict-v1"

[java_modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
```

Target and dependencies use the existing JuLC target vocabulary and pinned versions; do not invent a second protocol-version registry. Julc.Ledger is a generated/shared type module, not a browser Elm package.

Trace:

1. Elci's Elm-syntax parser creates the source AST with locations.
2. Bindings resolve Math.max and ScriptContext from shared exports.
3. Type checking confirms the three arguments and Bool result.
4. Provider compiles the existing MathLib Java implementation into PIR.
5. Elci frontend emits the comparison and library application, plus entrypoint metadata.
6. Backend links/checks definitions, builds the V3 wrapper, applies common passes and emits UPLC.
7. Existing VM/testkit evaluates full ledger-shaped contexts with integer datum/redeemer.

Acceptance cases: minimum 5/offered 6 succeeds; 5/4 fails; negative minimum/0 succeeds. Wrong datum/redeemer shape fails at the chosen boundary. Compare with equivalent Java compiled through the extracted pipeline. Do not claim marketplace/authorization security from this fixture.

## 10. Backend extraction strategy

Do not restructure every Gradle module first. Introduce a neutral API package inside julc-compiler, keeping JulcCompiler as the existing public compatibility facade. The Elci project initially depends on the pinned compiler artifact, even if that transitively contains Java implementation classes. API separation comes before physical dependency separation.

Extraction order:

1. Isolate the current post-wrapper passes, UPLC generation, optimization, target checks, and program result assembly without changing behavior.
2. Introduce explicit entrypoint metadata and move wrapper assembly into the backend without Java AST dependencies. Preserve existing Java decoding and source-map behavior.
3. Adapt Java compilation to produce the same unwrapped FrontendProgram used by Elci.
4. Expose Java library export/materialization services behind an adapter.
5. Add shared PIR validation where assumptions were previously guaranteed only by the Java producer.

Check bound variables, symbol closure, arities/type consistency where representable, named type references, representation legality, entrypoint signatures, and target requirements. PIR typing does not itself prove semantic preservation. Do not claim a full verifier on day one; list enforced invariants and remaining producer obligations.

Preserve parameter metadata and public CompileResult behavior through the Java facade. Neutral result metadata must not store Java source type strings as its universal ABI. Existing debug-mode optimization/source-map behavior must remain unchanged during extraction.

After two frontends work, consider physically extracting julc-compiler-api/backend/frontend-java artifacts. Avoid adding all modules as an upfront prerequisite.

## 11. Implementation PR sequence

| PR | Scope | Exit criterion |
| --- | --- | --- |
| 1 | Record ADR and backend extraction with Java adapter | Existing Java examples retain acceptance/failure, serialization, target and parameter behavior; identical artifacts where extraction should be behavior-preserving |
| 2 | Export metadata and Java provider for MathLib | Describe/materialize a real library function without exposing Java AST types through the API |
| 3 | Separate `elci` project; minimal parser/type checker and fixture | Exact sample syntax builds through the public API, no embedded copy of JulcCompiler |
| 4 | Cross-language currying, generics, records | Partial application and shared HOF/type representation cases pass, including negative tests |
| 5 | Entrypoint expansion and developer tooling | Additional purposes, configuration, diagnostics, formatter compatibility; documented feature matrix |

General recursion and library reuse must be proven before marketing a complete language. Pick realistic subsequent contracts only after these compiler contracts stabilize.

## 12. Required tests

- Regression: Java before/after extraction, including malformed boundary inputs, multi-validator dispatch, parameters, recursion, and optimized/debug configurations actually supported by the target.
- Differential: equivalent Java/Elci fixtures produce matching outcomes; record budgets separately, not require identical script bytes across languages.
- Strictness: a failing expression used as a captured partial-application argument fails at creation, even if the resulting closure is unused; untaken condition branches do not execute.
- Imports: unresolved names, overload ambiguity, version conflicts, cyclic dependencies, qualified-name collisions, generic specialization and transitive Java library calls.
- Data: round-trip fixed fixtures across frontend/provider/CCL, constructor tags, field ordering, optional values, wrong arity, and malformed nested data.
- Target: provider and frontend cannot silently switch versions or use unavailable builtins; final target checks remain active.
- Tooling: elm-format accepts the fixture; use Elm grammar for highlighting, but never trust stock Elm type diagnostics as authoritative for the JuLC profile.
- Costs: evaluate compile time, UPLC size and ledger budgets. No assertion that currying or a second frontend is automatically faster.

## 13. First decisions fixed; remaining decisions gated

Fixed: separate `elci` project using Elm syntax; unchanged grammar for supported constructs; shared Java libraries; pure functional execution; full raw ledger access as the language grows; explicit config entrypoints; Java compatibility facade; no new DSL/compiler feature unrelated to the slice.

Gate before PR 1: exact handoff type names and extraction points against current code; governing ADR number; target fixture.

Gate before PR 2: generic export representation and supported Java public type mappings; binding naming and collision rules.

Gate before public ABI release: canonical data encoding for native Elci types, version policy, module exports, feature/semantic compatibility documentation, and editor integration strategy.

## 14. Coding-agent handoff

Start with PR 1 and a concrete compilation fixture, not the whole language. Read current AGENTS.md, relevant compiler/library/strict-boundary ADRs and tests. Inspect the current branch rather than assuming the pinned paths remain exact. Preserve semantics, target provenance and existing Java APIs. Keep changes reversible and reviewable; do not change old wrappers or encodings to make Elci easier.

Produce: governing ADR, isolated backend API/extraction, Java regression results, and the precise public interface required by the separate frontend. Then prove MathLib export materialization before expanding Elci's supported Elm syntax. Report any failed/unavailable test gates honestly. Do not describe design-only deliverables as an implemented frontend.

## References

- [JuLC source baseline](https://github.com/bloxbean/julc/tree/bc7c8199674cdb02821f2bd7074793d7ff051e6a)
- [Java compilation pipeline](https://github.com/bloxbean/julc/blob/bc7c8199674cdb02821f2bd7074793d7ff051e6a/julc-compiler/src/main/java/org/julclang/compiler/JulcCompiler.java)
- [Java library compiler](https://github.com/bloxbean/julc/blob/bc7c8199674cdb02821f2bd7074793d7ff051e6a/julc-compiler/src/main/java/org/julclang/compiler/LibraryCompiler.java)
- [Existing MathLib](https://github.com/bloxbean/julc/blob/bc7c8199674cdb02821f2bd7074793d7ff051e6a/julc-stdlib/src/main/java/org/julclang/stdlib/lib/MathLib.java)
- [Elm function/type syntax](https://guide.elm-lang.org/types/reading_types)
- [Elm language server dependencies and capabilities](https://github.com/elm-tooling/elm-language-server)

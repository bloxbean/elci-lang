# ADR-001: Elci language syntax and compiler implementation

Status: **Proposed** — a design for implementation, not an implemented compiler or a production contract library.
Version: 0.1 · Date: 18 September 2026
Scope: Elci repository; companion to *JuLC multi-frontend compiler: Elci frontend boundary design*.

## 1. Decision

Build **Elci**, an Elm-syntax, strict, pure functional language for UTxO contracts, as a separate project using JuLC's shared compiler foundation and supported Java on-chain libraries.

**Use Elm syntax for every supported source construct.** Do not invent additional contract keywords, Java annotations, special loop syntax, or an alternative module grammar. Elci owns its type system profile, on-chain semantics, project configuration, and compilation pipeline. It does not promise compatibility with Elm's browser runtime or package ecosystem.

The boundary document defines how frontends connect to JuLC. This ADR defines the language that Elci users write, how its compiler is structured, and the sequence for implementing it. Proposed API names and configuration keys in this document are design sketches, not existing JuLC APIs.

Follow-on proposed decisions: [ADR-002: functional core completion](002-functional-core-completion.md)
specifies remaining patterns, aliases, local polymorphism, equality and recursion
gates; [ADR-003: generic library interoperability](003-generic-library-interop.md)
specifies provider schemes, concrete materialization and representation contracts.
These refine the roadmap; implementation status remains in the language guide.

### Goals

- A small language with immutable values, algebraic data types, pattern matching, higher-order functions, generics, and general recursion.
- Ordinary functional expressions capable of inspecting a complete transaction: many inputs, many outputs, reference inputs, minting, signatures, and validity intervals.
- Reuse of JuLC's supported Java library implementations, PIR infrastructure, UPLC backend, evaluator, target validation, and artifact services.
- An explicit, reusable compiler boundary that can later serve other frontends and domain-specific languages.
- Predictable evaluation, data encoding, failure behavior, and actionable compiler errors.

The language must not assume that every contract has one state UTxO, one continuation output, or one transition function. Transition and settlement helpers remain optional libraries.

### Non-goals for the first release

Browser Elm compatibility; arbitrary JVM library calls; Java-to-Elci library consumption; custom syntax; a new settlement framework; Lean generation; a source interpreter on Truffle; or a new backend optimizer. Elm editor support is a separate integration task: accepting Elm syntax does not make an existing Elm language server understand Elci's types or dependencies.

## 2. Syntax contract and release scope

Use the public Elm 0.19 language as the syntax reference. Pin a specific upstream syntax/tooling revision in the implementation repository before parser work. Maintain a syntax corpus checked against that reference. A supported Elci source file must also be syntactically valid Elm; it need not type-check with the Elm compiler.

Use `.elm` source files and standard Elm capitalization, comments, indentation, operator precedence, associativity, module headers, and import forms. The parser must track source spans and layout. Do not translate indentation into braces using regular expressions.

| Feature | First vertical slice | Core release |
| --- | --- | --- |
| Modules, imports, exposing, aliases | Yes | Yes |
| Function signatures and application | Yes | Yes |
| Int, Bool, conditionals, basic operators | Yes | Yes |
| Existing Java `MathLib.max` call | Yes | Yes |
| Lambdas and partial application | After slice | Yes |
| `let ... in`, local functions | After slice | Yes |
| Lists, pairs and triples | After slice | Yes |
| Closed records, aliases, updates | After slice | Yes |
| Custom union types and `case ... of` | After slice | Yes |
| Rank-1 polymorphism | After slice | Yes |
| Self-recursion and mutually recursive functions | After slice | Yes |
| Extensible record types and row polymorphism | No | Deferred |
| Float and `/` floating-point division | No | Unsupported in this profile |
| Ports, effects, browser APIs | No | Unsupported |

The core release is the intended small, computationally universal language. The first slice is only a compiler-boundary proof. On-chain execution always remains subject to transaction execution budgets; no termination guarantee is implied by type checking.

Initially support unit, arbitrary-precision `Int`, `Bool`, `String`, homogeneous lists, pairs, triples, closed structural records, custom union types, and function types. Bytes and ledger types come from compiler-recognized or imported modules. Character operations and additional Elm convenience constructs can be added separately without changing the grammar.

Unsupported syntax must receive a specific diagnostic. Where practical, recognize the construct before rejecting it; do not report an extensible record as a random unexpected brace.

## 3. What source code looks like

### 3.1 Functions, generic types, and records

```elm
module Market.Pricing exposing (Listing, Purchase(..), fee, mapMaybe)

import Julc.Math as Math

type alias Listing =
    { unitPrice : Int
    , remaining : Int
    , feeBps : Int
    }


type Purchase
    = Buy Int
    | Cancel


fee : Int -> Int -> Int
fee gross basisPoints =
    (gross * Math.max basisPoints 0) // 10000


mapMaybe : (a -> Maybe b) -> List a -> List b
mapMaybe transform items =
    case items of
        [] ->
            []

        first :: rest ->
            case transform first of
                Nothing ->
                    mapMaybe transform rest

                Just value ->
                    value :: mapMaybe transform rest
```

`Maybe` and `List` above are part of the proposed Elci core surface, not a dependency on Elm's runtime. `Math.max` resolves to a supported Java on-chain export. An import can expose `max` for unqualified use; qualified calls are a readability choice.

Aliases do not introduce nominal types. `Listing` is a name for its closed record shape. Custom types introduce nominal identities and constructors. Lowercase type variables are universally quantified at eligible function bindings.

### 3.2 Nested traversal with ordinary functions

The following is a pure settlement-checking example. It demonstrates nontrivial syntax, nested traversal, and associating payments with a consumed input reference. It is **not a complete marketplace validator**. Its normalized records must be derived from the real transaction by a trusted ledger adapter, which also performs decoding and the surrounding policy checks.

```elm
module Market.Settlement exposing (Obligation, Payment, settles)

import Julc.Ledger exposing (OutputReference)
import Julc.Bytes exposing (Bytes)


type alias Obligation =
    { origin : OutputReference
    , seller : Bytes
    , sellerDue : Int
    , feeRecipient : Bytes
    , feeDue : Int
    }


type alias Payment =
    { origin : OutputReference
    , recipient : Bytes
    , amount : Int
    }


paidTo : OutputReference -> Bytes -> List Payment -> Int
paidTo origin recipient payments =
    payments
        |> List.filter
            (\payment ->
                payment.origin == origin
                    && payment.recipient == recipient
            )
        |> List.foldl (\payment total -> total + payment.amount) 0


settlesOne : List Payment -> Obligation -> Bool
settlesOne payments obligation =
    let
        sellerPaid =
            paidTo obligation.origin obligation.seller payments

        feePaid =
            paidTo obligation.origin obligation.feeRecipient payments
    in
    obligation.sellerDue >= 0
        && obligation.feeDue >= 0
        && (if obligation.seller == obligation.feeRecipient then
                sellerPaid >= obligation.sellerDue + obligation.feeDue

            else
                sellerPaid >= obligation.sellerDue
                    && feePaid >= obligation.feeDue
           )


uniqueOrigins : List Obligation -> Bool
uniqueOrigins obligations =
    case obligations of
        [] ->
            True

        first :: rest ->
            List.all (\other -> other.origin /= first.origin) rest
                && uniqueOrigins rest


settles : List Obligation -> List Payment -> Bool
settles obligations payments =
    uniqueOrigins obligations
        && List.all (\payment -> payment.amount >= 0) payments
        && List.all (settlesOne payments) obligations
```

This uses Elm function syntax instead of a `for` construct. The nested work is explicit: each obligation scans payments. With `n` obligations and `m` payments, this implementation has O(nm + n²) traversal work, before accounting for integer and comparison costs. It is appropriate as a semantic fixture; a production library may use a more efficient allocation algorithm.

Each payment in this model has exactly one origin; an origin is an actual consumed input's output reference, not a user-selected order label. The real adapter must enumerate each qualifying output once, check the intended asset and recipient, decode the tag correctly, and bind obligations to authenticated listing inputs. It must not duplicate one output into several payments. The same recipient case adds both dues so the same funds cannot separately satisfy the seller and fee requirement.

The full validator additionally needs listing ownership and price checks, cancellation authorization, quantity and asset conservation, continuation rules where applicable, and validity constraints. Merely using this function does not prove those properties. The example intentionally allows multiple independent consumed listings and does not require a global state cell.

`Julc.Ledger`, `Julc.Bytes`, equality capabilities, and the Elci `List` surface are proposed interfaces. Their exact exports must be defined by the provider integration; these snippets are language-design fixtures, not code claimed to compile today.

## 4. Normative semantics for the core profile

### Evaluation

Use strict, deterministic, call-by-value evaluation. Evaluate a function expression and its arguments from left to right. Constructors and collection elements evaluate arguments in source order. Record fields evaluate in source order even if their physical representation uses a different order.

`if` evaluates only the selected branch. `&&` and `||` short-circuit. `case` evaluates its scrutinee once and then only the selected branch. A record update evaluates the base once, then replacement expressions in source order.

Local declarations may refer forward to other local declarations. Build a dependency graph. Evaluate acyclic value bindings in dependency order, using source order to break ties. Function-only recursive groups are allowed; eager value cycles are rejected. An unused strict binding may only be eliminated when doing so preserves termination and failure behavior. Backend optimizations must obey this requirement too.

Partial application captures evaluated arguments immediately. For a Java export presented as `f : A -> B -> C`, `f expressionA` must evaluate `expressionA` when that partial application is evaluated, not on every later call.

### Numbers and operators

`Int` is arbitrary precision, subject to resource limits. Integer literals infer `Int`; Elci does not implement Elm's full `number` constraint or Float semantics in this profile.

| Operation | Elci core decision |
| --- | --- |
| `+`, `-`, `*`, unary negation | Exact integer arithmetic |
| `//` | Integer quotient truncated toward zero |
| `remainderBy divisor value` | Remainder with the dividend's sign, or zero |
| Division or remainder by zero | Evaluation failure |
| Integer comparisons | Mathematical integer ordering |
| `==`, `/=` | Structural equality on supported equality-capable data; never functions |
| `++` | Lists and strings through compiler-known constraints |
| `::` | Prepend to a homogeneous list |
| `|>`, `<|`, `>>`, `<<` | Ordinary function application/composition with Elm precedence |

These are Elci decisions, not a claim of identical Elm runtime behavior. In particular, test all four signed division cases. Select a UPLC builtin or lowering sequence that actually implements truncation; do not substitute floor division. `modBy` may be added later with a separately specified negative-divisor policy.

An imported type supports equality only when its export descriptor provides a correct structural representation or an approved implementation. Never compare arbitrary raw encodings as a substitute for defined semantic equality.

### Types and patterns

Implement Hindley–Milner-style rank-1 inference with explicit, finite compiler-known constraints for equality, ordering, and append. This is not a user-extensible typeclass system. Public functions require signatures in the core release; local bindings can infer them. Recursive groups infer against monomorphic placeholders before generalization. Polymorphic recursion is unsupported.

Pattern matching supports constructors, literals of supported types, lists, cons, tuples, wildcards, variable bindings, record destructuring, and aliases as the implementation matures. Check exhaustiveness before lowering; non-exhaustive matches are errors. Report unreachable patterns. Lower matching to a decision tree without re-evaluating the scrutinee. Permit only irrefutable patterns in ordinary parameter/destructuring bindings; use `case` for refutable ones.

Closed records have structural types. Field access can be resolved from a known closed record type; unrestricted accessors requiring extensible row types are deferred. Function types may occur in computation but cannot occur in serialized datum, redeemer, or deployment-parameter schemas.

## 5. Modules, configuration, and entrypoints

Source paths follow Elm module names, for example `src/Market/Settlement.elm`. Imports must resolve unambiguously to a source module, an Elci core module, or a configured Java export module. Reject duplicate ownership and ambiguous exposed names. Do not silently prefer a Java provider over a source module. Reject import cycles initially; function recursion within a module remains supported.

A proposed `elci.toml`:

```toml
[project]
source-directories = ["src"]

[entrypoint]
module = "Example.Threshold"
function = "validate"
purpose = "spend"
boundary = "julc-strict-v1"

[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
```

Configuration spelling will be finalized with the CLI implementation. The compilation request must also pin a JuLC-supported target and dependency versions; the example omits their values rather than inventing a target identifier. Resolve defaults before frontend compilation and pass one immutable target identity throughout the pipeline.

The first fixture is:

```elm
module Example.Threshold exposing (validate)

import Julc.Ledger exposing (ScriptContext)
import Julc.Math as Math


validate : Int -> Int -> ScriptContext -> Bool
validate minimum offered _ =
    offered >= Math.max minimum 0
```

The first spend adapter interprets these arguments as required datum, redeemer, and script context. This fixed convention belongs to a versioned entrypoint profile. Later profiles describe deployment parameters, datum optionality, supported purposes, and result conventions explicitly. Do not guess roles from variable names.

Frontend output contains the unwrapped function plus a typed entrypoint descriptor. JuLC wraps it exactly once, decodes ledger data under the selected policy, and maps the result to the target's validator convention. Generic exported helpers are allowed; deployed entrypoints must have concrete types.

The context is an ordinary typed value. No syntax limits the number of consumed inputs or produced outputs, or requires the validator to find a successor state. Other purposes must have their own checked adapter descriptors rather than masquerading as spend functions.

## 6. Java stdlib interoperability

The Java/Kotlin analogy means one implementation and a shared supported type/representation contract. It does not mean JVM execution inside a validator.

A neutral `LibraryProvider` supplies export descriptions and materializes requested concrete implementations. Descriptions include stable qualified symbol identity, source arity, type scheme, runtime representation, target requirements, documentation, and dependency identities. JavaParser and Java type resolution stay inside the Java provider.

| Java on-chain surface | Elci presentation |
| --- | --- |
| `BigInteger` | `Int` |
| Supported boolean | `Bool` |
| Supported byte representation | Opaque `Bytes` |
| Supported list/container | Explicit mapped generic type and representation |
| Static method with two arguments | Curried `A -> B -> C` |
| Java record/ledger object | Nominal imported type with approved constructors/accessors |
| Overloaded methods | Explicit provider selection or distinct exported names |
| Null, reflection, mutable arbitrary objects | No automatic mapping |

A Java record and a structurally similar Elci record are not implicitly the same type. Generate explicit adapters only where the representation and validation rules are defined. Java exceptions do not automatically become an Elci `Result`; each supported export documents failure behavior.

For `Math.max minimum`, lower conceptually to a strict binding of `minimum` followed by a lambda accepting the second argument. Materialize the existing implementation once per required concrete specialization and target. Never duplicate its source into Elci's standard library.

Start with the real Java-source `MathLib.max` export. Then exercise a generic function, an imported record, and a programmatic PIR-backed export. The provider abstraction is incomplete if it only supports Java-source libraries and cannot describe the other existing stdlib implementation mechanisms.

## 7. Data representation and ABI

Separate source types from runtime representations. The representation planner runs after inference and specialization and records every encoding used across a serialized boundary.

For newly defined Elci serialized data, propose this initial profile:

- Custom union constructors use zero-based declaration order; fields use constructor argument order.
- Closed records use constructor tag zero and fields in lexicographic field-name order.
- Type aliases reuse the underlying representation.
- Lists and tuples use one documented codec per concrete representation; finalize these codecs against JuLC's existing supported encodings before enabling them at entrypoints.
- Imported Java types preserve their provider-declared representation exactly.
- Recursive data is supported only where the shared representation and decoder support it; function-containing shapes are rejected at serialization boundaries.

The choices above concern new Elci types. They must not override an existing Java type's encoding. Native computational representations and serialized Data representations may differ; insert explicit encoding and decoding operations.

Emit an ABI manifest containing constructor tags, field ordering, imported type identities, encodings, entrypoint roles, parameter order, target, and boundary-policy version. Golden fixtures must catch layout changes. Reordering union constructors or changing record fields can break an existing deployment; compile-time schema compatibility checks should reject drift when an expected ABI manifest is supplied. Never call a source-only change wire-compatible without comparing the schemas.

Decoders validate tags, arity, nested types, and the selected strictness policy. Malformed Data must fail according to the boundary contract. Successful inference is not evidence that untrusted ledger Data already has the inferred type.

## 8. Compiler implementation structure

Use Java for the first compiler implementation so integration, build tooling, and debugging fit the existing JuLC ecosystem. Start with a small number of build modules; package boundaries can enforce separation without creating a dozen published artifacts.

| Proposed package in Elci | Responsibility |
| --- | --- |
| `syntax` | Lexer, layout-aware parser, source AST, source spans |
| `modules` | Project sources, imports, exposing, module graph |
| `resolve` | Stable symbols, scopes, qualified references |
| `types` | Type variables, schemes, constraints, unification, inference |
| `analysis` | Recursive groups, pattern coverage, entrypoint validation |
| `core` | Typed elaborated expressions, explicit calls and pattern decisions |
| `specialize` | Reachable concrete instantiations and recursive specialization groups |
| `lowering` | Representation planning, strictness-preserving PIR construction |
| `interop` | Elci view of neutral library exports and adapters |
| `driver` | Compilation requests, stage orchestration, diagnostics |
| `cli` | Project configuration and build/check commands |

Keep editor services separate from the compiler core. The CLI and later language server call the same parser, resolver, and type checker.

### 8.1 Data structures

Use immutable Java records/sealed hierarchies where suitable. These names are illustrative:

```java
record SourceSpan(SourceId source, int startOffset, int endOffset) {}
record SymbolId(ModuleId module, String name, SymbolKind kind) {}
record TypeScheme(List<TypeVarId> quantified,
                  List<TypeConstraint> constraints,
                  SourceType body) {}
record TypedExpr(CoreExpr expression, SourceType type, SourceSpan span) {}
record SpecializationKey(SymbolId symbol,
                         List<ConcreteType> arguments,
                         TargetId target) {}
```

Source types include inference variables and schemes. JuLC PIR types are concrete backend types; do not force unresolved inference variables into them. Use stable symbol identities after resolution rather than string lookup during lowering. Keep source origins through generated adapters and specializations.

### 8.2 Pipeline and invariants

1. **Resolve project and target.** Pin dependencies, provider ownership, source identities, and configuration. No mutable global compilation state.
2. **Parse.** Build source AST with complete spans and layout information. Collect recoverable syntax errors.
3. **Resolve modules and names.** Apply import/exposing rules; assign symbols; detect cycles and duplicates.
4. **Infer and check types.** Instantiate library schemes, solve constraints, check annotations and public signatures.
5. **Analyze semantics.** Check pattern coverage, eager cycles, recursion groups, concrete entrypoints, and serializable boundary types.
6. **Elaborate.** Make currying, strict bindings, operators, constructor applications, and pattern decisions explicit in typed core.
7. **Specialize reachable definitions.** Work from configured entrypoints and parameters. Instantiate finite concrete generic uses; preserve recursive groups. Reject polymorphic recursion and unbounded specialization growth with an explanatory diagnostic.
8. **Plan representations and lower.** Translate typed core to unwrapped concrete PIR plus declared types, dependencies, entrypoint metadata, and source origins.
9. **Shared library materialization and linking.** Resolve requested concrete exports through the neutral provider. Preserve stable identities, hygienic binding, and dependency closure. Verify representation agreement.
10. **Shared backend.** Check PIR invariants, wrap the entrypoint once, apply supported passes, generate/optimize UPLC, validate target requirements, and produce artifacts.

The exact sequencing of demand-driven provider materialization may interleave with specialization. The invariant is that unresolved source schemes and frontend AST nodes never reach backend code generation.

General recursion may require an adapter from a multi-function recursive group to the recursion shapes JuLC currently supports. Prototype self and mutual recursion before committing to a particular encoding; the presence of a `LetRec` node alone is not proof that every recursive source construct is supported.

### 8.3 Lowering requirements

| Elci construct | Lowering obligation |
| --- | --- |
| Function/lambda | Nested lambda/application with explicit strict captures |
| `if`, `&&`, `||` | Preserve branch laziness; do not eager-evaluate both branches |
| `case` | One scrutinee binding plus exhaustive decision tree |
| Generic function | Concrete specialization or an explicitly supported erased representation |
| Record update | Evaluate base once, reuse unchanged fields, rebuild immutable value |
| List traversal | Library call or recursive function; no new loop primitive required |
| Java export call | Typed provider symbol, arity adaptation, concrete materialization |
| Custom type | Approved constructor/matcher representation and stable schema |
| Entrypoint | Unwrapped function plus metadata; shared backend owns wrapper |

PIR nodes have representation invariants. For example, do not use a matcher intended for a native `List<Data>` on any source list merely because both are called lists. The planner must choose and validate a compatible representation or emit conversions.

## 9. Changes required in JuLC

At inspected commit `bc7c8199674cdb02821f2bd7074793d7ff051e6a`, orchestration and library compilation are not yet a complete language-neutral public service. Reinspect the actual implementation branch before coding.

Introduce the smallest public service layer inside the existing compiler module first:

- A backend request accepting specialized, unwrapped typed PIR and explicit entrypoint metadata.
- Neutral library descriptions and a Java provider adapter around existing library compilation/lookup mechanisms.
- Shared target identity, diagnostics, source origins, schema metadata, and backend results.
- Explicit validation of concrete types, symbol closure, representation agreement, and required target capabilities.

Keep the existing `JulcCompiler` API as a compatibility facade. Extract its behavior incrementally and demonstrate Java regression equivalence before routing Elci through it. Do not duplicate the compiler pipeline into the Elci repository or expose JavaParser types in the neutral API.

The interface is initially versioned and in-memory. A persistent PIR interchange format and independent binary compatibility guarantees are outside the first implementation.

## 10. Diagnostics and tooling

Diagnostics carry a stable code, severity, source span, primary explanation, and relevant secondary locations. Examples:

- Unsupported Float literal: “Elci's on-chain profile supports Int; represent fractional amounts using an explicit scale.”
- Missing field: show the expected closed record and available fields.
- Non-exhaustive match: show a missing constructor/example pattern.
- Unsupported Java export: identify the unmappable parameter and qualified method.
- Polymorphic recursion: show the recursive call and incompatible instantiation.
- ABI drift: show the changed constructor index or field sequence.

Elm-aware highlighting and formatting are reuse candidates because syntax remains Elm. Validate the syntax corpus with a pinned formatter. Elci still needs its own semantic editor integration for imports, generic inference, Java exports, target-specific errors, and project configuration. Never advertise arbitrary Elm editors as fully working merely because they color the files correctly.

## 11. Implementation sequence and acceptance gates

| Milestone | Deliverable | Required acceptance evidence |
| --- | --- | --- |
| M0: shared boundary | Minimal JuLC backend extraction and provider contract | Existing Java regression suite passes; equivalent Java artifacts remain unchanged where extraction should be neutral |
| M1: first Elci validator | Parser/type checker subset, `Math.max` provider call, threshold spend fixture | Accept datum 5/redeemer 6; reject 5/4; accept negative minimum/0; malformed data rejected; deployable target-valid output |
| M2: functional core | Lambdas, local bindings, partial application, lists, self/mutual recursion | Failure/evaluation-order fixtures, recursive traversal, no eager evaluation of unused branches |
| M3: types and interop | ADTs, closed records, generics, pattern analysis, imported nominal types | Generic specialization, exhaustive matching errors, data codec fixtures, provider dependency closure |
| M4: UTxO proof | Full transaction adapter and multi-listing marketplace test contract | Two independent listings, mixed seller/fee recipients, cancellation, asset checks, adversarial double-satisfaction attempts |
| M5: release tooling | CLI diagnostics, ABI manifests, dependency pinning, editor baseline | Reproducible builds, stable schema checks, budget measurements, documented supported syntax |

Each milestone can exercise a narrowly scoped prerequisite early. For example, M2 can use concrete lists before generic list APIs are complete. Do not claim the whole core release is complete at M1.

For M4, test at least: reusing one payment for different input origins; duplicating payment entries; seller equal to fee recipient; duplicate obligations; wrong asset; correct tag but wrong recipient; incorrect origin; insufficient aggregate payment; malformed tags; unauthorized cancellation; and transactions without any singleton state cell. Define adapter behavior before using the earlier normalized example as a validator component.

### Testing strategy

Use focused tests for semantic risks, not snapshots of every internal class. Maintain parser golden cases, inference success/failure cases, signed arithmetic cases, strictness/currying cases, recursion and specialization cases, wire-format fixtures, and evaluator-based end-to-end tests. Test equivalent Java and Elci functions for behavior; different frontend lowering need not produce identical bytes.

Measure UPLC size, CPU, and memory for representative traversals at increasing transaction sizes. Functional syntax does not imply efficient code automatically. Optimization must preserve observable failure, evaluation order where relevant, and representation correctness.

## 12. Alternatives and consequences

**A new bespoke grammar:** rejected initially. It adds parser, formatter, learning, and editor work without establishing the shared backend boundary any better.

**Reuse Elm's compiler unchanged:** rejected as the architecture. Its runtime, type assumptions, packages, and backend are not the JuLC on-chain profile. Reuse compatible syntax specifications and tooling where licensing and integration permit.

**Translate Elci source to Java source:** rejected as the primary compilation path. It obscures source diagnostics, forces functional semantics through Java syntax, and weakens the neutral frontend boundary. Produce typed PIR through shared services.

**Add imperative loop syntax:** rejected. Folds, recursion, and higher-order functions already express nested traversal while keeping ordinary Elm syntax. A future library can expose specialized traversal helpers without changing the language.

**Require all contracts to be transitions:** rejected. It excludes useful transaction-wide validation and multi-input settlement structures.

The principal costs are a real type checker, representation/ABI design, provider metadata, strictness-preserving lowering, and semantic editor support. Elm syntax saves language-design and some tooling effort; JuLC reuse saves backend and library work. Neither removes frontend implementation work.

## 13. Decisions to close during implementation

The following are bounded engineering gates, not reasons to redesign the language:

1. Pin syntax reference/formatter versions and verify redistribution licenses for any reused grammar or code.
2. Confirm JuLC recursion support and select the encoding for mutual function groups.
3. Confirm exact list/tuple/recursive-data codecs and builtin arithmetic mappings with evaluator fixtures.
4. Define the minimal shared export type-scheme model and stable symbol identities.
5. Finalize CLI configuration keys, target selection, and schema manifest format.

If a shared capability is absent, add a focused backend/provider extension with its own tests; do not silently narrow source semantics to make the example compile.

## 14. References and reading order

Read the existing *JuLC multi-frontend compiler: Elci frontend boundary design* first for interface ownership, then this ADR for language and implementation decisions.

Syntax references: [Elm core language guide](https://guide.elm-lang.org/core_language), [Elm type signatures and variables](https://guide.elm-lang.org/types/reading_types), and [Elm custom types](https://guide.elm-lang.org/types/custom_types). These describe Elm; deviations and restrictions in this ADR define the proposed Elci profile.

Implementation baseline: [JuLC source snapshot](https://github.com/bloxbean/julc/tree/bc7c8199674cdb02821f2bd7074793d7ff051e6a). Relevant implementation areas include `JulcCompiler`, `LibraryCompiler`, `StdlibLookup`, `PirTerm`, `PirType`, and `ValidatorWrapper`. The APIs proposed here must be reconciled with the implementation branch before coding.

This ADR specifies a design and acceptance criteria. It does not establish that its sample contracts are audited, that proposed modules already exist, or that compiler compatibility has already been demonstrated.

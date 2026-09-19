# ADR-002: Elci functional core completion

Status: **Implemented for the core language profile** — diagnostic refinements remain.
Date: 19 September 2026
Baseline: Elci `974bd41`; refinements to [ADR-001](elci-language-syntax-compiler-design-adr.md).

## Context and scope

The current compiler implements the major expression forms, top-level generics,
closed records, regular recursive unions and a restricted pattern language.
Completing the functional core requires coordinated parser, type checker,
coverage analysis and lowering changes. Accepting syntax alone is insufficient.

This ADR specifies patterns and destructuring, generic aliases, local
polymorphism, structural equality and general function recursion groups. It
refines ADR-001 rather than replacing its evaluation, ABI or backend contracts.
Generic Java exports and provider metadata are governed separately by
[ADR-003](003-generic-library-interop.md).

“Functional core complete” means the gates in this ADR pass for the documented
profile. It does not mean full Elm compatibility, complete ledger adapters,
completion of ADR-001's marketplace milestone, or production readiness.

## Invariants and exclusions

Use Elm 0.19.1 syntax and elm-format 0.8.8 for every supported construct. Preserve
strict left-to-right application, immediate partial-application capture, strict
local values, selective branches and dependency-ordered local initialization.
Preserve existing codecs, union tags, nominal identities and entrypoint roles.

Row polymorphism and extensible records remain deferred. Float, ports, browser
effects, polymorphic recursion, higher-rank polymorphism and user-defined
typeclasses remain unsupported. Import cycles remain rejected. No new contract
keywords, loop syntax or implicit Java execution are introduced.

## 1. Patterns and destructuring

Retain one recursive pattern node and use compiler-reserved tags for product,
record, unit and alias forms. The lexer cannot produce the reserved `$elci$`/`$`
names from user source. Retain literal token kind/value, resolved constructor
identity and inferred field information through coverage analysis and lowering.
Tokens provide offsets and start locations; richer end spans and secondary
locations are a diagnostic refinement rather than a language gate.

| Form | Decision |
| --- | --- |
| Variables and `_` | Bind one name or ignore the value |
| Constructors | Permit nested patterns with checked constructor arity |
| Lists and cons | `[a, b]` matches exactly two elements; `a :: rest` matches a nonempty list |
| Pairs and triples | Permit nested component patterns |
| Record patterns | `{ price, owner }` selects named fields from a known closed structural record |
| Pattern aliases | `pattern as name` binds the entire matched value without reconstruction |
| Literals | Bool, Int and String, including supported negative integer syntax and string escapes |
| Unit | `()` matches the unique unit value in computation |

Examples of nested case branches are `Just (first :: rest)`, `[ first, second ]`,
`(first :: rest) as items`, and `"buy"`. Their legality in ordinary parameter
positions is a separate rule.

### Scope and irrefutability

Case-branch names exist only in that branch. Reject duplicate binders anywhere in
one pattern, including aliases and record names, and across one function's
parameter list. `_` is never a binder. Distinct lexical scopes may shadow names;
resolved identities must prevent accidental capture during specialization.

Function and lambda parameters and local destructuring bindings accept only
irrefutable patterns: variables, wildcards, unit, records, and tuples recursively
containing irrefutable patterns, with aliases where permitted by Elm grammar.
Constructor, list, cons and non-unit literal patterns are restricted to `case`,
including single-constructor unions for this release. Diagnostics direct users
to an explicit case expression instead of inserting a failing implicit match.

```elm
sumPair : ( Int, Int ) -> Int
sumPair ( a, b ) =
    a + b


sumLocal : ( Int, Int ) -> Int
sumLocal pair =
    let
        ( a, b ) =
            pair
    in
    a + b
```

A record pattern may select a subset of an already known closed record's fields.
It must not infer a row-polymorphic function. Defer the field constraint until
surrounding annotations or unification establish the record type; otherwise
report a missing closed-record annotation. Missing fields are errors. Imported
nominal records do not acquire structural destructuring implicitly.

### Evaluation and lowering

Evaluate a case scrutinee once, then select the first matching branch in source
order. Check outer constructors or list shape before projecting nested fields.
Failure to match is distinct from a decoding error; do not catch runtime errors
and treat them as a request to try another branch.

Elaborate parameter patterns inside the lambda for that parameter. Earlier
arguments remain evaluated and captured when partial application is created;
patterns for unapplied arguments are not evaluated early.

A local destructuring declaration is one dependency-graph node with multiple
binders. Evaluate its right-hand side once at that node, even when its binders are
unused. Projections share the result. Include all bound names when resolving
forward references and eager cycles. Do not clone the right-hand side for each
projection or each use.

Use a typed decision tree with explicit strict temporary bindings for matching.
Represent list literals as nil/cons patterns for analysis; they do not require
runtime list construction. Match aliases refer to existing temporaries.

### Coverage and reachability

Use a pattern-matrix usefulness/exhaustiveness analysis over resolved types.
Account for nested combinations, not merely the set of outer constructors.
Lists have nil/cons alternatives; tuples and closed records have one product
shape; unions have their declared constructor set.

Int and String have open literal domains: finite literal branches require a
catch-all for exhaustiveness. Normalize literal values before duplicate checks.
Report non-exhaustiveness and reject unreachable branches, preserving the current
compiler policy. Missing-example rendering and secondary covering locations may
be added without changing acceptance. Resource exhaustion must never count as
proof of exhaustiveness.

## 2. Generic type aliases

Accept parameterized aliases such as:

```elm
type alias Box a =
    { value : a }
```

Resolve alias names and parameter scopes before expansion. Check distinct
parameters, declared free variables and exact application arity. Expand by
capture-avoiding substitution, retaining the alias use and definition spans for
diagnostics. Aliases have no nominal identity and introduce no new runtime codec.
Imported aliases obey module exposing rules; do not expose private constructors
through an alias to a nominal union.

Reject direct and indirect cyclic alias expansion, including cycles beneath
container applications. Recursive data must pass through a nominal union
definition, whose body is not recursively expanded as an alias. Bound expansion
size separately from cycle detection and report the expansion chain on failure.

Record aliases support Elm-style constructor functions: alias parameters infer
normally, constructor arguments follow declared field order, and arguments
evaluate in call order. Construction then places fields in canonical runtime
order. Aliases to other types do not create constructors. Alias constructor
export behavior must be checked against the pinned Elm syntax/module fixtures.

Alias applications and their expanded shapes must produce equivalent wire schemas
after normalization. Preserve existing ABI manifest version and encoding rules;
if descriptive source-type labels differ, distinguish that from wire-layout
drift and specify any manifest-comparison change before implementing it.

## 3. Local polymorphism and strictness

Infer each dependency SCC with monomorphic placeholders. Solve its constraints
before generalization; quantify only variables absent from the surrounding
environment. Recursive calls inside an SCC use its monotypes. Generalize eligible
members only after the entire group has been checked. Reject polymorphic
recursion with the incompatible recursive instantiation shown.

Adopt a conservative value restriction for this implementation: generalize
non-expansive bindings, including function declarations, lambdas, identifiers,
literals, and immutable constructors/tuples/lists/records whose components are
non-expansive. Calls, conditionals, matches and record updates are expansive and
remain monomorphic. Destructuring projects schemes from one non-expansive
right-hand side; an expansive right-hand side leaves all projections monomorphic.

This explicitly narrows ADR-001's unspecified eligibility for local rank-1
generalization. It prevents monomorphization from duplicating or postponing an
arbitrary strict computation. Generalizing all pure expressions is deferred
until a representation strategy proves evaluation sharing and failure timing.

```elm
localIdentity : ( Int, Bool )
localIdentity =
    let
        identity value =
            value
    in
    ( identity 1, identity True )
```

Specialize eligible local functions by resolved identity, concrete type arguments
and captured environment. Captures refer to previously evaluated bindings;
specialized closures must not reevaluate them. Variables free in an enclosing
lambda remain monomorphic, even when referenced by a generalized inner helper.
Annotations cannot generalize captured variables or override the value restriction.

Keep compiler-known equality and append requirements in schemes and instantiate
them at every use. No requirement may be lost by annotation checking,
generalization, copying or exporting a binding. Show retained requirements in
diagnostics; an ordinary type-variable annotation cannot promise unrestricted
uses of a helper whose body requires equality. No constraint syntax is added.

## 4. Structural equality

Both operands evaluate once, left to right, before comparison. `/=` negates the
same comparison used by `==`. Do not replace semantic equality with serialization
byte equality.

| Type | Equality rule |
| --- | --- |
| Int, Bool, String, Bytes, unit | Value equality; strings use exact contents without normalization |
| List | Same length and pairwise equal elements |
| Tuple | Pairwise equal components |
| Closed record | Same structural type; compare corresponding canonical fields |
| Union | Same nominal type and constructor, then pairwise equal fields |
| Alias | Expanded underlying type's rule |
| Imported nominal type | Only a provider-declared verified equality capability |
| Function, opaque Data, unsupported imported type | Compile-time rejection |

Eligibility is structural over concrete type definitions, including all variants
of a union. A function in any reachable field makes the containing type
ineligible, even when a particular value uses another constructor. For recursive
types, analyze the finite graph of regular instantiated types with cycle tracking;
accept a recursive cycle only when every nonrecursive obligation is supported.

After checking structural eligibility, compare nonprimitive values through their
canonical Plutus Data trees with `EqualsData`; this is structural Data equality,
not CBOR or serialized-byte equality. Primitive values use their typed builtins.
Evaluate both operands once, left to right. Input decoding still follows the
strict boundary policy, so comparison cannot bypass required validation of
malformed input data. Equality does not expand ordering beyond Int.

## 5. General mutually recursive function groups

The language accepts finite function-only SCCs without a semantic limit of two
members. Mixed eager-value cycles remain errors. Specialization operates on the
whole group and preserves concrete recursive call signatures; polymorphic
recursion and unbounded specialization growth fail explicitly.

The implementation target is a shared Julc lowering for n-ary function recursion,
with deferred function bodies so tying the recursive environment cannot eagerly
evaluate the recursion. Its exact typed PIR encoding requires a separate generic
Julc backend ADR and prototype. Do not force differently typed functions through
an untyped Data dispatcher or change existing Java recursion semantics.

The backend gate is implemented by Julc's language-neutral general function
recursion ADR. Groups larger than two use its stable recursive Bekić decomposition.
The Julc document and review describe generic compiler functionality without
naming this private frontend.

## 6. Diagnostics and syntax corpus

Preserve source identity and start/end locations through resolution, alias
expansion, coverage decisions and specialization. Errors in imported modules must
identify that module, with relevant use sites as secondary locations. Allocate
stable diagnostic codes in an Elci catalogue during implementation; do not reuse
one parser error for every unsupported feature.

Maintain three corpus categories: valid supported source, Elm-valid but
unsupported source, and malformed source. Validate the first two with pinned
elm-format, and test Elci's expected acceptance or rejection separately. Include
layout, comments, escapes, operator precedence, qualified constructors and module
exposing forms. Formatting acceptance proves syntax compatibility, not type or
runtime equivalence with Elm.

## 7. Implementation stages and acceptance gates

| Stage | Deliverable | Required evidence |
| --- | --- | --- |
| C1 | Typed patterns, coverage and destructuring | Nested constructor/list/product combinations; exact list lengths; aliases and record subsets; missing-case witnesses; unreachable branches; duplicate binders; invalid parameter patterns; scrutinee/RHS evaluated once |
| C2 | Generic aliases and record constructors | Nested/imported applications at two types; arity and free-variable rejection; alias cycles; constructor evaluation order; normalized schema equivalence |
| C3 | Eligible local polymorphism | One local helper at Int and Bool; captured-variable rejection; monomorphic recursion; expansive-binding restriction; strict unused failure/divergence and capture timing; constraint retention |
| C4 | Structural equality | Equal/unequal lists, tuples, records and recursive unions; nested function rejection; nominal mismatch; unsupported imported types; strict operands; malformed boundary fixtures |
| C5 | General recursion groups | Three-plus heterogeneous functions with differing arities; terminating and budget-exhausting calls; dependency isolation; polymorphic recursion rejection; shared backend Java regressions |

Every stage requires parser/type success and failure tests, evaluator-based
semantic tests, deterministic compilation, and JVM/native CLI smoke tests for its
representative fixture. Use bounded VM execution for divergence tests. Record
script size and CPU/memory at increasing input sizes for matching, equality and
recursion; review regressions against fixed fixtures and budgets before landing.

Keep existing Elci tests passing. When a stage changes Julc, run the relevant
Java compiler, stdlib and backend suites plus before/after artifact comparisons
where behavior should be unchanged. Update the implemented language matrix only
after that stage's gates pass. The syntax corpus and diagnostic requirements
apply throughout C1–C5 rather than forming an optional final cleanup.

## Alternatives, risks and open engineering gates

Ad hoc nested-case expansion without coverage analysis is rejected: it can hide
missing combinations and duplicate scrutinee work. Full local generalization by
cloning strict expressions is rejected because it changes evaluation. Raw Data
equality is rejected for types without an explicit semantic equality contract.
An Elci-only recursion encoding is deferred in favor of shared backend ownership.

Pattern matrices, alias expansion, specialization and generated comparators can
grow rapidly. Their limits must be deterministic and diagnosed, never silent
fallbacks. Source spans and typed elaboration may require refactoring the current
compact AST/inference implementation before features are added.

The n-ary recursion encoding, bounded specialization/alias expansion, and
diagnostic catalogue are now implemented. Future work may improve full source
spans, missing-case witnesses and secondary locations without expanding the
language profile. Changes to the decisions above require an ADR amendment with
compatibility consequences, not an undocumented implementation shortcut.

# ADR-003: Generic library interoperability

Status: **Proposed** — provider extensions and acceptance gates remain open.
Date: 19 September 2026
Baseline: Elci `974bd41`; companion to [ADR-001](elci-language-syntax-compiler-design-adr.md),
[ADR-002](002-functional-core-completion.md) and the
[shared boundary design](julc-elm-frontend-boundary-design.md).

## Context and scope

The initial Java library provider describes concrete public static exports and
materializes closed PIR. It is insufficient for generic methods, imported type
capabilities and programmatic PIR implementations. Elci's type inference needs
export schemes before code generation; Julc needs concrete specializations and
representation agreements afterward.

Extend the neutral provider contract inside Julc and adapt it in Elci. Preserve
the existing Java compiler facade and library behavior. Keep JavaParser, Java
type resolution and implementation discovery behind the provider. Public Julc
ADRs and PRs describe generic DSL services without naming private consumers.

This ADR does not authorize arbitrary JVM libraries, reflection-based execution,
all Java generics, automatic ledger-record serialization, or an erased generic
PIR ABI. Complete transaction adapters remain a separate milestone.

## 1. Export descriptions and type schemes

Describe exports without compiling an implementation for an arbitrary guessed
type. Each descriptor carries:

- Stable module, library version/content identity and qualified export symbol.
- Source arity and ordered parameters; quantified type variables and type body.
- Finite compiler-known requirements such as equality or a supported container
  representation, with explicit supported substitutions.
- Nominal type identities, type-parameter arities, representation references and
  approved constructor/accessor/equality capabilities.
- Target requirements, implementation identity and diagnostic origin.

Reuse existing shared types where they express this information. The scheme
model is source/export metadata, not a new unresolved variable inside `PirType`.
An export unsupported by the provider must be diagnosed by symbol and reason;
do not advertise an erased `Data` signature as generic support.

Elci instantiates schemes freshly at each use, preserves requirements through
local generalization, and resolves them before materialization. Entrypoints stay
concrete. Java method type variables and their bounds require explicit mappings;
wildcards, arbitrary inheritance bounds, null and raw containers have no implicit
mapping. Overloads require unambiguous descriptor identities and explicit export
selection or aliases; source-order selection is forbidden.

## 2. Materialization and linking

Conceptually request an implementation by export identity, concrete type
arguments, representation choices and resolved target. Materialization returns
concrete typed PIR plus dependency and named-type metadata, or an actionable
unsupported-instantiation diagnostic.

Deduplicate specializations by library content/version, export identity, concrete
types, representation-policy revision, compiler/API revision and target-sensitive
options. Do not add persistent caching until these identities and invalidation
tests are established. Invocation-local memoization must not leak mutable
inference state between requests.

Resolve transitive dependencies with stable qualified symbols and hygienic
binders. Detect conflicting ownership, unavailable target capabilities, missing
dependencies and incompatible representations before backend generation. Preserve
supported recursive dependency groups and report current backend limitations.
No unresolved scheme or Java AST crosses into shared PIR lowering.

Java-source and programmatic PIR providers implement the same description and
materialization contract. Their implementation mechanism does not affect import
syntax or source typing. Do not recreate existing library source in Elci or
evaluate a JVM method to substitute for generated on-chain computation.

## 3. Calling convention and representations

Present supported multiargument exports as curried functions in declared argument
order. Evaluate the function and supplied arguments left to right. A partial
application evaluates and captures each supplied argument immediately and once;
later application must not reevaluate it. Higher-order exports are enabled only
when the provider supplies an explicit on-chain function calling convention.
No general mapping for arbitrary `java.util.function` objects is implied.

Container mappings describe both computational and serialized representations.
Native `List<Data>`, encoded `ListData` and a list of native integers cannot be
interchanged because their source names resemble one another. Conversions are
explicit typed operations with defined failure behavior.

Imported records, sums, newtypes and hashes retain nominal identity and existing
encodings. Exposing approved accessors does not make an imported type structural.
Elci `Maybe` and Java Optional require an explicit adapter when their tags differ.
Raw byte types and nominal hashes do not unify automatically.

Special ledger representations such as map-backed `Value` require dedicated
metadata and verified operations. A Java record declaration alone is not proof
of constructor-encoded runtime layout. Keep unverified fields/constructors opaque;
do not synthesize a record projection or enable boundary serialization by guess.

Imported equality is allowed only when the provider supplies an approved
implementation or a complete verified structural contract. An exception/failure
does not become `Maybe` or `Result` without an explicitly specified adapter.

## 4. Compatibility and versioning

Pin the experimental provider API revision and Julc dependency. Detect incompatible
metadata versions explicitly. Add an API extension or versioned adapter rather
than changing existing Java-facing signatures or wrappers silently.

Any Julc change receives a generic backend/provider ADR, focused tests and Java
regression checks in that repository. Elci documents supported exports and their
constraints separately from general language syntax. The existing concrete
`Math.max` path remains a regression fixture throughout this work.

## 5. Delivery stages and acceptance gates

| Stage | Deliverable | Required evidence |
| --- | --- | --- |
| I1 | Versioned neutral scheme/type metadata | Concrete export compatibility; fresh instantiation; retained constraints; rejected raw/wildcard/unmapped signatures; nominal separation and overload ambiguity |
| I2 | One real generic Java-source export | Same implementation at two distinct concrete types; valid VM results; unsupported substitution errors; strict partial capture; transitive dependency closure |
| I3 | One actual programmatic PIR export | Same import/type/materialization path; target rejection; supported HOF calling convention where applicable; no frontend implementation copy |
| I4 | One verified nominal record and explicit container adapter | Constructor/accessor round trips, wrong tags/arity/nested kinds, Java/Elci outcome agreement, pinned representation fixtures |

Select I2/I3 exports by inspecting actual Julc library support before implementation
and record the chosen symbols in this ADR. A fabricated identity-only provider
can test the protocol but cannot satisfy either integration gate. Start I4 with
a representation that is fully understood; it does not establish full ledger
adapter coverage.

Across all stages test conflicting module ownership, repeated import aliases,
specialization deduplication, missing dependencies, content/target changes and
repeated isolated compilations. Compare accepted Java/Elci computations through
the VM; cross-language byte identity is not required. Existing Java extraction
paths should retain artifacts where unchanged. Record script size and budgets.
Exercise representative imports with both JVM and native compiler binaries.

## Alternatives, risks and open decisions

Erasing unsupported generics to Data, treating nominal Java records as structural
Elci records, and maintaining an Elci copy of Java library code are rejected.
Exposing Java AST/type resolver objects would couple the frontend to Java internals
and is also rejected. Module discovery and a persistent PIR interchange format
are outside this initial provider revision.

The main risks are overstated export capabilities, mismatched codecs, incorrect
generic specialization and stale cached implementations. Open engineering gates
are the exact neutral scheme API, initial real export selection, explicit overload
configuration and provider capability/version negotiation. Settle these in the
Julc design before adding dependent Elci implementation. This ADR does not claim
those APIs or generic exports are available today.

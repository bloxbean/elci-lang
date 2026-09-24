# ADR-005: Remaining frontend work and shared backend integration

Status: **Accepted planning direction; implementation in progress** (see Progress).
Date: 24 September 2026
Elci baseline: `e0b4be0`.
Verified Julc main baseline: `c95ee610fd334fc1650a3d99ae9254fbf2afdad4`.

## Context

Julc PRs [#158](https://github.com/bloxbean/julc/pull/158),
[#159](https://github.com/bloxbean/julc/pull/159), and
[#160](https://github.com/bloxbean/julc/pull/160) are merged into main. They provide
the neutral compiler entrypoint, general function mutual recursion, concrete
nominal library descriptions and source JAR inputs. The earlier concern about
those PRs being unmerged no longer applies.

At the verified main revision, `FrontendProgram` still describes one closed,
specialized function and one FUNCTION, SPEND or MINT entrypoint. `CompilerBackend`
checks closure, target/policy agreement and declared signature shape, then invokes
shared wrapping and lowering. It is not a complete PIR type verifier.

`LibraryProvider` describes concrete exports and materializes them by symbol.
`LibraryType` carries nominal identities, representations, fields and sum tags;
generic schemes, specialization requests and explicit operation/codec capabilities
are not yet a complete public contract.

Elci's `gradle.properties` still pins the earlier Julc commit `d6e716e` and
`0.1.0-elci-dev`. Merging Julc PRs does not update that dependency, locally published
JARs or native executables. Moving to a tested mainline revision/release is a
separate integration milestone; this planning change does not change the pin.

This ADR coordinates the unfinished work in
[ADR-003](003-generic-library-interop.md) and
[ADR-004](004-named-validators-and-multi-purpose-handlers.md). It does not replace
their invariants or claim their proposed features are implemented. Julc's merged
neutral-backend ADR is now
[ADR-056](https://github.com/bloxbean/julc/blob/c95ee610fd334fc1650a3d99ae9254fbf2afdad4/adr/056-language-neutral-compiler-backend.md).
Use full file links when citing its library-source ADR because main currently
also contains a different document numbered ADR-050.

## Decision and ownership

Elci owns syntax, source-module resolution, inference, source diagnostics,
specialization, frontend representation planning, CLI configuration and output
publication. Julc owns shared boundary semantics, target validation, library
implementation discovery/materialization and the PIR-to-UPLC pipeline.

New Elci constructs can use existing PIR without a backend change when the
semantics are representable and verified. Ledger roles, encoding permissions and
library capabilities must come from explicit shared contracts. Elci must not
reimplement Java wrappers, hardcode individual library methods or infer nominal
identity from erased PIR types.

Public Julc issues and designs use generic DSL terminology. They must not name
Elci or link to its repository while it remains private/stealth. Dependencies are
linked from this repository toward Julc.

## Work tracks and dependencies

| Track | Elci deliverable | Julc prerequisite |
| --- | --- | --- |
| E0: Adopt merged baseline | Validate and pin a mainline revision/release; update CI bootstrap and compatibility evidence | Merged #158–#160; no new feature API required |
| E1: Named validators | Implement ADR-004 groups, handler resolution, role checking, CLI selection and per-group artifacts | [#180: typed multi-purpose handlers](https://github.com/bloxbean/julc/issues/180) |
| E2: Generic imports | Instantiate provider schemes, retain constraints, request concrete specializations and diagnose unsupported uses | [#181: polymorphic exports](https://github.com/bloxbean/julc/issues/181) |
| E3: Provider integration | Consume source and programmatic exports through the same typing/lowering path | [#182: programmatic providers](https://github.com/bloxbean/julc/issues/182), with #181 for generic exports |
| E4: Imported types | Enable approved constructors, patterns, equality, container conversions and datum/redeemer codecs | [#183: verified operations/codecs](https://github.com/bloxbean/julc/issues/183); #181 where types are generic |
| E5: Richer boundaries | Expose ordered deployment parameters and explicit spending datum profiles in configuration and ABI | [#184: deployment boundaries](https://github.com/bloxbean/julc/issues/184), coordinated with #180 |
| E6: Compatibility and diagnostics | Consume capability/revision checks and map backend failures to source/configuration locations | [#185: API and producer validation](https://github.com/bloxbean/julc/issues/185) |

All six Julc issues use the `generic-backend` label. These are staged extensions,
not prerequisites for every existing Elci compilation or one mandatory release.

### E0: Integrate the merged compiler

Select an immutable Julc revision or published version and rebuild all required
artifacts consistently. Do not replace `julcRevision` alone while testing against
stale Maven-local JARs carrying the same development version. Prefer a distinct
integration version or isolated local repository when validating a new revision.

Run Elci's suite, representative custom-source-JAR and ledger examples, and native
checks. Compare existing script bytes/hashes and ABI manifests with the current
baseline; investigate changes and distinguish intentional upstream compiler
changes from abstraction regressions. Backend extensions require affected Julc
Java regression evidence in addition to Elci tests.

### E1: Named validators and scalable initialization

Follow ADR-004: each `[validators.<name>]` defines one script, with one or more
purpose handlers. Separate groups produce separate scripts. Reuse Julc dispatch
and decode only the selected handler's arguments. Keep legacy `[entrypoint]`
projects working and reject mixed configuration forms.

`elci init --purpose` continues to select the first starter template; it does not
permanently classify a project. Once named validators are supported, define an
explicit migration/generation flow and test it. Do not silently reinterpret an
existing project's deployment identity. A future command to add a handler or
validator needs its own CLI decision; this ADR does not invent that syntax.

Publish deterministic artifacts beneath `build/validators/<name>/`, including
Julc-compatible output and a versioned Elci ABI. Validate all selected groups
before publishing any output, and remove stale files only when owned by a
generated-output manifest. Keep the existing Elci tracking
[issue #1](https://github.com/bloxbean/elci-lang/issues/1).

### E2–E4: Library and imported-type completion

Use the I1–I4 gates in ADR-003. Bind schemes freshly at each use, solve supported
constraints, specialize only concrete instances and preserve strict partial
application. Exercise at least one real generic Java export at two types and one
real programmatic export; fabricated providers alone are not completion evidence.

The provider already describes imported sums and fields. Audit what those
descriptions safely support before requesting additional metadata. Extend Elci's
constructor resolution, exhaustiveness analysis, nominal typing and lowering
only for verified operations. Do not make every imported record serializable or
structurally equal merely because its fields are visible.

Retain explicit conversions when a source type's computational and serialized
representations differ. Unsupported types/operations remain compile-time errors
with an explanation of the missing capability, rather than implicit Data erasure.

### E5–E6: Boundary profiles and compatibility

Finalize parameter configuration, application commands and ABI format in a
follow-up design after the neutral descriptor is agreed. Preserve parameter order
and the single ledger context argument after application. Required, optional and
absent spending datum profiles must be explicit and independently tested.

When version/capability negotiation exists, check it before materialization and
lowering. Elci still owns source typing even if Julc adds structural PIR checks.
Retain source/configuration origin information locally and map stable backend
diagnostics to it. Rich source mapping through optimizer/debugger stages is a
later API design, not a promised result of #185.

## Invariants

- Preserve strict evaluation order, captures, recursion semantics and failure
  behavior. No duplicate frontend implementation of shared wrappers or libraries.
- Preserve nominal identities, constructor tags, field order and encoding policy.
  Any deliberate ABI/hash change must be documented as a deployment change.
- Never enable a purpose, provider export or codec solely because parsing accepts
  it; both shared capability and end-to-end evidence are required.
- Keep legacy project configuration and current artifacts compatible unless an
  explicit versioned migration is designed. Do not mislabel `abi.json` or Julc's
  `validator.plutus.json` as a CIP-57 blueprint.
- Keep compiler/provider state invocation-local and specialization keys complete.
  Do not add persistent caches without invalidation and identity tests.
- Public Julc changes preserve Java compiler APIs and existing validator behavior.

## Acceptance and release gates

Each track needs positive and negative integration fixtures, with observable VM
outcomes and malformed boundary cases where applicable. E1 additionally requires
all supported purpose dispatches, unconfigured-role rejection, inactive-schema
isolation, group selection and deterministic publication. E2–E4 require source
and programmatic provider parity, nominal separation, closure, representation
round trips and unsupported-instantiation diagnostics. E5 requires parameter-order
and datum-profile cases. E6 requires capability mismatch and malformed PIR cases.

Run representative projects through JVM and native CLIs; platform release CI must
exercise the newly exposed CLI paths. Measure script size and execution budgets
for contracts affected by lowering/wrapping changes. Record the exact compiler
revisions used and the relevant Julc Java regression results.

Independent preview follow-ups in Elci include clearer unsupported-feature
diagnostics, broader syntax/contract examples and project initialization quality.
The current validator templates return `True`; replace or clearly present those
placeholders before treating generated projects as useful contract examples.
Do not generate purported runnable source tests until an actual test command and
test format are defined. These frontend tasks do not require a new backend API.

## Sequencing and deferred work

Begin with E0. E1 is the next contract-capability milestone, independent of full
generic interoperability. E2 and concrete E4 capability work may proceed
independently; E3 shares E2's contract where generic helpers are involved. Agree
the E6 revision policy alongside new descriptors rather than bolting it on after
consumers depend on them. E5 follows agreement on the E1 descriptor.

CIP-57 schemas/SDK bindings, a source-level contract test runner, richer debugger
source mapping, package distribution, language-server tooling, native BLS
execution, fully static musl builds, and additional ledger targets remain separate
decisions. Some may use existing Julc services; audit those interfaces before
claiming a new backend requirement. Native BLS metadata belongs primarily to the
library/runtime integration and is not part of these frontend abstraction issues.

Row polymorphism, polymorphic recursion, Float and browser effects retain their
existing excluded/deferred status. This roadmap does not promise all future
language features through a frozen backend contract.

## Progress

| Track | State | Evidence |
| --- | --- | --- |
| E0 | Done: pinned to Julc `17da92bb` (`0.1.0-gb-17da92b`), the head of the revision-2 stack (Julc PRs #187–#192) | [Validation](../docs/VALIDATION.md#julc-revision-2-baseline-evidence-adr-005-e0) |
| E1 | Done: named validators, handler roles, `--validator`, per-group artifacts and ABI revision 2 | [ADR-004 evidence](004-named-validators-and-multi-purpose-handlers.md#implementation-evidence) |
| E5 | Configuration and ABI done: `parameters`, `spend-datum = required/optional/absent` | [ADR-004 evidence](004-named-validators-and-multi-purpose-handlers.md#implementation-evidence) |

## Alternatives and open decisions

Keeping the current API forever would force duplicated dispatch and guessed
library representations into frontends. Implementing all future APIs upfront
would add contracts without real consumers or evidence. Adopt additive, tested
extensions per track instead.

Before implementation, finalize each Julc API in its own generic backend design
and review it against the merged mainline. Exact descriptor classes, capability
versioning, parameter configuration, ABI revision and atomic output mechanics
remain implementation-stage design decisions. This ADR creates the roadmap and
dependencies only; it does not implement any track or update the compiler pin.

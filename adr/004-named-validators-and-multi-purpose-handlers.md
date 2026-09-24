# ADR-004: Named validators and multi-purpose handlers

Status: **Implemented** (25 September 2026) on Julc revision 2; see
[Implementation evidence](#implementation-evidence).
Tracking issue: [#1](https://github.com/bloxbean/elci-lang/issues/1)
Backend prerequisite: [Julc #180](https://github.com/bloxbean/julc/issues/180).
Integration roadmap: [ADR-005](005-remaining-frontend-and-backend-integration.md).
Date: 20 September 2026
Baseline: Elci `011c293`; extends the
[language design](elci-language-syntax-compiler-design-adr.md) and
[shared compiler boundary](julc-elm-frontend-boundary-design.md).

## Context and goals

Elci selects one function and purpose in `[entrypoint]`. Supported purposes are
`spend`, `mint` and `function`. Source functions have ordinary Elm syntax;
configuration determines the deployment role. The neutral Julc `FrontendProgram`
currently accepts one entrypoint with these same three purposes.

Julc's Java frontend already supports purpose-indexed handlers in `@MultiValidator`
and withdrawal, certification, voting and proposing validators. Its
`ValidatorWrapper.wrapMultiValidator` builds deterministic purpose dispatch and
fails when no handler matches. That behavior is not yet exposed through Elci's
neutral backend entrypoint. Adding TOML alone cannot enable it.

Retain configuration-based deployment while supporting multiple independently
compiled validators and multiple purpose handlers within one validator. Share
Julc dispatch, decoding and target checks. Do not introduce Elci annotations,
contract keywords or a separate frontend dispatcher.

## Decision: named validators

Configuration:

```toml
[validators.vault]
boundary = "julc-strict-v1"
spend = "Vault.spend"
withdraw = "Vault.withdraw"

[validators.token]
boundary = "julc-strict-v1"
mint = "Token.mint"
```

Each table defines **one deployed script**. `vault` has spending and withdrawal
handlers and produces one multi-purpose script/hash. `token` produces a separate
minting script/hash. This is not one script per function. Configuration does not
automatically invoke every handler: the ledger purpose selects exactly one.

Handler references split at the final dot into module name and exposed function
name. Resolve through normal source-module ownership and visibility rules. Reject
missing/private functions, empty validator groups, duplicate handler keys, unknown
purposes and ambiguous ownership. Restrict validator names to a documented safe
filename component; reject path separators and traversal components.

The supported handler keys will be `spend`, `mint`, `withdraw`, `certify`, `vote`
and `propose`, enabled only when the shared boundary and tests support each role.
No `function` handler exists within a deployed validator. No fallback/catch-all
handler is introduced; unconfigured purposes fail explicitly. Purpose tags and
ledger argument extraction are owned by Julc, not inferred from source names.

## Handler types and boundaries

| Role | Required Elci signature in this iteration |
| --- | --- |
| `spend` | `Datum -> Redeemer -> ScriptContext -> Bool` |
| `mint`, `withdraw`, `certify`, `vote`, `propose` | `Redeemer -> ScriptContext -> Bool` |

Each handler may have its own concrete redeemer type. Spending requires a datum;
optional/no-datum spending remains deferred. Supported serialization rules apply
recursively; named validators do not relax imported-type restrictions. `True`
means success and `False` means failure under the existing boundary convention.

The deployed Plutus V3 script receives one ledger ScriptContext argument.
Source-level arguments are extracted by the shared wrapper, which is applied
exactly once. User datum/redeemer decoding must occur only for the selected role;
an inactive handler's different schema must not reject a valid transaction.
Ledger context/header validation remains governed by the shared wrapper policy.

## Shared backend responsibilities

Extend the neutral API with a validator descriptor containing a stable validator
identity, one resolved target/boundary policy, and purpose-indexed typed handlers.
Keep JavaParser objects and Elci ASTs out of this API. Determine the exact Java API
shape in a companion Julc ADR before implementing the backend extension.

Elci resolves and type-checks configured functions, specializes reachable source
and library definitions, and supplies unwrapped PIR plus named type definitions.
Julc owns purpose validation, strict argument decoding, dispatch, linking,
wrapping, lowering, optimization and target validation. Reuse/refactor the existing
Java multi-validator machinery; do not copy its dispatch into Elci or translate
Elci source to Java. Check closure, nominal-definition conflicts and target
agreement. Keep dependency linking deterministic and preserve strictness/hygiene.

Preserve the existing single-entrypoint API with an adapter or additive API. The
Java compiler facade and current Java validator behavior must not regress.
Public Julc work must describe generic frontend/backend functionality without
naming private consumers.

## CLI and artifacts

Commands:

```sh
elci check ./project
elci build ./project
elci build ./project --validator vault
```

Without selection, check/build all named validators in deterministic name order.
Use `--validator` rather than an ambiguous positional name: the existing second
positional argument is a project directory. Unknown selections fail clearly.
`eval` remains for existing `purpose = "function"` projects; this ADR does not
introduce a validator-context runner or silently choose a handler to evaluate.

For named validators, write independent artifact sets:

```text
build/validators/vault/validator.flat
build/validators/vault/validator.plutus
build/validators/vault/validator.plutus.json
build/validators/vault/abi.json
build/validators/token/...
```

Use Julc's artifact serialization and purpose vocabulary: `multi` for multiple
handlers, the corresponding single-purpose metadata otherwise. One group has one
compiled script and hash. Include the handler-to-purpose mapping and per-handler
schemas in the Elci ABI; version that ABI extension explicitly. Support per-group
`expected-abi` paths and check all selected groups before publishing their outputs.
A failed group must not leave a mixture of old and new selected artifacts.
Define cleanup for removed groups using a generated-output manifest, without
removing user-owned files or unselected outputs.

CIP-57 generation, SDK bindings and deployment parameters are outside this ADR.
The Julc artifact is not renamed or represented as a CIP-57 blueprint.

## Compatibility and invariants

- Keep `[entrypoint]` working, including `purpose = "function"`, existing artifact
  paths and single-entrypoint ABI drift checks.
- Reject mixing `[entrypoint]` and `[validators.*]` in one configuration.
- Preserve existing script bytes/hashes when using the legacy configuration;
  use the same single-purpose wrapping path for equivalent one-handler groups.
- Multiple handlers share a script; separate validator groups never share a
  deployment identity implicitly.
- Do not change evaluation order, eager-value behavior, serialization, constructor
  tags, datum requirements or signature requirements to enable dispatch.
- Configuration order must not affect generated code or hashes. Adding or changing
  a handler may change the group's hash and must be treated as a deployment change.
- Independent groups must not absorb each other's unreachable definitions.

## Alternatives and consequences

Source annotations introduce new syntax and couple reusable functions to one
particular deployment role; retain external configuration instead. Inferring
purpose from arity cannot distinguish minting from withdrawal and is rejected.
A separate script per handler cannot reproduce a shared multi-purpose identity.
A catch-all raw-context dispatcher would bypass typed role checking and is outside
this iteration. A handwritten Elci dispatcher would duplicate Julc semantics.

Configuration adds a navigation step between a function and its deployment role.
Diagnostics should identify both the configuration key and the source handler.
The main risks are wrong purpose tags, eager decoding of inactive branches,
incompatible wrapper policies, stale artifacts and unintended hash changes.

## Implementation milestones and acceptance criteria

1. **Julc boundary:** create a generic companion ADR and additive typed handler API;
   expose existing dispatch for all six roles. Test duplicate/unknown roles,
   incompatible types/targets, closure and nominal-definition conflicts.
2. **Elci configuration:** named groups, handler resolution, signature checks,
   selection flag and legacy compatibility. Add negative configuration fixtures.
3. **Artifacts:** deterministic per-group output, Julc purpose/hash metadata,
   versioned ABI schemas, per-group drift checking and safe output publication.
4. **Integration:** examples, documentation, JVM/native validation and Java
   regression evidence before declaring the feature available.

Required evidence includes:

- Spending+withdrawal and spending+minting groups compile to one script each;
  two named groups produce separate artifacts.
- Full ledger-shaped contexts dispatch every supported role correctly, with
  accepted and rejected cases; unconfigured purposes fail.
- Malformed selected datum/redeemer inputs and missing spending datums fail;
  distinct inactive redeemer schemas are not decoded prematurely.
- Equivalent Java `@MultiValidator` and Elci programs agree on VM outcomes.
- Reordered tables/handlers compile identically; repeated builds are deterministic;
  legacy fixtures retain bytes, hashes and ABI manifests.
- CLI selection, unknown names, mixed config forms, duplicate/private/missing
  handlers, artifact cleanup and failed-build publication are tested.
- Artifact hashes match Cardano Client Lib; multi-purpose ABI maps roles correctly.
- JVM and native builds work; affected Julc compiler, wrapper, stdlib, testkit and
  annotation-processor regression suites pass. Record size and execution budgets.

## Open engineering decisions

Finalize the neutral descriptor API and ABI format revision in the implementation
milestone. Specify output-manifest ownership and atomic publication mechanics.
Optional spending datums, deployment parameters, additional script languages and
multi-handler transaction testing commands require separate decisions.

## Implementation evidence

Implemented on Julc `17da92bb` (the ADR-059 revision-2 stack: `ValidatorProgram`,
parameters and datum profiles). Julc's generic companion is
[ADR-059](https://github.com/bloxbean/julc/blob/17da92bb427a51112e2296937f70bef55e3795e5/adr/059-generic-backend-revision-2.md).

- **Descriptor:** each group compiles to one `ValidatorProgram`.
  - Handlers reference shared, specialized program-level definitions.
  - Library exports are one linked import group per validator.
  - Julc verifies the producer PIR and always dispatches through
    `wrapMultiValidator`.
- **Resolution:** handler modules are resolved together with every declaration
  module-qualified. Missing, private and non-concrete handlers are rejected, and
  so are wrong role signatures (`ELCI2014`).
- **Beyond this ADR:** parameters and optional/absent datums (ADR-005 E5) are
  configured with `parameters` and `spend-datum`. An optional datum reaches the
  source as Elci's `Maybe` through a verified re-tagging adapter.
- **Pre-existing defect fixed:** a qualified reference could reach a private or
  unimported declaration of the flattened module graph. It is now `ELCI2104`.
- **Publication:** CLI outputs are validated for all selected groups before
  anything is written. Each file is replaced with an atomic move, and
  `build/validators/elci-outputs.json` records owned files for cleanup.

Tests (JVM):

- **`NamedValidatorTest`:** spend+withdraw dispatch, unconfigured purposes,
  inactive-schema isolation, missing/malformed datums and handler-order
  independence.
- **`NamedValidatorRolesTest`:**
  - all six purposes, in one group and as single-purpose groups, each rejecting
    every other purpose;
  - Java `@MultiValidator` parity on the 81-case purpose × redeemer × datum
    matrix, with 4 accepting cases;
  - parameter order and hashes, optional and absent datums;
  - role, visibility, concreteness, boundary and parameter-agreement rejections.
- **`NamedValidatorCliTest`:**
  - per-group artifacts with Cardano Client Lib hash agreement and ABI revision 2;
  - determinism across configuration order;
  - `--validator` selection;
  - drift and type failures publishing nothing;
  - owned-output cleanup;
  - configuration errors.
- **`TreasuryExampleTest` and `InitTest`:** the example round-trips through
  published artifacts and parameter application, and `init --validator` builds
  for every purpose.

All seven legacy examples keep byte-identical artifacts.

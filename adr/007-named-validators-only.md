# ADR-007: Named validators only

Status: **Accepted and implemented**.
Date: 25 September 2026
Supersedes the `[entrypoint]` compatibility rules of
[ADR-004](004-named-validators-and-multi-purpose-handlers.md#compatibility-and-invariants)
and [ADR-005](005-remaining-frontend-and-backend-integration.md).

## Context

ADR-004 added named validators but kept the older `[entrypoint]` form working, with
`purpose = "spend"`, `"mint"` or `"function"`. Keeping it had costs:

- **Two paths:** the older form compiled validators through Julc's revision-1
  path, with its own single-purpose wrapper, boundary rules and ABI roles.
- **Uneven features:** that path had no parameters, datum profiles, other purposes,
  verified PIR or imported boundary types.
- **Constraints:** it had to stay byte-stable, which limited the compiler and
  produced documentation caveats such as "`[entrypoint]` has no parameters".

Elci is a development preview. No deployed script depends on the old form's hashes.

## Decision

Validators exist only as `[validators.<name>]` groups.

- **Removed:** `[entrypoint]` with `purpose = "spend"` or `"mint"`. Such a
  configuration fails and names the replacement table, for example
  `[validators.<name>] with mint = "Main.validate"`.
- **Kept, as a function:** `[entrypoint]` names one concrete function or value for
  `elci eval`. `purpose` defaults to `"function"`, and `boundary` is optional.
- **Compiler API:** `ElciCompiler.compile`/`compileProject` compile function
  programs only. Validators go through `compileValidator`.
- **`elci init`:** creates a validator named `main` for every handler purpose
  (default `spend`). `--purpose function` creates an `[entrypoint]` function.
- **Examples:** `threshold`, `signed` and `vesting` are named validators.
  `Scenario` tests name validators only.

## Consequences

Every validator gets the same features: parameters, datum profiles, all six
purposes, imported boundary types, Julc's PIR verifier and ABI revision 2. There is
one validator compile path and one set of rules to document and test.

Every validator checks the purpose before dispatch, even with one handler. That is
the point: a purpose without a handler fails instead of running the handler. Julc
measured about 1.1M CPU, 4k memory and 34 bytes for that check (ADR-059 Milestone
2). The example scripts grow by 16–32 bytes, and their hashes change.

The revision-1 `FrontendProgram` path remains, but only for function programs:
`elci eval`, `elci test` programs and `ElciTestkit.value`. Its byte-stability
guarantee no longer applies to validators, because they no longer use it.

## Migration

Replace:

```toml
[entrypoint]
module = "Vesting"
function = "validate"
purpose = "spend"
boundary = "julc-strict-v1"
```

with:

```toml
[validators.vesting]
boundary = "julc-strict-v1"
spend = "Vesting.validate"
```

Artifacts move from `build/` to `build/validators/<name>/`. A project-level
`expected-abi` becomes a per-validator `expected-abi`, regenerated because the ABI
format is revision 2.

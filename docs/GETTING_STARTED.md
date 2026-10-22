# Getting started with Elci

## 1. Prerequisites

Use Java 25 and the checked-in Gradle wrapper. Building a native executable also
requires GraalVM Native Image and the platform C toolchain.

This development build depends on the Julc revision named by `julcVersion` and
`julcRevision` in `gradle.properties`. It is currently **`0.1.0-gb-5a3378b`**, Julc
commit `5a3378ba`, which contains:

- the generic-backend revision-2 stack with PR #195;
- the typed-map and byte-string follow-up (#196);
- the testkit's property generators (#197);
- `JulcArray` in library signatures and typed array templates (#198).

Publish it locally from a Julc checkout at that commit. Maven Central's existing Julc release does not contain
this experimental frontend boundary.

Set these paths for your development machine:

```sh
export JAVA_HOME=/path/to/jdk-25
export GRAALVM_HOME=/path/to/graalvm
export JULC_WORKTREE=/path/to/julc-worktree
```

Publish the required modules from the Julc worktree. Pass the pinned version on
the command line so that stale JARs published under another development version
cannot be picked up:

```sh
cd "$JULC_WORKTREE"
git checkout "$(sed -n 's/^julcRevision=//p' /path/to/elci-lang/gradle.properties)"
./gradlew -Pversion="$(sed -n 's/^julcVersion=//p' /path/to/elci-lang/gradle.properties)" \
  :julc-core:publishToMavenLocal \
  :julc-vm:publishToMavenLocal \
  :julc-ledger-api:publishToMavenLocal \
  :julc-compiler:publishToMavenLocal \
  :julc-stdlib:publishToMavenLocal \
  :julc-bls:publishToMavenLocal \
  :julc-vm-java:publishToMavenLocal \
  :julc-cardano-client-lib:publishToMavenLocal \
  :julc-testkit:publishToMavenLocal \
  :julc-testkit-jqwik:publishToMavenLocal -PskipSigning=true
```

Return to the Elci project:

```sh
./gradlew test installDist
build/install/elci/bin/elci --version
build/install/elci/bin/elci check examples/threshold
build/install/elci/bin/elci build examples/threshold
```

`check` runs the compiler through backend validation, without writing artifacts.
`build` writes artifacts beneath the selected project's `build/` directory.

## 2. Your first project

Create a spending-validator project:

```sh
elci init my-contract
cd my-contract
elci check .
elci test .
elci build .
```

This creates one validator named `main` whose first handler serves the `spend`
purpose. Choose another purpose or name with `--purpose` and `--validator`. For a
plain function to run with `elci eval`, use `--purpose function`:

```sh
elci init token-policy --purpose mint
elci init rewards --validator rewards --purpose withdraw
elci init calculation --purpose function
```

`--purpose` selects the starter source; it is not a permanent project
classification. The validator template accepts only transactions signed by an
`owner` deployment parameter. It comes with `tests/MainTest.elm`, two transaction
scenarios that `elci test` runs (see [Testing](TESTING.md)). Initialization refuses
to write into a non-empty directory.

The generated layout is:

```text
elci.toml
README.md
.gitignore
src/
  Main.elm
tests/
  MainTest.elm
```

A `--purpose function` project has no `tests/` directory.

The module header and source path must agree. Configuration is separate from the
Elm source:

```toml
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.threshold]
boundary = "julc-strict-v1"
spend = "Example.Threshold.validate"

[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
```

The configuration parser implements this strict TOML subset: named sections,
quoted strings without escapes, and arrays of quoted source-directory strings.
Blank lines and full-line `#` comments are accepted. Unknown keys, duplicate
keys, unsupported targets, and mismatched compiler versions are errors. Inline
comments and the wider TOML grammar are not supported yet.

A project configures its validators, or a single function to evaluate:

```toml
[entrypoint]
module = "Main"
function = "main"
purpose = "function"
```

An `[entrypoint]` is a concrete function or value that `elci eval` runs, useful
for exploring computations. It is not a validator. Validators are always named;
`[entrypoint]` with `purpose = "spend"` or `"mint"` was removed in the development
preview ([ADR-007](../adr/007-named-validators-only.md)).

### Named validators

A project defines one or more named validators. Each `[validators.<name>]` table
is one deployed script with its own hash. Handler keys name exposed functions,
and the ledger purpose selects the handler to run:

```toml
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

# One script: spends payouts and withdraws the address's staking rewards.
[validators.treasury]
boundary = "julc-strict-v1"
parameters = ["admin"]
spend = "Treasury.spend"
withdraw = "Treasury.withdraw"

# A separate script and minting policy.
[validators.badge]
boundary = "julc-strict-v1"
parameters = ["admin"]
mint = "Badge.mint"
```

Rules:

- **Handler keys:** `spend`, `mint`, `withdraw`, `certify`, `vote` and `propose`,
  each at most once per table. Unconfigured purposes fail on-chain.
- **Validator names:** lowercase letters, digits, `-` and `_`, starting with a
  letter, at most 64 characters. The name is the artifact directory.
- **`parameters`** (optional): deployment parameter names in application order.
- **`spend-datum`** (optional): `"required"` (default), `"optional"` or `"absent"`.
- **`expected-abi`** (optional): a committed `abi.json` for this group to compare.
- **Exclusive forms:** a project has either validators or an `[entrypoint]`
  function. Mixing them is an error.

See the [language guide](LANGUAGE.md#validators-and-handler-roles) for handler
signatures. `examples/treasury` is a complete example.

```sh
elci check .                       # every validator, in name order
elci build .                       # writes build/validators/<name>/
elci build . --validator treasury  # only the selected group(s)
```

Every selected group is compiled, and compared with its `expected-abi` if one is
set, before anything is written. A failure therefore publishes nothing. A full
build removes the outputs of groups that are no longer configured. It uses
`build/validators/elci-outputs.json`, which records the files Elci owns; other
files in those directories are left alone. A `--validator` build never removes
unselected outputs. `eval` applies only to `[entrypoint]` function projects.

An older project with `[entrypoint]` `purpose = "spend"` or `"mint"` is rejected
with the replacement table spelled out. Replace `[entrypoint]` with a
`[validators.<name>]` table whose handler key is the old purpose and whose value
is the old `module.function`. The script hash changes, because named validators
check the purpose before calling the handler.

## 3. Read the outputs

Each validator writes four files beneath `build/validators/<name>/`:

- `validator.flat`: FLAT-encoded UPLC.
- `validator.plutus`: a Plutus V3 text envelope with double-CBOR-wrapped script bytes.
- `validator.plutus.json`: Julc-compatible validator artifact with `type`, `purpose`,
  `description`, `cborHex`, `hash`, `params` and `sizeBytes`.
- `abi.json`: target, handler roles, nominal identities, codecs, constructor tags,
  record field order, and recursive type references.

`validator.plutus.json` uses Julc's `ValidatorOutput` format, not CIP-57. The
schema information stays in `abi.json`; neither file is a CIP-57 blueprint.
The compiler uses Julc's existing script adapter for CBOR encoding and hashing.
For a Cardano Client Lib application with `julc-cardano-client-lib` available:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.julclang.clientlib.ValidatorOutput;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;

var output = ValidatorOutput.fromJson(
    Files.readString(Path.of("build/validators/threshold/validator.plutus.json")));
var script = PlutusV3Script.builder().cborHex(output.cborHex()).build();
if (!HexFormat.of().formatHex(script.getScriptHash()).equals(output.hash())) {
    throw new IllegalStateException("Script hash mismatch");
}
```

An `[entrypoint]` function build writes `build/validator.flat`, `validator.plutus`
and `abi.json`, but no Julc validator artifact: functions are for evaluation, not
deployment.

In detail:

- **`validator.plutus.json`:** the Julc artifact.
  - `purpose` is `multi` for several handlers. For one handler it is Julc's
    purpose word: `spending`, `minting`, `withdraw`, `certifying`, `voting` or
    `proposing`.
  - `params` lists `name:type` in application order. Records are shown as
    `Record`, so the comma-separated list has one entry per parameter.
  - As in Julc, `hash` is empty for a parameterized script, because the hash
    changes when the parameters are applied.
- **`abi.json`:** `formatVersion` 2. It records the parameters, each handler's
  purpose, ledger tag, datum profile and datum/redeemer schemas, and the shared
  type definitions.

Apply deployment parameters with `elci apply`. Each value is Plutus Data in
cardano-cli's detailed JSON schema, given inline or as `@file`:

```sh
elci apply examples/treasury --validator badge \
  --param 'admin={"bytes": "01000000000000000000000000000000000000000000000000000000"}'
```

Before applying them, `apply` checks every value off-chain against the
parameter's type. It uses Julc's strict boundary program, so wrong kinds,
constructor tags and arities are rejected. The script itself only decodes
parameters. The result is a Julc artifact with the applied script's hash, written
to `build/validators/<name>/applied.plutus.json`, or to `--output <file>`. The next
build of that group removes an applied file under `build/`, because it came from
the previous script. The same application from Java, in the listed order:

```java
var output = ValidatorOutput.fromJson(Files.readString(
    Path.of("build/validators/treasury/validator.plutus.json")));
var program = JulcScriptAdapter.toProgram(output.cborHex())
    .applyParams(adminPubKeyHash.toPlutusData());
```

Keep the ABI with application code that constructs datum/redeemer data. To reject
unexpected ABI changes, save a copy outside `build/` and set it on the validator:

```toml
[validators.threshold]
# ... the handler keys ...
expected-abi = "expected-abi.json"
```

The compiler compares the canonical manifest before writing new artifacts.
Changing constructor order changes tags and therefore the ABI. Reordering a
closed record declaration does not change its lexicographically ordered encoding.

The threshold validator accepts datum `5` with redeemer `6`, rejects `5` with `4`,
and accepts `-5` with `0`. These cases and malformed-data cases are evaluated in
`ValidatorTest`. A compiled validator must be evaluated with a complete ledger
context, not with three independent datum/redeemer/context arguments.

## 4. Native compiler

```sh
./gradlew nativeCompile
build/native/elci --version
build/native/elci check examples/threshold
build/native/elci build examples/signed
build/native/elci build examples/vesting
build/native/elci eval examples/functional
build/native/elci eval examples/core-completion
```

The executable is platform-specific. It bundles the library sources and
JavaParser metadata needed to compile Java on-chain exports without a JDK at
runtime. `GRAALVM_HOME` must select a GraalVM installation containing
`bin/native-image`.

`eval` is for a `function` entrypoint. It runs the generated UPLC with Julc's Java
VM and prints the result in Elci syntax, then the execution budget and any
`Debug.trace` messages:

```text
$ elci eval examples/functional
12
cpu 11011670, mem 45999
```

`--builtins` also lists every builtin call. A failing evaluation prints
`evaluation failed: <reason>` and exits with status 1. Validator acceptance and
rejection belong in ledger-context tests.

## 5. Editor and diagnostics

Use `.elm` syntax highlighting. The syntax reference is Elm 0.19.1, and the
formatter corpus is pinned to `elm-format 0.8.8`:

```sh
npm exec --yes --package elm-format@0.8.8 -- \
  elm-format examples src/main/resources/elci/core \
  src/test/resources/syntax/valid src/test/resources/syntax/unsupported --validate
```

The [diagnostic catalogue](DIAGNOSTICS.md) lists the stable error categories. The
stock Elm language server does not know Elci's Java exports or ledger profile;
its type diagnostics are not authoritative for Elci. Elci errors include `ELCI`
codes and the file and token location of the module they occur in, followed by the
source line with a caret and any `Hint:` lines. A run reports every error it finds,
one per top-level declaration, followed by a count, and ends with the
`elci explain <code>` command that explains its codes. Named-validator errors also
name the `elci.toml` table. Backend rejections are located at the generated
declaration, which is precise to the declaration rather than the expression. For
editors and agents, `--report=json` on `check`, `build`, `eval` and `test` prints
each error as one JSON object on its own line (JSON Lines).

## 6. Troubleshooting

**Dependency not found:** publish the exact `julcVersion` modules above, then
check Elci's `gradle.properties`. A project's `julc-version` must equal the
compiler's pinned `julcVersion` (`elci --version` prints it). Both projects require Java 25.

**No supported Java export:** only bundled `@OnchainLibrary` sources with mapped
concrete signatures are exposed. Arbitrary JVM methods and overloaded methods
are not automatically available.

**Cannot determine a type (`ELCI3001`):** `Julc.Data.decode` and generic Java
exports must know the type they create; annotate the value, for example with a
local `amounts : List Int`. Types nothing determines are otherwise fine, as in
Elm: `List.length []` needs no annotation. Entrypoints must be concrete.
Expansive local values are deliberately monomorphic; make the helper a local
function or move it to a top-level function with a signature.

**Truffle regression suite fails under GraalVM:** the existing Julc Truffle
runtime version may not match the supplied GraalVM's libgraal. Run Julc's
cross-backend suite with the standard JDK 25 installation. Elci's evaluator uses
the Java VM; native compilation still uses the supplied GraalVM.

Custom on-chain libraries can be supplied as JARs using `project.library-jars`.
See [Java libraries](JAVA_LIBRARIES.md) and the
[custom-library example](../examples/custom-library/README.md).

See the [standard library guide](STDLIB.md) for the current export inventory and
how to write reusable Elci modules.

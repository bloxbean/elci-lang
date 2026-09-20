# Getting started with Elci

## 1. Prerequisites

Use Java 25 and the checked-in Gradle wrapper. Building a native executable also
requires GraalVM Native Image and the platform C toolchain.

This development build depends on **Julc `0.1.0-elci-dev`**, published locally from
the companion worktree. Maven Central's existing Julc release does not contain
this experimental frontend boundary.

Set these paths for your development machine:

```sh
export JAVA_HOME=/path/to/jdk-25
export GRAALVM_HOME=/path/to/graalvm
export JULC_WORKTREE=/path/to/julc-worktree
```

Publish the required modules from the Julc worktree, whose `gradle.properties`
version must be `0.1.0-elci-dev`:

```sh
cd "$JULC_WORKTREE"
./gradlew :julc-core:publishToMavenLocal \
  :julc-vm:publishToMavenLocal \
  :julc-ledger-api:publishToMavenLocal \
  :julc-compiler:publishToMavenLocal \
  :julc-stdlib:publishToMavenLocal \
  :julc-bls:publishToMavenLocal \
  :julc-vm-java:publishToMavenLocal \
  :julc-cardano-client-lib:publishToMavenLocal -PskipSigning=true
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

Copy `examples/threshold` to a new directory. Its layout is:

```text
elci.toml
src/
  Example/
    Threshold.elm
```

The module header and source path must agree. Configuration is separate from the
Elm source:

```toml
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-elci-dev"

[entrypoint]
module = "Example.Threshold"
function = "validate"
purpose = "spend"
boundary = "julc-strict-v1"

[java-modules]
"Julc.Math" = "org.julclang.stdlib.lib.MathLib"
```

The configuration parser implements this strict TOML subset: named sections,
quoted strings without escapes, and arrays of quoted source-directory strings.
Blank lines and full-line `#` comments are accepted. Unknown keys, duplicate
keys, unsupported targets, and mismatched compiler versions are errors. Inline
comments and the wider TOML grammar are not supported yet.

Entrypoint profiles:

| Purpose | Source type | Runtime |
| --- | --- | --- |
| `spend` | `Datum -> Redeemer -> ScriptContext -> Bool` | Required datum; one ledger context argument |
| `mint` | `Redeemer -> ScriptContext -> Bool` | One ledger context argument |
| `function` | Concrete function/value | Unwrapped computation, useful for evaluation |

Deployment parameters and optional-datum profiles are not implemented. Entrypoint
roles are positional; parameter names do not determine their meaning.

## 3. Read the outputs

A validator build produces:

- `validator.flat`: FLAT-encoded UPLC.
- `validator.plutus`: a Plutus V3 text envelope with double-CBOR-wrapped script bytes.
- `validator.plutus.json`: Julc-compatible validator artifact with `type`, `purpose`,
  `description`, `cborHex`, `hash`, `params` and `sizeBytes`. Generated for spending
  and minting validators; `params` is empty because deployment parameters are not
  supported yet.
- `abi.json`: target, entrypoint roles, nominal identities, codecs, constructor tags,
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
    Files.readString(Path.of("build/validator.plutus.json")));
var script = PlutusV3Script.builder().cborHex(output.cborHex()).build();
if (!HexFormat.of().formatHex(script.getScriptHash()).equals(output.hash())) {
    throw new IllegalStateException("Script hash mismatch");
}
```

A `purpose = "function"` build does not create the Julc validator artifact and
removes a stale one from a previous validator build. Function outputs are intended
for evaluation, not deployment as validators.

Keep the ABI with application code that constructs datum/redeemer data. To reject
unexpected ABI changes, save a copy outside `build/` and add:

```toml
[project]
# ... the existing project settings ...
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
VM and prints its result and execution budget. The functional example returns
`12`. Validator acceptance/rejection belongs in ledger-context tests.

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
codes and token locations where available. Backend/configuration/module errors
currently have less precise location information.

## 6. Troubleshooting

**Dependency not found:** publish the exact `0.1.0-elci-dev` modules above, then
check Elci's `gradle.properties`. Both projects require Java 25.

**No supported Java export:** only bundled `@OnchainLibrary` sources with mapped
concrete signatures are exposed. Arbitrary JVM methods and overloaded methods
are not automatically available.

**Unresolved source type:** entrypoints must be concrete. Expansive local values
are deliberately monomorphic; make the helper a local function or move it to a
top-level function with a signature.

**Truffle regression suite fails under GraalVM:** the existing Julc Truffle
runtime version may not match the supplied GraalVM's libgraal. Run Julc's
cross-backend suite with the standard JDK 25 installation. Elci's evaluator uses
the Java VM; native compilation still uses the supplied GraalVM.

Custom on-chain libraries can be supplied as JARs using `project.library-jars`.
See [Java libraries](JAVA_LIBRARIES.md) and the
[custom-library example](../examples/custom-library/README.md).

See the [standard library guide](STDLIB.md) for the current export inventory and
how to write reusable Elci modules.

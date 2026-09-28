---
title: Parameterized validators
description: Declare deployment parameters, test them with scenarios, and apply them to a built script with elci apply and cardano-cli's detailed Data JSON.
sidebar:
  order: 4
---

A parameter is a value baked into a script when you deploy it, such as an admin's key or a limit. This page shows how to declare parameters, test them, and apply them to the built script with `elci apply`.

## Why parameters

A datum belongs to one output; a parameter belongs to the whole script. Two deployments of the same code with different parameters are different scripts, with different hashes, addresses and policy ids. That is exactly what you want when the value decides who controls the script: someone who deploys your policy with their own admin key gets their own policy id, not yours.

## Declaring parameters

List the names under `parameters` in the validator's table. The handlers receive them as leading arguments, in that order. This minting policy lets an admin mint between a minimum and a maximum quantity per transaction:

```toml title="elci.toml"
[project]
source-directories = ["src"]
target = "plutus-v3-pv11-uplc-1.1.0"
julc-version = "0.1.0-gb-5a3378b"

[validators.drop]
boundary = "julc-strict-v1"
parameters = ["admin", "limits"]
mint = "Drop.mint"
```

```elm title="src/Drop.elm"
module Drop exposing (Limits, mint)

import Julc.Ledger exposing (PubKeyHash, ScriptContext)


type alias Limits =
    { minimum : Int
    , maximum : Int
    }


mint : PubKeyHash -> Limits -> Int -> ScriptContext -> Bool
mint admin limits quantity ctx =
    (quantity >= limits.minimum)
        && (quantity <= limits.maximum)
        && List.any (\signer -> signer == admin) ctx.txInfo.signatories
```

`admin` and `limits` come first, then the redeemer (here the quantity the transaction claims to mint), then the context.

The rules:

- **Order matters.** Parameters are applied in the listed order, and every handler of the validator receives all of them, in that order.
- **Types must agree.** Each parameter must have the same type in every handler. If `Treasury.spend` takes `admin : PubKeyHash` but `Treasury.withdraw` declares and uses it as an `Int`, the check fails:

  ```text
  .../treasury/src/Treasury.elm:11:1: ELCI2014: Handler Treasury.withdraw does not have the withdraw signature admin -> redeemer -> ScriptContext -> Bool that [validators.treasury] needs (1 parameter); its type is Int -> () -> ScriptContext -> Bool; it has `Int` where the configuration needs `PubKeyHash`

  11| withdraw : Int -> () -> ScriptContext -> Bool
      ^^^^^^^^

  In .../treasury/elci.toml:6: [validators.treasury]
  ```

- **Boundary types only.** Parameters follow the same rules as datums and redeemers: records, custom types, lists, dictionaries, maps and ledger types such as `PubKeyHash` or `TxOutRef` are fine; functions, arrays and `Value` are not. To parameterize a script by an amount of tokens, take a `List ( PolicyId, TokenName, Int )` and build the value with `Value.fromList`. See [Datums and redeemers](/contracts/datums-and-redeemers/).
- **Names** start with a lowercase letter and use letters, digits and `_`.

Parameters are decoded when the script is applied, but the script doesn't validate them on-chain. `elci apply` checks them off-chain instead, as shown below. The one exception is a `Dict`: the script checks its key order on every run, as it does in a datum.

## Testing with parameters

A scenario supplies parameters as `Data`, in the declared order, with `Scenario.parameters`:

```elm title="tests/DropTest.elm"
module DropTest exposing (adminMintsWithinLimits, onlyQuantitiesWithinLimits, tooManyAreRejected)

import Bytes
import Drop exposing (Limits)
import Julc.Data as Data
import Julc.Ledger exposing (PubKeyHash)
import Property exposing (Property)
import Scenario exposing (Scenario)


admin : PubKeyHash
admin =
    Scenario.key "admin"


limits : Limits
limits =
    { minimum = 1, maximum = 10 }


drop : Int -> Scenario
drop quantity =
    Scenario.mint "drop"
        |> Scenario.parameters [ Data.encode admin, Data.encode limits ]
        |> Scenario.minting (Bytes.fromString "DROP") quantity
        |> Scenario.redeemer (Data.encode quantity)
        |> Scenario.signedBy admin


adminMintsWithinLimits : Scenario
adminMintsWithinLimits =
    drop 10


tooManyAreRejected : Scenario
tooManyAreRejected =
    drop 11
        |> Scenario.expectFailure


onlyQuantitiesWithinLimits : Int -> Property
onlyQuantitiesWithinLimits quantity =
    if quantity >= 1 && quantity <= 10 then
        Property.scenario (drop quantity)

    else
        Property.scenario (Scenario.expectFailure (drop quantity))
```

```sh
elci test . --seed 42
```

```text
PASS DropTest.adminMintsWithinLimits (cpu 6369489, mem 23925)
PASS DropTest.tooManyAreRejected (cpu 3787162, mem 15380)
PASS DropTest.onlyQuantitiesWithinLimits (100 runs, max cpu 6369489, mem 23925)
3 passed, 0 failed (seed 42)
```

The test runner applies the parameters to the compiled script before it builds the transaction, so the script's address and policy id in each scenario are those of the applied script. A scenario must supply every parameter. If `drop` passed only `[ Data.encode admin ]`, its scenarios would fail before running:

```text
FAIL DropTest.adminMintsWithinLimits
    cannot prepare validator 'drop': it takes 2 parameters [admin, limits], but the scenario gives 1; set them with Scenario.parameters
0 passed, 1 failed
```

:::note
This example trusts the redeemer to tell the truth about the quantity, which keeps it short. A real policy should read the minted quantity from `ctx.txInfo.mint` with `Value.quantityOf`, as the [treasury tutorial](/tutorials/treasury/) does.
:::

## The built script

`elci build` writes the script **before** parameters are applied. Its `validator.plutus.json` lists the parameters and leaves the hash empty, because the hash only exists once the parameters are known:

```json title="build/validators/drop/validator.plutus.json"
{
  "type": "PlutusScriptV3",
  "purpose": "minting",
  "description": "drop",
  "cborHex": "...",
  "hash": "",
  "params": "admin:PubKeyHash,limits:Record",
  "sizeBytes": 218
}
```

`params` shows records as `Record`. The full layout of each parameter is in `abi.json`:

```sh
jq '.parameters' build/validators/drop/abi.json
```

```json
[
  {
    "name": "admin",
    "schema": {
      "encoding": "B",
      "kind": "bytes"
    },
    "sourceType": "org.julclang.ledger.PubKeyHash"
  },
  {
    "name": "limits",
    "schema": {
      "fields": [
        {
          "name": "maximum",
          "schema": {
            "encoding": "I",
            "kind": "integer"
          }
        },
        {
          "name": "minimum",
          "schema": {
            "encoding": "I",
            "kind": "integer"
          }
        }
      ],
      "identity": "elci.Record:maximum,minimum [Int, Int]",
      "kind": "record",
      "tag": 0
    },
    "sourceType": "Record:maximum,minimum [Int, Int]"
  }
]
```

Note the field order: `maximum` before `minimum`, because record fields are encoded sorted by name.

## Applying parameters with `elci apply`

`elci apply` compiles the validator, checks each value and writes the applied script. Pass every parameter with `--param name=value`, where the value is Plutus Data in cardano-cli's detailed JSON schema:

```sh
elci apply . --validator drop \
  --param 'admin={"bytes": "887d8fbb82ce756a9dac9210c3d1235e9d3ff2457a667d311d318142"}' \
  --param 'limits={"constructor": 0, "fields": [{"int": 10}, {"int": 1}]}'
```

```text
validator drop: applied 2 parameters; hash 4ba1e0d80a5891c79032b5eccdf0514392f90e90278ce6567a7d3d76; wrote .../token-drop/build/validators/drop/applied.plutus.json
```

The applied artifact has the same format as `validator.plutus.json`, now with a hash and no remaining parameters:

```json title="build/validators/drop/applied.plutus.json"
{
  "type": "PlutusScriptV3",
  "purpose": "minting",
  "description": "drop",
  "cborHex": "59010a590107010100333232323223222323232323293370e...",
  "hash": "4ba1e0d80a5891c79032b5eccdf0514392f90e90278ce6567a7d3d76",
  "params": "",
  "sizeBytes": 263
}
```

For a minting policy, this hash is the policy id. For a spending validator, the script address is derived from it.

### Plutus Data JSON

Each value is one JSON object in the detailed schema:

| JSON | Data | For Elci types |
| --- | --- | --- |
| `{"int": 42}` | integer | `Int` |
| `{"bytes": "0a1b"}` | byte string, as hex | `Bytes`, hashes, `String` (the UTF-8 bytes) |
| `{"list": [ ... ]}` | list | `List a` |
| `{"map": [{"k": ..., "v": ...}]}` | map | `Dict k v` (keys in ascending order), `Map k v` |
| `{"constructor": 0, "fields": [ ... ]}` | constructor | records, custom types, `Bool`, `Maybe`, tuples, `()`, `TxOutRef` |

Build values with the [encoding rules](/contracts/datums-and-redeemers/): a record is constructor 0 with its fields sorted by name, `True` is `{"constructor": 1, "fields": []}`, and a `TxOutRef` is `{"constructor": 0, "fields": [{"bytes": "<transaction id>"}, {"int": <index>}]}`. The parser accepts integers, hex strings, arrays and objects; strings with escape sequences are not supported.

### What `apply` checks

Before applying anything, `apply` runs Julc's strict decoder for each parameter's type against the value you gave. Wrong kinds, constructor tags and field counts are rejected, and nothing is written:

```text
elci: ELCI4001: parameter limits is not a well-formed Record:maximum,minimum [Int, Int] value
```

(from `{"constructor": 0, "fields": [{"int": 10}]}`, which has one field too few), and:

```text
elci: ELCI4001: parameter admin is not a well-formed org.julclang.ledger.PubKeyHash value
```

(from `{"int": 42}`). A `Dict` must list its keys in ascending order, each once, so `apply` also rejects

```json
{"map": [{"k": {"int": 2}, "v": {"int": 10}}, {"k": {"int": 1}, "v": {"int": 5}}]}
```

for a parameter `caps : Dict Int Int`:

```text
elci: ELCI4001: parameter caps has a Dict whose keys are not strictly ascending; write its keys sorted and without repeats
```

Other mistakes are reported the same way:

| Mistake | Message |
| --- | --- |
| No `--param limits=...` | `ELCI4001: missing --param limits=<plutus-data-json>` |
| `--param owner=...` | `ELCI4001: validator drop has no parameter 'owner'; declared: admin, limits` |
| `--param admin=887d8fbb` (bare hex, not JSON) | `ELCI4001: parameter admin is not Plutus Data JSON (for example {"int": 5}, {"bytes": "ab"} or {"constructor": 0, "fields": []}): unexpected trailing content at offset 3` |

:::caution[The check is about shape, not meaning]
`apply` checks the kind and layout of each value, not its meaning. It accepts `{"constructor": 0, "fields": [{"int": 1}, {"int": 10}]}` for `limits`, although that means `minimum = 10, maximum = 1` and nothing could ever be minted. It also accepts a `PubKeyHash` of the wrong length, because Elci adds no length rule to ledger hashes. Double-check values against `abi.json`, and test the applied parameters in scenarios.
:::

### Values from files

Long values are easier to keep in files. Write `@path` instead of the JSON. The path is resolved against the **project directory**, not your current directory:

```json title="params/limits.json"
{
  "constructor": 0,
  "fields": [
    { "int": 10 },
    { "int": 1 }
  ]
}
```

```sh
elci apply . --validator drop \
  --param 'admin={"bytes": "887d8fbb82ce756a9dac9210c3d1235e9d3ff2457a667d311d318142"}' \
  --param limits=@params/limits.json
```

### Where the result goes

By default the result is `build/validators/<name>/applied.plutus.json`. It belongs to the build: the next `elci build` of that validator removes it, because it was applied to the previous script. To keep an applied script, write it elsewhere with `--output`, which is resolved against your current directory:

```sh
elci apply token-drop --validator drop \
  --param 'admin={"bytes": "887d8fbb82ce756a9dac9210c3d1235e9d3ff2457a667d311d318142"}' \
  --param limits=@params/limits.json --output drop-preprod.plutus.json
```

```text
validator drop: applied 2 parameters; hash 4ba1e0d80a5891c79032b5eccdf0514392f90e90278ce6567a7d3d76; wrote .../drop-preprod.plutus.json
```

If the validator sets `expected-abi` and the current ABI differs, `apply` refuses to apply parameters. See [Build and deploy](/contracts/build-and-deploy/).

## Applying parameters from Java

Off-chain Java code can apply the same parameters to the unapplied artifact with Julc's Cardano Client Lib adapter. Pass `PlutusData` values in the declared order. This example gives the same hash as `elci apply` above:

```java title="ApplyParams.java (example)"
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.ValidatorOutput;
import org.julclang.core.PlutusData;

var output = ValidatorOutput.fromJson(Files.readString(
        Path.of("build/validators/drop/validator.plutus.json")));

var admin = PlutusData.bytes(HexFormat.of().parseHex(
        "887d8fbb82ce756a9dac9210c3d1235e9d3ff2457a667d311d318142"));
// Limits { minimum = 1, maximum = 10 }: fields are sorted by name, so maximum comes first.
var limits = new PlutusData.ConstrData(BigInteger.ZERO, List.of(
        PlutusData.integer(BigInteger.TEN),
        PlutusData.integer(BigInteger.ONE)));

var program = JulcScriptAdapter.toProgram(output.cborHex()).applyParams(admin, limits);
var script = JulcScriptAdapter.fromProgram(program); // a Cardano Client Lib PlutusV3Script
System.out.println(HexFormat.of().formatHex(script.getScriptHash()));
```

```text
4ba1e0d80a5891c79032b5eccdf0514392f90e90278ce6567a7d3d76
```

It needs `org.julclang:julc-cardano-client-lib` (at the Julc version your compiler uses) and Cardano Client Lib on the classpath. Unlike `elci apply`, this path doesn't check the values against their types, so validate them yourself.

# Vesting contract

This spending validator locks an output until a POSIX-time deadline. After the
deadline, only the beneficiary recorded in the datum can spend it.

The datum has the following Elci type:

```elm
type alias VestingDatum =
    { beneficiary : PubKeyHash
    , deadline : Int
    }
```

The redeemer is `Claim`, encoded as constructor `0` with no fields. The validator
accepts a claim only when both conditions hold:

1. the transaction validity interval has a finite lower bound greater than or
   equal to `deadline`; and
2. the transaction is signed by `beneficiary`.

Using the lower bound means every possible execution time admitted by the
transaction is at or after the deadline. A transaction whose range merely spans
the deadline is rejected.

From the repository root, check and compile the contract with:

```shell
build/install/elci/bin/elci check examples/vesting
build/install/elci/bin/elci build examples/vesting
```

After building the native CLI, the equivalent command is:

```shell
build/native/elci build examples/vesting
```

The generated artifacts are written under `examples/vesting/build/validators/vesting/`.

`tests/VestingTest.elm` holds transaction scenarios for claims at, after and before
the deadline, with and without the beneficiary's signature:

```shell
build/install/elci/bin/elci test examples/vesting
```

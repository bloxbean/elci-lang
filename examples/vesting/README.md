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

1. the transaction can only run after `deadline`:
   `Interval.isEntirelyAfter datum.deadline ctx.txInfo.validRange`; and
2. the transaction is signed by `beneficiary`: `Tx.signedBy datum.beneficiary ctx`.

The whole validity range must be after the deadline, so a transaction whose range
merely spans the deadline is rejected, and so is one that could run at the deadline
itself.

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
the deadline, with and without the beneficiary's signature, and a property that a
signed claim succeeds exactly when its range starts after the deadline:

```shell
build/install/elci/bin/elci test examples/vesting
```

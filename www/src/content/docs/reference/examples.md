---
title: Example projects
description: The example projects in the Elci repository, what each one demonstrates and how to check, build, test or evaluate it.
sidebar:
  order: 7
---

The Elci repository has eight small example projects under `examples/`. Each one builds with the current compiler, and those with tests pass `elci test`. This page tells you what each example demonstrates and how to run it.

Run the commands from the repository root, with `elci` on your `PATH` (see [Installation](/start/installation/)). `elci build` writes its artifacts to the example's own `build/` directory.

| Example | Kind | Demonstrates |
| --- | --- | --- |
| [threshold](#threshold) | spend validator | the smallest validator, `Int` datum and redeemer, a Julc Java library |
| [signed](#signed) | spend validator | reading the fee and signatories, the nominal `PubKeyHash` type |
| [vesting](#vesting) | spend validator with tests | a record datum, a validity range, scenarios and a property |
| [treasury](#treasury) | two validators with tests | spend, withdraw and mint handlers, parameters, a full test suite |
| [functional](#functional) | function project | recursive custom types, `List.map`, folds and pipelines |
| [core-completion](#core-completion) | function project | generic records and unions, nested patterns, destructuring |
| [elci-library](#elci-library) | function project | a reusable library written in Elci |
| [custom-library](#custom-library) | function project | a custom Java on-chain library supplied as a JAR |

A function project has an `[entrypoint]` function instead of validators. You run it with `elci eval`, which prints the result in Elci syntax and then its CPU and memory cost.

## threshold

[examples/threshold](https://github.com/bloxbean/elci-lang/tree/main/examples/threshold)

The smallest validator in the repository. `Example.Threshold.validate` takes an `Int` datum, the minimum, and an `Int` redeemer, the offer, and accepts when the offer is at least the minimum or 0, whichever is larger. The comparison uses `Math.max` from Julc's `MathLib`, imported through `[java-modules]` as `Julc.Math`. The module lives at `src/Example/Threshold.elm`, which shows how a dotted module name maps to a path.

```sh
elci check examples/threshold
elci build examples/threshold
```

The example has no tests, so `elci test examples/threshold` prints `No tests found in tests` and exits with status 1. It makes a good first exercise for [scenarios](/testing/scenarios/): spending with a datum of 10 and a redeemer of 12 must succeed, and a redeemer of 9 must fail.

## signed

[examples/signed](https://github.com/bloxbean/elci-lang/tree/main/examples/signed)

A spend validator that reads the transaction. Its datum is the owner's `PubKeyHash` and its redeemer a minimum fee. It accepts when `ctx.txInfo.fee` is at least that minimum and the owner is among `ctx.txInfo.signatories`. The `PubKeyHash` is a nominal ledger type: it compares with other key hashes, not with raw bytes.

```sh
elci check examples/signed
elci build examples/signed
```

## vesting

[examples/vesting](https://github.com/bloxbean/elci-lang/tree/main/examples/vesting)

A vesting contract that locks an output until a deadline. The datum is a record, `VestingDatum { beneficiary, deadline }`, and the redeemer a one-constructor union, `Claim`. A claim succeeds when the transaction's validity range starts at or after the deadline, read with `Interval.finiteLowerBound` from Julc's `IntervalLib`, and the beneficiary signs.

`tests/VestingTest.elm` holds scenarios for claims at, after and before the deadline, with and without the signature, and a property that a signed claim succeeds exactly from the deadline on.

```sh
elci check examples/vesting
elci test examples/vesting
elci build examples/vesting
```

```text
PASS VestingTest.claimsAtTheDeadline (cpu 14681744, mem 54515)
PASS VestingTest.claimsAfterTheDeadline (cpu 14681744, mem 54515)
PASS VestingTest.earlyClaimsFail (cpu 11653545, mem 44742)
PASS VestingTest.unsignedClaimsFail (cpu 13882675, mem 51354)
PASS VestingTest.claimsSucceedExactlyFromTheDeadline (100 runs, max cpu 14681744, mem 54515)
5 passed, 0 failed (seed 42)
```

The output above comes from `elci test examples/vesting --seed 42`. The tutorial [Build a vesting contract](/tutorials/vesting/) builds a contract like this one step by step.

## treasury

[examples/treasury](https://github.com/bloxbean/elci-lang/tree/main/examples/treasury)

Two independent scripts in one project, both parameterized by an `admin` key:

- **`treasury`** is one script with two handlers. Its `spend` handler releases a `Payout { beneficiary, amount }` to the beneficiary with `Release`, or lets the admin `Cancel`. Its `withdraw` handler lets the admin withdraw the script's staking rewards.
- **`badge`** is a minting policy that accepts a positive quantity signed by the admin.

`src/Auth.elm` holds the shared `signedBy` check, and each script links only what its handlers use. `tests/TreasuryTest.elm` is the most complete test suite in the repository: scenarios for every role, a budget limit, a `Bool` and an `Expectation` test, and three properties.

```sh
elci check examples/treasury
elci test examples/treasury
elci build examples/treasury
```

```text
PASS TreasuryTest.beneficiaryReleases (cpu 11746810, mem 46979)
PASS TreasuryTest.strangerCannotRelease (cpu 12125243, mem 48911)
PASS TreasuryTest.adminCancels (cpu 11358693, mem 45820)
PASS TreasuryTest.adminWithdrawsRewards (cpu 6103274, mem 24333)
PASS TreasuryTest.rewardsNeedTheAdmin (cpu 6481707, mem 26265)
PASS TreasuryTest.adminMintsBadges (cpu 5468168, mem 21200)
PASS TreasuryTest.zeroBadgesAreRejected (cpu 2789841, mem 12055)
PASS TreasuryTest.keysAreDistinct (cpu 1320080, mem 4623)
PASS TreasuryTest.payoutsRoundTripThroughData (cpu 6436315, mem 23234)
PASS TreasuryTest.payoutsAlwaysRoundTrip (100 runs, max cpu 9087645, mem 36791)
PASS TreasuryTest.onlyTheBeneficiaryReleases (100 runs, max cpu 12125243, mem 48911)
PASS TreasuryTest.badgesNeedAPositiveQuantity (100 runs, max cpu 5468168, mem 21200)
12 passed, 0 failed (seed 42)
```

The output above comes from `elci test examples/treasury --seed 42`. `elci build` writes `build/validators/treasury/` and `build/validators/badge/`. The tutorial [A treasury and its badge policy](/tutorials/treasury/) walks through the design.

## functional

[examples/functional](https://github.com/bloxbean/elci-lang/tree/main/examples/functional)

A function project that sums a list of binary trees. It defines a recursive custom type, `Tree = Leaf Int | Branch Tree Tree`, a recursive function over it, and a pipeline with `List.map` and `List.foldl`.

```sh
elci eval examples/functional
```

```text
12
cpu 11011670, mem 45999
```

## core-completion

[examples/core-completion](https://github.com/bloxbean/elci-lang/tree/main/examples/core-completion)

A tour of the core language in one function project: a generic record alias `Box a` and its constructor, a generic union `Choice a`, nested list patterns with an `as` alias, tuple and record destructuring in function parameters and in `let`, and a generic equality helper.

```sh
elci eval examples/core-completion
```

```text
15
cpu 11311095, mem 41004
```

## elci-library

[examples/elci-library](https://github.com/bloxbean/elci-lang/tree/main/examples/elci-library)

A library written in Elci. `lib/Finance/Pricing.elm` exposes `totalWithFee` and keeps its `subtotal` helper private, and `src/Main.elm` imports it like any other module. Both directories are listed in `source-directories`, so no Java mapping or JAR is needed. To share such a library, share its source directory and add it to the consuming project's `source-directories`.

```sh
elci eval examples/elci-library
```

```text
105
cpu 2943076, mem 14126
```

## custom-library

[examples/custom-library](https://github.com/bloxbean/elci-lang/tree/main/examples/custom-library)

A custom on-chain Java library. `java/META-INF/plutus-sources/example/Pricing.java` is a Julc `@OnchainLibrary` class with a Java record, `Quote`. The project lists the JAR in `library-jars` and maps the class to the Elci module `Custom.Pricing` in `[java-modules]`. Elci reads the Java source from the JAR and Julc compiles it; no JVM code from the JAR runs. `Quote` stays a nominal type: its fields can be read, and values are made with the factory `Pricing.quote`.

Package the JAR first, then evaluate:

```sh
mkdir -p examples/custom-library/build
jar --create --file examples/custom-library/build/pricing.jar -C examples/custom-library/java .
elci eval examples/custom-library
```

```text
42
cpu 1525434, mem 6453
```

[Java libraries](/advanced/java-libraries/) covers library JARs in detail.

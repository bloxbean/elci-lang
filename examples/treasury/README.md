# Treasury: named validators

This example configures two independent scripts in one project.

- **`treasury`**: one script with a `spend` and a `withdraw` handler.
  - Spending a `Payout` with `Release` requires the beneficiary's signature and a
    positive amount. `Cancel` requires the admin's signature.
  - Withdrawing the script address's staking rewards requires the admin's
    signature.
- **`badge`**: a separate minting policy. It accepts a positive quantity signed by
  the admin.

Both are parameterized by `admin`, a `PubKeyHash` applied before deployment.
`Auth.signedBy` is shared source; each script links only what its handlers reach.

```sh
elci build examples/treasury
ls examples/treasury/build/validators/treasury examples/treasury/build/validators/badge
```

`TreasuryExampleTest` builds the project with the CLI and reloads each artifact. It
applies the `admin` parameter, then evaluates every role and some rejected
purposes with complete ledger contexts.

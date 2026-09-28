# End-to-end tests on a devnet

These tests deploy Elci's example contracts on a local
[Yaci DevKit](https://github.com/bloxbean/yaci-devkit) devnet and submit real
transactions with [Cardano Client Lib](https://github.com/bloxbean/cardano-client-lib).
The node, not an evaluator, decides every outcome: accepted transactions are on
chain, and every attack is submitted and must be rejected with a `PlutusFailure`.

| Test | What the node checks |
| --- | --- |
| `VestingE2ETest` | A claim that could run at the deadline is rejected; one after it is accepted. |
| `EscrowE2ETest` | A release paid to the seller in a tagged output is accepted. One payment cannot release two escrows (double satisfaction), and an untagged payment releases nothing. |
| `MarketplaceE2ETest` | With its `Market` parameter applied, a buyer pays the seller and the operator and takes a real NFT. A seller paid one lovelace short keeps the NFT; only the seller cancels. |
| `SwapE2ETest` | With real native tokens, an order is filled in two parts, the rest returning to the script under the same order. A returned order padded with another token is rejected. |
| `BadgeE2ETest` | The treasury example's minting policy mints for its admin, and rejects anyone else and a quantity of zero. |
| `BudgetE2ETest` | The node charges exactly the budget Julc's evaluator (the VM `elci test` runs) computes: it accepts the release at that budget and rejects it one CPU step or one memory unit short. Julc and Yaci Store's evaluator agree. |

## Running

Start a devnet at protocol version 11, which Elci's target needs:

```sh
devkit start          # then, in the Yaci CLI: create-node -o --start
./gradlew :e2e-test:e2eTest
```

The tests read Yaci Store at `http://localhost:8080/api/v1/` and the admin API at
`http://localhost:10000/local-cluster/api/`; override them with
`-PyaciStoreUrl=...` and `-PyaciAdminUrl=...`. Without a devnet the tests are
skipped. `./gradlew test` never runs them.

Each test funds fresh accounts from the devnet's faucet, so the tests are
independent of each other and of earlier runs.

## Notes

- Scripts are compiled with `ElciTestkit`, exactly as `elci build` compiles
  them, and turned into Cardano Client Lib scripts with Julc's
  `JulcScriptAdapter`.
- Datums follow Elci's encoding: a record is constructor 0 with its fields
  sorted by name (`Contracts.record`), as `abi.json` lists them.
- A transaction expected to fail is priced with fixed budgets (`FixedUnits`), so
  it reaches the node, which must reject it itself.
- The swap uses the protocol's native value builtins (CIP-153), which Yaci
  Store's evaluator does not run yet; its fills are priced with Julc's
  evaluator, and the node validates them.

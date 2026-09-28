# Order swap

A limit order: a maker locks GOLD tokens and asks 3 SILVER for each. A taker fills any part
of it by paying the maker. The maker may cancel. Only the offered token is for sale:

- A partial fill must return everything else in the order to the script under the same
  order datum: the remaining GOLD, the lovelace and any other tokens
  (`partialFillMustReturnEverything`, `partialFillMustReturnTheOtherTokens`).
- The returned order holds exactly those tokens and no others (`junkTokensRejected`), so
  no one can pad an order until its full fill no longer fits in one output.
- A full fill pays the maker the price and everything the order held besides the GOLD
  (`fullFillMustPayTheDeposit`, `fullFillMustPayTheOtherTokens`).
- The returned order must be the same order (`changedOrderRejected`).
- The maker sets the price; a price of 0 or less gives the GOLD away.

Two rules keep one output from counting twice:

- The maker's payment carries the order's output reference as its inline datum
  (`untaggedPaymentRejected`, `wrongTagRejected`).
- A fill may spend only one input of this script, whatever its staking part, so one
  returned output cannot stand for two orders (`secondOrderInTheSameTransactionRejected`,
  `secondOrderUnderAnotherStakeRejected`).

The maker is identified by payment credential, so the taker chooses the staking part of the
maker's payment. A contract that must protect the maker's staking rights stores the maker's
full address and compares it.

It uses `Tx.ownInput`, `Tx.continuingOutputs`, `Tx.outputsTo`, `Tx.inlineDatum`,
`Value.geq` and `Value.withoutLovelace`. The property `fillsAreChecked` covers every fill size with exact and short
payments.

```sh
elci test examples/order-swap
```

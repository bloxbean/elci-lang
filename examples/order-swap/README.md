# Order swap

A limit order: a maker locks GOLD tokens and asks 3 SILVER for each. A taker fills any part
of it by paying the maker; a partial fill must return the rest to the script under the same
order datum. The maker may cancel.

Two rules keep one output from counting twice:

- the maker's payment carries the order's output reference as its inline datum;
- a fill may spend only one output of this script, so one returned output cannot stand
  for two orders (`secondOrderInTheSameTransactionRejected`).

It uses `Tx.ownInput`, `Tx.continuingOutputs`, `Tx.outputsTo`, `Tx.inlineDatum` and
`Value.quantityOf`. The property `fillsAreChecked` covers every fill size with exact and
short payments.

```sh
elci test examples/order-swap
```

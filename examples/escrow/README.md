# Escrow

A buyer locks a payment for a seller:

- **Release**: the buyer signs, and the transaction pays the seller at least the price.
- **Refund**: the buyer signs a transaction that runs entirely after the deadline.

The seller's payment must carry the escrow's output reference as its inline datum. Without
that tag, one payment could settle two escrows for the same seller in one transaction
(double satisfaction); `doubleSatisfactionRejected` shows the attack failing.

It uses `Tx.signedBy`, `Tx.outputsTo`, `Tx.inlineDatum`, `Tx.ownRef` and
`Interval.isEntirelyAfter`.

```sh
elci test examples/escrow
elci build examples/escrow
```

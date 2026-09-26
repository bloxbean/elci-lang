# Escrow

A buyer locks a payment for a seller, who is paid when the buyer approves; after a deadline
the buyer may take it back:

- **Release**: the buyer signs, and the transaction pays the seller at least the price.
- **Refund**: the buyer signs a transaction that runs entirely after the deadline.

The seller's payment must carry the escrow's output reference as its inline datum. Without
that tag, one payment could settle two escrows for the same seller in one transaction
(double satisfaction). `doubleSatisfactionRejected` releases two escrows with one payment
and fails; `paymentWithoutTagRejected` and `wrongTagRejected` show the tag is required.

The seller is identified by payment credential, so the buyer chooses the staking part of the
seller's payment. A contract that must protect the seller's staking rights stores the
seller's full address and compares it.

It uses `Tx.signedBy`, `Tx.outputsTo`, `Tx.inlineDatum`, `Tx.ownRef` and
`Interval.isEntirelyAfter`.

```sh
elci test examples/escrow
elci build examples/escrow
```

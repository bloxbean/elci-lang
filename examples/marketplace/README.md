# NFT marketplace

A seller locks an NFT with a listing (seller and price in lovelace). Anyone may buy it by
paying the seller the price less the marketplace's fee and paying the operator the fee,
both tagged with the listing's output reference. The seller may cancel.

- The tag keeps one pair of payments from buying two listings
  (`onePaymentForTwoListingsRejected`, `untaggedPaymentRejected`).
- An operator selling their own NFT is owed the whole price, in one tagged output
  (`operatorSellingIsPaidInFull`).
- A fee outside 0 to 10000 basis points rejects every purchase (`feeOutOfRangeRejected`,
  `negativeFeeRejected`).
- The buyer takes everything in the listing's output: the NFT, the lovelace the ledger
  requires with it, and anything else the seller locked there. A marketplace that wants the
  lovelace returned to the seller adds it to the seller's payment.
- Payees are identified by payment credential, so the buyer chooses the staking part of
  each payment.

The marketplace (operator and fee in basis points) is a deployment parameter:

```sh
elci test examples/marketplace
elci build examples/marketplace
```

It uses `Tx.ownRef`, `Tx.outputsTo`, `Tx.inlineDatum`, `Tx.signedBy` and `Value.lovelaceOf`,
and exact integer arithmetic for the fee: `fee` rounds down. The property
`buyingPaysExactly` checks, at any price and fee, that the exact split buys the NFT and one
lovelace less to the seller does not.

# NFT marketplace

A seller locks an NFT with a listing (seller and price in lovelace). Anyone may buy it by
paying the seller the price less the marketplace's fee and paying the operator the fee,
both tagged with the listing's output reference. The seller may cancel.

The marketplace (operator and fee in basis points) is a deployment parameter:

```sh
elci test examples/marketplace
elci build examples/marketplace
```

It uses `Tx.ownRef`, `Tx.outputsTo`, `Tx.inlineDatum`, `Tx.signedBy` and `Value.lovelaceOf`,
and exact integer arithmetic for the fee (`fee` rounds down; a property checks the split).

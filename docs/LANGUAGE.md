# Elci language guide

## Implemented profile

Elci uses an implemented subset of Elm syntax and its own on-chain semantics.
The design ADR remains the roadmap; this implementation does not complete every
core-release or marketplace milestone in that roadmap.

| Feature | Current support |
| --- | --- |
| Modules, imports, aliases, exposing | Source modules and configured Java modules; import cycles rejected |
| Functions, lambdas, partial application | Strict, curried application and immediate argument capture |
| Top-level generics | Rank-1 inference, signature checking, concrete specialization |
| Local declarations | Dependency ordering and local functions; local bindings currently monomorphic |
| Recursion | Self-recursion and two-function mutual groups; larger groups rejected |
| Basic types | Arbitrary-precision Int, Bool, String, unit |
| Lists | Homogeneous Data-backed lists, list patterns, generic core functions |
| Tuples | Pairs/triples and irrefutable tuple case patterns; tag-zero encoding |
| Records | Closed structural records, monomorphic aliases, field access and updates |
| Custom types | Generic and regular recursive unions; declaration-order tags |
| Patterns | Variables, wildcard, Bool/Int, empty list/cons, tuples, constructor fields |
| Pattern coverage | Exhaustive cases required; duplicate/unreachable branches rejected |
| Ledger access | Typed record views, transaction fields, nominal hash types |
| Java libraries | Concrete supported exports from bundled on-chain Java sources |
| CLI | Check/build/function evaluation; pinned target/version; ABI comparison |
| Native image | GraalVM executable with bundled source and reflection resources |

Not yet implemented: row polymorphism, generic record aliases, polymorphic
recursion, general local let-polymorphism, arbitrary-sized mutual groups,
refutable nested patterns, parameter destructuring, full constrained structural
equality, generic Java export schemes/programmatic provider catalogues, deployment
parameters, optional-datum profiles, other script purposes, complete ledger-sum
pattern adapters, or an Elci language server. Java wildcard exposing is currently
rejected; use explicit exported names. Float, browser effects and ports are
outside the on-chain profile.

## Functions and types

```elm
identity : a -> a
identity value =
    value


apply : (a -> b) -> a -> b
apply transform value =
    transform value
```

Every top-level definition needs a signature. Lowercase type variables are
universally quantified at eligible top-level functions. A signature cannot claim
`a -> a` for an implementation that only operates on integers. Generic helpers
are specialized for their concrete uses. Entrypoints cannot retain type variables.

Application is curried and strict. `f expressionA expressionB` evaluates the
function, then `expressionA`, then `expressionB`. A partial application evaluates
and captures its supplied argument immediately. It does not defer a failure until
the missing argument arrives.

Lambdas use `\argument -> expression`. Multiple arguments are supported. A
function can call itself or another function declared later. Eager value cycles
are errors.

## Arithmetic and control flow

`Int` is arbitrary precision. `+`, `-`, and `*` are exact; `//` truncates toward
zero. `remainderBy divisor value` uses the dividend's sign. Division/remainder by
zero fail evaluation. `/` and Float literals are rejected.

`if`, `&&`, `||`, and `case` evaluate only the selected branch. Local values are
strict even when unused: a failing or diverging unused binding cannot be removed.
Forward local references are evaluated in dependency order; source order breaks
independent ties.

`==` and `/=` currently support Int, Bool, String, raw bytes and matching nominal
byte-backed ledger hashes. Function equality and general structural equality are
not supported. Integer ordering uses `<`, `<=`, `>`, `>=`. Comparisons cannot be
chained without parentheses or Boolean operators.

`|>` and `<|` apply functions; `>>` and `<<` compose them. Composition captures
its function operands strictly. `++` appends lists or strings; `::` prepends a list
element.

## Lists and recursion

`List` is implicitly available. The bundled core exposes `map`, `filter`, `foldl`,
`foldr`, `all`, `any`, `length`, `reverse`, `append`, and `concatMap`.

```elm
sumPositive : List Int -> Int
sumPositive values =
    values
        |> List.filter (\value -> value > 0)
        |> List.foldl (\value total -> value + total) 0
```

These core helpers are Elci source, compiled through the same frontend. Their
implementation is visible in `src/main/resources/elci/core/List.elm`.

```elm
sum : List Int -> Int
sum values =
    case values of
        [] ->
            0

        first :: rest ->
            first + sum rest
```

Lists use Julc's native `List<Data>` representation internally, with explicit
encoding/decoding of each element. Serialized lists use `ListData`. The compiler
does not pretend native integer lists and Data lists are interchangeable.

## Records and tuples

```elm
type alias Listing =
    { price : Int, active : Bool }


increase : Listing -> Listing
increase listing =
    { listing | price = listing.price + 1 }
```

Aliases reuse structural record types. Source field order does not determine wire
order: serialized records use tag zero and fields sorted lexicographically by
name. Field expressions still evaluate in source order. Updates evaluate the base
once, then replacements in source order.

A record field access needs enough context to determine a closed record type.
Unconstrained row-polymorphic accessors are not inferred. Generic fields containing
functions or unit are not yet supported in serialized containers.

Pairs and triples use `( first, second )` and `( first, second, third )`. Use a
`case` with a tuple pattern to destructure them. Their encoding is tag zero with
fields in positional order, as recorded in the ABI; this is an Elci tuple codec,
not a promise of representation equivalence with arbitrary Java tuple types.

## Custom types and matching

```elm
type Tree
    = Leaf Int
    | Branch Tree Tree
```

Constructors are curried functions. The first constructor has tag zero, the
second tag one, and fields preserve argument order. Recursive references retain a
stable nominal identity. `Maybe a`, `Nothing`, and `Just` are built in, with
`Nothing` tag zero and `Just` tag one. This differs from Julc Java Optional's tags;
they are not silently interchangeable.

Cases must be exhaustive. Constructor fields, list heads/tails, and tuple elements
can bind names or `_`. For nested refutable patterns, write a nested `case`.
Patterns evaluate the scrutinee once. Duplicate variable names in a pattern are
errors.

## Modules and Java imports

A source module `Market.Pricing` belongs at `src/Market/Pricing.elm` under a
configured source directory. Imports can use `as` and explicit `exposing`. Only
exposed source names are importable. Module cycles and duplicate ownership are
errors.

Configure a Java library mapping in `elci.toml`, then write normal Elm imports:

```elm
import Julc.Math as Math exposing (max)
```

`Math.max` and `max` resolve to the configured class's public on-chain export.
The provider parses and compiles its real Java source into PIR. It does not run
that Java function on the JVM to fake an on-chain result. Ambiguous Java overloads
are rejected. Generic Java signatures and unmapped result/parameter types are not
exported as erased Data.

## Ledger context and boundaries

```elm
import Julc.Ledger exposing (PubKeyHash, ScriptContext)


validate : PubKeyHash -> Int -> ScriptContext -> Bool
validate owner minimumFee ctx =
    ctx.txInfo.fee >= minimumFee
        && List.any (\signer -> signer == owner) ctx.txInfo.signatories
```

The imported ledger model retains nominal type identity. `PubKeyHash` and raw
bytes do not unify even though their runtime representations are byte strings.
The `.hash` accessor explicitly exposes the underlying bytes where available.
Ledger hash lengths follow Julc's existing model; no new length invariant is
invented by Elci.

Ledger records are currently context views. Datum/redeemer schemas accept Elci
records/unions/lists/tuples and supported primitive or nominal hash types; imported
ledger records and ScriptContext are rejected as user-data schemas until their
full adapter/codec surface is verified. Complex ledger sums, maps, Value APIs and
all transaction-wide contract patterns are not yet a complete supported surface.

Strict boundary checks validate nested datum/redeemer kinds, constructor tags,
arity and fields, even when the handler ignores those values. The ledger context
uses Julc's existing ledger-supplied-context convention; it is not a new universal
decoder that certifies arbitrary off-chain context data.

## Costs and limits

Recursion expresses unbounded computations, but ledger execution is budgeted.
List functions are ordinary traversals; nested scans can be quadratic. Use the
VM and inspect CPU/memory budgets. `BudgetTest` writes representative traversal
measurements to `build/reports/elci/budgets.csv`.

Compilation has finite specialization and recursive-type expansion guards.
Unsupported growth is a compile error, not implicit type erasure. Compiler size
and resource limits will need further hardening before accepting untrusted source
as a hosted service.

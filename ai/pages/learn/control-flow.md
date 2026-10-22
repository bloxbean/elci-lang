# Conditionals, let and pipelines

> Make decisions with if and case, name intermediate values with let, chain calls with pipelines, and understand Elci's strict evaluation.

Source: https://elci-lang.dev/learn/control-flow/

This page shows how to make decisions with `if` and `case`, name intermediate results with `let`, and chain
function calls with pipelines. It ends with how Elci evaluates your code, which matters for both
correctness and cost on-chain.

## if … then … else

`if` is an expression, so it always has an `else` and both branches have the same type. Chain conditions
with `else if`:

```elm title="src/Main.elm"
module Main exposing (main)

feeFor : Int -> Int
feeFor amount =
    if amount < 1000 then
        0

    else if amount < 100000 then
        10

    else
        amount // 1000

main : Int
main =
    feeFor 500 + feeFor 5000 + feeFor 250000
```

With `--builtins`, `elci eval` also lists every builtin operation the script performed:

```sh
elci eval . --builtins
```

```text
260
cpu 1489307, mem 5910
builtin: LessThanInteger(500, 1000) → True
builtin: LessThanInteger(5000, 1000) → False
builtin: LessThanInteger(5000, 100000) → True
builtin: AddInteger(0, 10) → 10
builtin: LessThanInteger(250000, 1000) → False
builtin: LessThanInteger(250000, 100000) → False
builtin: QuotientInteger(250000, 1000) → 250
builtin: AddInteger(10, 250) → 260
```

Each call only tested the conditions it needed: `feeFor 500` stopped after the first comparison, and only
`feeFor 250000` divided.

## case … of

`case` compares a value against patterns, top to bottom, and evaluates the first branch that matches. `_`
matches anything:

```elm title="src/Main.elm"
module Main exposing (main)

label : Int -> String
label count =
    case count of
        0 ->
            "none"

        1 ->
            "one"

        _ ->
            "many"

main : ( String, String, String )
main =
    ( label 0, label 1, label 7 )
```

```text
( "none", "one", "many" )
cpu 1894636, mem 8277
```

Patterns can be `Int`, `String` or `Bool` literals, names, `_`, tuples, records, lists and constructors of
custom types. A `case` must cover every possible value; without the `_` branch above, the compiler rejects
it. [Custom types and pattern matching](https://elci-lang.dev/learn/custom-types/) goes further.

## let … in

`let` names intermediate values. The names are visible in the rest of the `let` and in the expression after
`in`:

```elm title="src/Main.elm"
module Main exposing (main)

firstShare : Int -> Int -> Int
firstShare total shares =
    let
        perShare =
            total // shares

        leftover =
            remainderBy shares total
    in
    perShare + leftover

main : Int
main =
    firstShare 1000 3
```

```text
334
cpu 813990, mem 2904
```

A `let` can also define local functions, optionally with a signature, and destructure tuples and records.
You'll see those forms on the next pages.

## Pipelines and composition

Nested calls read inside out. The pipe operator `|>` passes a value into the next function, so the steps
read top to bottom:

```elm title="src/Main.elm"
module Main exposing (main)

main : Int
main =
    [ 5, -3, 12, 0, 8 ]
        |> List.filter (\n -> n > 0)
        |> List.map (\n -> n * 2)
        |> List.foldl (\n total -> n + total) 0
```

```text
50
cpu 12396014, mem 60117
```

`x |> f` means `f x`, and `f <| x` also means `f x`, which saves a pair of parentheses. `>>` and `<<` compose
two functions into a new one: `(f >> g) x` is `g (f x)`, and `(g << f) x` is the same.

```elm title="src/Main.elm"
module Main exposing (main)

double : Int -> Int
double n =
    n * 2

isLarge : Int -> Bool
isLarge n =
    n > 10

main : Bool
main =
    let
        doubledIsLarge =
            double >> isLarge
    in
    doubledIsLarge 6 && (not <| doubledIsLarge 5)
```

```text
True
cpu 1197680, mem 5906
```

`<|` and `|>` have the lowest precedence of all operators, which is why `not <| doubledIsLarge 5` needs
parentheses when it's part of a larger expression.

## Strict evaluation

Elci evaluates eagerly, like Elm. Knowing exactly what gets evaluated matters on-chain, for two reasons:
every step costs budget, and a single failure rejects the whole transaction.

**Local values are always evaluated, even when unused.** This program fails, although it never uses
`unused`:

```elm title="src/Main.elm"
module Main exposing (main)

main : Int
main =
    let
        unused =
            1 // 0
    in
    5
```

```text
evaluation failed: QuotientInteger: division by zero
cpu 276441, mem 1001
```

`Debug.trace message value` returns `value` and records `message`, and `elci eval` prints each message on a
`trace:` line, so you can watch the order. `Debug`, like `List`, is imported into every module. Both values
are computed here, in source order, although the `if` only returns one of them:

```elm title="src/Main.elm"
module Main exposing (main)

main : Int
main =
    let
        small =
            Debug.trace "computing small" 1

        large =
            Debug.trace "computing large" 1000
    in
    if True then
        small

    else
        large
```

```text
1
cpu 471096, mem 2364
trace: computing small
trace: computing large
```

**`if`, `case`, `&&` and `||` evaluate only what they need.** Only the chosen branch of an `if` or `case`
runs, and `&&` and `||` skip their right side when the left side decides the answer. So put expensive or
failing work inside a branch:

```elm title="src/Main.elm"
module Main exposing (main)

safeDivide : Int -> Int -> Int
safeDivide amount parts =
    if parts == 0 then
        0

    else
        amount // parts

main : Int
main =
    safeDivide 10 0 + safeDivide 10 3
```

```text
3
cpu 962315, mem 4005
```

The other rules follow the same idea:

- A function call evaluates the function, then its arguments from left to right, before the body runs.
  So `Maybe.withDefault fallback m` evaluates `fallback` even when `m` is a `Just`, and `(&&) a b`, the
  operator used as a function, evaluates `b` even when `a` is `False`.
- A partial application such as `add (10 // 0)` evaluates its argument immediately.
- In a validator, top-level values the handlers use are evaluated once per script run, before the handler
  is chosen. A failing top-level value therefore fails every purpose of the script.

:::tip
In a validator, cheap checks that often fail belong on the left of `&&`. When the left side is `False`, the
expensive right side never runs, which saves budget.
:::

`Debug.trace` also costs budget on-chain, so remove traces before you deploy. [Debugging and
budgets](https://elci-lang.dev/testing/debugging/) has more.

## Next

[Records and tuples](https://elci-lang.dev/learn/records-and-tuples/) groups values into structures.

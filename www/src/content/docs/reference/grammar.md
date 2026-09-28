---
title: Grammar
description: Elci's formal grammar in EBNF — the lexical grammar, the exact layout rules, the syntactic grammar, operator precedence, the adjacency rules for `.` and `-`, and the Elm syntax Elci rejects.
sidebar:
  order: 6
---

This page is Elci's formal grammar: the lexical grammar, the layout rules and the syntactic grammar. It
describes what the compiler's parser accepts, including the edge cases. For a summary of the syntax, see
the [language reference](/reference/language/#syntax-summary); for what programs mean, the rest of that
page.

The grammar covers syntax only. A program that matches it can still be rejected later, for example for a
type error, a missing signature or an unknown name.

The same grammar is kept in the repository as
[`docs/GRAMMAR.md`](https://github.com/bloxbean/elci-lang/blob/main/docs/GRAMMAR.md). A test parses every
complete module it shows, and the syntax corpus has a module for each area of the grammar. The repository also has
a [tree-sitter grammar](https://github.com/bloxbean/elci-lang/tree/main/tree-sitter-elci) that follows it,
for highlighting and folding in Neovim, Helix and Zed.

## Notation

The grammar uses the W3C EBNF notation of the XML 1.0 specification (section 6):

| Notation | Meaning |
| --- | --- |
| `A ::= ...` | defines the symbol `A` |
| `"text"` | the literal text |
| `[abc]`, `[^abc]` | one character from the set, or one not in it |
| `#xN` | the character with hexadecimal code point `N` |
| `A B` | `A` followed by `B` |
| `A \| B` | `A` or `B` |
| `A?`, `A*`, `A+` | `A` optionally, zero or more times, one or more times |
| `A - B` | what `A` matches, except what `B` matches |
| `( ... )` | grouping |
| `/* ... */` | a comment |

Elci's layout needs two additions, used only in the syntactic grammar:

| Notation | Meaning |
| --- | --- |
| `X<f>` | the symbol `X` read with the *floor* `f`, a column (see [Layout](#layout)); `X<none>` has no floor |
| `CONT<f>` | matches nothing; allowed only when the next token is not a *boundary* for the floor `f` |
| `COL<c>` | matches nothing; allowed only when the next token is in column `c` |

The lexical grammar is over characters. The syntactic grammar is over tokens: a quoted string there
means a token with that text, and the lexer has already removed whitespace and comments.

## Lexical grammar

The lexer reads the source from left to right and produces tokens. Between tokens it skips
whitespace and comments. At each position it tries the rules in this order: whitespace, line
comment, block comment, name, integer, string, symbol.

### Whitespace and positions

```text
Space     ::= /* a character for which Java's Character.isWhitespace is true, except tab */
Tab       ::= #x9
```

- A tab outside a string or comment is an error: `ELCI1001: Tabs are not permitted; use spaces for
  Elm layout`. Tabs inside strings and comments are allowed.
- Every token has a line and a column, both counted from 1. The column counts characters (Java
  `char`s) from the start of the line. A newline is `#xA`; `#xD` is whitespace, so CRLF line endings
  work.

### Comments

```text
LineComment  ::= "--" [^#xA]*
BlockComment ::= "{-" CommentText ( BlockComment CommentText )* "-}"
CommentText  ::= Char* - ( Char* ( "{-" | "-}" ) Char* )
Char         ::= /* any character */
```

- Block comments nest: `{- a {- b -} c -}` is one comment.
- The lexer scans a block comment from left to right: each `{-` opens a nested comment, and each
  `-}` closes one. So `{- a{-}` is unterminated, since its `{-}` opens a comment
  (`ELCI1001: Unterminated block comment`).
- `{-| ... -}`, Elm's doc comment, is an ordinary block comment. The compiler does not read it.
- An unterminated block comment is `ELCI1001: Unterminated block comment`.
- Comments are not tokens. They take no part in layout: a token after a comment is placed by its
  own line and column.

### Names and reserved words

```text
Letter    ::= /* a character for which Character.isLetter is true */
Digit     ::= /* a character for which Character.isDigit is true */
Name      ::= ( Letter | "_" ) ( Letter | Digit | "_" )*
LowerName ::= Name - Reserved   /* whose first character is a lowercase letter */
UpperName ::= Name              /* whose first character is an uppercase letter */
Reserved  ::= "then" | "else" | "in" | "of" | "module" | "import" | "exposing" | "as" | "type"
            | "port"
```

- `_` alone is the wildcard pattern.
- `if`, `case` and `let` are keywords where an expression starts. `alias` is a keyword only after
  `type`. `True` and `False` are the Boolean literals.

### Integers

```text
Int ::= Digit+
```

- An integer is decimal and has no size limit.
- A `.` followed by a digit right after an integer is an error: `ELCI1001: Float is unsupported;
  represent fractional amounts using an explicit integer scale`.
- `0xFF` and `1e3` are not literals: they read as the integer `0` or `1` followed by a name.

### Strings

```text
String     ::= '"' ( StringChar | Escape )* '"'
StringChar ::= Char - ( '"' | "\" | #xA | #xD )
Escape     ::= "\" ( "n" | "r" | "t" | '"' | "\" )
```

- A string is on one line. A raw line break in it is `ELCI1001: Use an escaped newline in a string`.
- Any other escape, such as `\u{0041}`, is `ELCI1001: Unsupported string escape`.
- There are no character literals and no triple-quoted strings.

### Symbols

```text
Symbol ::= "->" | "//" | "==" | "/=" | "<=" | ">=" | "&&" | "||" | "::" | "++" | "|>" | "<|"
         | ">>" | "<<" | ".."
         | "(" | ")" | "[" | "]" | "{" | "}" | "," | ":" | "=" | "|" | "." | "\" | "+" | "-"
         | "*" | "<" | ">" | "/" | "^"
```

- The two-character symbols are tried first, so `a+++b` reads as `a ++ + b`.
- Any other character is `ELCI1001: Unsupported character 'c'`, for example `%`, `'`, `` ` `` and
  `!`.
- `/` is a symbol but not an operator: the parser rejects it (see [Operators](#operators)).

## Layout

Indentation is significant. The parser reads it from token columns; it does not insert layout
tokens. These are the exact rules.

**New line.** A token *starts a new line* when its line is after the line of the token before it.

**Floors and boundaries.** Every expression and type is read with a *floor* `f`, a column. A token
is a *boundary* for the floor `f` when it is the end of the input, or when it starts a new line in
column `f` or less. A boundary ends the expression or type being read. `none` is a floor that no
token reaches: only the end of the input is a boundary for it.

**Where boundaries are checked.** The parser looks for a boundary only at these points, written
`CONT<f>` in the grammar:

- in an expression, before a binary operator, before a function argument (including a negative
  argument such as `-1` in `f -1`) and before a field access `.field`;
- in a type, before each argument of a type application;
- in a custom type, before each argument type of a constructor.

Everything else is read at any column: the first token of an expression; the keywords `then`,
`else`, `of` and `in`; the symbols `->`, `=`, `:`, `|`, `,` and the closing brackets; every token of
a pattern; and the module header, imports and exposing lists.

**Blocks.** Three places start a block whose items align at one column `c`:

| Block | Column `c` | Items | The block ends |
| --- | --- | --- | --- |
| The module | 1 | declarations | at the end of the input |
| `let` | the column of the first token after `let` | declarations | at `in`, in any column |
| `case` | the column of the first token after `of` | branches | at a token not in column `c`, or at `in`, `else` or the end of the input |

- Each item starts in column `c`. After an item, the next token must end the block or be in column
  `c`. Otherwise it is:
  - after a value declaration, `ELCI1002: Unexpected token after declaration`;
  - after a `type`, a `type alias` or a destructuring, `ELCI1002: Declarations must align at
    column c`.
- A declaration's expressions and types have the floor `c`. A branch's body has the floor `c` too.
  So a line that continues a declaration or branch must be indented past `c`.

**Floors elsewhere.**

- The parts of `if`, the body of a lambda, the body after `in` and the scrutinee of `case` keep the
  floor of the expression they are in.
- Inside `( )`, `[ ]` and `{ }` in an expression, and inside a record type, the floor is `none`. A
  `let` or `case` inside brackets still has its own block column.
- Inside `( )` in a type, the floor is kept.

This module shows the rules:

```elm
module Layout exposing (total)


total : List Int -> Int
total values =
    let
        doubled =
            List.map (\value -> value * 2) values

        sum =
            List.foldl (+) 0 doubled
    in
    case doubled of
        [] ->
            0

        first :: _ ->
            sum
                + first
```

`sum` and `doubled` align in column 9. `first :: _` aligns with `[]` in column 9, and
`+ first` continues the branch because column 17 is past 9. `in` ends the `let` block at any
column.

## Syntactic grammar

### Modules and imports

```text
Module       ::= Header Import* ( COL<1> TopDecl )*
Header       ::= "module" ModuleName "exposing" Exposing
ModuleName   ::= UpperName ( "." UpperName )*
Import       ::= "import" ModuleName ( "as" UpperName )? ( "exposing" Exposing )?
Exposing     ::= "(" ( ".." | ( Exposed ( "," Exposed )* )? ) ")"
Exposed      ::= LowerName | UpperName ( "(" ".." ")" )?
```

- There are no `port module` or `effect module` headers.
- An exposing list names values, types, and types with all their constructors (`Shape(..)`). It
  cannot list constructors one by one or name an operator.
- Imports come before the first declaration.

```elm
module Example.Shapes exposing (Shape(..), area, unit)

import Dict exposing (Dict)
import Julc.Data as Data


type Shape
    = Square Int
    | Rect Int Int


area : Shape -> Int
area shape =
    case shape of
        Square side ->
            side * side

        Rect width height ->
            width * height


unit : Data
unit =
    Data.encode (Dict.fromList [ ( 1, area (Square 1) ) ])
```

### Declarations

```text
TopDecl         ::= TypeAlias<1> | CustomType<1> | ValueDecl<1>
LetDecl<c>      ::= TypeAlias<c> | CustomType<c> | ValueDecl<c> | Destructuring<c>

TypeAlias<c>    ::= "type" "alias" UpperName LowerName* "=" Type<c>
CustomType<c>   ::= "type" UpperName LowerName* "=" Constructor<c> ( "|" Constructor<c> )*
Constructor<c>  ::= UpperName ( CONT<c> TypeAtom<c> )*

ValueDecl<c>    ::= Signature<c>? LowerName PatternAtom* "=" Expr<c>
Signature<c>    ::= LowerName ":" Type<c> COL<c>
Destructuring<c> ::= ( PatternAtom - LowerName ) "=" Expr<c>
```

- A signature must be followed directly by the definition of the same name, in the same column.
  When another value declaration follows instead, it is
  `ELCI1002: A signature must precede its matching definition`. When the input ends, or a `type`
  or an uppercase name follows, it is `ELCI1002: Expected name` or `Expected lowercase name`. Every
  top-level value needs a signature, but that is a type-checking rule (`ELCI2004`).
- A function's parameters and a destructuring pattern must be irrefutable: `_`, a name, `()`, a
  tuple of irrefutable patterns, a record pattern, or an irrefutable pattern with `as`. Otherwise:
  `ELCI1002: Function parameters require irrefutable patterns; use case for refutable matching`,
  and likewise for lambda parameters, and `ELCI1002: Local destructuring requires an irrefutable
  tuple or record pattern`.
- Destructuring is only for `let`. At the top level it is `ELCI1002: Top-level declarations require
  a name`.
- A record type alias also declares a constructor function, with the fields in declaration order.

```elm
module Declarations exposing (Pair, Tree(..), main)


type alias Pair a =
    ( a, a )


type Tree a
    = Leaf
    | Node (Tree a) a (Tree a)


type alias Account =
    { owner : String, balance : Int }


main : Int
main =
    let
        ( low, high ) =
            ( 1, 2 )

        { balance } =
            Account "alice" 5

        add : Int -> Int -> Int
        add a b =
            a + b
    in
    add low high + balance
```

### Types

```text
Type<f>       ::= TypeApp<f> ( "->" Type<f> )?
TypeApp<f>    ::= TypeAtom<f> ( CONT<f> TypeAtom<f> )*
TypeAtom<f>   ::= QualifiedType | LowerName
                | "(" ")"
                | "(" Type<f> ")"
                | "(" Type<f> "," Type<f> ( "," Type<f> )* ")"
                | "{" ( FieldType ( "," FieldType )* )? "}"
FieldType     ::= LowerName ":" Type<none>
QualifiedType ::= UpperName ( "." UpperName )*
```

- `->` groups to the right: `a -> b -> c` is `a -> (b -> c)`.
- A lowercase name is a type variable. `comparable`, `appendable` and `compappend` (and names that
  start with them) are constrained, as in Elm.
- Tuples are written `( a, b )` or `( a, b, c )`; a longer tuple is `ELCI2012: Tuples require two
  or three elements`. `()` is the unit type. `Tuple` and `Unit` are not type names: `ELCI2012: Write
  tuple types as ( a, b ) or ( a, b, c )` and `ELCI2012: Write the unit type as ()`.
- Records are closed. `{ r | field : Int }` is `ELCI1002: Extensible records are unsupported; use a
  closed record`.
- `Float` is `ELCI2001: Float is unsupported; use scaled Int`.

### Expressions

```text
Expr<f>        ::= Operand<f> ( CONT<f> BinaryOperator Operand<f> )*   /* grouped by the operator table */

Operand<f>     ::= Head<f> ( CONT<f> Suffix )*
Head<f>        ::= Atom
                 | "-" Operand<f> ( CONT<f> ( ">>" | "<<" ) Operand<f> )*   /* negation */
                 | "if" Expr<f> "then" Expr<f> "else" Expr<f>
                 | "let" LetBlock "in" Expr<f>
                 | "case" Expr<f> "of" CaseBlock
                 | "\" PatternAtom+ "->" Expr<f>
Suffix         ::= FieldAccess | Argument | NegativeArgument
Argument       ::= Atom FieldAccess*
NegativeArgument ::= "-" Atom FieldAccess*     /* see Adjacency rules */
FieldAccess    ::= "." LowerName               /* the "." touches the token before it */

LetBlock       ::= ( COL<c> LetDecl<c> )*      /* c: the column of the first token after "let" */
CaseBlock      ::= Branch<c> ( COL<c> Branch<c> )*   /* c: the column of the first token after "of" */
Branch<c>      ::= Pattern "->" Expr<c>

Atom           ::= QualifiedName
                 | "True" | "False" | Int | String
                 | "(" ")"
                 | "(" Expr<none> ")"
                 | "(" Expr<none> "," Expr<none> ( "," Expr<none> )? ")"
                 | "(" BinaryOperator ")"
                 | "[" ( Expr<none> ( "," Expr<none> )* )? "]"
                 | "{" ( Field ( "," Field )* )? "}"
                 | "{" LowerName "|" Field ( "," Field )* "}"
                 | "." LowerName               /* accessor: see Adjacency rules */
Field          ::= LowerName "=" Expr<none>
QualifiedName  ::= LowerName
                 | UpperName ( "." UpperName )* ( "." LowerName )?   /* no spaces, see Adjacency rules */
BinaryOperator ::= "|>" | "<|" | "||" | "&&" | "==" | "/=" | "<" | ">" | "<=" | ">=" | "::" | "++"
                 | "+" | "-" | "*" | "//" | "^" | ">>" | "<<"
```

- `if` needs its `else`.
- `if`, `let` and a lambda extend as far to the right as they can: `if a then b else c |> f` is
  `if a then b else (c |> f)`. A `case` ends where its block ends, so an operator on a line left of
  its branches applies to the whole `case`.
- A `-` after an operand is either `BinaryOperator` or the start of a `NegativeArgument`; the
  characters around it decide (see [Adjacency rules](#adjacency-rules)).
- A tuple has two or three elements: `ELCI1002: Tuples have two or three elements`.
- `(op)` is the operator as a function of two arguments, for every operator in the table:
  `(+)`, `(::)`, `(|>)`. `(/)` is rejected, like `/`.
- `{ r | x = 1 }` updates the record `r`, which must be a plain lowercase name.
- `.field` is the function `\r -> r.field`.
- An argument can't start with `if`, `let`, `case`, `\` or `-`; put it in parentheses. A minus
  written as in `f -1` is the exception: see [Adjacency rules](#adjacency-rules).

```elm
module Expressions exposing (main)


type alias Point =
    { x : Int, y : Int }


main : ( Int, List Int, Bool )
main =
    let
        origin =
            { x = 0, y = 0 }

        moved =
            { origin | x = 3 }

        points =
            [ moved, Point 1 2 ]
    in
    ( List.foldl (+) 0 (List.map .x points)
    , List.map (\point -> point.y * -1) points
    , if moved.x > origin.x then True else False
    )
```

### Patterns

```text
Pattern      ::= PatternApp ( "::" Pattern )? ( "as" LowerName )?
PatternApp   ::= QualifiedCtor PatternAtom+ | PatternAtom   /* True and False take no arguments */
PatternAtom  ::= "_" | LowerName | QualifiedCtor
               | Int | "-" Int | String
               | "(" ")"
               | "(" Pattern ")"
               | "(" Pattern "," Pattern ( "," Pattern )? ")"
               | "[" ( Pattern ( "," Pattern )* )? "]"
               | "{" ( LowerName ( "," LowerName )* )? "}"
QualifiedCtor ::= UpperName ( "." UpperName )*
```

- `True` and `False` take no arguments: `True x ->` is `ELCI1002: Expected '->', found 'x'`.
- `::` groups to the right: `a :: b :: rest`.
- `as` names the pattern just before it. In `first :: rest as all`, `all` names `rest`, not the whole
  list (Elm names the whole list); write `(first :: rest) as all` for that.
- A tuple pattern has two or three elements: `ELCI1002: Tuple patterns have two or three elements`.
- `[ a, b ]` is the pattern `a :: b :: []`.
- Function and lambda parameters and `let` destructuring take a single `PatternAtom`, which must be
  irrefutable (see [Declarations](#declarations)).

```elm
module Patterns exposing (describe)


describe : List (Maybe Int) -> String
describe values =
    case values of
        [] ->
            "none"

        [ Just 0 ] ->
            "one zero"

        (Just -1) :: _ ->
            "starts at minus one"

        ((Just _) :: _) as all ->
            if List.length all > 2 then
                "many"

            else
                "some"

        Nothing :: _ ->
            "starts empty"
```

## Operators

From the tightest binding to the loosest:

| Precedence | Operators | Associativity |
| ---: | --- | --- |
| | `record.field` | |
| 10 | function application `f x` | left |
| 9 | `>>` | right |
| 9 | `<<` | left |
| | negation `-x`, a `-` where an expression starts | |
| 8 | `^` | right |
| 7 | `*`, `//` | left |
| 6 | `+`, `-` | left |
| 5 | `::`, `++` | right |
| 4 | `==`, `/=`, `<`, `>`, `<=`, `>=` | none |
| 3 | `&&` | right |
| 2 | `\|\|` | right |
| 0 | `\|>` | left |
| 0 | `<\|` | right |

- The precedences are those of Elm 0.19, and of `Parser.PRECEDENCE`. Negation has no number in the
  parser: it reads its operand at precedence 9, so it covers applications and `>>`/`<<` chains and
  stops before `^`. `-x ^ 2` is `(-x) ^ 2`, `-f x` is `-(f x)` and `2 * -3` is `2 * (-3)`.
- Comparisons do not chain: `a < b < c` is `ELCI1002: Comparison operators cannot be chained; use &&
  or explicit parentheses`.
- Operators of one precedence that associate in opposite directions do not mix:
  `f <| x |> g` is `ELCI1002: Mixing (<|) and (|>) needs parentheses: they associate in opposite
  directions, as in Elm`, and the same holds for `>>` and `<<`.
- `/` is `ELCI2001: Float division is unsupported; use // for integer quotient`, and `(/)` is
  `ELCI2001: Float division is unsupported; use (//) for integer quotient`. `%` does not exist: use
  `modBy` or `remainderBy`.

```elm
module Operators exposing (main)


main : List Int
main =
    [ 1 + 2 * 3 ^ 2
    , 2 ^ 3 ^ 2
    , (List.map ((*) 2) >> List.sum) [ 1, 2 ]
    , [ 1, 2 ] |> List.map negate |> List.sum
    , List.sum <| 0 :: [ 1 ] ++ [ 2 ]
    ]
```

## Adjacency rules

Some tokens mean different things depending on whether they *touch*: two tokens touch when no
character, space or comment, separates them.

**Field access and accessors.** A `.` that touches the token before it reads a field of that value.
A `.` that does not touch the token before it, followed by a lowercase name that touches the `.`, is
an accessor function.

| Source | Meaning |
| --- | --- |
| `r.field` | the field `field` of `r` |
| `r .field` | `r` applied to the accessor `.field` |
| `List.map .amount orders` | `.amount` is `\o -> o.amount` |
| `(f x).amount`, `"text".length` | a field of a parenthesized expression, or of a literal |
| `r{- note -}.field` | `r` applied to `.field`: the comment separates them |

**Qualified names.** In an expression, a qualified name is written without spaces and ends at its
first lowercase part. Every `.` in it touches the names on both sides.

| Source | Meaning |
| --- | --- |
| `List.map` | the value `map` of the module `List` |
| `Maybe.Just` | the constructor `Just` of the module `Maybe` |
| `Config.settings.fee` | the field `fee` of the value `Config.settings` |
| `List . map` | an error: `List` followed by a stray `.` |

In module names, imports, types and patterns, the parser accepts spaces around the `.`, but no
formatter writes them.

**Negative arguments.** A `-` after an operand is read by the characters around it, as in Elm 0.19:

- It negates the operand that follows when the character before it is whitespace (or `(`, `[`,
  `{`, `,`, or the start of the file) and the character after it is neither whitespace nor `-`. The
  negated operand is then an argument of the function before it.
- Otherwise it subtracts.
- A `-` where an expression starts always negates, with or without a space after it.

| Source | Meaning |
| --- | --- |
| `f -1` | `f (-1)` |
| `modBy 3 -7` | `modBy 3 (-7)` |
| `a - 1`, `a-1`, `a- 1` | subtraction |
| `a -b` | `a (-b)`: an application, as in Elm |
| `f -x.y` | `f (-(x.y))` |
| `(-1)`, `[ -1 ]`, `x = -1` | negation where an expression starts |
| `f{- c -}-1` | subtraction: the character before `-` is `}` |

```elm
module Negation exposing (main)


main : List Int
main =
    let
        x =
            4

        scale factor value =
            factor * value
    in
    [ scale -x 10, modBy 3 -7, x - 1, x-1, -x * 2, -(scale x 2), 2 * -3 ]
```

## Elm syntax that Elci does not support

These parts of Elm 0.19's syntax are rejected. The messages are the compiler's:

| Elm syntax | Elci's message |
| --- | --- |
| `port module Main exposing (..)` | `ELCI1002: Expected 'module', found 'port'` |
| `effect module ... where { ... }` | `ELCI1002: Expected 'module', found 'effect'` |
| `1.5`, `Float` | `ELCI1001: Float is unsupported; represent fractional amounts using an explicit integer scale`; `ELCI2001: Float is unsupported; use scaled Int` |
| `x / y` | `ELCI2001: Float division is unsupported; use // for integer quotient` |
| `'a'` | `ELCI1001: Unsupported character '''` |
| `0xFF`, `1e3` | read as `0 xFF` and `1 e3`: `ELCI2005: Unknown name 'xFF'` |
| `"\u{0041}"` | `ELCI1001: Unsupported string escape` |
| `"""multi-line"""` | read as `""` applied to `"multi-line"` and `""`: `` ELCI2002: This is `String`, not a function, so it cannot take an argument `` |
| `{ r \| x : Int }` | `ELCI1002: Extensible records are unsupported; use a closed record` |
| `( 1, 2, 3, 4 )` | `ELCI1002: Tuples have two or three elements` |
| `(,)` | `ELCI1002: Expected expression, found ','` |
| `exposing (Shape(Circle))` | `ELCI1002: Expected '..', found 'Circle'` |
| `exposing ((+))` | `ELCI1002: Expected name` |
| `( a, b ) = pair` at the top level | `ELCI1002: Top-level declarations require a name` |
| `(+++) : Int -> Int -> Int` | `ELCI1002: Top-level declarations require a name` |
| `` a `max` b ``, `a % b` | `` ELCI1001: Unsupported character '`' ``, `ELCI1001: Unsupported character '%'` |
| a tab in the indentation | `ELCI1001: Tabs are not permitted; use spaces for Elm layout` |

## Parser details that differ from Elm

The parser accepts some programs that Elm rejects, and in one case reads a program differently.
These are the details as implemented; formatted code does not rely on them.

- `as` in a cons pattern names only the tail: see [Patterns](#patterns).
- `-` where an expression starts negates even with a space after it: `- 1` is `-1`.
- The first token of an expression, and every token taken by keyword (`then`, `else`, `of`, `in`,
  `->`, `=`, `:`, `|`), may be in any column. So `main =` followed by `1` in column 1 is accepted,
  and a signature may continue on a line that starts with `->` in column 1.
- A `case` branch whose body starts in the branch column is accepted: the body's first token is not
  checked.
- A field access may have spaces after the dot: `r. field` is `r.field`.
- `let` may declare types (`type alias` and `type`). They belong to the module, not the `let`: a
  top-level signature can use them. A `let` with no declarations (`let in x`) is accepted.
- The module header and imports are not checked for layout, and the header may be indented.
- An empty exposing list, `exposing ()`, is accepted, and so is `(..)` after a lowercase name, as in
  `exposing (main(..))`. Neither exposes anything.
- `if`, `case` and `let` are not reserved: they can name a declaration or a parameter, which then
  can't be used. Reserved words can name pattern variables, as in `\of -> 1`.
- `_` is accepted as a type parameter (`type alias Box _ = Int`), a record field (`{ _ = 1 }`) and an
  `as` name.
- A name may start with `_`, as in `_count`. It can't be declared (`ELCI1002: Top-level declarations
  require a name`), and in a pattern it reads as a constructor (`ELCI2001: Unknown constructor
  _count in this pattern`).
- Digits are Unicode decimal digits: `١٢` is `12`.

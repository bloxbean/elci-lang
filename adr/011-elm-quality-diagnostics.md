# ADR-011: Elm-quality diagnostics

Status: **Accepted**.
Date: 26 September 2026

## Context

Elm's error messages are the part of Elm that developers remember. They show the code, say
precisely what is wrong in the reader's terms, and suggest the fix. Elci's diagnostics are
located and coded (`path:line:col: ELCIxxxx: message`). ADR-008 to ADR-010 improved many
messages, but Elci still fell short of Elm in five ways:

- **No code was shown.** The reader had to open the file at the reported position.
- **Type mismatches lacked context.** `Type mismatch: expected Int, found String` did not
  say which argument, which branch or which list element was wrong, or why Int was
  expected.
- **A non-exhaustive `case` named no missing pattern.** It said to add "the missing
  constructor" without naming it.
- **Unknown names got no suggestions.** A misspelt `List.lenght` or `doubel` said only that
  the name was unknown.
- **Tools had to parse text.** Editors and agents scraped the first line.

## Decision

### 1. The first line stays; the explanation goes below it

Every diagnostic still starts with `path:line:col: ELCIxxxx: message`. Agents, editors and
the test suite parse that line, and the codes are stable. Below it, the CLI adds what Elm
shows:

```
src/Main.elm:6:20: ELCI2002: The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int`

6|     String.fromInt "42"
                      ^^^^
```

- **An excerpt.** The line with its number, and a caret under the reported token: its whole
  qualified name, or a string literal with its quotes. At the end of the file, the caret
  sits just past the last code. The excerpt is omitted for bundled modules and synthetic
  positions.
- **Hints.** Zero or more `Hint:` lines with the likely fix.

### 2. Type mismatches say where and why

Types are written as source, in backticks, with one naming across the two, so a type
variable shared between them keeps its name. A type is named without its module
(`Result a Int`, `Order`), unless two types of one name meet in a message; then both stay
qualified (`Main.Value`, `Julc.Ledger.Value`).

- **Application.** `The Nth argument to f is X, but f needs Y`. `f` is named as written; an
  unnamed function is "this function". A piped value is the next argument of the function
  it is piped into: `The 2nd argument to List.map, the value piped in, is …`.
  - Too many arguments: `f takes N arguments, but it is given M`, with a hint about missing
    commas or parentheses.
  - A value applied as a function: `x is Int, not a function, so it cannot take an
    argument`.
- **`if`.** `The condition of this if is X, but it must be Bool`, and `The branches of this
  if have different types: the then branch is X, but the else branch is Y`.
- **`case`.** `The Nth branch of this case is Y, but the first branch is X` (or "the
  branches before it are"). A pattern of the wrong type: `This pattern matches X, but the
  value it is matched against is Y`. A constructor pattern with the wrong number of
  arguments: `The constructor Just takes 1 argument, but this pattern gives it 2` (ELCI2001,
  as for library constructors).
- **Lists.** `The Nth element of this list is Y, but the first element is X` (or "the
  elements before it are").
- **Operators.** `::` names the element and the list; `++`, `==`, `/=` and the comparisons
  name both sides; `&&` and `||` name the side that is not a `Bool`; `>>` and `<<` name
  what one side returns and the other takes. ADR-008's arithmetic messages are kept.
- **Annotations.**
  - `The annotation of f says it returns X, but its body is Y`, or `says it is X, but its
    definition is Y` for a value.
  - `The annotation of f gives it N arguments, but its definition has M parameters`.
  - An annotation more general than its definition: `The annotation of f says a can be any
    type, but its definition needs Int` ("any comparable type" for `comparable`), and the
    two-variable form, `says a and b can be different types, but its definition needs them
    to be the same`.
  - The annotations of a recursive group are all known before any body is checked, so a
    mistake is reported in the body that makes it.
- **Self-referential types.** `This needs a type that contains itself: a would have to be
  List a`, wherever the occurs check fails.
- **Fields.** `The field x is X, but it is used here as Y`.
- **Hints for common confusions**, at the whole types and at the first pair that differs
  inside them. At most two are shown, and only those that fit. `Just` and
  `Maybe.withDefault` are suggested only when the value fits the Maybe, and comparisons
  never suggest converting to a type that cannot be compared.
  - `Int` where a `String` is needed: `String.fromInt`.
  - `String` and `Bytes`: `Bytes.fromString`, `Bytes.toString`.
  - `Bytes` where a hash type is needed: wrap it with the constructor. A hash type where
    `Bytes` are needed: its `.hash` field. The field name is read from the ledger type.
  - A `Maybe` where a plain value is needed: a `case` or `Maybe.withDefault`. A plain value
    where a `Maybe` is needed: `Just`.
  - A `Result` where a plain value is needed: a `case` or `Result.withDefault`.
  - `Value` and `Int`: `Value.lovelaceOf`, `Value.lovelace`.
  - `Data` and a typed value: `Data.decode`, `Data.encode`.
  - A function where a value is needed: a missing argument.
- Pattern mismatches carry no hint: a pattern has no conversion to suggest.

### 3. Missing patterns are listed

A non-exhaustive `case` lists up to three patterns it does not cover, written as source:
`Nothing`, `Just (Err _)`, `( True, False )`, `[]`, `[ _ ]`, `_ :: _ :: _`. A literal
pattern's type is open, so what it leaves out is `_`. It adds `and N more` when there are
more, or `and more` beyond fifty. Constructors are written by their own names, as a
module that exposes them writes them; library constructors too (`ScriptCredential _`).

The list is complete: adding a branch for each pattern makes the case exhaustive. One
usefulness algorithm decides exhaustiveness and produces the list. It is Maranget's, as in
Elm, extended to find every missing vector:

- A row of wildcards covers everything.
- Otherwise what the column's default rows leave uncovered is missing for every constructor
  the column never names, or for "any other value" when the type has no fixed constructors.
  The literals a column names are covered by that "any other value".
- Each constructor the column names is split on as well, since a named constructor can
  itself be partly covered.

The search is depth first and stops at fifty, so it stays quick even for a case whose
missing patterns are exponentially many, such as pairs of `Bool` columns that each exclude
the equal values.

The message is `This case does not cover every possibility; it is missing …`, with the hint
`add a branch for each missing pattern`.

### 4. Suggestions for unknown names

Unknown names are matched against what the module can write, then against qualified names.
The kinds are values and constructors (locals, own and exposed names, the built-in `True`,
`False`, `Just` and `Nothing`), types, constructor patterns, record fields, qualified names,
and exposing lists. Suggestions are close names:

- the same name ignoring case;
- an edit distance of 1 for names of three to five characters;
- an edit distance of 2 for longer names, where swapping two neighbours counts as one edit;
- nothing for names of one or two characters, except a match ignoring case.

Up to three are shown: `Hint: did you mean List.length?`.

- A name spelt right but not exposed gets `Hint: write Helper.double, or expose double in
  its import`.
- A qualified name gives one of three messages:
  - `Module Helper does not expose 'doubel'`, when the module is imported;
  - `Module Dict is not imported; add import Dict`, when it exists but is not imported;
  - `No module Lsit is imported, and none has that name`, with the hint `did you mean
    List.length?`.
- Import lines are covered too: a misspelt module or ledger type in an `import` gets a
  suggestion.

### 5. A JSON report for tools

`--report=json` on `check`, `build`, `eval` and `test` prints a compile failure as one JSON
object on stderr, as Elm's `--report=json` does. The fields are:

- `code`, `path`, `line`, `column` and `message`;
- `hints`, an array;
- `excerpt`, the text shown under the first line, or null;
- `context`, where in the project the failure arose, or null.

A failure without a source location keeps the shape, with null location fields, and its
code when the message starts with one. A failure in a named validator adds `context`, the
`elci.toml` line of its `[validators.<name>]` group; elsewhere `context` is null. Test
results and scenarios keep their text output. Other commands reject the option with
ELCI4001.

### 6. Found while reviewing the messages

- **Elm's accessor functions and qualified field access.** `.amount` is the function
  `\r -> r.amount`, as in Elm, and `Config.settings.amount` reads a field of a qualified
  value. A qualified name is written without spaces and ends at its first lowercase
  segment. Before, `List.map .amount` was read as the name `List.map.amount`.
- **A record pattern on a library record**, such as `{ fee }` against a `TxInfo`, crashed
  the compiler. It is now ELCI2001, which says to read the field instead. An empty record
  pattern still matches anything.
- **A module names its own declarations without a qualifier.** `Main.other` inside `Main`
  says so, instead of advising `import Main`.
- **A local annotation's type arity is checked**, as a top-level one is.
- **A named validator's diagnostic comes first.** The line naming the
  `[validators.<name>]` group follows the diagnostic, so its first line is the diagnostic's,
  as for every other failure.
- **A source file that is not UTF-8** is named in an ELCI4001 message, instead of
  `Input length = 1`.

### 7. What does not change

- **The codes and their meanings.**
- **The first line's format.**
- **The locations.** Each diagnostic stays at the token it reported before, with two
  exceptions. A pattern whose type differs from the value it matches is reported at the
  pattern, not at a sub-pattern inside it. A mistake in an annotated recursive group is
  reported in the body that makes it.

## Invariants and evidence required

- **Tests for each new message shape** (`ElmQualityDiagnosticsTest`):
  - argument position, piped arguments, too many arguments, non-functions;
  - `if`, `case` and list mismatches, operators, annotations, fields and patterns;
  - the listed missing patterns for `Maybe`, `Result`, `Bool`, lists, tuples, literals and
    nested custom types, with `and N more`, and complete when a named constructor is only
    partly covered;
  - suggestions for values, locals, constructors, qualified names, types, patterns, fields
    and exposing lists;
  - each hint;
  - the CLI excerpt, including string literals and the end of the file;
  - the JSON report.
- **The diagnostics reference shows real CLI output.** Every example in it was produced by
  the CLI.
- **Exhaustiveness agrees with the previous checker.** A differential run over generated
  case matrices accepts and rejects exactly the same programs, and adding every listed
  pattern always makes a case exhaustive.
- **No compiled output changes.** Example scripts are byte-identical.

## Alternatives rejected

- **Elm's exact layout**, with a `-- TYPE MISMATCH ---` banner and no `path:line:col` line.
  It would break every agent and editor integration that parses the first line, for a
  cosmetic gain. The explanation beneath the line gives the same help.
- **Rich output only on a TTY.** Agents read piped output and benefit from the excerpt and
  hints as much as people do.
- **Collecting several errors per run.** Elm reports every error; Elci stops at the first.
  Reporting more needs error recovery in the parser and in inference, which is a larger
  change. The first error is always shown with full context.

# ADR-012: Every error in a run, terminal presentation and `elci explain`

Status: **Accepted**.
Date: 28 September 2026
Follows ADR-011. Issues #18, #19 and #20.

## Context

ADR-011 gave each diagnostic an excerpt, hints, contextual messages and a JSON form. Three
gaps remained against Elm:

- **The compiler stops at the first error.** A module with three mistakes takes three runs.
  The language server (#17) needs every error of a file.
- **Plain presentation.** Output has no colour, the caret marks one token, and a long type
  mismatch puts both types on one line.
- **No long-form help.** A code such as `ELCI2002` has no explanation beyond its message and
  the reference page.

## Decision

### 1. Every error in a run (#18)

Compilation keeps its phases: lexing and parsing, name resolution, and type checking. Each
phase now reports all its errors. A phase runs only when the phases before it found none, as
Elm does, so one mistake is not reported again as a different error in a later phase.

- **Lexing.** An unsupported character, a float literal, or a bad string escape or string end
  is recorded, and lexing continues after it. A module with lexing errors is not parsed.
  - A string that runs past its line ends lexing: what follows is the string's text, and
    lexing it as code would report errors that aren't there.
  - An unsupported character is reported once per line, so prose left outside a comment
    gives one error per line, not one per character.
- **Parsing.** A syntax error inside a top-level declaration is recorded, and parsing resumes
  at the next token in column 1 that starts another declaration. A declaration's annotation and
  definition are skipped together. Errors in the module header or imports end that module's
  parse. Every module is parsed before any is resolved.
- **Name resolution.** Each import and each top-level declaration is resolved on its own. An
  error is recorded, and resolution continues. A module whose imports fail does not resolve its
  declarations, since its names would be missing.
- **Type checking.** Each top-level declaration group is checked on its own.
  - A group that fails records its error. Its names are then bound to their annotated types,
    which every top-level declaration has, so callers check against the annotation and nothing
    cascades.
  - Requirements a failed group recorded, such as equality or comparable, are discarded.
  - Requirements checked after inference are checked one by one.
  - Invalid annotations are reported together, and their declarations are treated as failed.
    Annotations are checked even when a type definition failed; checking stops after them.
    A definition that uses a failed type alias is not checked, since its error would be the
    alias's again.
  - A failed declaration without a usable annotation is bound to any type. A group that uses
    it may fail only because of that, so its errors are dropped, except a missing signature.
    Its requirements, such as equality, are dropped too.
  - Constructor conflicts and cycles between values are recorded with the rest, and stop
    checking.
- **Order and limit.** Diagnostics are reported in source order: by module, then line and
  column. At most 20 are shown per run, followed by `and N more`.
- **Once each.** One mistake can fail several checks alike, such as each comparison inside
  `List.sort`. A diagnostic with the same position, code and message is reported once.
- **One exception carries them all.** The first diagnostic is thrown as before, and carries
  the others. Every caller that catches a `Diagnostic` sees the same first error as it would
  have. The CLI prints them all, with a blank line between them, and ends with a summary line
  such as `3 errors` when there is more than one.
- **JSON.** `--report=json` prints one JSON object per diagnostic, one per line (JSON Lines).
  A single error is still a single line, so existing readers keep working.
- **What stays one at a time.** Code generation (`ELCI3xxx`), configuration (`ELCI4xxx`) and
  errors inside one declaration: a declaration reports its first error.

### 2. Terminal presentation (#19)

- **Colour** when stderr is a terminal and `NO_COLOR` is not set. Standard error is a
  terminal when it is a character device, read from `/dev/fd/2`; where that can't be read,
  the console decides. Colour is used for:
  - the code, and the path of the first line;
  - the caret line;
  - `Hint:`.

  `--color=auto|always|never` overrides the default. Piped output, tests and `--report=json`
  stay plain.
- **The whole expression is underlined.** A diagnostic may carry the span of the expression it
  reports, such as an argument, a branch body or a list element. The carets cover that span.
  - A span over several lines shows each line, up to 5, then `…`.
  - The first line's position is unchanged: it is the span's start, which is the token
    reported before.
  - Only an expression reported at its first token, or at the parenthesis before it, is
    underlined whole. An operation is reported at its operator, and a call without
    parentheses at its last argument. An underline from there would mark part of the
    expression, so the caret marks that token alone.
- **Long types are laid out.** When a mismatch's two types don't fit on one line, the message
  names them, and the excerpt is followed by both types on their own lines, with the first
  difference marked.

The first line and the JSON fields keep their exact format.

### 3. `elci explain` (#20)

- `elci explain ELCI2002` prints a long-form entry for the code. `2002` and `elci2002` name
  it too. An entry has:
  - what the code means and its common causes;
  - a failing example with its exact output;
  - the fix, and the pages to read.

  `elci explain` with no code lists every code with its title.
- **Bundled with the compiler.** The entries are Markdown files in the compiler's resources,
  `elci/explain/ELCI2002.md`, printed with headings and code fences removed and code
  indented. Tests check that:
  - the codes with entries are exactly the codes in the compiler's source;
  - every example gives exactly the output its entry shows;
  - every fix compiles.

  ELCI3002, ELCI4002 and ELCI4003 have no example. The first needs a program too large to
  show, and the others a fault in the compiler or in Julc.
- **Pointed to from errors.** A failed run's text report ends with one line naming the codes
  it showed, such as ``For more on ELCI2002, run `elci explain ELCI2002`.`` A hint on each
  error would repeat itself and change every diagnostic; one line at the end, as `rustc` does,
  does neither. The JSON report is unchanged.
- **Configuration errors have a code.** An error in `elci.toml`, in a project's files or on
  the command line that had no code is now `ELCI4001`, with the code first. A problem in one
  validator's table is `ELCI4001: <elci.toml>:<line>: [validators.<name>]: <message>`. Only
  three errors from reading a Java library remain without a code.

## Invariants and evidence required

- **Differential run.** On the corpus of failing programs from ADR-011's fuzzing and the test
  suite, the error the previous compiler reported is among those now reported. The first one
  now reported is the first in source order.
- **No cascades.** Tests with several independent errors in a module, across modules, and mixed
  syntax, name and type errors report each one exactly once.
- **Nothing else changes.** Accepted programs and compiled bytes are unchanged.
- **Tests** for colour on and off, `NO_COLOR`, multi-line spans and laid-out types. Plain
  output is unchanged when colour is off.
- **Every code has an entry,** and every entry's examples compile as stated.

## Alternatives rejected

- **Recovery inside expressions.** Reporting several errors within one declaration needs error
  types that unify with everything. Checks such as arithmetic and equality would then have to
  learn to ignore them. One error per declaration is how Elm usually reports, and it keeps the
  checker simple.
- **A JSON array for `--report=json`.** It would break readers of the single-object form
  documented by ADR-011. JSON Lines keeps a single error byte-identical.

# Elci diagnostic catalogue

Elci diagnostics have stable category codes and a source token location. The CLI
renders the first line as `path:line:column: code: message`. A blank line follows,
then the source line with carets under the reported token or expression, then zero
or more `Hint:` lines with the likely fix ([ADR-011](../adr/011-elm-quality-diagnostics.md)).
A failed run ends with the `elci explain` command for its codes
([ADR-012](../adr/012-diagnostics-recovery-and-presentation.md)):

```text
src/Main.elm:6:20: ELCI2002: The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int`

6|     String.fromInt "42"
                      ^^^^

For more on ELCI2002, run `elci explain ELCI2002`.
```

The first line's format is stable; tools parse it.

## Several errors

A run reports every error of a phase: lexing and parsing, name resolution, then type
checking. A phase runs only when the earlier ones found none. Each top-level
declaration reports its first error; a declaration that fails keeps its annotated
type, so its callers don't report errors of their own. A mistake that fails several
checks alike is reported once. Code generation (`ELCI3xxx`) and configuration
(`ELCI4xxx`) errors stop the run at the first.

Diagnostics are printed in source order (by module, then line and column), with a
blank line between them. At most 20 are shown, then `... and N more`. When there is
more than one, a count such as `3 errors` follows. The last line names the codes
shown, such as ``For more on ELCI2002 and ELCI2005, run `elci explain <code>`.``

## Presentation

- **Colour.** On a terminal, the path and code, the carets and `Hint:` are coloured,
  unless `NO_COLOR` is set. `--color=auto|always|never` overrides this. Without
  colour the text is the same, character for character.
- **Underlines.** A mismatch in an argument, a list element or a `case` branch body
  underlines the whole expression, and a field access underlines `.field`. An
  expression over several lines is underlined on each, up to five lines.
- **Long types.** When the two types of a mismatch are 60 characters or longer
  together, `It is:` and `It needs:` lines follow the excerpt, with the first
  difference marked.

## `elci explain`

`elci explain ELCI2002` (or `elci2002`, or `2002`) prints the entry for a code: what
it means, its common causes, a failing example with its output, the fix and where to
read more. `elci explain` alone lists every code. The entries are Markdown resources
under `src/main/resources/elci/explain/`. Tests check that the codes with entries
are exactly the codes in the compiler's source, that each example gives the output
its entry shows, and that each fix compiles.

## Codes

| Code | Meaning |
| --- | --- |
| `ELCI1001` | Invalid source character or literal |
| `ELCI1002` | Malformed or unsupported syntax |
| `ELCI2001` | Invalid pattern or expression form, including a constructor pattern with the wrong number of arguments and a record pattern on a library record |
| `ELCI2002` | Type mismatch, or a type that would have to contain itself |
| `ELCI2003` | Unsupported equality, ordering or append type; a constrained type variable (`comparable`, `appendable`) missing or misnamed, or `number`; a `Dict` decoded from Data whose keys are not comparable |
| `ELCI2004` | Missing required top-level signature |
| `ELCI2005` | Unknown value name |
| `ELCI2006` | Duplicate binder or unreachable pattern |
| `ELCI2007` | Non-exhaustive case expression |
| `ELCI2008` | Eager recursive value cycle |
| `ELCI2009` | Non-concrete handler or entrypoint |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor, or a built-in constructor or type name (`Just`, `Nothing`, `True`, `False`; `Int`, `Maybe`, `List` and the other built-in types) |
| `ELCI2012` | Invalid type, alias, arity or alias cycle; `Tuple` or `Unit` written as a type name |
| `ELCI2013` | Unsupported serialized boundary type (including a `Value`, which has no strict boundary codec yet), or a value without a Data encoding (a function or an `Array`) stored in data |
| `ELCI2014` | Validator handler signature does not match its configured purpose, parameters or datum profile |
| `ELCI2101` | Unknown or missing module or imported export; a source module importing the test library |
| `ELCI2102` | Ambiguous name: two imports, possibly a default import, expose different things under it |
| `ELCI2103` | Entrypoint or handler is not exposed by its module |
| `ELCI2104` | Source module path, ownership or cycle error; reserved core module name or alias; qualified reference to an unexposed or unimported name, a name a core module lacks or an Elm function Elci does not provide |
| `ELCI2105` | Unsupported Java export signature, with provider reason; a Java export whose result can hold the ledger `Value` (except Julc's `ContextsLib` and `OutputLib`), which Java libraries do not keep canonical |
| `ELCI3001` | PIR lowering cannot represent the inferred program, such as `Julc.Data.decode` or a generic Java export at a type nothing determines |
| `ELCI3002` | Specialization or polymorphic-recursion limit |
| `ELCI3003` | Generic library type argument violates the export's constraint (for example `DATA_ENCODABLE`), or contains a `Dict` or a `Value`, which a Java library could build without the key-order or canonical-form check; a `Julc.Data` conversion of a type without a Data encoding, such as a function |
| `ELCI4001` | Invalid project configuration or CLI request (`elci.toml`, `elci init`, options, `elci explain`), including a `[java-modules]` key that names a core module, an `elci apply` parameter that doesn't fit its type (such as a `Dict` whose keys are not strictly ascending) and a source file that is not UTF-8 |
| `ELCI4002` | Julc backend rejected generated PIR; reported at the Elci declaration it names, with the `JULC00xx` code |
| `ELCI4003` | The Julc backend lacks a contract revision or capability the program needs |

A diagnostic is rendered at the file of the module its token came from, including
modules imported by the entrypoint or handlers. A failure inside a bundled module,
such as the core `List`, is reported at the use in your code when there is one.
Otherwise it is reported as `<bundled>/List.elm`. Named-validator failures are
followed by a line naming the `elci.toml` line and `[validators.<name>]` table.

Julc's catalogued `JULC00xx` backend codes are kept in the message.

- **Early capability checks.** Before type checking, Elci checks that the backend
  implements contract revision 2 and every capability the validator's roles
  need. Before each library request is materialized, it checks the request kind.
  A missing capability is `ELCI4003`, reported at the configuration or the use.
- **Located rejections.** If Julc's verifier rejects generated PIR, the rejection
  names an Elci definition (`$elci$<declaration>$<n>`) or a handler symbol. Elci
  maps it back to that declaration as `ELCI4002`.

The code identifies the failure class; the message names the construct and types
involved in the reader's terms. A type mismatch says which argument, `if` or `case`
branch, list element, operator side, annotation or field differs, with types written
as in source. A non-exhaustive `case` lists the patterns it misses, up to three and
then how many more. Unknown names, types, constructors, fields, qualified names and
imports get "did you mean" hints. Parser rejections share `ELCI1002` while their
messages distinguish deferred features such as extensible records from malformed
input.

## JSON report

`--report=json` on `check`, `build`, `eval` and `test` prints each failure on
standard error as one JSON object on its own line (JSON Lines), with these fields.
A single error is a single line. There is no count line and no `elci explain` line:

| Field | Contents |
| --- | --- |
| `code` | The `ELCI` code, or null |
| `path`, `line`, `column` | The location, or null for a failure without one |
| `message` | The message, without the path and code |
| `hints` | An array of hint strings, possibly empty |
| `excerpt` | The source lines and their caret lines, or null |
| `context` | The `elci.toml` line and `[validators.<name>]` table of a named validator, or null |

```text
{"code":"ELCI2005","path":"src/Main.elm","line":6,"column":5,"message":"Unknown name 'lenght'","hints":["did you mean `List.length`?"],"excerpt":"6|     lenght [ 1, 2 ]\n       ^^^^^^","context":null}
```

Results, test output and a failed `eval` run stay text on standard output. Other
commands reject the option with `ELCI4001`.

Configuration and command-line errors, including those of `elci.toml` and
`elci init`, are `ELCI4001`. A few still have no code: a missing `elci.toml`,
library JAR, source directory, entrypoint module or handler function, and a Java
library class that is missing or that Julc cannot load.

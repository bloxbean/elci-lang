# Elci diagnostic catalogue

Elci diagnostics have stable category codes and a source token location. The CLI
renders the first line as `path:line:column: code: message`. A blank line follows,
then the source line with a caret under the reported token, then zero or more
`Hint:` lines with the likely fix ([ADR-011](../adr/011-elm-quality-diagnostics.md)):

```text
src/Main.elm:6:20: ELCI2002: The 1st argument to `String.fromInt` is `String`, but `String.fromInt` needs `Int`

6|     String.fromInt "42"
                      ^^^^
```

The first line's format is stable; tools parse it. The compiler stops at the first
error.

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
| `ELCI2103` | Entrypoint is not exposed |
| `ELCI2104` | Source module path, ownership or cycle error; reserved core module name or alias; qualified reference to an unexposed or unimported name, a name a core module lacks or an Elm function Elci does not provide |
| `ELCI2105` | Unsupported Java export signature, with provider reason; a Java export whose result can hold the ledger `Value` (except Julc's `ContextsLib` and `OutputLib`), which Java libraries do not keep canonical |
| `ELCI3001` | PIR lowering cannot represent the inferred program, such as `Julc.Data.decode` or a generic Java export at a type nothing determines |
| `ELCI3002` | Specialization or polymorphic-recursion limit |
| `ELCI3003` | Generic library type argument violates the export's constraint (for example `DATA_ENCODABLE`), or contains a `Dict` or a `Value`, which a Java library could build without the key-order or canonical-form check |
| `ELCI4001` | Invalid project configuration or CLI request, including a `[java-modules]` key that names a core module, an `elci apply` parameter that doesn't fit its type (such as a `Dict` whose keys are not strictly ascending) and a source file that is not UTF-8 |
| `ELCI4002` | Julc backend rejected generated PIR; reported at the Elci declaration it names, with the `JULC00xx` code |
| `ELCI4003` | The Julc backend lacks a contract revision or capability the program needs |

A diagnostic is rendered at the file of the module its token came from, including
modules imported by the entrypoint or handlers. A failure inside a bundled module,
such as the core `List`, is reported at the use in your code when there is one.
Otherwise it is reported as `<bundled>/List.elm`. Named-validator failures are
prefixed with the `elci.toml` line and `[validators.<name>]` table, followed by the
source diagnostic.

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

`--report=json` on `check`, `build`, `eval` and `test` prints a failure on standard
error as one JSON object on one line, with these fields:

| Field | Contents |
| --- | --- |
| `code` | The `ELCI` code, or null |
| `path`, `line`, `column` | The location, or null for a failure without one |
| `message` | The message, without the path and code |
| `hints` | An array of hint strings, possibly empty |
| `excerpt` | The source and caret lines, or null |
| `context` | The `elci.toml` line and `[validators.<name>]` table of a named validator, or null |

```text
{"code":"ELCI2005","path":"src/Main.elm","line":6,"column":5,"message":"Unknown name 'lenght'","hints":["did you mean `List.length`?"],"excerpt":"6|     lenght [ 1, 2 ]\n       ^^^^^^","context":null}
```

Results, test output and a failed `eval` run stay text on standard output. Other
commands reject the option with `ELCI4001`.

# Elci diagnostic catalogue

Elci diagnostics have stable category codes and a source token location. The CLI
renders them as `path:line:column: code: message`.

| Code | Meaning |
| --- | --- |
| `ELCI1001` | Invalid source character or literal |
| `ELCI1002` | Malformed or unsupported syntax |
| `ELCI2001` | Invalid pattern or expression form |
| `ELCI2002` | Type mismatch or infinite type |
| `ELCI2003` | Unsupported equality or append type |
| `ELCI2004` | Missing required top-level signature |
| `ELCI2005` | Unknown value name |
| `ELCI2006` | Duplicate binder or unreachable pattern |
| `ELCI2007` | Non-exhaustive case expression |
| `ELCI2008` | Eager recursive value cycle |
| `ELCI2009` | Non-concrete handler or entrypoint |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor |
| `ELCI2012` | Invalid type, alias, arity or alias cycle |
| `ELCI2013` | Unsupported serialized boundary type, or a value without a Data encoding (a function or an `Array`) stored in data |
| `ELCI2014` | Validator handler signature does not match its configured purpose, parameters or datum profile |
| `ELCI2101` | Unknown or missing module or imported export; a source module importing the test library |
| `ELCI2102` | Ambiguous or conflicting imported name |
| `ELCI2103` | Entrypoint is not exposed |
| `ELCI2104` | Source module path, ownership or cycle error; qualified reference to an unexposed or unimported name |
| `ELCI2105` | Unsupported Java export signature, with provider reason |
| `ELCI3001` | PIR lowering cannot represent the inferred program, such as a generic function used at a type nothing determines |
| `ELCI3002` | Specialization or polymorphic-recursion limit |
| `ELCI3003` | Generic library type argument violates the export's constraint (for example `DATA_ENCODABLE`) |
| `ELCI4001` | Invalid project configuration or CLI request |
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

The code identifies the failure class; the message supplies the construct and
type involved. Parser rejections share `ELCI1002` while their messages distinguish
deferred features such as extensible records from malformed input.

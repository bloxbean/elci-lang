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
| `ELCI2009` | Non-concrete deployed entrypoint |
| `ELCI2010` | Unknown or unconstrained record field |
| `ELCI2011` | Duplicate type or constructor |
| `ELCI2012` | Invalid type, alias, arity or alias cycle |
| `ELCI2013` | Unsupported serialized boundary type |
| `ELCI2014` | Validator handler signature does not match its configured purpose, parameters or datum profile |
| `ELCI2101` | Unknown module or imported export |
| `ELCI2102` | Ambiguous or conflicting imported name |
| `ELCI2103` | Entrypoint is not exposed |
| `ELCI2104` | Source module path, ownership or cycle error; qualified reference to an unexposed or unimported name |
| `ELCI2105` | Unsupported Java export signature, with provider reason |
| `ELCI3001` | PIR lowering cannot represent the inferred program |
| `ELCI3002` | Specialization or polymorphic-recursion limit |
| `ELCI3003` | Generic library type argument violates the export's constraint (for example `DATA_ENCODABLE`) |
| `ELCI4001` | Invalid project configuration or CLI request |

Named-validator failures are prefixed with the `elci.toml` line and
`[validators.<name>]` table, followed by the source diagnostic. Julc backend
rejections keep their catalogued `JULC00xx` code in the message.

The code identifies the failure class; the message supplies the construct and
type involved. Parser rejections share `ELCI1002` while their messages distinguish
deferred features such as extensible records from malformed input.

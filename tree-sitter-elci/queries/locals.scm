; Scopes, definitions and references for Elci, so that a local name highlights like the
; parameter or pattern that binds it.

[
  (value_declaration)
  (let_in_expr)
  (case_of_branch)
  (anonymous_function_expr)
] @local.scope

(function_declaration_left
  (lower_pattern
    (lower_case_identifier) @local.definition.parameter))

(function_declaration_left
  (_
    (lower_pattern
      (lower_case_identifier) @local.definition.parameter)))

(anonymous_function_expr
  param: (lower_pattern
    (lower_case_identifier) @local.definition.parameter))

(lower_pattern
  (lower_case_identifier) @local.definition.var)

(value_qid
  !module
  name: (lower_case_identifier) @local.reference)

; Highlights for Elci. When several patterns capture one node, the last one wins (as in
; tree-sitter-highlight and Neovim), so general patterns come first.

; Variables

(value_qid
  name: (lower_case_identifier) @variable)

(lower_pattern) @variable

(anything_pattern) @variable

(record_base_identifier) @variable

(function_declaration_left
  parameter: (lower_pattern) @variable.parameter)

(anonymous_function_expr
  param: (lower_pattern) @variable.parameter)

; Fields

(field
  name: (lower_case_identifier) @property)

(field_type
  name: (lower_case_identifier) @property)

(field_access_expr
  name: (lower_case_identifier) @property)

(field_accessor_function_expr
  name: (lower_case_identifier) @property)

(record_pattern
  field: (lower_pattern) @property)

; Functions

(function_declaration_left
  name: (lower_case_identifier) @function)

(type_annotation
  name: (lower_case_identifier) @function)

(function_call_expr
  target: (value_expr
    name: (value_qid
      name: (lower_case_identifier) @function)))

(exposed_value) @function

; Types and constructors

(type_variable) @type

(type_ref
  name: (upper_case_qid
    name: (upper_case_identifier) @type))

(exposed_type
  (upper_case_identifier) @type)

(type_declaration
  name: (upper_case_identifier) @type)

(type_alias_declaration
  name: (upper_case_identifier) @type)

(union_variant
  name: (upper_case_identifier) @constructor)

(union_pattern
  constructor: (upper_case_qid
    name: (upper_case_identifier) @constructor))

(value_expr
  name: (upper_case_qid
    name: (upper_case_identifier) @constructor))

; Modules

(module_name) @module

(module_prefix) @module

; Literals

(string_constant_expr) @string

(string_escape) @string.escape

(number_constant_expr) @number

(boolean) @constant.builtin

; Keywords

[
  "module"
  "exposing"
  "import"
  "as"
  "type"
  "alias"
  "let"
  "in"
  "if"
  "then"
  "else"
  "case"
  "of"
  "\\"
] @keyword

; Operators and punctuation

[
  (operator)
  (arrow)
  "="
  "|"
] @operator

[
  ":"
  ","
  "."
] @punctuation.delimiter

(double_dot) @punctuation.special

[
  "("
  ")"
  "["
  "]"
  "{"
  "}"
] @punctuation.bracket

; Comments

[
  (line_comment)
  (block_comment)
] @comment

((block_comment) @comment.documentation
  (#match? @comment.documentation "^\\{-\\|"))

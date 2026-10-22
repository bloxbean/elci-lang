; Indentation for Elci, with nvim-treesitter's capture names.

[
  (value_declaration)
  (type_declaration)
  (type_alias_declaration)
  (let_in_expr)
  (case_of_expr)
  (case_of_branch)
  (if_else_expr)
  (anonymous_function_expr)
  (record_expr)
  (record_type)
  (list_expr)
  (tuple_expr)
  (parenthesized_expr)
  (exposing_list)
] @indent.begin

[
  "in"
  "then"
  "else"
] @indent.branch

[
  ")"
  "]"
  "}"
] @indent.branch @indent.end

[
  (line_comment)
  (block_comment)
] @indent.auto

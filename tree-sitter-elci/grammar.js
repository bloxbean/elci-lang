/**
 * @file Elci grammar for tree-sitter
 *
 * Follows docs/GRAMMAR.md, which describes what the Elci compiler's parser accepts
 * (src/main/java/org/elci/syntax/Parser.java). The external scanner (src/scanner.c) turns
 * Elci's column-based layout into the zero-width tokens _layout_sep, _layout_close,
 * _let_open and _case_open, and recognizes the negative argument of `f -1`.
 */

/// <reference types="tree-sitter-cli/dsl" />
// @ts-check

// Elci's precedences (Parser.PRECEDENCE) times ten, so that negation fits between ^ and >>.
const PREC = {
  pipe: 10,
  or: 20,
  and: 30,
  compare: 40,
  append: 50,
  add: 60,
  multiply: 70,
  power: 80,
  negate: 85,
  compose: 90,
  call: 100,
  field: 110,
};

// [operator, precedence, associativity]. Comparisons are non-associative in Elci (chaining is an
// error); the tree-sitter grammar accepts a chain and groups it to the left.
const OPERATORS = [
  ['|>', 'pipe', 'left'],
  ['<|', 'pipe', 'right'],
  ['||', 'or', 'right'],
  ['&&', 'and', 'right'],
  ['==', 'compare', 'left'],
  ['/=', 'compare', 'left'],
  ['<', 'compare', 'left'],
  ['>', 'compare', 'left'],
  ['<=', 'compare', 'left'],
  ['>=', 'compare', 'left'],
  ['::', 'append', 'right'],
  ['++', 'append', 'right'],
  ['+', 'add', 'left'],
  ['-', 'add', 'left'],
  ['*', 'multiply', 'left'],
  ['//', 'multiply', 'left'],
  ['^', 'power', 'right'],
  ['>>', 'compose', 'right'],
  ['<<', 'compose', 'left'],
];

const NAME_REST = '[\\p{L}\\p{Nd}_]*';
const LOWER = new RegExp('\\p{Ll}' + NAME_REST, 'u');
const UPPER = new RegExp('\\p{Lu}' + NAME_REST, 'u');

/**
 * @param {RuleOrLiteral} rule
 * @returns {SeqRule}
 */
function commaSep1(rule) {
  return seq(rule, repeat(seq(',', rule)));
}

module.exports = grammar({
  name: 'elci',

  word: $ => $.lower_case_identifier,

  extras: $ => [/\s/, $.line_comment, $.block_comment],

  externals: $ => [
    $._layout_sep,
    $._layout_close,
    $._let_open,
    $._case_open,
    $._minus_arg,
    $._block_comment_content,
    $._error_sentinel,
  ],

  rules: {
    source_file: $ =>
      seq(
        field('moduleDeclaration', $.module_declaration),
        repeat(field('import', $.import_clause)),
        optional(seq($._top_declaration, repeat(seq($._layout_sep, $._top_declaration)))),
      ),

    // Modules and imports

    module_declaration: $ =>
      seq('module', field('name', $.module_name), field('exposing', $.exposing_list)),

    import_clause: $ =>
      seq(
        'import',
        field('moduleName', $.module_name),
        optional(field('asClause', $.as_clause)),
        optional(field('exposing', $.exposing_list)),
      ),

    as_clause: $ => seq('as', field('name', alias($.upper_case_identifier, $.module_name))),

    exposing_list: $ =>
      seq(
        'exposing',
        '(',
        optional(choice(field('doubleDot', $.double_dot), commaSep1($._exposed_item))),
        ')',
      ),

    double_dot: _ => '..',

    _exposed_item: $ => choice($.exposed_value, $.exposed_type),

    exposed_value: $ => $.lower_case_identifier,

    exposed_type: $ =>
      seq($.upper_case_identifier, optional(field('constructors', $.exposed_union_constructors))),

    exposed_union_constructors: $ => seq('(', $.double_dot, ')'),

    // Declarations

    _top_declaration: $ => choice($.value_declaration, $.type_alias_declaration, $.type_declaration),

    _let_declaration: $ =>
      choice(
        $.value_declaration,
        $.destructuring_declaration,
        $.type_alias_declaration,
        $.type_declaration,
      ),

    value_declaration: $ =>
      seq(
        optional(seq(field('typeAnnotation', $.type_annotation), $._layout_sep)),
        field('functionDeclarationLeft', $.function_declaration_left),
        '=',
        field('body', $._expression),
      ),

    type_annotation: $ =>
      seq(field('name', $.lower_case_identifier), ':', field('typeExpression', $.type_expression)),

    function_declaration_left: $ =>
      seq(field('name', $.lower_case_identifier), repeat(field('parameter', $._pattern_atom))),

    destructuring_declaration: $ =>
      seq(
        field('pattern', choice(
          $.anything_pattern,
          $.unit_pattern,
          $.tuple_pattern,
          $.record_pattern,
          $.parenthesized_pattern,
        )),
        '=',
        field('body', $._expression),
      ),

    type_alias_declaration: $ =>
      seq(
        'type',
        'alias',
        field('name', $.upper_case_identifier),
        repeat(field('typeVariable', $.type_variable)),
        '=',
        field('typeExpression', $.type_expression),
      ),

    type_declaration: $ =>
      seq(
        'type',
        field('name', $.upper_case_identifier),
        repeat(field('typeVariable', $.type_variable)),
        '=',
        field('variant', $.union_variant),
        repeat(seq('|', field('variant', $.union_variant))),
      ),

    union_variant: $ =>
      seq(field('name', $.upper_case_identifier), repeat(field('field', $._type_atom))),

    // Types

    type_expression: $ => seq($._type_operand, repeat(seq(alias('->', $.arrow), $._type_operand))),

    _type_operand: $ => choice($.type_ref, $._type_atom),

    type_ref: $ =>
      prec.right(seq(field('name', $.upper_case_qid), repeat1(field('argument', $._type_atom)))),

    _type_atom: $ =>
      choice(
        alias($._bare_type_ref, $.type_ref),
        $.type_variable,
        $.record_type,
        $.tuple_type,
        $.unit_type,
        $.parenthesized_type,
      ),

    _bare_type_ref: $ => field('name', $.upper_case_qid),

    type_variable: $ => $.lower_case_identifier,

    record_type: $ => seq('{', optional(commaSep1(field('fieldType', $.field_type))), '}'),

    field_type: $ =>
      seq(field('name', $.lower_case_identifier), ':', field('typeExpression', $.type_expression)),

    tuple_type: $ =>
      seq(
        '(',
        field('element', $.type_expression),
        repeat1(seq(',', field('element', $.type_expression))),
        ')',
      ),

    unit_type: _ => seq('(', ')'),

    parenthesized_type: $ => seq('(', $.type_expression, ')'),

    // Expressions

    _expression: $ =>
      choice(
        $.bin_op_expr,
        $.negate_expr,
        $.function_call_expr,
        $._atom,
        $.if_else_expr,
        $.case_of_expr,
        $.let_in_expr,
        $.anonymous_function_expr,
      ),

    bin_op_expr: $ =>
      choice(
        ...OPERATORS.map(([operator, level, associativity]) =>
          (associativity === 'left' ? prec.left : prec.right)(
            PREC[level],
            seq(
              field('left', $._expression),
              field('operator', alias(operator, $.operator)),
              field('right', $._expression),
            ),
          ),
        ),
      ),

    // A leading minus negates what follows, including a function application: -f x is -(f x).
    negate_expr: $ => prec(PREC.negate, seq(alias('-', $.operator), field('expr', $._expression))),

    function_call_expr: $ =>
      prec.left(
        PREC.call,
        seq(field('target', $._atom), repeat1(field('arg', choice($._atom, $.negative_argument)))),
      ),

    // f -1: a minus after whitespace and touching its operand negates an argument.
    negative_argument: $ => seq(alias($._minus_arg, $.operator), field('expr', $._atom)),

    _atom: $ =>
      choice(
        $.value_expr,
        $.boolean,
        $.number_constant_expr,
        $.string_constant_expr,
        $.unit_expr,
        $.parenthesized_expr,
        $.tuple_expr,
        $.list_expr,
        $.record_expr,
        $.field_access_expr,
        $.field_accessor_function_expr,
        $.operator_as_function_expr,
      ),

    // A name as an expression: a value, a function or a constructor, possibly qualified.
    value_expr: $ => choice(field('name', $.value_qid), field('name', $.upper_case_qid)),

    // r.field: the dot touches what precedes it.
    field_access_expr: $ =>
      prec.left(
        PREC.field,
        seq(
          field('target', $._atom),
          alias(token.immediate(prec(1, '.')), '.'),
          field('name', $.lower_case_identifier),
        ),
      ),

    // .field: a dot that does not touch what precedes it, then a field name that touches the dot.
    field_accessor_function_expr: $ =>
      seq('.', field('name', alias(token.immediate(LOWER), $.lower_case_identifier))),

    operator_as_function_expr: $ =>
      seq('(', field('operator', alias(choice(...OPERATORS.map(([op]) => op)), $.operator)), ')'),

    unit_expr: _ => seq('(', ')'),

    parenthesized_expr: $ => seq('(', field('expression', $._expression), ')'),

    tuple_expr: $ =>
      seq(
        '(',
        field('expr', $._expression),
        repeat1(seq(',', field('expr', $._expression))),
        ')',
      ),

    list_expr: $ => seq('[', optional(commaSep1(field('expr', $._expression))), ']'),

    record_expr: $ =>
      seq(
        '{',
        optional(
          choice(
            seq(field('baseRecord', $.record_base_identifier), '|', commaSep1(field('field', $.field))),
            commaSep1(field('field', $.field)),
          ),
        ),
        '}',
      ),

    record_base_identifier: $ => $.lower_case_identifier,

    field: $ =>
      seq(field('name', $.lower_case_identifier), '=', field('expression', $._expression)),

    if_else_expr: $ =>
      prec.right(
        seq(
          'if',
          field('condition', $._expression),
          'then',
          field('then', $._expression),
          'else',
          field('else', $._expression),
        ),
      ),

    case_of_expr: $ =>
      seq(
        'case',
        field('expr', $._expression),
        'of',
        $._case_open,
        field('branch', $.case_of_branch),
        repeat(seq($._layout_sep, field('branch', $.case_of_branch))),
        $._layout_close,
      ),

    case_of_branch: $ =>
      seq(field('pattern', $._pattern), alias('->', $.arrow), field('expr', $._expression)),

    let_in_expr: $ =>
      prec.right(
        seq(
          'let',
          $._let_open,
          field('valueDeclaration', $._let_declaration),
          repeat(seq($._layout_sep, field('valueDeclaration', $._let_declaration))),
          $._layout_close,
          'in',
          field('body', $._expression),
        ),
      ),

    anonymous_function_expr: $ =>
      prec.right(
        seq(
          '\\',
          repeat1(field('param', $._pattern_atom)),
          alias('->', $.arrow),
          field('expr', $._expression),
        ),
      ),

    // Patterns. As in Elci's parser, `as` binds only the pattern just before it: in
    // a :: rest as all, the name all is the tail rest.

    _pattern: $ => choice($.cons_pattern, $.as_pattern, $._pattern_app),

    cons_pattern: $ =>
      seq(field('head', $._pattern_app), alias('::', $.operator), field('tail', $._pattern)),

    as_pattern: $ =>
      seq(field('pattern', $._pattern_app), 'as', field('name', $.lower_pattern)),

    _pattern_app: $ => choice($.union_pattern, $._pattern_atom),

    union_pattern: $ =>
      seq(field('constructor', $.upper_case_qid), repeat1(field('argument', $._pattern_atom))),

    _pattern_atom: $ =>
      choice(
        $.anything_pattern,
        $.lower_pattern,
        alias($._bare_union_pattern, $.union_pattern),
        $.boolean,
        $.number_constant_expr,
        $.negative_number_pattern,
        $.string_constant_expr,
        $.unit_pattern,
        $.tuple_pattern,
        $.parenthesized_pattern,
        $.list_pattern,
        $.record_pattern,
      ),

    _bare_union_pattern: $ => field('constructor', $.upper_case_qid),

    anything_pattern: _ => '_',

    lower_pattern: $ => $.lower_case_identifier,

    negative_number_pattern: $ => seq(alias('-', $.operator), $.number_constant_expr),

    unit_pattern: _ => seq('(', ')'),

    tuple_pattern: $ =>
      seq(
        '(',
        field('pattern', $._pattern),
        repeat1(seq(',', field('pattern', $._pattern))),
        ')',
      ),

    parenthesized_pattern: $ => seq('(', field('pattern', $._pattern), ')'),

    list_pattern: $ => seq('[', optional(commaSep1(field('pattern', $._pattern))), ']'),

    record_pattern: $ => seq('{', optional(commaSep1(field('field', $.lower_pattern))), '}'),

    // Names. A qualified name is written without spaces: List.map, Maybe.Just.

    value_qid: $ =>
      choice(
        field('name', $.lower_case_identifier),
        seq(
          field('module', $.module_prefix),
          field('name', alias(token.immediate(LOWER), $.lower_case_identifier)),
        ),
      ),

    upper_case_qid: $ =>
      choice(
        field('name', $.upper_case_identifier),
        seq(
          field('module', $.module_prefix),
          field('name', alias(token.immediate(UPPER), $.upper_case_identifier)),
        ),
      ),

    // The module part of a qualified name, with its trailing dot: `List.` in List.map.
    module_prefix: _ => new RegExp('(\\p{Lu}' + NAME_REST + '\\.)+', 'u'),

    module_name: _ => new RegExp('\\p{Lu}' + NAME_REST + '(\\.\\p{Lu}' + NAME_REST + ')*', 'u'),

    lower_case_identifier: _ => LOWER,

    upper_case_identifier: _ => UPPER,

    // Literals

    // True and False are literals; in a pattern they take no arguments.
    boolean: _ => choice('True', 'False'),

    number_constant_expr: _ => /[0-9]+/,

    string_constant_expr: $ =>
      seq('"', repeat(choice($._string_content, $.string_escape)), '"'),

    _string_content: _ => token.immediate(prec(1, /[^"\\\r\n]+/)),

    string_escape: _ => token.immediate(/\\[nrt"\\]/),

    // Comments. Block comments nest; {-| ... -} doc comments are block comments.

    line_comment: _ => token(seq('--', /[^\n]*/)),

    block_comment: $ => seq('{-', optional($._block_comment_content), '-}'),
  },
});

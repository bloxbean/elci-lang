/*
 * External scanner for tree-sitter-elci.
 *
 * Elci's parser (src/main/java/org/elci/syntax/Parser.java) reads layout from token columns.
 * Top-level declarations start in column 1. A `let` block's declarations align at the column
 * of the first token after `let`, and a `case`'s branches at the column of the first token
 * after `of`. A token that starts a new line at or left of that column ends the expression
 * being read. This scanner keeps a stack of those block columns and emits zero-width tokens:
 *
 *   _let_open, _case_open  after `let` / `of`: push the column of the next token;
 *   _layout_sep            a new line starts at the block's column: the next item begins;
 *   _layout_close          the block ends: a new line starts left of its column, or the next
 *                          token is `in`, `then`, `else`, `of`, `)`, `]`, `}` or `,`, or EOF.
 *
 * Tree-sitter reports which tokens the grammar can accept at this point (valid_symbols). Inside
 * brackets a layout token is never valid until the bracket closes, which matches Elci: layout
 * does not apply inside ( ), [ ] and { } except in a nested let or case.
 *
 * Elci takes some tokens without looking at their column: `->`, `=`, `:`, `|` and the keywords
 * `then`, `else`, `of` and `in`. A new line that starts with one of them never separates items.
 *
 * _minus_arg is the `-` of `f -1`: a minus after whitespace whose operand touches it, where the
 * grammar can take a function argument. Elsewhere `-` is the operator of `a - 1` or a
 * negation.
 *
 * _block_comment_content is the text of a {- ... -} comment, which nests.
 */

#include "tree_sitter/alloc.h"
#include "tree_sitter/array.h"
#include "tree_sitter/parser.h"

#include <stdint.h>
#include <string.h>
#include <wctype.h>

enum TokenType {
  LAYOUT_SEP,
  LAYOUT_CLOSE,
  LET_OPEN,
  CASE_OPEN,
  MINUS_ARG,
  BLOCK_COMMENT_CONTENT,
  ERROR_SENTINEL,
};

enum BlockKind { BLOCK_LET = 1, BLOCK_CASE = 2 };

typedef struct {
  uint8_t kind;
  uint16_t column;
} Block;

/*
 * The open let and case blocks, innermost last, with 0-based columns. The top level is an implicit
 * block at column 0. Serialization keeps the outermost 341 blocks.
 */
typedef struct {
  Array(Block) blocks;
} Scanner;

static inline void advance(TSLexer *lexer) { lexer->advance(lexer, false); }

static inline void skip(TSLexer *lexer) { lexer->advance(lexer, true); }

static inline bool is_space(int32_t c) {
  return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v' ||
         (c > 127 && iswspace((wint_t)c));
}

static inline bool is_name_char(int32_t c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' ||
         (c > 127 && iswalnum((wint_t)c));
}

/* Skips the rest of a block comment whose "{-" has been consumed. Comments nest. */
static void skip_block_comment_rest(TSLexer *lexer) {
  unsigned depth = 1;
  while (!lexer->eof(lexer)) {
    if (lexer->lookahead == '{') {
      advance(lexer);
      if (lexer->lookahead == '-') {
        advance(lexer);
        depth++;
      }
    } else if (lexer->lookahead == '-') {
      advance(lexer);
      if (lexer->lookahead == '}') {
        advance(lexer);
        if (--depth == 0) return;
      }
    } else {
      advance(lexer);
    }
  }
}

/* The text of a block comment up to, not including, the "-}" that closes it. */
static bool scan_block_comment_content(TSLexer *lexer) {
  unsigned depth = 1;
  bool any = false;
  for (;;) {
    if (lexer->eof(lexer)) {
      lexer->mark_end(lexer);
      return any;
    }
    if (lexer->lookahead == '-') {
      lexer->mark_end(lexer);
      advance(lexer);
      if (lexer->lookahead == '}') {
        if (depth == 1) return any;
        advance(lexer);
        depth--;
      }
      any = true;
    } else if (lexer->lookahead == '{') {
      advance(lexer);
      if (lexer->lookahead == '-') {
        advance(lexer);
        depth++;
      }
      any = true;
    } else {
      advance(lexer);
      any = true;
    }
  }
}

static bool scan(Scanner *scanner, TSLexer *lexer, const bool *valid) {
  // During error recovery every token is valid; layout tokens would only add noise.
  if (valid[ERROR_SENTINEL]) return false;

  if (valid[BLOCK_COMMENT_CONTENT]) {
    lexer->result_symbol = BLOCK_COMMENT_CONTENT;
    return scan_block_comment_content(lexer);
  }

  // Layout tokens are zero-width and sit right after the previous token.
  lexer->mark_end(lexer);

  // Find the next real token: skip whitespace and comments.
  bool newline = false, space = false, comment = false, eof = false;
  int32_t first = 0, second = 0;
  bool consumed_first = false;
  uint32_t column = 0;
  for (;;) {
    if (lexer->eof(lexer)) {
      eof = true;
      break;
    }
    int32_t c = lexer->lookahead;
    if (is_space(c)) {
      if (c == '\n') newline = true;
      space = true;
      skip(lexer);
      continue;
    }
    column = lexer->get_column(lexer);
    first = c;
    if (c == '-' || c == '{') {
      advance(lexer);
      second = lexer->lookahead;
      consumed_first = true;
      if (c == '-' && second == '-') {
        comment = true;
        space = false;
        while (!lexer->eof(lexer) && lexer->lookahead != '\n') advance(lexer);
        consumed_first = false;
        continue;
      }
      if (c == '{' && second == '-') {
        comment = true;
        space = false;
        advance(lexer);
        skip_block_comment_rest(lexer);
        consumed_first = false;
        continue;
      }
    }
    break;
  }

  // Classify the next token.
  bool closing_keyword = false;
  if (!eof && !consumed_first && first >= 'a' && first <= 'z') {
    char word[6];
    unsigned length = 0;
    while (length < 5 && is_name_char(lexer->lookahead)) {
      word[length++] = (char)lexer->lookahead;
      advance(lexer);
    }
    word[length] = 0;
    if (!is_name_char(lexer->lookahead))
      closing_keyword = strcmp(word, "in") == 0 || strcmp(word, "then") == 0 ||
                        strcmp(word, "else") == 0 || strcmp(word, "of") == 0;
  } else if (!eof && !consumed_first) {
    advance(lexer);
    second = lexer->lookahead;
    consumed_first = true;
  }
  bool closing_bracket = !eof && (first == ')' || first == ']' || first == '}' || first == ',');
  // Tokens Elci's parser takes at any column: they continue what precedes them.
  bool continuation = !eof && ((first == '-' && second == '>') || (first == '=' && second != '=') ||
                               (first == ':' && second != ':') ||
                               (first == '|' && second != '|' && second != '>'));

  if (valid[LET_OPEN] || valid[CASE_OPEN]) {
    Block block = {valid[LET_OPEN] ? BLOCK_LET : BLOCK_CASE,
                   (uint16_t)(column > 65535 ? 65535 : column)};
    array_push(&scanner->blocks, block);
    lexer->result_symbol = valid[LET_OPEN] ? LET_OPEN : CASE_OPEN;
    return true;
  }

  uint32_t block_column = 0;
  bool in_block = scanner->blocks.size > 0;
  if (in_block) block_column = array_back(&scanner->blocks)->column;

  if (in_block && valid[LAYOUT_CLOSE] &&
      (eof || closing_bracket || closing_keyword ||
       (newline && column < block_column && !continuation))) {
    array_pop(&scanner->blocks);
    lexer->result_symbol = LAYOUT_CLOSE;
    return true;
  }

  if (valid[LAYOUT_SEP] && newline && !eof && column == block_column && !continuation &&
      !closing_keyword) {
    lexer->result_symbol = LAYOUT_SEP;
    return true;
  }

  // A comment comes first: let the lexer read it, then look again.
  if (comment) return false;

  if (valid[MINUS_ARG] && first == '-' && space && consumed_first && !eof && second != '-' &&
      second != '>' && !lexer->eof(lexer) && !is_space(second)) {
    lexer->mark_end(lexer);
    lexer->result_symbol = MINUS_ARG;
    return true;
  }

  return false;
}

void *tree_sitter_elci_external_scanner_create(void) {
  Scanner *scanner = ts_calloc(1, sizeof(Scanner));
  array_init(&scanner->blocks);
  return scanner;
}

void tree_sitter_elci_external_scanner_destroy(void *payload) {
  Scanner *scanner = (Scanner *)payload;
  array_delete(&scanner->blocks);
  ts_free(scanner);
}

unsigned tree_sitter_elci_external_scanner_serialize(void *payload, char *buffer) {
  Scanner *scanner = (Scanner *)payload;
  unsigned size = 0;
  for (uint32_t i = 0; i < scanner->blocks.size; i++) {
    if (size + 3 > TREE_SITTER_SERIALIZATION_BUFFER_SIZE) break;
    Block block = *array_get(&scanner->blocks, i);
    buffer[size++] = (char)block.kind;
    buffer[size++] = (char)(block.column & 0xFF);
    buffer[size++] = (char)(block.column >> 8);
  }
  return size;
}

void tree_sitter_elci_external_scanner_deserialize(void *payload, const char *buffer,
                                                   unsigned length) {
  Scanner *scanner = (Scanner *)payload;
  array_clear(&scanner->blocks);
  for (unsigned i = 0; i + 3 <= length; i += 3) {
    Block block = {(uint8_t)buffer[i],
                   (uint16_t)((uint8_t)buffer[i + 1] | ((uint8_t)buffer[i + 2] << 8))};
    array_push(&scanner->blocks, block);
  }
}

bool tree_sitter_elci_external_scanner_scan(void *payload, TSLexer *lexer,
                                            const bool *valid_symbols) {
  return scan((Scanner *)payload, lexer, valid_symbols);
}

package com.tom.rv2ide.lsp.java.providers.completion.ts

enum class TSCompletionContext {
  COMMENT_OR_STRING,
  IMPORT_DECLARATION,
  PACKAGE_DECLARATION,
  MEMBER_ACCESS,
  METHOD_CALL_ARGUMENTS,
  TYPE_BODY,
  METHOD_BODY,
  BROKEN_SYNTAX_NEAR_CURSOR,
  UNKNOWN,
}

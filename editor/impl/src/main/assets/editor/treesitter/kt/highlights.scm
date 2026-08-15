;; AndroidCodeStudio Kotlin highlight adapter.
;;
;; Based on the nvim-treesitter Kotlin highlighting query under the Apache License:
;; https://github.com/nvim-treesitter/nvim-treesitter/blob/f8ab59861eed4a1c168505e3433462ed800f2bae/queries/kotlin/highlights.scm
;;
;; The AST baseline and new syntax rules follow fwcd/tree-sitter-kotlin. Capture
;; names and ranges follow this project's stable Kotlin highlighting contract;
;; see README.md in this directory. Do not replace this file with the upstream
;; query verbatim.

;;; Specific identifiers
;; Broad identifier/type fallbacks are intentionally placed at the end because
;; captures with identical ranges preserve query order. Nested narrower captures
;; override their parents independently of order.

; `it` keyword inside lambdas
; FIXME: This will highlight the keyword outside of lambdas since tree-sitter
;        does not allow us to check for arbitrary nestation
((simple_identifier) @variable.builtin
(#eq? @variable.builtin "it"))

; `field` keyword inside property getter/setter
; FIXME: This will highlight the keyword outside of getters and setters
;        since tree-sitter does not allow us to check for arbitrary nestation
((simple_identifier) @variable.builtin
(#eq? @variable.builtin "field"))

; `this` this keyword inside classes
(this_expression) @variable.builtin

; `super` keyword inside classes
(super_expression) @variable.builtin

;;; Annotations and constructor calls
;; These context-specific type captures must precede builtin and ordinary type
;; rules when they cover the same identifier range.
(annotation
	"@" @attribute (use_site_target)? @attribute)
(annotation
	(user_type
		(type_identifier) @attribute))
(annotation
	(constructor_invocation
		(user_type
			(type_identifier) @attribute)))

(file_annotation
	"@" @attribute "file" @attribute ":" @attribute)
(file_annotation
	(user_type
		(type_identifier) @attribute))
(file_annotation
	(constructor_invocation
		(user_type
			(type_identifier) @attribute)))

(constructor_invocation
	(user_type
		(type_identifier) @constructor))

(statements
	(property_declaration
		(variable_declaration
			(simple_identifier) @property.local)))

(source_file
	(property_declaration
		(variable_declaration
			(simple_identifier) @property.top_level)))

; Only val/var primary-constructor parameters declare properties.
(class_parameter
	(binding_pattern_kind)
	(simple_identifier) @property.class)

; A primary-constructor parameter without val/var is an ordinary parameter.
(class_parameter
	(simple_identifier) @parameter)

(class_body
	(property_declaration
		(variable_declaration
			(simple_identifier) @property.class)))

(enum_entry
	(simple_identifier) @constant)

; Only the nullable marker is punctuation; keep the wrapped type's own color.
(nullable_type
	(quest) @punctuation.special)

((type_identifier) @type.builtin
	(#any-of? @type.builtin
		"Byte"
		"Short"
		"Int"
		"Long"
		"UByte"
		"UShort"
		"UInt"
		"ULong"
		"Float"
		"Double"
		"Boolean"
		"Char"
		"String"
		"Array"
		"ByteArray"
		"ShortArray"
		"IntArray"
		"LongArray"
		"UByteArray"
		"UShortArray"
		"UIntArray"
		"ULongArray"
		"FloatArray"
		"DoubleArray"
		"BooleanArray"
		"CharArray"
		"Map"
		"Set"
		"List"
		"EmptyMap"
		"EmptySet"
		"EmptyList"
		"MutableMap"
		"MutableSet"
		"MutableList"
))

(package_header
	"package" @keyword)

(import_header
	"import" @keyword)


; TODO: Seperate labeled returns/breaks/continue/super/this
;       Must be implemented in the parser first
(label) @label

;;; Function definitions

(function_declaration
	(simple_identifier) @function.declaration)

(getter
	("get") @function.builtin)
(setter
	("set") @function.builtin)

(primary_constructor
  (primary_constructor_keyword) @keyword)

(secondary_constructor
  ("constructor") @keyword)

(constructor_delegation_call
	["this" "super"] @keyword)

(anonymous_initializer
	("init") @keyword)

(parameter
	(simple_identifier) @parameter)

(parameter_with_optional_type
	(simple_identifier) @parameter)

; lambda parameters
(lambda_literal
	(lambda_parameters
		(variable_declaration
			(simple_identifier) @parameter)))

;;; Function calls

; Built-in calls must precede ordinary call rules because they capture the same
; identifier range and identical ranges preserve query order.
(call_expression
	. (simple_identifier) @function.builtin
    (#any-of? @function.builtin
		"arrayOf"
		"arrayOfNulls"
		"byteArrayOf"
		"shortArrayOf"
		"intArrayOf"
		"longArrayOf"
		"ubyteArrayOf"
		"ushortArrayOf"
		"uintArrayOf"
		"ulongArrayOf"
		"floatArrayOf"
		"doubleArrayOf"
		"booleanArrayOf"
		"charArrayOf"
		"emptyArray"
		"mapOf"
		"setOf"
		"listOf"
		"emptyMap"
		"emptySet"
		"emptyList"
		"mutableMapOf"
		"mutableSetOf"
		"mutableListOf"
		"print"
		"println"
		"error"
		"TODO"
		"run"
		"runCatching"
		"repeat"
		"lazy"
		"lazyOf"
		"enumValues"
		"enumValueOf"
		"assert"
		"check"
		"checkNotNull"
		"require"
		"requireNotNull"
		"with"
		"synchronized"
))

; function()
(call_expression
	. (simple_identifier) @function.invocation)

; object.function() or object.property.function()
(call_expression
	(navigation_expression
		(navigation_suffix
			(simple_identifier) @function.invocation) . ))

; ::function and Type::function. The direct simple_identifier is the referenced
; callable; an optional receiver is represented as type_identifier.
(callable_reference
	(simple_identifier) @function.invocation)

; Remaining navigation suffixes are assumed to be property accesses. This rule
; follows all call rules so method names keep function.invocation styling.
(_
	(navigation_suffix
		(simple_identifier) @property.class))

; SCREAMING_CASE is a text heuristic, so it follows declaration, call and member
; access rules and only precedes the broad identifier fallback.
((simple_identifier) @constant
	(#match? @constant "^[A-Z][A-Z0-9_]*$"))

;;; Literals

[
	(line_comment)
	(multiline_comment)
] @comment

(shebang_line) @preproc

(real_literal) @number
[
	(integer_literal)
	(long_literal)
	(hex_literal)
	(bin_literal)
	(unsigned_literal)
] @number

[
	(null_literal)
	(boolean_literal)
] @constant.builtin

(character_literal) @string

; There are 3 ways to define a regex. Keep these rules before the ordinary
; string fallback because both captures start at the opening quote.
;    - "[abc]?".toRegex()
(call_expression
	(navigation_expression
		((string_literal) @string.regex)
		(navigation_suffix
			((simple_identifier) @_function
			(#eq? @_function "toRegex")))))

;    - Regex("[abc]?")
(call_expression
	((simple_identifier) @_function
	(#eq? @_function "Regex"))
	(call_suffix
		(value_arguments
			(value_argument
				(string_literal) @string.regex))))

;   - Regex.fromLiteral("[abc]?")
(call_expression
	(navigation_expression
		((simple_identifier) @_class
		(#eq? @_class "Regex"))
		(navigation_suffix
			((simple_identifier) @_function
			(#eq? @_function "fromLiteral"))))
	(call_suffix
		(value_arguments
			(value_argument
				(string_literal) @string.regex))))

; Ordinary string fallback follows regex-specific captures.
(string_literal) @string

; This node is emitted for character literals. Ordinary string escapes are part of the scanner's
; string_content token and are split by the Kotlin-aware TreeSitterSpanFactory instead.
(character_escape_seq) @string.escape

;;; Keywords

(type_alias "typealias" @keyword)

(companion_object
	"companion" @keyword)

[
	(class_modifier)
	(member_modifier)
	(function_modifier)
	(property_modifier)
	(platform_modifier)
	(variance_modifier)
	(parameter_modifier)
	(visibility_modifier)
	(reification_modifier)
	(inheritance_modifier)
] @type.qualifier

[
	"val"
	"var"
	"enum"
	"class"
	"object"
	"interface"
;	"typeof" ; NOTE: It is reserved for future use
] @keyword

("fun") @keyword

; `where` is the type-constraint introducer, not a general identifier.
(type_constraints "where" @keyword)

[
	"return"
	"continue"
	"break"
	"throw"
] @keyword

[
	"if"
	"else"
	"when"
] @keyword

[
	"for"
	"do"
	"while"
] @keyword

[
	"try"
	"catch"
	"finally"
] @keyword


;;; Operators & Punctuation

[
	"!"
	"!="
	"!=="
	"="
	"=="
	"==="
	">"
	">="
	"<"
	"<="
	"||"
	"&&"
	"+"
	"++"
	"+="
	"-"
	"--"
	"-="
	"*"
	"*="
	"/"
	"/="
	"%"
	"%="
	"?."
	"?:"
	"!!"
	"is"
	"in"
	"as"
	"as?"
	".."
	"..<"
	"->"
] @operator

[
	"(" ")"
	"[" "]"
	"{" "}"
] @bracket

[
	"."
	","
	";"
	":"
	"::"
] @operator

; NOTE: `interpolated_identifier`s can be highlighted in any way
(string_literal
	(interpolation_identifier_start) @punctuation.special
	(interpolated_identifier) @none)
(string_literal
	(interpolation_expression_start) @punctuation.special
	(interpolated_expression) @none
	(interpolation_expression_end) @punctuation.special)

;;; Broad fallbacks
;; Keep these patterns last. Specific captures covering the same identifier range
;; must be emitted before these broad type/identifier defaults.
(type_identifier) @type
(simple_identifier) @identifier

package com.example.core.agent

sealed class CalculationResult {
    data class Success(val value: Double, val formattedResult: String) : CalculationResult()
    data class Error(val message: String) : CalculationResult()
}

/**
 * Safe local mathematical expression parser and evaluator.
 * Implements an AST/Recursive Descent parser for basic arithmetic without eval() or external scripts.
 * Supports: +, -, *, /, parentheses, operator precedence, decimals, negative numbers, division by zero detection.
 */
object LocalMathCalculator {

    fun calculate(expression: String): CalculationResult {
        val sanitized = expression.trim()
            .replace("×", "*")
            .replace("÷", "/")
            .replace("x", "*") // Handle common mobile input 'x'
            .replace(" ", "")

        if (sanitized.isEmpty()) {
            return CalculationResult.Error("Empty expression")
        }

        return try {
            val parser = MathParser(sanitized)
            val result = parser.parse()
            if (result.isInfinite() || result.isNaN()) {
                CalculationResult.Error("Division by zero is undefined")
            } else {
                val formatted = if (result % 1.0 == 0.0) {
                    result.toLong().toString()
                } else {
                    String.format(java.util.Locale.US, "%.4f", result).trimEnd('0').trimEnd('.')
                }
                CalculationResult.Success(result, formatted)
            }
        } catch (e: ArithmeticException) {
            CalculationResult.Error(e.message ?: "Arithmetic error")
        } catch (e: Exception) {
            CalculationResult.Error("Invalid mathematical expression: ${e.message}")
        }
    }

    private class MathParser(private val src: String) {
        private var pos = 0
        private val len = src.length

        fun parse(): Double {
            val result = parseExpression()
            if (pos < len) {
                throw IllegalArgumentException("Unexpected character '${src[pos]}' at position $pos")
            }
            return result
        }

        // Expression: Term (('+' | '-') Term)*
        private fun parseExpression(): Double {
            var value = parseTerm()
            while (pos < len) {
                val op = src[pos]
                if (op == '+' || op == '-') {
                    pos++
                    val nextTerm = parseTerm()
                    value = if (op == '+') value + nextTerm else value - nextTerm
                } else {
                    break
                }
            }
            return value
        }

        // Term: Factor (('*' | '/') Factor)*
        private fun parseTerm(): Double {
            var value = parseFactor()
            while (pos < len) {
                val op = src[pos]
                if (op == '*' || op == '/') {
                    pos++
                    val nextFactor = parseFactor()
                    if (op == '/') {
                        if (nextFactor == 0.0) {
                            throw ArithmeticException("Division by zero is undefined")
                        }
                        value /= nextFactor
                    } else {
                        value *= nextFactor
                    }
                } else {
                    break
                }
            }
            return value
        }

        // Factor: Number | '(' Expression ')' | '-' Factor | '+' Factor
        private fun parseFactor(): Double {
            if (pos >= len) {
                throw IllegalArgumentException("Premature end of expression")
            }

            val ch = src[pos]

            // Unary minus
            if (ch == '-') {
                pos++
                return -parseFactor()
            }
            // Unary plus
            if (ch == '+') {
                pos++
                return parseFactor()
            }

            // Parentheses
            if (ch == '(') {
                pos++ // consume '('
                val result = parseExpression()
                if (pos >= len || src[pos] != ')') {
                    throw IllegalArgumentException("Missing closing parenthesis ')'")
                }
                pos++ // consume ')'
                return result
            }

            // Number
            val start = pos
            if (ch in '0'..'9' || ch == '.') {
                var hasDot = ch == '.'
                pos++
                while (pos < len) {
                    val curr = src[pos]
                    if (curr in '0'..'9') {
                        pos++
                    } else if (curr == '.') {
                        if (hasDot) throw IllegalArgumentException("Multiple decimal points in number")
                        hasDot = true
                        pos++
                    } else {
                        break
                    }
                }
                val numStr = src.substring(start, pos)
                return numStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $numStr")
            }

            throw IllegalArgumentException("Unexpected character '$ch' at position $pos")
        }
    }
}

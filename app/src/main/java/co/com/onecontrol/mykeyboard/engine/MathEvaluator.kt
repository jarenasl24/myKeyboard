package co.com.onecontrol.mykeyboard.engine

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlin.math.roundToLong

object MathEvaluator {

    data class CalculationResult(
        val expression: String,
        val resultValue: Double,
        val formattedResult: String
    )

    private val numberFormatter = DecimalFormat("#,##0.####", DecimalFormatSymbols(Locale.US))

    /**
     * Checks if the text ending at the cursor contains an evaluable mathematical expression.
     * Examples: "1500+250", "50 * 12", "100 / 4", "2500 - 450", "1500+250="
     */
    fun findMathExpression(textBeforeCursor: String): CalculationResult? {
        val trimmed = textBeforeCursor.trimEnd().removeSuffix("=").trimEnd()
        if (trimmed.length < 3) return null

        // Look for the last chunk that contains digits and arithmetic operators (+, -, *, x, /, %)
        var startIdx = trimmed.length - 1
        var hasOperator = false
        var hasDigit = false

        while (startIdx >= 0) {
            val ch = trimmed[startIdx]
            if (ch.isDigit()) {
                hasDigit = true
                startIdx--
            } else if (ch == '+' || ch == '-' || ch == '*' || ch == 'x' || ch == 'X' || ch == '/' || ch == '%' || ch == '.' || ch == '(' || ch == ')') {
                if (ch == '+' || ch == '-' || ch == '*' || ch == 'x' || ch == 'X' || ch == '/' || ch == '%') {
                    hasOperator = true
                }
                startIdx--
            } else if (ch == ' ' || ch == '\t') {
                startIdx--
            } else {
                break
            }
        }

        if (!hasOperator || !hasDigit) return null

        val candidateExpr = trimmed.substring(startIdx + 1).trim()
        if (candidateExpr.length < 3) return null

        // Must end with a digit or closing parenthesis or %
        val lastChar = candidateExpr.last()
        if (!lastChar.isDigit() && lastChar != ')' && lastChar != '%') return null

        val evalResult = evaluateSimpleExpression(candidateExpr) ?: return null
        if (evalResult.isNaN() || evalResult.isInfinite()) return null

        val formatted = if (evalResult == evalResult.roundToLong().toDouble()) {
            numberFormatter.format(evalResult.roundToLong())
        } else {
            numberFormatter.format(evalResult)
        }

        return CalculationResult(
            expression = candidateExpr,
            resultValue = evalResult,
            formattedResult = formatted
        )
    }

    /**
     * Evaluates simple expressions with +, -, *, /, x, %, and optional parentheses.
     */
    fun evaluateSimpleExpression(rawExpr: String): Double? {
        val normalized = rawExpr
            .replace("x", "*", ignoreCase = true)
            .replace(" ", "")

        return try {
            val tokens = tokenize(normalized) ?: return null
            if (tokens.isEmpty()) return null
            val rpn = shuntingYard(tokens) ?: return null
            evalRpn(rpn)
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(expr: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = 0
        var expectUnary = true

        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder()
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        sb.append(expr[i])
                        i++
                    }
                    if (i < expr.length && expr[i] == '%') {
                        val num = sb.toString().toDoubleOrNull() ?: return null
                        tokens.add((num / 100.0).toString())
                        i++
                    } else {
                        tokens.add(sb.toString())
                    }
                    expectUnary = false
                }
                c == '+' || c == '-' -> {
                    if (expectUnary) {
                        // Unary plus/minus: fold into next number
                        val sign = c
                        i++
                        val sb = StringBuilder().append(sign)
                        while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                            sb.append(expr[i])
                            i++
                        }
                        tokens.add(sb.toString())
                        expectUnary = false
                    } else {
                        tokens.add(c.toString())
                        expectUnary = true
                        i++
                    }
                }
                c == '*' || c == '/' -> {
                    tokens.add(c.toString())
                    expectUnary = true
                    i++
                }
                c == '(' -> {
                    tokens.add("(")
                    expectUnary = true
                    i++
                }
                c == ')' -> {
                    tokens.add(")")
                    expectUnary = false
                    i++
                }
                else -> return null
            }
        }
        return tokens
    }

    private fun precedence(op: String): Int = when (op) {
        "+", "-" -> 1
        "*", "/" -> 2
        else -> 0
    }

    private fun shuntingYard(tokens: List<String>): List<String>? {
        val output = mutableListOf<String>()
        val ops = ArrayDeque<String>()

        for (token in tokens) {
            val num = token.toDoubleOrNull()
            if (num != null) {
                output.add(token)
            } else if (token == "(") {
                ops.addLast(token)
            } else if (token == ")") {
                while (ops.isNotEmpty() && ops.last() != "(") {
                    output.add(ops.removeLast())
                }
                if (ops.isEmpty() || ops.removeLast() != "(") return null
            } else {
                while (ops.isNotEmpty() && ops.last() != "(" && precedence(ops.last()) >= precedence(token)) {
                    output.add(ops.removeLast())
                }
                ops.addLast(token)
            }
        }
        while (ops.isNotEmpty()) {
            val op = ops.removeLast()
            if (op == "(" || op == ")") return null
            output.add(op)
        }
        return output
    }

    private fun evalRpn(rpn: List<String>): Double? {
        val stack = ArrayDeque<Double>()
        for (token in rpn) {
            val num = token.toDoubleOrNull()
            if (num != null) {
                stack.addLast(num)
            } else {
                if (stack.size < 2) return null
                val b = stack.removeLast()
                val a = stack.removeLast()
                val res = when (token) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0.0) return null else a / b
                    else -> return null
                }
                stack.addLast(res)
            }
        }
        return if (stack.size == 1) stack.last() else null
    }
}

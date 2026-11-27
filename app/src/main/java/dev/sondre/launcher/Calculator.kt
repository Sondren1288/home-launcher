package dev.sondre.launcher

import java.math.BigDecimal
import java.math.MathContext

/**
 * Evaluates arithmetic typed into the search bar: `3+3`, `5*12/3`, `2,5 × (4 − 1)`, `2^10`,
 * `200 + 10%` (= 220, like a phone calculator), `50 * 15%` (= 7.5).
 *
 * Only shown for real calculations: a lone number (`42`, `-3`) isn't one, and anything with
 * letters or stray symbols isn't either. Missing closing brackets at the end are allowed, so
 * a result shows while you type. Pure and Android-free so it's unit tested on the JVM.
 */
object Calculator {

    /** The formatted result, or null if [query] isn't a calculation. */
    fun calculate(query: String): String? {
        val value = evaluate(query) ?: return null
        // Answer with the decimal separator the user typed: "2,5*2" → "5", "1,5/2" → "0,75".
        val comma = ',' in query && '.' !in query
        return format(value).let { if (comma) it.replace('.', ',') else it }
    }

    /** The value of [query], or null if it isn't a calculation (or has no finite answer). */
    fun evaluate(query: String): Double? {
        val tokens = tokenize(query) ?: return null
        val parser = Parser(tokens)
        val result = runCatching { parser.parse() }.getOrNull() ?: return null
        if (!parser.sawOperator || !result.value.isFinite()) return null
        return result.value
    }

    /** Up to 12 significant digits, no trailing zeros, no exponent for everyday sizes. */
    fun format(value: Double): String {
        if (value == 0.0) return "0" // also -0.0
        val bd = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()
        val plain = bd.abs() >= BigDecimal("1e-6") && bd.abs() < BigDecimal("1e15")
        return if (plain) bd.toPlainString() else bd.toString().replace("E+", "e").replace("E", "e")
    }

    private sealed interface Tok {
        data class Num(val v: Double) : Tok
        data class Op(val c: Char) : Tok
    }

    private fun tokenize(s: String): List<Tok>? {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' || c == ',' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                    val text = s.substring(start, i).replace(',', '.')
                    if (text.count { it == '.' } > 1) return null
                    out += Tok.Num(text.toDoubleOrNull() ?: return null)
                }
                else -> {
                    val op = when (c) {
                        '+' -> '+'
                        '-', '−', '–' -> '-'
                        '*', '×', '·', 'x', 'X' -> '*'
                        '/', '÷', ':' -> '/'
                        '^' -> '^'
                        '%' -> '%'
                        '(', ')' -> c
                        else -> return null
                    }
                    out += Tok.Op(op)
                    i++
                }
            }
        }
        return out
    }

    /** A value, and whether it was written as a percentage (`10%`), for `200 + 10%`. */
    private data class Value(val value: Double, val percent: Boolean = false)

    /**
     * expr    := term (('+' | '-') term)*
     * term    := unary (('*' | '/') unary | implicit '(' …)*
     * unary   := ('-' | '+') unary | power
     * power   := postfix ('^' unary)?
     * postfix := primary '%'?
     * primary := number | '(' expr ')'?   (a missing ')' at the very end is fine)
     */
    private class Parser(private val tokens: List<Tok>) {
        private var pos = 0
        /** At least one binary operator or a percentage: "42" alone isn't a calculation. */
        var sawOperator = false
            private set

        fun parse(): Value {
            val v = expr()
            check(pos == tokens.size) { "trailing input" }
            return v
        }

        private fun peekOp(): Char? = (tokens.getOrNull(pos) as? Tok.Op)?.c

        private fun expr(): Value {
            var left = term().value
            while (true) {
                val op = peekOp()?.takeIf { it == '+' || it == '-' } ?: break
                pos++
                sawOperator = true
                val right = term()
                // Phone-calculator percentages: 200 + 10% = 220, 200 - 10% = 180.
                val r = if (right.percent) left * right.value else right.value
                left = if (op == '+') left + r else left - r
            }
            return Value(left)
        }

        private fun term(): Value {
            var left = unary()
            while (true) {
                val op = peekOp()
                when (op) {
                    '*', '/' -> {
                        pos++
                        sawOperator = true
                        val right = unary().value
                        left = Value(if (op == '*') left.value * right else left.value / right)
                    }
                    // 2(3+4) = 14
                    '(' -> {
                        sawOperator = true
                        left = Value(left.value * unary().value)
                    }
                    else -> return left
                }
            }
        }

        private fun unary(): Value = when (peekOp()) {
            '-' -> { pos++; unary().let { Value(-it.value, it.percent) } }
            '+' -> { pos++; unary() }
            else -> power()
        }

        private fun power(): Value {
            val base = postfix()
            if (peekOp() != '^') return base
            pos++
            sawOperator = true
            return Value(Math.pow(base.value, unary().value))
        }

        private fun postfix(): Value {
            val v = primary()
            if (peekOp() != '%') return v
            pos++
            sawOperator = true
            return Value(v.value / 100, percent = true)
        }

        private fun primary(): Value {
            when (val t = tokens.getOrNull(pos) ?: error("unexpected end")) {
                is Tok.Num -> { pos++; return Value(t.v) }
                is Tok.Op -> {
                    check(t.c == '(') { "unexpected ${t.c}" }
                    pos++
                    val v = expr()
                    when {
                        peekOp() == ')' -> pos++
                        pos == tokens.size -> {} // still typing: "(3+4"
                        else -> error("missing )")
                    }
                    return Value(v.value)
                }
            }
        }
    }
}

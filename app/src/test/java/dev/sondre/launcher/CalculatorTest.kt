package dev.sondre.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    private fun calc(q: String) = Calculator.calculate(q)

    @Test
    fun `basic arithmetic and precedence`() {
        assertEquals("6", calc("3+3"))
        assertEquals("20", calc("5*12/3"))
        assertEquals("14", calc("2+3*4"))
        assertEquals("20", calc("(2+3)*4"))
        assertEquals("-1", calc("2-3"))
        assertEquals("1024", calc("2^10"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("512", calc("2^3^2")) // right-associative
    }

    @Test
    fun `typed symbols and spaces`() {
        assertEquals("12", calc("3 × 4"))
        assertEquals("12", calc("3x4"))
        assertEquals("2", calc("6 ÷ 3"))
        assertEquals("2", calc("6:3"))
        assertEquals("1", calc("4 − 3"))
    }

    @Test
    fun `decimals answer with the separator you typed`() {
        assertEquals("5", calc("2,5*2"))
        assertEquals("0,75", calc("1,5/2"))
        assertEquals("0.75", calc("1.5/2"))
        assertEquals("0.3", calc("0.1+0.2")) // rounded, not 0.30000000000000004
        assertEquals("0.333333333333", calc("1/3"))
    }

    @Test
    fun `percentages work like a phone calculator`() {
        assertEquals("220", calc("200+10%"))
        assertEquals("180", calc("200-10%"))
        assertEquals("7.5", calc("50*15%"))
    }

    @Test
    fun `implicit multiplication and unclosed brackets while typing`() {
        assertEquals("14", calc("2(3+4)"))
        assertEquals("7", calc("(3+4"))
        assertEquals("14", calc("2*(3+4"))
    }

    @Test
    fun `not a calculation`() {
        assertNull(calc("42"))
        assertNull(calc("-3"))
        assertNull(calc("3+"))
        assertNull(calc("maps"))
        assertNull(calc("2x"))
        assertNull(calc("1.2.3"))
        assertNull(calc("3+3)"))
        assertNull(calc(""))
        assertNull(calc("1/0")) // no finite answer
    }

    @Test
    fun `large and tiny results`() {
        assertEquals("1000000000000", calc("10^12"))
        assertEquals("1e20", calc("10^20"))
        assertEquals("1e-9", calc("10^-9"))
    }
}

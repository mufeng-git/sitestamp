package com.cscec.sitestamp

import com.cscec.sitestamp.core.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * EXIF GPS 度分秒有理数："度/1,分/1,毫秒/1000"。
 *
 * EXIF 只存绝对值，南纬 / 西经靠单独的 N/S、E/W 标记，因此负值必须取绝对值。
 */
class ExifRationalTest {

    /** 取出三段有理数，顺便校验分母约定。 */
    private fun parts(rational: String): List<Pair<Long, Long>> {
        val segments = rational.split(',')
        assertEquals("应当正好三段：$rational", 3, segments.size)
        return segments.map {
            val numDen = it.split('/')
            assertEquals("每段应当形如 a/b：$it", 2, numDen.size)
            numDen[0].toLong() to numDen[1].toLong()
        }
    }

    /** 把有理数还原成十进制度，用于验证进位正确。 */
    private fun toDegrees(rational: String): Double {
        val p = parts(rational)
        return p[0].first.toDouble() / p[0].second +
            p[1].first.toDouble() / p[1].second / 60.0 +
            p[2].first.toDouble() / p[2].second / 3600.0
    }

    private fun assertStructure(rational: String) {
        val p = parts(rational)
        assertEquals(1L, p[0].second)
        assertEquals(1L, p[1].second)
        assertEquals(1000L, p[2].second)
        assertTrue("分值越界：${p[1].first}", p[1].first in 0L..59L)
        assertTrue("秒值越界：${p[2].first}", p[2].first in 0L..59_999L)
    }

    @Test
    fun integerDegreeHasZeroMinutesAndSeconds() {
        val out = WatermarkRenderer.toDmsRational(31.0)
        assertEquals("31/1,0/1,0/1000", out)
        assertStructure(out)
    }

    @Test
    fun halfDegreeBecomesThirtyMinutes() {
        val out = WatermarkRenderer.toDmsRational(31.5)
        assertEquals("31/1,30/1,0/1000", out)
        assertStructure(out)
    }

    @Test
    fun oneDegreeWithFractionalMinutes() {
        val out = WatermarkRenderer.toDmsRational(1.25)
        assertEquals("1/1,15/1,0/1000", out)
        assertStructure(out)
    }

    @Test
    fun zeroIsZero() {
        val out = WatermarkRenderer.toDmsRational(0.0)
        assertEquals("0/1,0/1,0/1000", out)
        assertStructure(out)
    }

    @Test
    fun smallFractionBecomesSecondsMillis() {
        val out = WatermarkRenderer.toDmsRational(0.0001)
        assertEquals("0/1,0/1,360/1000", out)
        assertStructure(out)
    }

    @Test
    fun highPrecisionValueDoesNotOverflowMinutesOrSeconds() {
        val value = 41.2995085
        val out = WatermarkRenderer.toDmsRational(value)
        assertStructure(out)
        val p = parts(out)
        assertEquals(41L, p[0].first)
        assertEquals(17L, p[1].first)
        assertEquals(58_231L, p[2].first)
        // 进位后还原，误差应在毫秒级以内
        assertEquals(value, toDegrees(out), 1e-5)
    }

    @Test
    fun negativeLatitudeUsesAbsoluteValue() {
        val out = WatermarkRenderer.toDmsRational(-33.8688)
        assertFalse("EXIF 数值不带负号：$out", out.contains('-'))
        assertEquals(WatermarkRenderer.toDmsRational(33.8688), out)
        assertEquals("33/1,52/1,7680/1000", out)
    }

    @Test
    fun negativeLongitudeUsesAbsoluteValue() {
        val out = WatermarkRenderer.toDmsRational(-122.4194)
        assertFalse("EXIF 数值不带负号：$out", out.contains('-'))
        assertEquals(WatermarkRenderer.toDmsRational(122.4194), out)
        assertEquals(122.4194, toDegrees(out), 1e-5)
    }

    @Test
    fun roundingNearWholeDegreeCarriesIntoDegrees() {
        val out = WatermarkRenderer.toDmsRational(31.99999999)
        assertStructure(out)
        val p = parts(out)
        assertEquals("应当进位成整度", 32L, p[0].first)
        assertEquals(0L, p[1].first)
        assertEquals(0L, p[2].first)
    }

    @Test
    fun formatIsAlwaysThreeRationals() {
        listOf(0.0, 0.1, 41.2995085, -0.5, 89.9999999, 180.0).forEach { v ->
            val out = WatermarkRenderer.toDmsRational(v)
            assertTrue("格式不符：$out", Regex("""^\d+/\d+,\d+/\d+,\d+/\d+$""").matches(out))
            assertStructure(out)
            assertEquals(abs(v), toDegrees(out), 1e-4)
        }
    }
}

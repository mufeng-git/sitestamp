package com.cscec.sitestamp

import com.cscec.sitestamp.core.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍摄时间转 EXIF 日期字符串：输出固定为 yyyy:MM:dd HH:mm:ss。
 *
 * 输入支持三种现场格式；无法解析时退化为当前时间，因此这类用例只断言格式形状。
 */
class ExifDateTimeTest {

    private val exifShape = Regex("""^\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}$""")

    private fun assertShape(value: String) {
        assertTrue("不符合 EXIF 日期形状：$value", exifShape.matches(value))
    }

    @Test
    fun dotFormatWithMinutes() {
        assertEquals("2026:11:10 07:34:00", WatermarkRenderer.toExifDateTime("2026.11.10 07:34"))
    }

    @Test
    fun dashFormatWithSeconds() {
        assertEquals("2026:11:10 07:34:59", WatermarkRenderer.toExifDateTime("2026-11-10 07:34:59"))
    }

    @Test
    fun dashFormatWithoutSeconds() {
        assertEquals("2026:11:10 07:34:00", WatermarkRenderer.toExifDateTime("2026-11-10 07:34"))
    }

    @Test
    fun singleDigitMonthAndDayArePadded() {
        assertEquals("2026:01:05 08:05:00", WatermarkRenderer.toExifDateTime("2026.1.5 8:05"))
    }

    @Test
    fun midnightIsPreserved() {
        assertEquals("2026:01:02 00:00:00", WatermarkRenderer.toExifDateTime("2026.01.02 00:00"))
    }

    @Test
    fun lastSecondOfYearIsPreserved() {
        assertEquals("2026:12:31 23:59:59", WatermarkRenderer.toExifDateTime("2026-12-31 23:59:59"))
    }

    @Test
    fun unparseableTextFallsBackToCurrentTimeWithExifShape() {
        assertShape(WatermarkRenderer.toExifDateTime("不是时间"))
    }

    @Test
    fun emptyStringFallsBackToCurrentTimeWithExifShape() {
        assertShape(WatermarkRenderer.toExifDateTime(""))
    }

    @Test
    fun prefixedTextFallsBackToCurrentTimeWithExifShape() {
        // 带“拍摄时间：”前缀的原始文案不是 toExifDateTime 的输入格式，走兜底
        assertShape(WatermarkRenderer.toExifDateTime("拍摄时间：2026.11.10 07:34"))
    }

    @Test
    fun outputAlwaysMatchesExifShape() {
        listOf(
            "2026.11.10 07:34",
            "2026-11-10 07:34:59",
            "2026-11-10 07:34",
            "garbage",
            ""
        ).forEach { assertShape(WatermarkRenderer.toExifDateTime(it)) }
    }
}

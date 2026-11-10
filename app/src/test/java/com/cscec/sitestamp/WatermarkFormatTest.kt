package com.cscec.sitestamp

import com.cscec.sitestamp.core.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 水印文本清洗：拍摄时间 / GPS / 地址。
 *
 * 现场接口返回的文案里冒号可能是全角「：」也可能是半角「:」，
 * 另外还会混入“定位未获取”“不可用”这类占位值，渲染前必须清干净。
 */
class WatermarkFormatTest {

    // ------------------------------------------------------------------ cleanTime

    @Test
    fun cleanTime_keepsPlainTime() {
        assertEquals("2026.11.10 07:34", WatermarkRenderer.cleanTime("2026.11.10 07:34"))
    }

    @Test
    fun cleanTime_stripsFullWidthPrefix() {
        assertEquals("2026.11.10 07:34", WatermarkRenderer.cleanTime("拍摄时间：2026.11.10 07:34"))
    }

    @Test
    fun cleanTime_stripsHalfWidthPrefix() {
        assertEquals("2026.11.10 07:34", WatermarkRenderer.cleanTime("拍摄时间:2026.11.10 07:34"))
    }

    @Test
    fun cleanTime_trimsSurroundingWhitespace() {
        assertEquals("2026.11.10 07:34", WatermarkRenderer.cleanTime("  2026.11.10 07:34  "))
    }

    @Test
    fun cleanTime_trimsPrefixWithSpaceAfterColon() {
        assertEquals("2026.11.10 07:34", WatermarkRenderer.cleanTime("拍摄时间： 2026.11.10 07:34 "))
    }

    @Test
    fun cleanTime_emptyStringBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanTime(""))
    }

    @Test
    fun cleanTime_prefixOnlyBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanTime("拍摄时间："))
        assertEquals("", WatermarkRenderer.cleanTime("拍摄时间:"))
    }

    @Test
    fun cleanTime_blankStringBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanTime("   "))
    }

    @Test
    fun cleanTime_keepsInternalColonsOfTime() {
        // 只清前缀，时间本身的冒号必须保留
        assertEquals("2026.11.10 07:34:59", WatermarkRenderer.cleanTime("拍摄时间：2026.11.10 07:34:59"))
    }

    // ------------------------------------------------------------------ cleanGps

    @Test
    fun cleanGps_stripsFullWidthPrefixAndLabelsResult() {
        assertEquals("坐标 31.2304,121.4737", WatermarkRenderer.cleanGps("GPS：31.2304,121.4737"))
    }

    @Test
    fun cleanGps_stripsHalfWidthPrefixAndLabelsResult() {
        assertEquals("坐标 31.2304,121.4737", WatermarkRenderer.cleanGps("GPS:31.2304,121.4737"))
    }

    @Test
    fun cleanGps_trimsWhitespace() {
        assertEquals("坐标 31.2304,121.4737", WatermarkRenderer.cleanGps("  GPS： 31.2304,121.4737  "))
    }

    @Test
    fun cleanGps_emptyStringBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanGps(""))
    }

    @Test
    fun cleanGps_prefixOnlyBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanGps("GPS："))
        assertEquals("", WatermarkRenderer.cleanGps("GPS:"))
    }

    @Test
    fun cleanGps_placeholderNotLocatedBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanGps("定位未获取"))
    }

    @Test
    fun cleanGps_prefixedPlaceholderBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanGps("GPS：定位未获取"))
        assertEquals("", WatermarkRenderer.cleanGps("GPS: 定位未获取 "))
    }

    @Test
    fun cleanGps_negativeCoordinatesKeptWithSigns() {
        assertEquals("坐标 -31.5,-70.25", WatermarkRenderer.cleanGps("GPS：-31.5,-70.25"))
    }

    @Test
    fun cleanGps_fullWidthCommaInsideValueIsKept() {
        // 清洗只处理前缀，经纬度内容的标点原样保留
        assertEquals("坐标 31.2304，121.4737", WatermarkRenderer.cleanGps("GPS：31.2304，121.4737"))
    }

    // ------------------------------------------------------------------ cleanAddress

    @Test
    fun cleanAddress_stripsFullWidthPrefix() {
        assertEquals("北京市朝阳区", WatermarkRenderer.cleanAddress("地址：北京市朝阳区"))
    }

    @Test
    fun cleanAddress_stripsHalfWidthPrefix() {
        assertEquals("北京市朝阳区", WatermarkRenderer.cleanAddress("地址:北京市朝阳区"))
    }

    @Test
    fun cleanAddress_trimsWhitespace() {
        assertEquals("北京市朝阳区", WatermarkRenderer.cleanAddress("  北京市朝阳区  "))
    }

    @Test
    fun cleanAddress_emptyStringBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanAddress(""))
    }

    @Test
    fun cleanAddress_prefixOnlyBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanAddress("地址："))
        assertEquals("", WatermarkRenderer.cleanAddress("地址:"))
    }

    @Test
    fun cleanAddress_placeholderUnavailableBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanAddress("不可用"))
    }

    @Test
    fun cleanAddress_prefixedPlaceholderBecomesEmpty() {
        assertEquals("", WatermarkRenderer.cleanAddress("地址：不可用"))
        assertEquals("", WatermarkRenderer.cleanAddress("地址: 不可用 "))
    }

    @Test
    fun cleanAddress_splitsKnownCountryCity() {
        assertEquals("乌兹别克斯坦 塔什干", WatermarkRenderer.cleanAddress("乌兹别克斯坦塔什干"))
        assertEquals("乌兹别克斯坦 塔什干", WatermarkRenderer.cleanAddress("地址：乌兹别克斯坦塔什干"))
    }

    @Test
    fun cleanAddress_knownCountryCityWithSuffix() {
        assertEquals(
            "乌兹别克斯坦 塔什干 某工地",
            WatermarkRenderer.cleanAddress("地址：乌兹别克斯坦塔什干某工地")
        )
    }

    @Test
    fun cleanAddress_keepsInternalColonOfAddress() {
        // 只清「地址：」前缀，地址内容里的冒号不能误删
        assertEquals("A:1号", WatermarkRenderer.cleanAddress("地址：A:1号"))
    }
}

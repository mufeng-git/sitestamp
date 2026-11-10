package com.cscec.sitestamp

import com.cscec.sitestamp.core.describeWeatherCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WMO 天气代码 -> 现场口语。
 *
 * 覆盖源码里定义的全部映射，以及未定义码的「天气」兜底。
 */
class WeatherCodeTest {

    @Test
    fun clearSkyCodes() {
        assertEquals("晴", describeWeatherCode(0))
        assertEquals("晴间多云", describeWeatherCode(1))
        assertEquals("多云", describeWeatherCode(2))
        assertEquals("阴", describeWeatherCode(3))
    }

    @Test
    fun fogCodes() {
        assertEquals("雾", describeWeatherCode(45))
        assertEquals("雾", describeWeatherCode(48))
    }

    @Test
    fun drizzleCodes() {
        listOf(51, 53, 55, 56, 57).forEach {
            assertEquals("毛毛雨($it)", "毛毛雨", describeWeatherCode(it))
        }
    }

    @Test
    fun rainCodes() {
        assertEquals("小雨", describeWeatherCode(61))
        assertEquals("中雨", describeWeatherCode(63))
        assertEquals("大雨", describeWeatherCode(65))
    }

    @Test
    fun freezingRainCodes() {
        assertEquals("冻雨", describeWeatherCode(66))
        assertEquals("冻雨", describeWeatherCode(67))
    }

    @Test
    fun snowCodes() {
        assertEquals("小雪", describeWeatherCode(71))
        assertEquals("中雪", describeWeatherCode(73))
        assertEquals("大雪", describeWeatherCode(75))
        assertEquals("米雪", describeWeatherCode(77))
    }

    @Test
    fun showerCodes() {
        assertEquals("阵雨", describeWeatherCode(80))
        assertEquals("阵雨", describeWeatherCode(81))
        assertEquals("强阵雨", describeWeatherCode(82))
    }

    @Test
    fun snowShowerCodes() {
        assertEquals("阵雪", describeWeatherCode(85))
        assertEquals("阵雪", describeWeatherCode(86))
    }

    @Test
    fun thunderstormCodes() {
        assertEquals("雷阵雨", describeWeatherCode(95))
        assertEquals("雷暴冰雹", describeWeatherCode(96))
        assertEquals("雷暴冰雹", describeWeatherCode(99))
    }

    @Test
    fun undefinedCodesFallBackToGenericWeather() {
        assertEquals("天气", describeWeatherCode(-1))
        assertEquals("天气", describeWeatherCode(100))
        assertEquals("天气", describeWeatherCode(Int.MIN_VALUE))
        assertEquals("天气", describeWeatherCode(Int.MAX_VALUE))
    }
}

package com.cscec.sitestamp

import com.cscec.sitestamp.core.WatermarkRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码降采样倍数。
 *
 * 不记忆具体返回值，而是验证不变量：
 * 按返回值做整数除法降采样后，宽和高都不超过 max；且返回的是能满足条件的最小 2 的幂。
 */
class InSampleSizeTest {

    private fun check(w: Int, h: Int, max: Int): Int {
        val sample = WatermarkRenderer.calculateInSampleSize(w, h, max)
        assertTrue("sample 必须是正整数：$sample", sample >= 1)
        assertTrue("sample 必须是 2 的幂：$sample", sample and (sample - 1) == 0)
        assertTrue("宽降采样后超限：$w/$sample > $max", w / sample <= max)
        assertTrue("高降采样后超限：$h/$sample > $max", h / sample <= max)
        if (sample > 1) {
            val prev = sample / 2
            assertTrue(
                "sample 不是最小值：$w/$prev、$h/$prev 其实已满足上限 $max",
                w / prev > max || h / prev > max
            )
        }
        return sample
    }

    @Test
    fun imageSmallerThanMaxNeedsNoSampling() {
        assertEquals(1, check(100, 100, 4096))
    }

    @Test
    fun imageExactlyAtMaxIsNotSampled() {
        assertEquals(1, check(4096, 4096, 4096))
    }

    @Test
    fun bothDimensionsExactlyAtMaxIsNotSampled() {
        assertEquals(1, check(3000, 2000, 3000))
    }

    @Test
    fun onePixelOverMaxIsSampled() {
        assertEquals(2, check(4097, 100, 4096))
    }

    @Test
    fun largePhotoIsSampled() {
        assertEquals(2, check(8000, 6000, 4096))
    }

    @Test
    fun hugePhotoIsSampledFurther() {
        assertEquals(4, check(12000, 9000, 4096))
    }

    @Test
    fun onlyWidthOverMaxTriggersSampling() {
        assertEquals(2, check(5000, 100, 4096))
    }

    @Test
    fun onlyHeightOverMaxTriggersSampling() {
        assertEquals(2, check(100, 5000, 4096))
    }

    @Test
    fun extremeWideAspectRatioStaysWithinLimit() {
        val sample = check(100_000, 10, 4096)
        assertEquals(32, sample)
    }

    @Test
    fun extremeTallAspectRatioStaysWithinLimit() {
        val sample = check(10, 100_000, 4096)
        assertEquals(32, sample)
    }

    @Test
    fun tinyMaxStillReturnsPowerOfTwoWithinLimit() {
        check(2, 2, 1)
        check(3, 3, 1)
        check(1024, 768, 1)
    }

    @Test
    fun invariantHoldsAcrossTypicalDimensions() {
        val sizes = listOf(
            1 to 1, 640 to 480, 1920 to 1080, 4032 to 3024,
            4096 to 4096, 4097 to 4097, 8000 to 6000, 12000 to 9000,
            40_000 to 30_000, 100_000 to 10, 10 to 100_000
        )
        for (max in listOf(1, 16, 100, 2048, 4096)) {
            for ((w, h) in sizes) check(w, h, max)
        }
    }
}

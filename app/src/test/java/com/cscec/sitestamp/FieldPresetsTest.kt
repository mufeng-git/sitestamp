package com.cscec.sitestamp

import com.cscec.sitestamp.data.FieldPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段快捷选项预设。
 *
 * forLabel 按标签查预设（内部 trim）；merge 把数据库显式配置的选项放在前面，
 * 再接预设，逗号/中文逗号/分号都可作分隔符，最后去重。
 */
class FieldPresetsTest {

    @Test
    fun knownLabelReturnsNonEmptyPreset() {
        val area = FieldPresets.forLabel("施工区域")
        assertTrue(area.isNotEmpty())
        assertTrue(area.contains("4A"))
    }

    @Test
    fun knownLabelsAllReturnPresets() {
        listOf("施工区域", "检查事项", "检查类型", "作业内容", "天气", "责任单位").forEach {
            assertTrue("$it 应有预设", FieldPresets.forLabel(it).isNotEmpty())
        }
    }

    @Test
    fun labelWithSurroundingSpacesMatches() {
        assertEquals(FieldPresets.forLabel("施工区域"), FieldPresets.forLabel("  施工区域  "))
    }

    @Test
    fun unknownLabelReturnsEmptyList() {
        assertEquals(emptyList<String>(), FieldPresets.forLabel("不存在的字段"))
    }

    @Test
    fun blankLabelReturnsEmptyList() {
        assertEquals(emptyList<String>(), FieldPresets.forLabel(""))
        assertEquals(emptyList<String>(), FieldPresets.forLabel("   "))
    }

    @Test
    fun mergeWithoutDbOptionsEqualsPreset() {
        assertEquals(FieldPresets.forLabel("施工区域"), FieldPresets.merge("施工区域", null))
        assertEquals(FieldPresets.forLabel("施工区域"), FieldPresets.merge("施工区域", ""))
    }

    @Test
    fun mergePutsDbOptionsFirstThenPresets() {
        val merged = FieldPresets.merge("天气", "大风,晴")
        assertEquals("大风", merged[0])
        assertEquals("晴", merged[1])
        // 晴 与预设重复，只保留一次
        assertEquals(1, merged.count { it == "晴" })
        assertTrue(merged.contains("多云"))
    }

    @Test
    fun mergeSupportsChineseCommaAndSemicolons() {
        assertEquals(
            listOf("甲", "乙", "丙", "丁"),
            FieldPresets.merge("不存在的字段", "甲，乙;丙；丁")
        )
    }

    @Test
    fun mergeTrimsAndDropsBlankEntries() {
        assertEquals(
            listOf("甲", "乙"),
            FieldPresets.merge("不存在的字段", " 甲 , ,乙,  ")
        )
    }

    @Test
    fun mergeDeduplicatesRepeatedDbOptions() {
        val merged = FieldPresets.merge("施工区域", "4A,4A, 4A")
        assertEquals(1, merged.count { it == "4A" })
        assertEquals(FieldPresets.forLabel("施工区域").size, merged.size)
    }

    @Test
    fun mergeWithUnknownLabelAndNoDbOptionsIsEmpty() {
        assertEquals(emptyList<String>(), FieldPresets.merge("不存在的字段", null))
    }

    @Test
    fun mergeKeepsDbOnlyOptions() {
        val merged = FieldPresets.merge("检查类型", "临时新增类型")
        assertTrue(merged.contains("临时新增类型"))
        assertTrue(merged.contains("质量巡查"))
        assertFalse(merged.isEmpty())
    }
}

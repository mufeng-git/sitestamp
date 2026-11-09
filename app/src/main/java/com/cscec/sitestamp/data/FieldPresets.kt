package com.cscec.sitestamp.data

/**
 * 字段快捷选项预设。
 *
 * 现场使用节奏是"抬手就拍"，字段值绝大多数情况下只是有限几个枚举：
 * 手打一次 "清水混凝土" 要好几个来回，所以拍摄界面必须能一键选中。
 * 预设按字段中文标签匹配，不依赖数据库迁移——老用户库里 field.options 为空也能拿到选项。
 */
object FieldPresets {

    private val presets: Map<String, List<String>> = mapOf(
        "施工区域" to listOf(
            "4A", "4B", "4C", "5A", "5B", "B1", "B2", "屋面", "室外", "其他"
        ),
        "检查事项" to listOf(
            "清水混凝土", "砌体工程", "抹灰工程", "钢筋工程", "模板工程", "混凝土浇筑",
            "防水工程", "机电安装", "安全防护", "文明施工", "材料堆放", "其他"
        ),
        "检查类型" to listOf("质量巡查", "安全巡查", "专项检查", "整改复验"),
        "作业内容" to listOf(
            "模板支设", "钢筋绑扎", "混凝土浇筑", "架体搭设", "架体拆除", "吊装作业",
            "动火作业", "临电作业", "高处作业", "其他"
        ),
        "天气" to listOf("晴", "多云", "阴", "雨", "雪", "大风", "沙尘"),
        "责任单位" to listOf("上海亿中", "上海共革", "中建国际乌兹公司", "其他")
    )

    fun forLabel(label: String): List<String> = presets[label.trim()] ?: emptyList()

    /** 数据库里显式配置过 options 时优先用配置值（逗号分隔）。 */
    fun merge(label: String, options: String?): List<String> {
        val fromDb = options
            ?.split(',', '，', ';', '；')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
        val base = forLabel(label)
        return (fromDb + base).distinct()
    }
}

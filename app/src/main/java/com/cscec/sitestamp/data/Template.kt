package com.cscec.sitestamp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "templates")
data class Template(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val layoutConfig: String, // JSON 水印布局配置
    val styleConfig: String,  // JSON 字体颜色等配置
    val isDefault: Boolean = false,
    val sortOrder: Int = 0
)
